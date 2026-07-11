package com.integration.core;

import com.integration.audit.AuditWriter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;

import java.util.List;
import java.util.Map;

/**
 * ═══════════════════════════════════════════════════════════════════
 * AbstractUseCaseExecutor — Template Method Pattern
 * ═══════════════════════════════════════════════════════════════════
 *
 * Defines the FIXED, FINAL pipeline that runs identically for every
 * use case and every incoming data row. The pipeline order cannot be
 * changed by any use case subclass.
 *
 * Use case teams implement ONLY two methods:
 *   getUseCaseType()  — returns the use case identifier e.g. "DMT"
 *   getConfigKey()    — returns the config prefix e.g. "dmt"
 *
 * All pipeline logic (validate, execute, rollback, post-validate,
 * audit) is inherited from this class and its platform collaborators.
 *
 * Platform collaborators (autowired — invisible to use case teams):
 *   RollbackEngine — executes compensating steps in reverse order
 *   AuditWriter    — writes append-only audit record after every run
 *   Environment    — resolves active Spring profile
 *
 * ── Pipeline Stages (in fixed order) ────────────────────────────────
 *
 *   Stage 1: PRE-VALIDATE
 *     Checks all :placeholder fields present and non-empty in the row.
 *     Optional per-usecase rules applied from validations defined
 *     by the subclass via validateRow().
 *     On failure → audit and return immediately. No DB writes occur.
 *
 *   Stage 2: EXECUTE
 *     Runs all EXECUTE and CONFIG_WRITE steps in STEP_ID order.
 *     Each step routed to SQL or Aerospike via executeStep().
 *     On any step failure → trigger Stage 3 (Rollback).
 *
 *   Stage 3: ROLLBACK (conditional — only if Stage 2 fails)
 *     Delegates to RollbackEngine.
 *     Runs compensating steps in reverse STEP_ID order.
 *     Rollback failures are recorded but never thrown.
 *     After rollback → audit and return.
 *
 *   Stage 4: POST-VALIDATE (only if Stage 2 fully succeeds)
 *     Runs all POST_VALIDATE steps to verify DB/Aerospike state.
 *     Failures recorded in context — does not trigger rollback.
 *
 *   Stage 5: AUDIT (always — regardless of any stage outcome)
 *     Delegates to AuditWriter.
 *     Writes one append-only audit record per row.
 *
 * ── Adding a new use case ───────────────────────────────────────────
 *   1. Extend this class
 *   2. Implement getUseCaseType() and getConfigKey()
 *   3. Annotate with @Component
 *   That is all. No other code changes required.
 */
@Slf4j
public abstract class AbstractUseCaseExecutor {

    /*
     * Platform collaborators — autowired by Spring.
     * Use case subclasses never reference these directly.
     */
    @Autowired private RollbackEngine rollbackEngine;
    @Autowired private AuditWriter    auditWriter;
    @Autowired private Environment    environment;

    // ── Use case contract ────────────────────────────────────────────

    /**
     * Returns the unique identifier for this use case.
     * Used by UseCaseOrchestrator to route incoming rows.
     * Must match the useCaseType value in incoming CSV/JSON rows.
     *
     * Example: "DMT", "CMS", "FASTAG", "INTERNAL"
     */
    public abstract String getUseCaseType();

    /**
     * Returns the Spring Cloud Config property prefix for this use case.
     * Used by StepLoader to resolve {configKey}.steps property.
     * Used by BaseDBConfig and BaseAerospikeConfig to resolve connection details.
     *
     * Example: "dmt", "cms", "fastag", "internal"
     */
    public abstract String getConfigKey();

    // ── Fixed pipeline — FINAL — cannot be overridden ────────────────

