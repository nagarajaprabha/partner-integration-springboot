package com.integration.audit;

import com.integration.core.StepResult;
import lombok.Builder;
import lombok.Getter;

import java.time.Instant;
import java.util.List;

/**
 * ═══════════════════════════════════════════════════════════════════
 * AuditRecord — Builder Pattern
 * ═══════════════════════════════════════════════════════════════════
 *
 * Immutable record of one complete pipeline run for one data row.
 * Built by AuditWriter from ExecutionContext after every pipeline run.
 *
 * Written to append-only audit log — one JSON line per record.
 * Never updated or deleted after writing.
 *
 * Fields:
 *   timestamp      — UTC instant when audit record was written
 *   useCase        — use case name e.g. "dmt", "cms"
 *   activeProfile  — Spring profile active at execution time
 *   schemaVersion  — step schema version (for future schema evolution)
 *   row            — raw data row values from CSV/JSON
 *   stepResults    — ordered list of all step outcomes (forward + rollback)
 *   overallSuccess — true only if all steps succeeded and no failures
 *   warnings       — non-fatal observations (reserved for next enhancement)
 */
@Getter
@Builder
public class AuditRecord {

    private final Instant           timestamp;
    private final String            useCase;
    private final String            activeProfile;
    private final int               schemaVersion;

    /*
     * Raw row data — all field values as received from CSV/JSON.
     * Stored as JSON string — preserves original structure.
     * Never modified — source of truth for what was submitted.
     */
    private final String            rawRow;

    /*
     * Ordered list of step results.
     * Includes: forward EXECUTE steps, ROLLBACK steps (if any),
     * POST_VALIDATE steps, and any hook results.
     * Each StepResult records: stepId, name, type, engine,
     * success, message, executionMs.
     */
    private final List<StepResult>  stepResults;

    /*
     * Overall success — true only if every step in stepResults
     * succeeded and no failures recorded.
     * Derived from ExecutionContext.hasFailure() — single source of truth.
     */
    private final boolean           overallSuccess;

    /*
     * Warnings — non-fatal observations about this row.
     * Reserved for next enhancement (unmapped CSV columns etc).
     * Empty list for now.
     */
    @Builder.Default
    private final List<String>      warnings = List.of();
}
