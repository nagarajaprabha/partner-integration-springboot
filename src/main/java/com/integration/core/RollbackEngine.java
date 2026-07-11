package com.integration.core;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.List;
import java.util.function.BiFunction;

/**
 * Rollback engine — executes compensating steps in reverse order
 * when any EXECUTE step fails.
 *
 * Phase 1 (now):
 *   - Filters ROLLBACK steps where compensatesStepId <= failedStepId
 *   - Sorts by compensatesStepId DESCENDING (highest first = reverse)
 *   - Executes each via the executeStepFn provided by AbstractUseCaseExecutor
 *   - Rollback failure is recorded in context, never thrown
 *
 * Phase 2 (deferred):
 *   - Explicit linkage between rollback steps and their forward steps
 *
 * Spring @Component — autowired into AbstractUseCaseExecutor.
 * Use case code never calls this directly.
 */
@Slf4j
@Component
public class RollbackEngine {

    /**
     * Executes all applicable rollback steps for a failed pipeline run.
     * Results are added to ExecutionContext for audit.
     *
     * @param steps         full list of steps for this use case
     * @param failedStepId  STEP_ID of the step that failed
     * @param ctx           execution context — rollback results accumulated here
     * @param executeStepFn function that executes one step — same fn used by forward pipeline
     */
    public void rollback(List<StepCommand> steps,
                         int failedStepId,
                         ExecutionContext ctx,
                         BiFunction<StepCommand, ExecutionContext, Boolean> executeStepFn) {

        List<StepCommand> rollbackSteps = steps.stream()
            .filter(s -> s.getType() == StepType.ROLLBACK)
            .filter(s -> s.getCompensatesStepId() != null
                      && s.getCompensatesStepId() <= failedStepId)
            .sorted(Comparator.comparingInt(StepCommand::getCompensatesStepId).reversed())
            .toList();

        if (rollbackSteps.isEmpty()) {
            log.info("No rollback steps applicable: useCase={} failedStepId={}",
                ctx.getUseCase(), failedStepId);
            return;
        }

        log.warn("ROLLBACK START: useCase={} failedStepId={} rollbackCount={}",
            ctx.getUseCase(), failedStepId, rollbackSteps.size());

        for (StepCommand step : rollbackSteps) {
            StepResult result = executeRollbackStep(step, ctx, executeStepFn);
            ctx.addResult(result);
        }

        log.warn("ROLLBACK COMPLETE: useCase={}", ctx.getUseCase());
    }

    // ── Private ───────────────────────────────────────────────────────

    /**
     * Executes one rollback step.
     * Catches all exceptions — rollback failure is never propagated.
     */
    private StepResult executeRollbackStep(StepCommand step,
                                            ExecutionContext ctx,
                                            BiFunction<StepCommand, ExecutionContext, Boolean> executeStepFn) {
        long start = System.currentTimeMillis();
        boolean success = false;
        String message = "";

        try {
            success = executeStepFn.apply(step, ctx);
            log.info("ROLLBACK OK: stepId={} compensates={} useCase={}",
                step.getStepId(), step.getCompensatesStepId(), ctx.getUseCase());
        } catch (Exception e) {
            message = e.getMessage();
            log.error("ROLLBACK FAILED: stepId={} compensates={} useCase={} error={}",
                step.getStepId(), step.getCompensatesStepId(), ctx.getUseCase(), message);
        }

        return StepResult.builder()
            .stepId(step.getStepId())
            .stepName(step.getName())
            .type(StepType.ROLLBACK)
            .engine(step.getEngine())
            .success(success)
            .message(message)
            .executionMs(System.currentTimeMillis() - start)
            .build();
    }
}
