package com.integration.api.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * ═══════════════════════════════════════════════════════════════════
 * AuditController — Audit Log Endpoint
 * ═══════════════════════════════════════════════════════════════════
 *
 * GET /api/audit/{useCase}?limit=50
 *   Returns recent audit log entries for a specific use case.
 *   Reads from the append-only audit log file written by AuditWriter.
 *   Filters by useCase — one endpoint for all use cases.
 *   Access: OPERATOR, VIEWER, ADMIN.
 *
 * Audit log is JSON Lines format (one JSON object per line).
 * Returns the most recent N entries (newest first).
 * Default limit: 50. Maximum limit: 200 — prevents large responses.
 */
@Slf4j
@RestController
@RequestMapping("/api/audit")
@RequiredArgsConstructor
public class AuditController {

    private final ObjectMapper objectMapper;

    @Value("${integration.audit.log.path:/var/log/partner-integration/audit.log}")
    private String auditLogPath;

    private static final int DEFAULT_LIMIT = 50;
    private static final int MAX_LIMIT     = 200;

    /**
     * Returns recent audit log entries for a use case.
     *
     * @param useCase use case to filter by e.g. "dmt"
     * @param limit   max entries to return (default 50, max 200)
     *
     * Response:
     * {
     *   "useCase": "dmt",
     *   "limit": 50,
     *   "entries": [
     *     {
     *       "timestamp": "2026-07-15T10:30:00Z",
     *       "useCase": "dmt",
     *       "overallSuccess": true,
     *       "stepResults": [...],
     *       ...
     *     }
     *   ]
     * }
     */
    @GetMapping("/{useCase}")
    @PreAuthorize("hasAnyRole('OPERATOR', 'VIEWER', 'ADMIN')")
    public ResponseEntity<?> getAuditEntries(
            @PathVariable String useCase,
            @RequestParam(defaultValue = "50") int limit) {

        /*
         * Clamp limit to prevent oversized responses.
         */
        int effectiveLimit = Math.min(Math.max(1, limit), MAX_LIMIT);

        log.debug("Audit request: useCase={} limit={}", useCase, effectiveLimit);

        /*
         * Check audit log file exists.
         */
        if (!Files.exists(Paths.get(auditLogPath))) {
            return ResponseEntity.ok(Map.of(
                "useCase", useCase,
                "limit",   effectiveLimit,
                "entries", List.of()
            ));
        }

        try {
            List<Map<String, Object>> entries =
                readAuditEntries(useCase.toLowerCase(), effectiveLimit);

            return ResponseEntity.ok(Map.of(
                "useCase", useCase,
                "limit",   effectiveLimit,
                "entries", entries
            ));

        } catch (IOException e) {
            log.error("Audit read error: useCase={} error={}", useCase, e.getMessage());
            return ResponseEntity.internalServerError()
                .body(Map.of("error", "Could not read audit log: " + e.getMessage()));
        }
    }

    // ── Private ───────────────────────────────────────────────────────

    /**
     * Reads audit log file, filters by useCase, returns most recent N entries.
     *
     * Strategy: read all matching lines into a list, take the last N.
     * Audit log is append-only — newest entries are at the bottom.
     * For very large logs, a proper log aggregator (ELK) should be used.
     * This implementation is suitable for operator review of recent activity.
     */
    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> readAuditEntries(String useCase,
                                                         int limit) throws IOException {
        List<Map<String, Object>> matching = new ArrayList<>();

        /*
         * BufferedReader — Apache Commons IO not needed here,
         * standard Java NIO BufferedReader is sufficient for line reading.
         * Jackson ObjectMapper parses each JSON line.
         */
        try (BufferedReader reader = new BufferedReader(
                new FileReader(auditLogPath))) {

            String line;
            while ((line = reader.readLine()) != null) {
                if (StringUtils.isBlank(line)) continue;

                try {
                    Map<String, Object> entry = objectMapper.readValue(
                        line, Map.class);

                    /*
                     * Filter by useCase — audit log contains entries
                     * for all use cases in one file.
                     */
                    if (useCase.equalsIgnoreCase(
                            String.valueOf(entry.get("useCase")))) {
                        matching.add(entry);
                    }
                } catch (Exception e) {
                    /*
                     * Malformed line — skip silently.
                     * Audit log should never have malformed lines
                     * but defensive handling prevents a single bad line
                     * from breaking the entire response.
                     */
                    log.warn("Malformed audit line skipped: {}", line);
                }
            }
        }

        /*
         * Return the last N entries — newest are at the bottom of the file.
         * subList from (size - limit) to end gives the most recent entries.
         */
        int size  = matching.size();
        int start = Math.max(0, size - limit);
        List<Map<String, Object>> recent = new ArrayList<>(
            matching.subList(start, size));

        /*
         * Reverse to return newest first — consistent with UI expectation.
         */
        java.util.Collections.reverse(recent);

        log.debug("Audit entries: useCase={} total={} returned={}",
            useCase, size, recent.size());

        return recent;
    }
}
