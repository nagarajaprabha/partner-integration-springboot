package com.integration.intake;

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
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * ═══════════════════════════════════════════════════════════════════
 * UseCasePoller — SFTP Poller for One Use Case
 * ═══════════════════════════════════════════════════════════════════
 *
 * Uses Spring Integration SftpRemoteFileTemplate — no manual JSch
 * session/channel management needed.
 *
 * Spring Integration handles:
 *   - SFTP session lifecycle (open/close/reconnect)
 *   - Remote file listing via ChannelSftp.ls()
 *   - File download to local staging folder
 *   - Remote file rename (move to processed/)
 *
 * One UseCasePoller instance per use case.
 * All pollers run in separate threads via PollerManager's
 * ThreadPoolTaskScheduler — simultaneous onboarding. (Option A)
 *
 * Per-poll cycle:
 *   1. List files in remote /uploads/{usecase}/
 *   2. For each file (CSV or JSON only):
 *      a. Download to local staging folder
 *      b. Resolve FileReader by extension
 *      c. Read all rows
 *      d. Run Pipeline.execute() per row
 *      e. Rename remote file to /uploads/{usecase}/processed/{ts}_{name}
 *      f. Delete local staging copy
 *
 * Steps and validation rules loaded from StepLoader cache at first poll.
 * No file I/O per row at pipeline execution time.
 */
@Slf4j
public class UseCasePoller implements Runnable {

    private final String                 useCase;
    private final String                 remoteUploadPath;
    private final String                 remoteProcessedPath;
    private final String                 localStagingPath;
    private final SftpRemoteFileTemplate sftpTemplate;
    private final Pipeline               pipeline;
    private final StepLoader             stepLoader;
    private final FileReaderResolver     readerResolver;
    private final ValidationRuleLoader   ruleLoader;
    private final String                 activeProfile;

    // Cached at first poll — steps are constant per use case
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
                         String activeProfile) {
        this.useCase             = useCase;
        this.remoteUploadPath    = sftpUploadRoot + "/" + useCase;
        this.remoteProcessedPath = sftpUploadRoot + "/" + useCase + "/processed";
        this.localStagingPath    = localStagingRoot + "/" + useCase;
        this.sftpTemplate        = sftpTemplate;
        this.pipeline            = pipeline;
        this.stepLoader          = stepLoader;
        this.readerResolver      = readerResolver;
        this.ruleLoader          = ruleLoader;
        this.activeProfile       = activeProfile;
    }

    /**
     * One poll cycle — called by PollerManager scheduler per interval.
     * Lists remote files, processes each, moves to processed/ on SFTP.
     */
    @Override
    public void run() {
        log.debug("POLL CYCLE: useCase={} remotePath={}", useCase, remoteUploadPath);

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
             * SftpRemoteFileTemplate.execute() — lists remote directory.
             * Spring Integration handles SFTP session open/close.
             * Returns null if directory empty — guard added below.
             */
            ChannelSftp.LsEntry[] entries = sftpTemplate.execute(session ->
                session.list(remoteUploadPath + "/*")
            );

            if (entries == null || entries.length == 0) {
                log.debug("No files: useCase={}", useCase);
                return;
            }

            /*
             * Filter: files only, no hidden files, supported extensions only.
             * Skips the processed/ subdirectory automatically (isDir check).
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
     * Downloads one remote file, processes all rows, moves to processed/.
     * Always moves remote file — even on partial failure.
     */
    private void processRemoteFile(String filename) {
        log.info("File detected: useCase={} file={}", useCase, filename);

        String remotePath = remoteUploadPath + "/" + filename;
        File   localFile  = Paths.get(localStagingPath, filename).toFile();
        int    succeeded  = 0;
        int    failed     = 0;

        try {
            /*
             * Create local staging directory.
             * Apache Commons IO FileUtils.forceMkdir() creates all parents.
             */
            FileUtils.forceMkdir(localFile.getParentFile());

            /*
             * SftpRemoteFileTemplate.get() — downloads remote file.
             * Spring Integration manages SFTP session.
             * Apache Commons IO FileUtils.copyInputStreamToFile() writes to disk.
             */
            sftpTemplate.get(remotePath,
                stream -> FileUtils.copyInputStreamToFile(stream, localFile));

            log.info("Downloaded: useCase={} file={} bytes={}",
                useCase, filename, localFile.length());

            /*
             * Resolve reader (CSV or JSON) by file extension.
             * FileReaderResolver uses Apache Commons IO FilenameUtils.
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
             * All outcomes recorded in ExecutionContext and written to audit.
             */
            for (Map<String, String> row : rows) {
                ExecutionContext ctx = pipeline.execute(
                    useCase, row, cachedSteps, cachedRules, activeProfile);

                if (ctx.hasFailure()) { failed++;    }
                else                  { succeeded++; }
            }

            log.info("File complete: useCase={} file={} succeeded={} failed={}",
                useCase, filename, succeeded, failed);

        } catch (IOException e) {
            log.error("Processing error: useCase={} file={} error={}",
                useCase, filename, e.getMessage());
        } finally {
            /*
             * Always move remote file to processed/ on SFTP.
             * Prevents re-processing on next poll cycle.
             */
            moveRemoteToProcessed(remotePath, filename);

            /*
             * Delete local staging copy.
             * Apache Commons IO FileUtils.deleteQuietly() never throws.
             */
            FileUtils.deleteQuietly(localFile);
        }
    }

    /**
     * Renames remote file to /uploads/{usecase}/processed/{ts}_{filename}.
     * SftpRemoteFileTemplate.rename() — no manual SFTP channel needed.
     */
    private void moveRemoteToProcessed(String remotePath, String filename) {
        try {
            String processedPath = remoteProcessedPath + "/"
                + Instant.now().toEpochMilli() + "_" + filename;
            sftpTemplate.rename(remotePath, processedPath);
            log.info("Moved to SFTP processed: useCase={} dest={}",
                useCase, processedPath);
        } catch (Exception e) {
            log.error("Move to processed failed: useCase={} file={} error={}",
                useCase, filename, e.getMessage());
        }
    }

    /**
     * Returns true if filename has a reader-supported extension.
     * Single source of truth — delegates to FileReaderResolver.
     */
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
