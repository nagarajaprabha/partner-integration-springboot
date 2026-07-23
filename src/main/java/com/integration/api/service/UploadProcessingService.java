package com.integration.api.service;

import com.integration.api.dto.UploadResultDto;
import com.integration.api.dto.UploadResultDto.RowResultDto;
import com.integration.audit.ResultReportWriter;
import com.integration.core.ExecutionContext;
import com.integration.core.Pipeline;
import com.integration.core.StepCommand;
import com.integration.core.StepLoader;
import com.integration.core.StepResult;
import com.integration.core.StepType;
import com.integration.core.ValidationRule;
import com.integration.intake.FileReader;
import com.integration.intake.FileReaderResolver;
import com.integration.intake.ValidationRuleLoader;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.integration.sftp.session.SftpRemoteFileTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * ═══════════════════════════════════════════════════════════════════
 * UploadProcessingService — Upload API Orchestration Layer
 * ═══════════════════════════════════════════════════════════════════
 *
 * Handles the full lifecycle of a browser-uploaded file:
 *   1. Resolve FileReader by file extension (CSV or JSON)
 *   2. Read all rows from the uploaded MultipartFile
 *   3. Load steps and validation rules for the use case
 *   4. Run Pipeline.execute() for each row — collect results
 *   5. Generate CSV result report via ResultReportWriter
 *   6. Upload result report to SFTP /uploads/{useCase}/results/
 *   7. Build and return UploadResultDto for the HTTP response
 *
 * Keeps UploadController thin — controller only handles HTTP concerns.
 * All business orchestration lives here.
 *
 * This is the REST API path (Option B):
 *   Browser → POST /api/upload/{useCase} → UploadController
 *   → UploadProcessingService → Pipeline → result in HTTP response
 *
 * The SFTP poller path remains unchanged and independent.
 * Both paths use the same Pipeline, FileReader, ResultReportWriter.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UploadProcessingService {

    private final FileReaderResolver   readerResolver;
    private final StepLoader           stepLoader;
    private final ValidationRuleLoader ruleLoader;
    private final Pipeline             pipeline;
    private final ResultReportWriter   reportWriter;
    private final SftpRemoteFileTemplate sftpTemplate;

    @Value("${sftp.upload.root:/uploads}")
    private String sftpUploadRoot;

    @Value("${spring.profiles.active:dev}")
    private String activeProfile;

    /**
     * Processes an uploaded file for a given use case.
     * Returns UploadResultDto containing per-row results.
     *
     * @param useCase   use case name e.g. "dmt"
     * @param file      uploaded MultipartFile (CSV or JSON)
     * @return          UploadResultDto with full processing results
     * @throws IOException if file cannot be read
     */
    public UploadResultDto process(String useCase,
                                    MultipartFile file) throws IOException {

        String filename = StringUtils.defaultIfBlank(
            file.getOriginalFilename(), "upload");

        log.info("Upload processing START: useCase={} file={} size={}",
            useCase, filename, file.getSize());

        /*
         * Resolve FileReader by file extension.
         * CsvFileReader for .csv, JsonFileReader for .json.
         * FileReaderResolver uses Apache Commons IO FilenameUtils.
         */
        FileReader reader = readerResolver.resolve(filename);

        /*
         * Read all rows from MultipartFile InputStream.
         * Apache Commons CSV / Jackson handle parsing — no custom logic.
         */
        List<Map<String, String>> rows;
        try (InputStream stream = file.getInputStream()) {
            rows = reader.read(stream);
        }

        log.info("Upload rows read: useCase={} file={} rows={}",
            useCase, filename, rows.size());

        /*
         * Load steps from StepLoader cache.
         * Steps are constant per use case — loaded once, cached.
         */
        List<StepCommand>    steps = stepLoader.loadSteps(useCase);
        List<ValidationRule> rules = ruleLoader.load(useCase);

        /*
         * Run Pipeline per row — collect all ExecutionContext results.
         * Pipeline never throws — all outcomes recorded in context.
         */
        List<ExecutionContext> allResults = new ArrayList<>();
        for (Map<String, String> row : rows) {
            ExecutionContext ctx = pipeline.execute(
                useCase, row, steps, rules, activeProfile);
            allResults.add(ctx);
        }

        /*
         * Generate CSV result report from all row results.
         * Upload to SFTP /uploads/{useCase}/results/ for audit trail.
         * Report path returned in UploadResultDto so frontend can show it.
         */
        String reportPath = uploadResultReport(useCase, filename, allResults);

        /*
         * Build and return UploadResultDto — HTTP response to frontend.
         */
        UploadResultDto result = buildResultDto(
            useCase, filename, allResults, reportPath);

        log.info("Upload processing COMPLETE: useCase={} file={} "
            + "succeeded={} failed={} skipped={}",
            useCase, filename,
            result.getSucceeded(), result.getFailed(), result.getSkipped());

        return result;
    }

    // ── Private ───────────────────────────────────────────────────────

    /**
     * Generates CSV result report and uploads to SFTP results/ folder.
     * Returns the remote SFTP path of the uploaded report.
     * Upload failure is logged but never propagates — result DTO still returned.
     */
    private String uploadResultReport(String useCase,
                                       String filename,
                                       List<ExecutionContext> results) {
        String timestamp  = String.valueOf(Instant.now().toEpochMilli());
        String reportName = timestamp + "_" + filename + "_result.csv";
        String remotePath = sftpUploadRoot + "/" + useCase + "/results/" + reportName;

        try {
            InputStream reportStream = reportWriter.generateReport(results, useCase);
            sftpTemplate.send(reportStream, remotePath);
            log.info("Result report uploaded: useCase={} path={}", useCase, remotePath);
        } catch (Exception e) {
            log.error("Result report upload failed: useCase={} error={}",
                useCase, e.getMessage());
        }

        return remotePath;
    }

    /**
     * Builds UploadResultDto from list of ExecutionContext results.
     * Maps each context to a RowResultDto.
     */
    private UploadResultDto buildResultDto(String useCase,
                                            String filename,
                                            List<ExecutionContext> allResults,
                                            String reportPath) {
        List<RowResultDto> rowResults = new ArrayList<>();

        for (int i = 0; i < allResults.size(); i++) {
            ExecutionContext ctx = allResults.get(i);
            rowResults.add(buildRowResult(i + 1, ctx));
        }

        long succeeded = rowResults.stream()
            .filter(r -> "SUCCESS".equals(r.getStatus())).count();
        long failed = rowResults.stream()
            .filter(r -> "FAILED".equals(r.getStatus())).count();
        long skipped = rowResults.stream()
            .filter(r -> "SKIPPED".equals(r.getStatus())).count();

        return UploadResultDto.builder()
            .useCase(useCase)
            .filename(filename)
            .totalRows(allResults.size())
            .succeeded(succeeded)
            .failed(failed)
            .skipped(skipped)
            .reportPath(reportPath)
            .results(rowResults)
            .build();
    }

    /**
     * Builds one RowResultDto from an ExecutionContext.
     * Mirrors the same logic used by ResultReportWriter for the CSV report.
     */
    private RowResultDto buildRowResult(int rowNumber, ExecutionContext ctx) {

        /*
         * Determine status — mirrors ResultReportWriter.determineStatus().
         */
        String status;
        if (!ctx.hasFailure()) {
            status = "SUCCESS";
        } else {
            boolean isPreValidate = ctx.getStepResults().stream()
                .anyMatch(r -> r.getStepId() == 0 && !r.isSuccess());
            status = isPreValidate ? "SKIPPED" : "FAILED";
        }

        /*
         * First failed step name — empty if SUCCESS.
         */
        String failedStep = ctx.getStepResults().stream()
            .filter(r -> !r.isSuccess())
            .map(StepResult::getStepName)
            .findFirst()
            .orElse(StringUtils.EMPTY);

        /*
         * All error messages joined — empty if SUCCESS.
         */
        String errorMessage = ctx.getStepResults().stream()
            .filter(r -> !r.isSuccess())
            .map(StepResult::getMessage)
            .filter(StringUtils::isNotBlank)
            .reduce((a, b) -> a + " | " + b)
            .orElse(StringUtils.EMPTY);

        /*
         * Count EXECUTE and CONFIG_WRITE steps that were attempted.
         */
        long stepsExecuted = ctx.getStepResults().stream()
            .filter(r -> r.getType() == StepType.EXECUTE
                      || r.getType() == StepType.CONFIG_WRITE)
            .count();

        /*
         * Total execution time across all steps.
         */
        long executionMs = ctx.getStepResults().stream()
            .mapToLong(StepResult::getExecutionMs)
            .sum();

        /*
         * Resolve partner identifier — try common key names.
         */
        String partnerCode = ctx.getRow().getOrDefault("partnerCode",
            ctx.getRow().getOrDefault("mandateId",
                ctx.getRow().getOrDefault("plazaCode",
                    ctx.getRow().getOrDefault("appId",
                        StringUtils.EMPTY))));

        return RowResultDto.builder()
            .rowNumber(rowNumber)
            .status(status)
            .partnerCode(partnerCode)
            .stepsExecuted(stepsExecuted)
            .failedStep(failedStep)
            .errorMessage(errorMessage)
            .executionMs(executionMs)
            .build();
    }
}
