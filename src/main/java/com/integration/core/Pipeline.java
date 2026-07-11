package com.integration.core;

import com.integration.audit.AuditWriter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * ═══════════════════════════════════════════════════════════════════
 * Pipeline — Fixed Execution Pipeline
 * ═══════════════════════════════════════════════════════════════════
 *
 * Concrete @Service — not abstract. Use case teams do NOT extend this.
 * Executes identically for every use case and every data row.
 * Pipeline stage order is fixed and cannot be changed by any caller.
 *
 * Use case teams:
 *   REQUIRED  → add {usecase}.properties to Config Server
 *   REQUIRED  → add step files to /integration-config/{usecase}/steps/
 *   OPTIONAL  → implement UseCaseHook @Component for custom behaviour
 *   NOTHING ELSE — no Java code required for standard onboarding
 *
 * Platform collaborators (injected — invisible to use case teams):
 *   Validator     — validates row before any writes (Stage 1)
 *   StepExecutor  — routes SQL/Aerospike execution (Stages 2, 4)
 *   RollbackEngine — compensating steps in reverse (Stage 3)
 *   AuditWriter   — append-only audit record (Stage 5)
 *
 * Optional collaborator:
 *   UseCaseHook  — extension point, applied if @Component exists for useCase
 *
 * ── Dynamic use case support (Option A) ─────────────────────────────
 *   New use case added without restart:
 *     1. Add {usecase}.properties to Config Server + push
 *     2. Add credentials to Vault
 *     3. Add step files to /integration-config/{usecase}/steps/
 *     4. POST /actuator/refresh
 *     5. Drop file in /uploads/{usecase}/ — pipeline runs immediately
 *
 * ── Pipeline Stages ──────────────────────────────────────────────────
 *   Stage 0: UseCaseHook.beforeValidate (if hook registered)
 *   Stage 1: Validator.validate (placeholder presence + optional rules)
 *   Stage 2: StepExecutor.execute for EXECUTE + CONFIG_WRITE steps
 *   Stage 3: RollbackEngine.rollback (only if Stage 2 fails)
 *   Stage 4: UseCaseHook.afterExecute (if hook registered, only if no failure)
 *   Stage 5: StepExecutor.execute for POST_VALIDATE steps
 *   Stage 6: AuditWriter.write (always — regardless of outcome)
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class Pipeline {

    private final Validator      validator;
    private final StepExecutor   stepExecutor;
    private final RollbackEngine rollbackEngine;
    private final AuditWriter    auditWriter;

    /*
     * All registered UseCaseHook @Components injected by Spring.
     * Empty list if no hooks registered — not an error.
     */
    private final List<UseCaseHook> hooks;

    /**
     * Executes the fixed pipeline for one data row.
     *
     * @param useCase use case name e.g. "dmt"
     * @param row     field values from one CSV or JSON record
     * @param steps   pre-loaded, sorted StepCommand list for this use case
     * @param rules   optional validation rules (empty list if none configured)
     * @param profile active Spring profile (dev / uat / prod)
     * @return        ExecutionContext with all step results for audit
     */
    public ExecutionContext execute(String useCase,
                                    Map<String, String> row,
                                    List<StepCommand> steps,
                                    List<ValidationRule> rules,
                                    String profile) {

        /*
         * Build ExecutionContext — carries all state through every stage.
         * Immutable fields set here. Step results accumulated via addResult().
         */
        ExecutionContext ctx = ExecutionContext.builder()
            .useCase(useCase)
            .row(row)
            .activeProfile(profile)
            .schemaVersion(1)
            .build();

        log.info("PIPELINE START: useCase={} profile={}", useCase, profile);

        /*
         * Resolve optional hook for this use case.
         * Returns empty if no hook registered — pipeline continues normally.
         */
        Optional<UseCaseHook> hook = resolveHook(useCase);

        // ── Stage 0: Hook — beforeValidate ───────────────────────────
        /*
         * Optional use case extension point.
         * Runs before platform validation.
         * Use for: duplicate checks, cross-field rules, external lookups.
         * Throws IllegalArgumentException to fail validation.
         * Skipped silently if no hook registered.
         */
        if (hook.isPresent()) {
            try {
                hook.get().beforeValidate(row, ctx);
                log.debug("HOOK beforeValidate OK: useCase={}", useCase);
            } catch (IllegalArgumentException e) {
                log.warn("HOOK beforeValidate FAILED: useCase={} reason={}",
                    useCase, e.getMessage());
                return recordAndAudit(ctx, 0, "HOOK_PRE_VALIDATE",
                    StepType.EXECUTE, StepEngine.SQL, false, e.getMessage());
            }
        }

        // ── Stage 1: Validate ─────────────────────────────────────────
        /*
         * Layer 1: auto-checks all :placeholder fields from step queries
         *          are present and non-empty in the row.
         * Layer 2: optional rules from Config Server if configured.
         * All errors collected before throwing — operator sees all issues.
         * On failure: no DB writes occur. Audit written and return.
         */
        try {
            validator.validate(row, steps, rules);
            log.debug("VALIDATION PASSED: useCase={}", useCase);
        } catch (IllegalArgumentException e) {
            log.warn("VALIDATION FAILED: useCase={} reason={}", useCase, e.getMessage());
            return recordAndAudit(ctx, 0, "PRE_VALIDATE",
                StepType.EXECUTE, StepEngine.SQL, false, e.getMessage());
        }

        // ── Stage 2: Execute EXECUTE + CONFIG_WRITE steps ─────────────
        /*
         * Runs steps in STEP_ID ascending order (pre-sorted by StepLoader).
         * Each step routed to SQL or Aerospike by StepExecutor.
         * Stops on first failure — remaining steps not executed.
         * On failure: triggers Stage 3 (Rollback).
         */
        int failedStepId = -1;

        for (StepCommand step : steps) {
            if (step.getType() != StepType.EXECUTE
                    && step.getType() != StepType.CONFIG_WRITE) {
                continue;
            }

            StepResult result = runStep(useCase, step, row);
            ctx.addResult(result);

            if (!result.isSuccess()) {
                failedStepId = step.getStepId();
                log.error("STEP FAILED: useCase={} stepId={} name='{}'",
                    useCase, step.getStepId(), step.getName());
                break;
            }

            log.info("STEP OK: useCase={} stepId={} name='{}'",
                useCase, step.getStepId(), step.getName());
        }

        // ── Stage 3: Rollback ─────────────────────────────────────────
        /*
         * Only triggered if Stage 2 had a failure.
         * Delegates to RollbackEngine — runs compensating steps
         * in reverse STEP_ID order.
         * Rollback results added to ctx by RollbackEngine.
         * After rollback: audit and return. No further stages.
         */
        if (failedStepId > 0) {
            log.warn("ROLLBACK TRIGGERED: useCase={} failedStepId={}",
                useCase, failedStepId);

            rollbackEngine.rollback(
                steps,
                failedStepId,
                ctx,
                /*
                 * Pass StepExecutor.execute as the rollback execution function.
                 * RollbackEngine calls this for each compensating step.
                 * Same execution path as forward steps — no separate rollback runner.
                 */
                (step, context) -> stepExecutor.execute(useCase, step, row)
            );

            auditWriter.write(ctx);
            return ctx;
        }

        // ── Stage 4: Hook — afterExecute ──────────────────────────────
        /*
         * Optional use case extension point.
         * Runs after all EXECUTE steps succeed, before POST_VALIDATE.
         * Use for: notifications, downstream triggers, cache warming.
         * Exceptions caught and recorded — do not trigger rollback.
         * Skipped silently if no hook registered.
         */
        if (hook.isPresent()) {
            try {
                hook.get().afterExecute(row, ctx);
                log.debug("HOOK afterExecute OK: useCase={}", useCase);
            } catch (Exception e) {
                log.warn("HOOK afterExecute FAILED (non-fatal): useCase={} reason={}",
                    useCase, e.getMessage());
                ctx.addResult(StepResult.builder()
                    .stepId(-1)
                    .stepName("HOOK_AFTER_EXECUTE")
                    .type(StepType.EXECUTE)
                    .engine(StepEngine.SQL)
                    .success(false)
                    .message(e.getMessage())
                    .executionMs(0L)
                    .build());
            }
        }

        // ── Stage 5: POST_VALIDATE steps ──────────────────────────────
        /*
         * Verifies DB/Aerospike state after all EXECUTE steps succeed.
         * Runs via StepExecutor — same execution path as EXECUTE steps.
         * Failures recorded in context. Do NOT trigger rollback.
         */
        for (StepCommand step : steps) {
            if (step.getType() != StepType.POST_VALIDATE) continue;

            StepResult result = runStep(useCase, step, row);
            ctx.addResult(result);

            log.info("POST-VALIDATE {}: useCase={} stepId={} name='{}'",
                result.isSuccess() ? "OK" : "FAILED",
                useCase, step.getStepId(), step.getName());
        }

        // ── Stage 6: Audit ────────────────────────────────────────────
        /*
         * Always executes — success, failure, rollback, or post-validate failure.
         * AuditWriter never throws — audit failure is logged only.
         * Writes one append-only record per row containing:
         *   use case, profile, schema version, raw row,
         *   all step results, overall success flag.
         */
        auditWriter.write(ctx);

        log.info("PIPELINE COMPLETE: useCase={} success={}", useCase, !ctx.hasFailure());
        return ctx;
    }

    // ── Private helpers ───────────────────────────────────────────────

    /**
     * Executes one step via StepExecutor.
     * Catches all exceptions — records failure, never propagates.
     * Records execution time for audit and monitoring.
     */
    private StepResult runStep(String useCase, StepCommand step, Map<String, String> row) {
        long start = System.currentTimeMillis();
        boolean success = false;
        String message = "";

        try {
            success = stepExecutor.execute(useCase, step, row);
        } catch (Exception e) {
            message = e.getMessage();
            log.error("STEP EXCEPTION: useCase={} stepId={} error={}",
                useCase, step.getStepId(), message);
        }

        return StepResult.builder()
            .stepId(step.getStepId())
            .stepName(step.getName())
            .type(step.getType())
            .engine(step.getEngine())
            .success(success)
            .message(message)
            .executionMs(System.currentTimeMillis() - start)
            .build();
    }

    /**
     * Records a single failed step result, writes audit, and returns context.
     * Used for Stage 0 and Stage 1 failures — before any DB writes occur.
     */
    private ExecutionContext recordAndAudit(ExecutionContext ctx,
                                             int stepId, String stepName,
                                             StepType type, StepEngine engine,
                                             boolean success, String message) {
        ctx.addResult(StepResult.builder()
            .stepId(stepId)
            .stepName(stepName)
            .type(type)
            .engine(engine)
            .success(success)
            .message(message)
            .executionMs(0L)
            .build());
        auditWriter.write(ctx);
        return ctx;
    }

    /**
     * Resolves the optional UseCaseHook for a use case.
     * Returns empty if no hook @Component registered for this use case.
     */
    private Optional<UseCaseHook> resolveHook(String useCase) {
        return hooks.stream()
            .filter(h -> h.getUseCaseType().equalsIgnoreCase(useCase))
            .findFirst();
    }
}
