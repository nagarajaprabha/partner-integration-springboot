package com.integration.intake;

import com.integration.audit.ResultReportWriter;
import com.integration.core.ExecutionContext;
import com.integration.core.Pipeline;
import com.integration.core.StepCommand;
import com.integration.core.StepLoader;
import com.integration.core.ValidationRule;
import com.jcraft.jsch.ChannelSftp;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.io.FileUtils;
import org.springframework.integration.sftp.session.SftpRemoteFileTemplate;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * ═══════════════════════════════════════════════════════════════════
 * UseCasePoller — SFTP Poller for One Use Case
 * ═══════════════════════════════════════════════════════════════════
 *
 * Uses Spring Integration SftpRemoteFileTemplate — no manual JSch
 * session/channel management.
 *
 * Per-poll cycle:
 *   1. List files in remote /uploads/{usecase}/
 *   2. For each file (CSV or JSON):
 *      a. Download to local staging
 *      b. Read all rows via FileReader
 *      c. Run Pipeline.execute() per row — collect ExecutionContext list
 *      d. Generate CSV result report via ResultReportWriter
 *      e. Upload result report to /uploads/{usecase}/results/
 *      f. Move original file to /uploads/{usecase}/processed/
 *      g. Delete local staging copy
 *
 * Result report — one CSV file per uploaded data file:
 *   Path: /uploads/{usecase}/results/{timestamp}_{filename}_result.csv
 *   Operator downloads this file to review successes, failures, errors.
 *
 * Steps and validation rules loaded from StepLoader cache at first poll.
 */
@Slf4j
public class UseCasePoller implements Runnable {

    private final String                 useCase;
    private final String                 remoteUploadPath;
    private final String                 remoteProcessedPath;
    private final String                 remoteResultsPath;
    private final String                 localStagingPath;
    private final SftpRemoteFileTemplate sftpTemplate;
    private final Pipeline               pipeline;
    private final StepLoader             stepLoader;
    private final FileReaderResolver     readerResolver;
    private final ValidationRuleLoader   ruleLoader;
    private final ResultReportWriter     reportWriter;
    private final String                 activeProfile;

    // Cached at first poll — constant per use case
    private List<StepCommand>    cachedSteps;
    private List<ValidationRule> cachedRules;

    public UseCasePoller(String useCase,
                         String sftpUploadRoot,
                         String localStagingRoot,
                         SftpRemoteFileTemplate sftpTemplate,
                         Pipeline pipeline,
                         StepLoader stepLoader,
                         FileReaderResolver readerResolver,
                         ValidationRuleLoader ruleLoader,
                         ResultReportWriter reportWriter,
                         String activeProfile) {
        this.useCase             = useCase;
        this.remoteUploadPath    = sftpUploadRoot + "/" + useCase;
        this.remoteProcessedPath = sftpUploadRoot + "/" + useCase + "/processed";
        this.remoteResultsPath   = sftpUploadRoot + "/" + useCase + "/results";
        this.localStagingPath    = localStagingRoot + "/" + useCase;
        this.sftpTemplate        = sftpTemplate;
        this.pipeline            = pipeline;
        this.stepLoader          = stepLoader;
        this.readerResolver      = readerResolver;
        this.ruleLoader          = ruleLoader;
        this.reportWriter        = reportWriter;
        this.activeProfile       = activeProfile;
    }

    @Override
    public void run() {
        log.debug("POLL CYCLE: useCase={}", useCase);

        /*
         * Load steps and rules lazily on first poll.
         * StepLoader cache serves subsequent calls — no file I/O.
         */
        if (cachedSteps == null) {
            cachedSteps = stepLoader.loadSteps(useCase);
            cachedRules = ruleLoader.load(useCase);
            log.info("Steps cached: useCase={} steps={} rules={}",
                useCase, cachedSteps.size(), cachedRules.size());
        }

        try {
            /*
             * List remote directory — Spring Integration handles session.
             */
            ChannelSftp.LsEntry[] entries = sftpTemplate.execute(session ->
                session.list(remoteUploadPath + "/*")
            );

            if (entries == null || entries.length == 0) {
                log.debug("No files: useCase={}", useCase);
                return;
            }

            /*
             * Filter: files only, no hidden files, supported extensions.
             * Skips processed/ and results/ subdirectories (isDir check).
             */
            Arrays.stream(entries)
                .filter(e -> !e.getAttrs().isDir())
                .filter(e -> !e.getFilename().startsWith("."))
                .filter(e -> isSupportedExtension(e.getFilename()))
                .forEach(e -> processRemoteFile(e.getFilename()));

        } catch (Exception e) {
            log.error("POLL ERROR: useCase={} error={}", useCase, e.getMessage());
        }
    }

    // ── Private ───────────────────────────────────────────────────────

