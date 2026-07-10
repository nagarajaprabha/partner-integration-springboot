package com.integration.core;

import lombok.Builder;
import lombok.Getter;

/**
 * Builder pattern — immutable record of one step's execution outcome.
 * Accumulated in ExecutionContext during pipeline run.
 * Written to audit log after pipeline completes.
 */
@Getter
@Builder
public class StepResult {

    private final int        stepId;
    private final String     stepName;
    private final StepType   type;
    private final StepEngine engine;
    private final boolean    success;
    private final String     message;      // error message or validation detail, empty on success
    private final long       executionMs;
}
