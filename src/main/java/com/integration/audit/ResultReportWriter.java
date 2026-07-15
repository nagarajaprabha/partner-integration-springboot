package com.integration.audit;

import com.integration.core.ExecutionContext;
import com.integration.core.StepResult;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVPrinter;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * ═══════════════════════════════════════════════════════════════════
 * ResultReportWriter — CSV Result Report Generator
 * ═══════════════════════════════════════════════════════════════════
 *
 * Generates one CSV result report per uploaded data file.
 * Called by UseCasePoller after all rows in a file are processed.
 *
 * Uses Apache Commons CSV CSVPrinter — no custom CSV writing logic.
 * Apache Commons CSV handles:
 *   - Header row generation
 *   - Value quoting (fields containing commas or newlines)
 *   - Null value handling
 *
 * Report format — one row per data row processed:
 *   rowNumber      — 1-based position in the original file
 *   status         — SUCCESS | FAILED | SKIPPED (pre-validate failed)
 *   useCase        — e.g. "DMT"
 *   partnerCode    — value from the data row (if present)
 *   stepsExecuted  — count of EXECUTE steps that ran
 *   failedStep     — name of the step that failed (empty if SUCCESS)
 *   errorMessage   — error detail (empty if SUCCESS)
 *   totalSteps     — total step results recorded in context
 *   executionMs    — sum of all step execution times
 *
 * Output: InputStream of CSV bytes — uploaded to SFTP by UseCasePoller.
 * No local file created — written to in-memory buffer only.
 */
@Slf4j
@Component
public class ResultReportWriter {

    /*
     * CSV report headers — fixed order, matches row output below.
     */
    private static final String[] HEADERS = {
        "rowNumber",
        "status",
        "useCase",
        "partnerCode",
        "stepsExecuted",
        "failedStep",
        "errorMessage",
        "totalSteps",
        "executionMs"
    };

    /*
     * Apache Commons CSV format for report output.
     * withHeader() adds header row automatically.
     * DEFAULT format uses comma delimiter, handles quoting.
     */
    private static final CSVFormat REPORT_FORMAT = CSVFormat.DEFAULT
        .builder()
        .setHeader(HEADERS)
        .build();

    /**
     * Generates a CSV result report from a list of ExecutionContext results.
     * Returns an InputStream of the CSV content — caller uploads to SFTP.
     *
     * @param results   ordered list of ExecutionContext, one per data row
     * @param useCase   use case name e.g. "dmt"
     * @return          InputStream of CSV report bytes (UTF-8)
     * @throws IOException if CSV generation fails
     */
    public InputStream generateReport(List<ExecutionContext> results,
                                       String useCase) throws IOException {
        /*
         * Write CSV to in-memory buffer.
         * No temp file created — avoids disk I/O and cleanup concerns.
         */
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();

        try (OutputStreamWriter writer = new OutputStreamWriter(buffer, StandardCharsets.UTF_8);
             CSVPrinter printer = new CSVPrinter(writer, REPORT_FORMAT)) {

            for (int i = 0; i < results.size(); i++) {
                ExecutionContext ctx = results.get(i);

                /*
                 * Determine row status:
                 *   SKIPPED — pre-validation failed (stepId=0 in results)
                 *   FAILED  — one or more EXECUTE steps failed
                 *   SUCCESS — all steps succeeded
                 */
                String status = determineStatus(ctx);

                /*
                 * Find the first failed step — name shown in report.
                 * Empty string if no failure.
                 */
                String failedStep = ctx.getStepResults().stream()
                    .filter(r -> !r.isSuccess())
                    .map(StepResult::getStepName)
                    .findFirst()
                    .orElse(StringUtils.EMPTY);

                /*
                 * Collect all error messages from failed steps.
                 * Joined with " | " for readability in one CSV cell.
                 */
                String errorMessage = ctx.getStepResults().stream()
                    .filter(r -> !r.isSuccess())
                    .map(StepResult::getMessage)
                    .filter(StringUtils::isNotBlank)
                    .reduce((a, b) -> a + " | " + b)
                    .orElse(StringUtils.EMPTY);

                /*
                 * Count EXECUTE steps that were attempted.
                 * Excludes ROLLBACK and POST_VALIDATE steps.
                 */
                long stepsExecuted = ctx.getStepResults().stream()
                    .filter(r -> r.getType() != null)
                    .filter(r -> r.getType().name().equals("EXECUTE")
                              || r.getType().name().equals("CONFIG_WRITE"))
                    .count();

                /*
                 * Sum all step execution times for total row processing time.
                 */
                long totalExecutionMs = ctx.getStepResults().stream()
                    .mapToLong(StepResult::getExecutionMs)
                    .sum();

                /*
                 * partnerCode — common identifier across use cases.
                 * Falls back to empty string if not present in row.
                 */
                String partnerCode = ctx.getRow()
                    .getOrDefault("partnerCode",
                        ctx.getRow().getOrDefault("mandateId",
                            ctx.getRow().getOrDefault("plazaCode",
                                ctx.getRow().getOrDefault("appId",
                                    StringUtils.EMPTY))));

                /*
                 * Apache Commons CSV CSVPrinter.printRecord() —
                 * handles quoting, escaping, and delimiter automatically.
                 * Values must match HEADERS order exactly.
                 */
                printer.printRecord(
                    i + 1,              // rowNumber (1-based)
                    status,             // SUCCESS | FAILED | SKIPPED
                    useCase,            // e.g. "dmt"
                    partnerCode,        // partner/mandate/plaza/app identifier
                    stepsExecuted,      // count of EXECUTE steps attempted
                    failedStep,         // name of first failed step
                    errorMessage,       // error messages joined with " | "
                    ctx.getStepResults().size(), // total step results
                    totalExecutionMs    // total processing time in ms
                );
            }

            /*
             * Flush CSVPrinter before reading the buffer.
             * Ensures all buffered content is written to ByteArrayOutputStream.
             */
            printer.flush();
        }

        log.info("Result report generated: useCase={} rows={} bytes={}",
            useCase, results.size(), buffer.size());

        return new ByteArrayInputStream(buffer.toByteArray());
    }

    /**
     * Determines the status string for one row's ExecutionContext.
     *
     * SKIPPED — pre-validation failed (step result with stepId=0)
     *           No DB writes occurred for this row.
     *
     * FAILED  — at least one EXECUTE step failed.
     *           Rollback may have run. Partial data may exist.
     *
     * SUCCESS — all steps completed without failure.
     */
    private String determineStatus(ExecutionContext ctx) {
        if (!ctx.hasFailure()) {
            return "SUCCESS";
        }
        boolean isPreValidateFail = ctx.getStepResults().stream()
            .anyMatch(r -> r.getStepId() == 0 && !r.isSuccess());
        return isPreValidateFail ? "SKIPPED" : "FAILED";
    }
}