    /**
     * Executes the fixed pipeline for one data row.
     *
     * This method is FINAL. The pipeline stage order is immutable.
     * No use case subclass may override, extend, or bypass any stage.
     *
     * @param row   field values from one CSV or JSON record
     * @param steps pre-loaded, sorted StepCommand list for this use case
     * @return      ExecutionContext containing all step results for audit
     */
    public final ExecutionContext execute(Map<String, String> row,
                                          List<StepCommand> steps) {

        /*
         * Resolve active Spring profile at execution time.
         * Passed into ExecutionContext so connectors know which
         * DB/Aerospike config to use (dev / uat / prod).
         */
        String profile = environment.getActiveProfiles().length > 0
            ? environment.getActiveProfiles()[0] : "dev";

        /*
         * Build ExecutionContext using Builder pattern.
         * Carries all state through every pipeline stage.
         * Step results accumulate in context — never replaced.
         */
        ExecutionContext ctx = ExecutionContext.builder()
            .useCase(getUseCaseType())
            .row(row)
            .activeProfile(profile)
            .schemaVersion(1)
            .build();

        log.info("PIPELINE START: useCase={} profile={} rowKeys={}",
            getUseCaseType(), profile, row.keySet());

        // ── Stage 1: PRE-VALIDATE ────────────────────────────────────
        /*
         * Validates the row before any DB or Aerospike writes occur.
         * Two layers:
         *   Layer 1 (platform): auto-checks all :placeholder fields
         *                       from step queries are present and non-empty
         *   Layer 2 (use case): subclass may override validateRow()
         *                       for domain-specific rules
         *
         * On failure: adds a failed StepResult, writes audit, returns.
         * No DB writes occur if pre-validation fails.
         */
        try {
            validateRow(row, steps, ctx);
        } catch (IllegalArgumentException e) {
            log.warn("PRE-VALIDATE FAILED: useCase={} reason={}",
                getUseCaseType(), e.getMessage());

            ctx.addResult(StepResult.builder()
                .stepId(0)
                .stepName("PRE_VALIDATE")
                .type(StepType.EXECUTE)
                .engine(StepEngine.SQL)
                .success(false)
                .message(e.getMessage())
                .executionMs(0L)
                .build());

            auditWriter.write(ctx);
            return ctx;
        }

        // ── Stage 2: EXECUTE steps in STEP_ID order ──────────────────
        /*
         * Runs all EXECUTE and CONFIG_WRITE steps sequentially.
         * Each step is routed to the correct connector (SQL or Aerospike)
         * via executeStep() — overridden by use case subclass.
         *
         * On first failure: records the result, stops forward execution,
         * and immediately triggers Stage 3 (Rollback).
         */
        int failedStepId = -1;

        for (StepCommand step : steps) {
            if (step.getType() != StepType.EXECUTE
                    && step.getType() != StepType.CONFIG_WRITE) {
                continue; // POST_VALIDATE and ROLLBACK steps handled separately
            }

            StepResult result = runStep(step, ctx);
            ctx.addResult(result);

            if (!result.isSuccess()) {
                failedStepId = step.getStepId();
                log.error("STEP FAILED: useCase={} stepId={} stepName='{}'",
                    getUseCaseType(), step.getStepId(), step.getName());
                break;
            }

            log.info("STEP OK: useCase={} stepId={} stepName='{}'",
                getUseCaseType(), step.getStepId(), step.getName());
        }

        // ── Stage 3: ROLLBACK (only if a step failed) ────────────────
        /*
         * Delegates entirely to RollbackEngine.
         * Runs compensating steps in reverse STEP_ID order.
         * Results added to context by RollbackEngine.
         * After rollback completes → write audit and return.
         * Rollback is terminal — no post-validate after a rollback.
         */
        if (failedStepId > 0) {
            log.warn("ROLLBACK TRIGGERED: useCase={} failedStepId={}",
                getUseCaseType(), failedStepId);

            rollbackEngine.rollback(steps, failedStepId, ctx, this::executeStep);

            auditWriter.write(ctx);
            return ctx;
        }

        // ── Stage 4: POST-VALIDATE ────────────────────────────────────
        /*
         * Runs all POST_VALIDATE steps to confirm DB/Aerospike state
         * is correct after all EXECUTE steps succeeded.
         * Failures are recorded but do not trigger rollback.
         * Each POST_VALIDATE step is executed via the same executeStep()
         * hook used for EXECUTE steps.
         */
        for (StepCommand step : steps) {
            if (step.getType() != StepType.POST_VALIDATE) continue;

            StepResult result = runStep(step, ctx);
            ctx.addResult(result);

            log.info("POST-VALIDATE {}: useCase={} stepId={} stepName='{}'",
                result.isSuccess() ? "OK" : "FAILED",
                getUseCaseType(), step.getStepId(), step.getName());
        }

        // ── Stage 5: AUDIT ────────────────────────────────────────────
        /*
         * Always executes — regardless of any stage outcome.
         * Writes one append-only audit record containing:
         *   - use case, active profile, schema version
         *   - raw row data
         *   - all step results (forward + rollback if any)
         *   - overall success flag
         *
         * AuditWriter never throws — audit failure is logged only.
         */
        auditWriter.write(ctx);

        log.info("PIPELINE COMPLETE: useCase={} success={}",
            getUseCaseType(), !ctx.hasFailure());

        return ctx;
    }