    /**
     * Downloads file, runs pipeline per row, generates result report,
     * uploads report to SFTP results/, moves original to processed/.
     */
    private void processRemoteFile(String filename) {
        log.info("File detected: useCase={} file={}", useCase, filename);

        String remotePath = remoteUploadPath + "/" + filename;
        File   localFile  = Paths.get(localStagingPath, filename).toFile();

        /*
         * Collect ExecutionContext for every row — used to generate
         * the result report after all rows are processed.
         */
        List<ExecutionContext> allResults = new ArrayList<>();

        try {
            /*
             * Create local staging directory.
             * Apache Commons IO FileUtils.forceMkdir().
             */
            FileUtils.forceMkdir(localFile.getParentFile());

            /*
             * Download remote file to local staging.
             * Spring Integration SftpRemoteFileTemplate.get() handles session.
             * Apache Commons IO FileUtils.copyInputStreamToFile() writes bytes.
             */
            sftpTemplate.get(remotePath,
                stream -> FileUtils.copyInputStreamToFile(stream, localFile));

            log.info("Downloaded: useCase={} file={} bytes={}",
                useCase, filename, localFile.length());

            /*
             * Resolve reader by file extension — CSV or JSON.
             */
            FileReader reader = readerResolver.resolve(filename);
            List<Map<String, String>> rows;

            try (InputStream stream = new FileInputStream(localFile)) {
                rows = reader.read(stream);
            }

            log.info("Processing: useCase={} file={} rows={}",
                useCase, filename, rows.size());

            /*
             * Run Pipeline per row — never throws.
             * Collect every ExecutionContext for the result report.
             */
            for (Map<String, String> row : rows) {
                ExecutionContext ctx = pipeline.execute(
                    useCase, row, cachedSteps, cachedRules, activeProfile);
                allResults.add(ctx);
            }

            long succeeded = allResults.stream().filter(r -> !r.hasFailure()).count();
            long failed    = allResults.size() - succeeded;

            log.info("File complete: useCase={} file={} succeeded={} failed={}",
                useCase, filename, succeeded, failed);

            /*
             * Generate CSV result report from all row results.
             * Upload report to /uploads/{usecase}/results/ on SFTP.
             * Operator downloads this file to review outcomes.
             */
            uploadResultReport(filename, allResults);

        } catch (IOException e) {
            log.error("Processing error: useCase={} file={} error={}",
                useCase, filename, e.getMessage());
        } finally {
            /*
             * Always move original file to processed/ on SFTP.
             * Always delete local staging copy.
             */
            moveRemoteToProcessed(remotePath, filename);
            FileUtils.deleteQuietly(localFile);
        }
    }

    /**
     * Generates CSV result report and uploads to SFTP results/ folder.
     *
     * Report path: /uploads/{usecase}/results/{timestamp}_{filename}_result.csv
     * Operator navigates to this path on SFTP to download and review results.
     */
    private void uploadResultReport(String originalFilename,
                                     List<ExecutionContext> results) {
        String timestamp   = String.valueOf(Instant.now().toEpochMilli());
        String reportName  = timestamp + "_" + originalFilename + "_result.csv";
        String remotePath  = remoteResultsPath + "/" + reportName;

        try {
            /*
             * ResultReportWriter generates CSV in memory — no local file.
             * Returns InputStream of CSV bytes ready for SFTP upload.
             */
            InputStream reportStream = reportWriter.generateReport(results, useCase);

            /*
             * SftpRemoteFileTemplate.send() — uploads InputStream to remote path.
             * setAutoCreateDirectory(true) in SftpConfig ensures
             * /results/ folder is created if it does not exist.
             */
            sftpTemplate.send(reportStream, remotePath);

            log.info("Result report uploaded: useCase={} path={} rows={}",
                useCase, remotePath, results.size());

        } catch (Exception e) {
            log.error("Result report upload failed: useCase={} file={} error={}",
                useCase, originalFilename, e.getMessage());
        }
    }

    /**
     * Renames remote original file to /uploads/{usecase}/processed/{ts}_{name}.
     */
    private void moveRemoteToProcessed(String remotePath, String filename) {
        try {
            String processedPath = remoteProcessedPath + "/"
                + Instant.now().toEpochMilli() + "_" + filename;
            sftpTemplate.rename(remotePath, processedPath);
            log.info("Moved to SFTP processed: useCase={} file={}", useCase, filename);
        } catch (Exception e) {
            log.error("Move to processed failed: useCase={} file={} error={}",
                useCase, filename, e.getMessage());
        }
    }

    private boolean isSupportedExtension(String filename) {
        try {
            readerResolver.resolve(filename);
            return true;
        } catch (IllegalArgumentException e) {
            log.debug("Skipping unsupported file: useCase={} file={}", useCase, filename);
            return false;
        }
    }
}
