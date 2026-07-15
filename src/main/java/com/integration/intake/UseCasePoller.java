package com.integration.intake;

import com.integration.core.ExecutionContext;
import com.integration.core.Pipeline;
import com.integration.core.StepCommand;
import com.integration.core.StepLoader;
import com.integration.core.ValidationRule;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.filefilter.FileFilterUtils;
import org.apache.commons.io.monitor.FileAlterationListenerAdaptor;
import org.apache.commons.io.monitor.FileAlterationMonitor;
import org.apache.commons.io.monitor.FileAlterationObserver;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * ═══════════════════════════════════════════════════════════════════
 * UseCasePoller — File Presence Watcher per Use Case
 * ═══════════════════════════════════════════════════════════════════
 *
 * Uses Apache Commons IO FileAlterationMonitor — no custom thread
 * management, no custom polling loop.
 *
 * Apache Commons IO handles:
 *   - Directory watching via FileAlterationObserver
 *   - File creation event via FileAlterationListenerAdaptor.onFileCreate()
 *   - Watcher thread lifecycle (start/stop)
 *   - Polling interval configuration
 *
 * One UseCasePoller instance per use case — all run simultaneously
 * in independent watcher threads. DMT and CMS process files
 * at the same time without blocking each other. (Option A)
 *
 * On new file detected:
 *   1. Resolve FileReader by extension (CSV or JSON)
 *   2. Read all rows via FileReader
 *   3. Run Pipeline.execute() per row
 *   4. Move file to processed/ subfolder (prevents re-processing)
 *
 * Steps and validation rules loaded once at poller startup from
 * StepLoader cache — no file I/O per row at runtime.
 *
 * Processed folder: {uploadDir}/processed/{timestamp}_{filename}
 * Ensures original file is never re-processed on next poll cycle.
 */
@Slf4j
public class UseCasePoller {

    private final String              useCase;
    private final String              uploadDir;
    private final long                pollIntervalMs;
    private final Pipeline            pipeline;
    private final StepLoader          stepLoader;
    private final FileReaderResolver  readerResolver;
    private final ValidationRuleLoader ruleLoader;
    private final String              activeProfile;

    /*
     * Apache Commons IO FileAlterationMonitor — manages the watcher thread.
     * Stopped cleanly on application shutdown.
     */
    private FileAlterationMonitor monitor;

    public UseCasePoller(String useCase,
                         String uploadDir,
                         long pollIntervalMs,
                         Pipeline pipeline,
                         StepLoader stepLoader,
                         FileReaderResolver readerResolver,
                         ValidationRuleLoader ruleLoader,
                         String activeProfile) {
        this.useCase        = useCase;
        this.uploadDir      = uploadDir;
        this.pollIntervalMs = pollIntervalMs;
        this.pipeline       = pipeline;
        this.stepLoader     = stepLoader;
        this.readerResolver = readerResolver;
        this.ruleLoader     = ruleLoader;
        this.activeProfile  = activeProfile;
    }

    /**
     * Starts the file watcher for this use case's upload directory.
     * Apache Commons IO manages the watcher thread — no manual thread creation.
     *
     * Steps and validation rules are loaded from cache at start —
     * changes to step files are picked up by StepLoader's file watcher separately.
     */
    public void start() throws Exception {
        /*
         * Ensure upload directory and processed/ subfolder exist.
         * Apache Commons IO FileUtils.forceMkdir() creates all parent dirs.
         */
        File uploadFolder    = new File(uploadDir);
        File processedFolder = new File(uploadDir, "processed");
        FileUtils.forceMkdir(uploadFolder);
        FileUtils.forceMkdir(processedFolder);

        /*
         * Load steps and validation rules once at startup.
         * StepLoader cache serves these — no file I/O per row.
         */
        List<StepCommand>   steps = stepLoader.loadSteps(useCase);
        List<ValidationRule> rules = ruleLoader.load(useCase);

        log.info("Poller starting: useCase={} uploadDir={} steps={} rules={} intervalMs={}",
            useCase, uploadDir, steps.size(), rules.size(), pollIntervalMs);

        /*
         * FileAlterationObserver watches the upload directory.
         * FileFilterUtils.fileFileFilter() — only watches files, not subdirectories.
         * This prevents the observer from triggering on processed/ folder events.
         */
        FileAlterationObserver observer = new FileAlterationObserver(
            uploadFolder,
            FileFilterUtils.and(
                FileFilterUtils.fileFileFilter(),
                FileFilterUtils.notFileFilter(
                    FileFilterUtils.prefixFileFilter(".")  // skip hidden files
                )
            )
        );

        /*
         * FileAlterationListenerAdaptor — only override onFileCreate().
         * Apache Commons IO calls this when a new file appears in the directory.
         * No polling logic, no thread management needed.
         */
        observer.addListener(new FileAlterationListenerAdaptor() {
            @Override
            public void onFileCreate(File file) {
                /*
                 * Skip files inside the processed/ subfolder.
                 * Observer watches the parent directory — this guard
                 * prevents triggering on files moved into processed/.
                 */
                if (file.getParentFile().getName().equals("processed")) {
                    return;
                }
                log.info("File detected: useCase={} file={}", useCase, file.getName());
                processFile(file, steps, rules);
            }
        });

        /*
         * FileAlterationMonitor manages the watcher thread.
         * pollIntervalMs controls how often the observer checks for changes.
         * start() launches the background watcher thread.
         */
        monitor = new FileAlterationMonitor(pollIntervalMs, observer);
        monitor.start();

        log.info("Poller started: useCase={} watching={}",
            useCase, uploadFolder.getAbsolutePath());
    }