    // ── Hooks — subclasses override these for use-case behaviour ─────

    /**
     * Executes one step against the correct connector.
     *
     * Subclass implementation routes by step.getEngine():
     *   SQL       → JdbcTemplate using DataSource from BaseDBConfig
     *   AEROSPIKE → AerospikeClient from BaseAerospikeConfig
     *
     * Called by the pipeline for EXECUTE, CONFIG_WRITE, POST_VALIDATE,
     * and ROLLBACK steps (via RollbackEngine).
     *
     * @param step  the step to execute (query already substituted by caller)
     * @param ctx   execution context — use for activeProfile, row values
     * @return      true if step succeeded, false if it failed
     */
    protected abstract boolean executeStep(StepCommand step, ExecutionContext ctx);

    /**
     * Optional hook for use-case-specific pre-validation rules.
     *
     * Default implementation performs Layer 1 validation only:
     *   Checks all :placeholder field names in EXECUTE step queries
     *   are present and non-empty in the incoming row.
     *
     * Use case subclasses may override to add domain-specific rules
     * e.g. "bankCode must be in approved list", "partnerCode must be unique".
     *
     * Override contract:
     *   - Always call super.validateRow(row, steps, ctx) first
     *   - Throw IllegalArgumentException with a descriptive message on failure
     *   - Do NOT perform any DB writes inside this method
     *
     * @param row   incoming data row
     * @param steps all steps for this use case (for placeholder extraction)
     * @param ctx   execution context
     * @throws IllegalArgumentException if validation fails
     */
    protected void validateRow(Map<String, String> row,
                                List<StepCommand> steps,
                                ExecutionContext ctx) {
        /*
         * Layer 1: Auto-derive required fields from :placeholder names
         * in all EXECUTE step queries. Check each is present and non-empty.
         */
        steps.stream()
            .filter(s -> s.getType() == StepType.EXECUTE
                      || s.getType() == StepType.POST_VALIDATE)
            .forEach(step -> {
                java.util.regex.Matcher matcher =
                    java.util.regex.Pattern.compile(":([a-zA-Z_][a-zA-Z0-9_]*)")
                        .matcher(step.getQuery());
                while (matcher.find()) {
                    String field = matcher.group(1);
                    String value = row.getOrDefault(field, "").trim();
                    if (value.isEmpty()) {
                        throw new IllegalArgumentException(
                            "Required field '" + field + "' is missing or empty " +
                            "(referenced in step " + step.getStepId() + ")");
                    }
                }
            });
    }

    // ── Private pipeline helper ───────────────────────────────────────

    /**
     * Executes one step and wraps the outcome in a StepResult.
     * Catches all exceptions — never propagates.
     * Records execution time for audit and performance monitoring.
     */
    private StepResult runStep(StepCommand step, ExecutionContext ctx) {
        long start = System.currentTimeMillis();
        boolean success = false;
        String message = "";

        try {
            success = executeStep(step, ctx);
        } catch (Exception e) {
            message = e.getMessage();
            log.error("STEP EXCEPTION: useCase={} stepId={} error={}",
                getUseCaseType(), step.getStepId(), message);
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
}
