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
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * ═══════════════════════════════════════════════════════════════════
 * PollerManager — Lifecycle Manager for All Use Case Pollers
 * ═══════════════════════════════════════════════════════════════════
 *
 * Starts one UseCasePoller per active use case at application startup.
 * All pollers run simultaneously — independent watcher threads per use case.
 *
 * @RefreshScope — when POST /actuator/refresh is called:
 *   1. Spring Cloud Config re-reads integration.usecases from Config Server
 *   2. PollerManager bean is refreshed
 *   3. @PreDestroy stops all existing pollers
 *   4. @PostConstruct starts pollers for the updated use case list
 *   New use case becomes active without application restart. (Option A)
 *
 * Upload directory per use case: {integration.upload.root}/{usecase}/
 * e.g. /uploads/dmt/, /uploads/cms/, /uploads/fastag/, /uploads/internal/
 */
@Slf4j
@Component
@RefreshScope
@RequiredArgsConstructor
public class PollerManager {

    private final Pipeline             pipeline;
    private final StepLoader           stepLoader;
    private final FileReaderResolver   readerResolver;
    private final ValidationRuleLoader ruleLoader;
    private final Environment          environment;

    @Value("${integration.upload.root:/uploads}")
    private String uploadRoot;

    @Value("${integration.poller.interval.seconds:10}")
    private long pollIntervalSeconds;

    @Value("${integration.usecases}")
    private String useCasesProperty;

    /*
     * All active poller instances — stopped cleanly on shutdown or refresh.
     */
    private final List<UseCasePoller> activePollers = new ArrayList<>();

    /**
     * Starts one UseCasePoller per active use case.
     * Called at application startup and after /actuator/refresh.
     */
    @PostConstruct
    public void startPollers() {
        /*
         * Active Spring profile — passed to each poller for pipeline context.
         * Pipeline uses this to select the correct DB/Aerospike connection.
         */
        String activeProfile = environment.getActiveProfiles().length > 0
            ? environment.getActiveProfiles()[0] : "dev";

        long pollIntervalMs = pollIntervalSeconds * 1000;

        Arrays.stream(useCasesProperty.split(","))
            .map(StringUtils::trimToEmpty)
            .filter(StringUtils::isNotBlank)
            .forEach(useCase -> {
                try {
                    String uploadDir = uploadRoot + "/" + useCase;

                    UseCasePoller poller = new UseCasePoller(
                        useCase,
                        uploadDir,
                        pollIntervalMs,
                        pipeline,
                        stepLoader,
                        readerResolver,
                        ruleLoader,
                        activeProfile
                    );

                    poller.start();
                    activePollers.add(poller);

                    log.info("Poller registered: useCase={} uploadDir={}",
                        useCase, uploadDir);

                } catch (Exception e) {
                    /*
                     * One poller failing to start does not prevent others.
                     * Error logged — operator should check Config Server and
                     * step files for the failing use case.
                     */
                    log.error("Failed to start poller: useCase={} error={}",
                        useCase, e.getMessage());
                }
            });

        log.info("PollerManager started: {} pollers active", activePollers.size());
    }

    /**
     * Stops all active pollers cleanly.
     * Called on application shutdown and before /actuator/refresh re-init.
     */
    @PreDestroy
    public void stopPollers() {
        log.info("PollerManager stopping: {} pollers to stop", activePollers.size());
        activePollers.forEach(UseCasePoller::stop);
        activePollers.clear();
        log.info("PollerManager stopped");
    }
}
