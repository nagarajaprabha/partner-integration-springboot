package com.integration.audit;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.integration.core.ExecutionContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.io.FileUtils;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;

/**
 * ═══════════════════════════════════════════════════════════════════
 * AuditWriter — Append-Only Audit Log
 * ═══════════════════════════════════════════════════════════════════
 *
 * Writes one AuditRecord per pipeline run to an append-only log file.
 * Called by Pipeline after every run — success, failure, or rollback.
 *
 * Format: one JSON line per record (JSON Lines / NDJSON format).
 * Each line is a self-contained, parseable JSON object.
 * Easy to ingest into ELK, Splunk, or any log aggregator.
 *
 * Uses:
 *   Jackson ObjectMapper     — AuditRecord → JSON string
 *   Apache Commons IO        — append line to file, create parent dirs
 *   JavaTimeModule           — Instant serialised as ISO-8601 string
 *
 * NEVER throws — audit failure must not affect the pipeline result.
 * All exceptions caught and logged. Pipeline continues regardless.
 *
 * Audit log path: configured via integration.audit.log.path
 * Default:        /var/log/partner-integration/audit.log
 *
 * File is append-only — no rotation implemented here.
 * Use logrotate or a log aggregator for rotation in production.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AuditWriter {

    /*
     * Spring-managed ObjectMapper — shared across application.
     * Configured with JavaTimeModule for Instant → ISO-8601 serialisation.
     */
    private final ObjectMapper objectMapper;

    @Value("${integration.audit.log.path:/var/log/partner-integration/audit.log}")
    private String auditLogPath;

    /**
     * Writes one AuditRecord to the append-only audit log.
     * Builds AuditRecord from ExecutionContext using Builder pattern.
     * Never throws — exceptions are caught and logged only.
     *
     * @param ctx ExecutionContext from completed pipeline run
     */
    public void write(ExecutionContext ctx) {
        try {
            /*
             * Serialise raw row to JSON string.
             * Stored in AuditRecord.rawRow for full traceability.
             */
            String rawRow = objectMapper.writeValueAsString(ctx.getRow());

            /*
             * Build AuditRecord via Builder.
             * overallSuccess derived from hasFailure() — single source of truth.
             */
            AuditRecord record = AuditRecord.builder()
                .timestamp(Instant.now())
                .useCase(ctx.getUseCase())
                .activeProfile(ctx.getActiveProfile())
                .schemaVersion(ctx.getSchemaVersion())
                .rawRow(rawRow)
                .stepResults(ctx.getStepResults())
                .overallSuccess(!ctx.hasFailure())
                .build();

            /*
             * Serialise AuditRecord to JSON string.
             * One compact JSON line — no pretty printing.
             * JavaTimeModule handles Instant → ISO-8601.
             */
            String jsonLine = objectMapper
                .configure(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS, false)
                .writeValueAsString(record);

            /*
             * Append JSON line + newline to audit log file.
             * Apache Commons IO FileUtils.writeStringToFile():
             *   - Creates parent directories if they do not exist
             *   - append=true — never overwrites existing content
             *   - UTF-8 encoding
             * No custom file I/O logic needed.
             */
            FileUtils.writeStringToFile(
                new File(auditLogPath),
                jsonLine + System.lineSeparator(),
                StandardCharsets.UTF_8,
                true  // append=true
            );

            log.debug("AUDIT written: useCase={} success={} steps={}",
                ctx.getUseCase(), !ctx.hasFailure(), ctx.getStepResults().size());

        } catch (Exception e) {
            /*
             * Audit failure is NEVER propagated to the pipeline.
             * Logged at ERROR level — operator should investigate.
             * Pipeline result and data integrity are unaffected.
             */
            log.error("AUDIT WRITE FAILED: useCase={} error={}",
                ctx.getUseCase(), e.getMessage());
        }
    }
}
