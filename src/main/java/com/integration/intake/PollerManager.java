package com.integration.intake;

import com.integration.core.Pipeline;
import com.integration.core.StepLoader;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.context.config.annotation.RefreshScope;
import org.springframework.core.env.Environment;
import org.springframework.integration.sftp.session.SftpRemoteFileTemplate;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.scheduling.support.PeriodicTrigger;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ScheduledFuture;

/**
 * ═══════════════════════════════════════════════════════════════════
 * PollerManager — Lifecycle Manager for All Use Case Pollers
 * ═══════════════════════════════════════════════════════════════════
 *
 * Starts one UseCasePoller per active use case at application startup.
 * Each poller runs in its own thread via ThreadPoolTaskScheduler —
 * all use cases polled simultaneously. (Option A)
 *
 * ThreadPoolTaskScheduler:
 *   - Pool size = number of active use cases
 *   - Each poller runs independently — DMT poll does not block CMS poll
 *   - PeriodicTrigger controls poll interval per poller
 *
 * @RefreshScope — POST /actuator/refresh:
 *   1. @PreDestroy stops all active pollers and scheduler
 *   2. Spring Cloud Config re-reads integration.usecases
 *   3. @PostConstruct starts fresh pollers for updated use case list
 *   New use case live without application restart. (Option A)
 *
 * SFTP path per use case: {sftp.upload.root}/{usecase}/
 * Local staging:          {sftp.local.staging.root}/{usecase}/
 */
@Slf4j
@Component
@RefreshScope
@RequiredArgsConstructor
public class PollerManager {

    private final Pipeline               pipeline;
    private final StepLoader             stepLoader;
    private final FileReaderResolver     readerResolver;
    private final ValidationRuleLoader   ruleLoader;
    private final SftpRemoteFileTemplate sftpTemplate;
    private final Environment            environment;

    @Value("${sftp.upload.root:/uploads}")
    private String sftpUploadRoot;

    @Value("${sftp.local.staging.root:${java.io.tmpdir}/pip-staging}")
    private String localStagingRoot;

    @Value("${integration.poller.interval.seconds:10}")
    private long pollIntervalSeconds;

    @Value("${integration.usecases}")
    private String useCasesProperty;

    /*
     * Scheduler and future references — stopped cleanly on shutdown or refresh.
     */
    private ThreadPoolTaskScheduler       scheduler;
    private final List<ScheduledFuture<?>> scheduledFutures = new ArrayList<>();

    /**
     * Starts one UseCasePoller per active use case.
     * Called at application startup and after /actuator/refresh.
     *
     * ThreadPoolTaskScheduler pool size = number of use cases,
     * so each poller gets its own dedicated thread.
     */
    @PostConstruct
    public void startPollers() {
        String activeProfile = environment.getActiveProfiles().length > 0
            ? environment.getActiveProfiles()[0] : "dev";

        String[] useCases = Arrays.stream(useCasesProperty.split(","))
            .map(StringUtils::trimToEmpty)
            .filter(StringUtils::isNotBlank)
            .toArray(String[]::new);

        /*
         * ThreadPoolTaskScheduler — pool size matches use case count.
         * Each use case poller runs in its own dedicated thread.
         * No use case blocks another.
         */
        scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(useCases.length);
        scheduler.setThreadNamePrefix("poller-");
        scheduler.initialize();

        /*
         * PeriodicTrigger — fires each poller every pollIntervalSeconds.
         * Separate trigger instance per poller — independent scheduling.
         */
        Duration pollInterval = Duration.ofSeconds(pollIntervalSeconds);

        for (String useCase : useCases) {
            try {
                UseCasePoller poller = new UseCasePoller(
                    useCase,
                    sftpUploadRoot,
                    localStagingRoot,
                    sftpTemplate,
                    pipeline,
                    stepLoader,
                    readerResolver,
                    ruleLoader,
                    activeProfile
                );

                ScheduledFuture<?> future = scheduler.schedule(
                    poller,
                    new PeriodicTrigger(pollInterval)
                );

                scheduledFutures.add(future);
                log.info("Poller scheduled: useCase={} interval={}s",
                    useCase, pollIntervalSeconds);

            } catch (Exception e) {
                /*
                 * One poller failing to start does not prevent others.
                 * Operator should check Config Server and step files.
                 */
                log.error("Failed to schedule poller: useCase={} error={}",
                    useCase, e.getMessage());
            }
        }

        log.info("PollerManager started: {} pollers scheduled", scheduledFutures.size());
    }

    /**
     * Stops all active pollers and shuts down scheduler cleanly.
     * Called on application shutdown and before /actuator/refresh re-init.
     */
    @PreDestroy
    public void stopPollers() {
        log.info("PollerManager stopping: {} pollers", scheduledFutures.size());

        /*
         * Cancel each scheduled poller — mayInterruptIfRunning=false
         * allows current poll cycle to complete before stopping.
         */
        scheduledFutures.forEach(f -> f.cancel(false));
        scheduledFutures.clear();

        if (scheduler != null) {
            scheduler.shutdown();
            scheduler = null;
        }

        log.info("PollerManager stopped");
    }
}
