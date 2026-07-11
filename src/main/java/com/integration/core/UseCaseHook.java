package com.integration.core;

import java.util.Map;

/**
 * ═══════════════════════════════════════════════════════════════════
 * UseCaseHook — Optional Extension Interface
 * ═══════════════════════════════════════════════════════════════════
 *
 * Open/Closed Principle:
 *   Platform is CLOSED for modification — use case teams never
 *   change Pipeline, StepExecutor, Validator, or RollbackEngine.
 *
 *   Platform is OPEN for extension — use case teams MAY implement
 *   this interface to add custom behaviour without touching platform code.
 *
 * Implementation is OPTIONAL.
 *   Pipeline checks if a hook exists for the current use case.
 *   If found → applies it at the defined stage.
 *   If not found → skips silently. Not an error.
 *
 * To add custom behaviour for a use case:
 *   1. Create a class implementing UseCaseHook
 *   2. Annotate with @Component
 *   3. Return the use case name from getUseCaseType()
 *   That is all. No other code changes required.
 *
 * Default implementations return without action —
 * implementor overrides only the methods they need.
 *
 * ── Extension points ────────────────────────────────────────────────
 *
 *   beforeValidate  — runs before platform auto-validation
 *                     use for: duplicate checks via JPA repository,
 *                     cross-field business rules, external lookups
 *
 *   afterExecute    — runs after all EXECUTE steps succeed and
 *                     before POST_VALIDATE steps
 *                     use for: notifications, downstream triggers,
 *                     cache warming
 */
public interface UseCaseHook {

    /**
     * Returns the use case type this hook applies to.
     * Must match the useCaseType value in incoming CSV/JSON rows.
     * Example: "DMT", "CMS", "FASTAG", "INTERNAL"
     */
    String getUseCaseType();

    /**
     * Called before platform auto-validation.
     * Throw IllegalArgumentException with descriptive message to fail validation.
     * Do NOT perform any DB writes inside this method.
     *
     * Default: no-op — override only if custom pre-validation is needed.
     *
     * @param row incoming data row
     * @param ctx execution context — read-only at this stage
     * @throws IllegalArgumentException if custom validation fails
     */
    default void beforeValidate(Map<String, String> row, ExecutionContext ctx) {
        // No-op by default — use case team overrides if needed
    }

    /**
     * Called after all EXECUTE steps succeed, before POST_VALIDATE steps.
     * Use for notifications, downstream triggers, or cache warming.
     * Exceptions are caught by pipeline and recorded as a step result.
     *
     * Default: no-op — override only if post-execution action is needed.
     *
     * @param row incoming data row
     * @param ctx execution context — step results available here
     */
    default void afterExecute(Map<String, String> row, ExecutionContext ctx) {
        // No-op by default — use case team overrides if needed
    }
}