    /**
     * Stops the file watcher cleanly.
     * Called by PollerManager on application shutdown.
     */
    public void stop() {
        if (monitor != null) {
            try {
                monitor.stop();
                log.info("Poller stopped: useCase={}", useCase);
            } catch (Exception e) {
                log.error("Error stopping poller: useCase={} error={}", useCase, e.getMessage());
            }
        }
    }

    // ── Private ───────────────────────────────────────────────────────

    /**
     * Reads file, runs pipeline per row, moves file to processed/.
     * Always moves the file — even on partial failure — to prevent
     * re-processing on next poll cycle.
     */
    private void processFile(File file,
                             List<StepCommand> steps,
                             List<ValidationRule> rules) {
        int succeeded = 0;
        int failed    = 0;

        try {
            /*
             * FileReaderResolver picks CSV or JSON reader by file extension.
             * Apache Commons IO FilenameUtils used internally.
             */
            FileReader reader = readerResolver.resolve(file.getName());
            List<Map<String, String>> rows;

            try (InputStream stream = new FileInputStream(file)) {
                rows = reader.read(stream);
            }

            log.info("Processing: useCase={} file={} rows={}",
                useCase, file.getName(), rows.size());

            for (Map<String, String> row : rows) {
                /*
                 * Pipeline.execute() runs the full fixed pipeline for one row.
                 * Stages: validate → execute → rollback → post-validate → audit.
                 * Never throws — all exceptions caught and recorded in context.
                 */
                ExecutionContext ctx = pipeline.execute(
                    useCase, row, steps, rules, activeProfile);

                if (ctx.hasFailure()) {
                    failed++;
                } else {
                    succeeded++;
                }
            }

            log.info("File complete: useCase={} file={} succeeded={} failed={}",
                useCase, file.getName(), succeeded, failed);

        } catch (IOException e) {
            log.error("File read error: useCase={} file={} error={}",
                useCase, file.getName(), e.getMessage());
        } finally {
            /*
             * Always move file to processed/ — regardless of outcome.
             * Timestamp prefix prevents filename collisions on reprocessing.
             */
            moveToProcessed(file);
        }
    }

    /**
     * Moves processed file to {uploadDir}/processed/{timestamp}_{filename}.
     * Uses Apache Commons IO FileUtils.moveFile() — atomic on same filesystem.
     */
    private void moveToProcessed(File file) {
        try {
            String timestamp  = String.valueOf(Instant.now().toEpochMilli());
            Path   destPath   = Paths.get(uploadDir, "processed",
                timestamp + "_" + file.getName());

            /*
             * Apache Commons IO FileUtils.moveFile() handles:
             *   - Cross-filesystem moves (copy + delete)
             *   - Parent directory creation
             * No custom move logic needed.
             */
            FileUtils.moveFile(file, destPath.toFile());
            log.info("Moved to processed: {}", destPath.getFileName());

        } catch (IOException e) {
            log.error("Failed to move file to processed: file={} error={}",
                file.getName(), e.getMessage());
        }
    }
}
