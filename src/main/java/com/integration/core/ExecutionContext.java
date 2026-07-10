package com.integration.core;

import lombok.Builder;
import lombok.Getter;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Builder pattern — per-row execution state.
 *
 * Created once per data row by AbstractUseCaseExecutor.execute().
 * Passed through every pipeline stage.
 * Step results accumulated via addResult() — never replaced.
 *
 * Immutable after construction except for stepResults accumulation.
 * hasFailure() is the single source of truth for overall pipeline outcome.
 */
@Getter
@Builder
public class ExecutionContext {

    private final String              useCase;
    private final Map<String, String> row;           // data row from CSV or JSON
    private final String              activeProfile; // dev / uat / prod
    private final int                 schemaVersion;

    @Builder.Default
    private final List<StepResult> stepResults = new ArrayList<>();

    /**
     * Appends a step result.
     * Called by pipeline after each step executes.
     */
    public void addResult(StepResult result) {
        stepResults.add(result);
    }

    /**
     * Returns true if any step in this execution failed.
     * Checked by pipeline to decide whether to proceed or rollback.
     */
    public boolean hasFailure() {
        return stepResults.stream().anyMatch(r -> !r.isSuccess());
    }

    /**
     * Returns an unmodifiable view of step results.
     * Audit writer reads this — must not modify.
     */
    public List<StepResult> getStepResults() {
        return Collections.unmodifiableList(stepResults);
    }
}
