package com.integration;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.context.config.annotation.RefreshScope;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * ═══════════════════════════════════════════════════════════════════
 * Partner Integration Platform — Spring Boot Entry Point
 * ═══════════════════════════════════════════════════════════════════
 *
 * @SpringBootApplication     — component scan, auto-configuration
 * @EnableJpaAuditing         — BaseEntity createdAt/updatedAt population
 * @EnableScheduling          — ThreadPoolTaskScheduler in PollerManager
 *
 * Dynamic use case support (Option A):
 *   POST /actuator/refresh → Spring Cloud Config re-reads use case list
 *   PollerManager @RefreshScope stops old pollers, starts new ones
 *   New use case live without restart.
 *
 * No use case Java code required for standard onboarding.
 * Add {usecase}.properties to Config Server + step files to SFTP
 * config path + POST /actuator/refresh = new use case active.
 */
@SpringBootApplication
@EnableJpaAuditing
@EnableScheduling
@ComponentScan(basePackages = "com.integration")
public class PartnerIntegrationApplication {

    public static void main(String[] args) {
        SpringApplication.run(PartnerIntegrationApplication.class, args);
    }
}
