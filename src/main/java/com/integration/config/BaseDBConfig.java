package com.integration.config;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * BaseDBConfig — builds one HikariCP DataSource per use case.
 *
 * For each use case:
 *   1. Reads connection details from Spring Cloud Config (via Environment)
 *      Keys: {usecase}.db.host, .port, .schema, .service-name,
 *            .hikari.minimum-idle, .hikari.maximum-pool-size
 *   2. Reads credentials from Vault via VaultConfig
 *      Keys: {usecase}.db.username, {usecase}.db.password
 *   3. Builds DbConnectionDto (Builder)
 *   4. Constructs HikariCP DataSource with Oracle JDBC URL
 *
 * DataSources are built eagerly at startup and cached.
 * Fail-fast: missing config or credentials throws at startup.
 *
 * No use case code touches this class. New use case = new entry
 * in integration.usecases property only.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class BaseDBConfig {

    private final Environment environment;
    private final VaultConfig vaultConfig;

    @Value("${integration.hikari.minimum-idle:2}")
    private int defaultMinIdle;

    @Value("${integration.hikari.maximum-pool-size:10}")
    private int defaultMaxPoolSize;

    @Value("${integration.hikari.connection-timeout:30000}")
    private long connectionTimeout;

    @Value("${integration.hikari.idle-timeout:600000}")
    private long idleTimeout;

    @Value("${integration.hikari.max-lifetime:1800000}")
    private long maxLifetime;

    @Value("${integration.hikari.connection-test-query:SELECT 1 FROM DUAL}")
    private String connectionTestQuery;

    @Value("${integration.usecases}")
    private List<String> useCases;

    // Cache — one DataSource per use case
    private final Map<String, DataSource> dataSources = new ConcurrentHashMap<>();

    /**
     * Returns the DataSource for a use case.
     * Built lazily on first access, cached for subsequent calls.
     */
    public DataSource getDataSource(String useCase) {
        return dataSources.computeIfAbsent(useCase, this::buildDataSource);
    }

    /**
     * Builds DbConnectionDto for a use case by merging
     * Spring Cloud Config properties with Vault credentials.
     * Called once per use case at first access.
     */
    public DbConnectionDto buildConnectionDto(String useCase) {
        VaultConfig.DbCredentials credentials = vaultConfig.getDbCredentials(useCase);

        return DbConnectionDto.builder()
            .useCase(useCase)
            .host(requireProperty(useCase + ".db.host", useCase))
            .port(Integer.parseInt(
                environment.getProperty(useCase + ".db.port", "1521")))
            .schema(requireProperty(useCase + ".db.schema", useCase))
            .serviceName(requireProperty(useCase + ".db.service-name", useCase))
            .poolMinIdle(Integer.parseInt(
                environment.getProperty(useCase + ".db.hikari.minimum-idle",
                    String.valueOf(defaultMinIdle))))
            .poolMaxSize(Integer.parseInt(
                environment.getProperty(useCase + ".db.hikari.maximum-pool-size",
                    String.valueOf(defaultMaxPoolSize))))
            .username(credentials.username())
            .password(credentials.password())
            .build();
    }

    // ── Private ───────────────────────────────────────────────────────

    private DataSource buildDataSource(String useCase) {
        DbConnectionDto dto = buildConnectionDto(useCase);

        HikariConfig hikari = new HikariConfig();
        hikari.setJdbcUrl(dto.jdbcUrl());
        hikari.setUsername(dto.getUsername());
        hikari.setPassword(dto.getPassword());
        hikari.setMinimumIdle(dto.getPoolMinIdle());
        hikari.setMaximumPoolSize(dto.getPoolMaxSize());
        hikari.setConnectionTimeout(connectionTimeout);
        hikari.setIdleTimeout(idleTimeout);
        hikari.setMaxLifetime(maxLifetime);
        hikari.setConnectionTestQuery(connectionTestQuery);
        hikari.setPoolName("HikariPool-" + useCase.toUpperCase());

        // Oracle-specific settings
        hikari.addDataSourceProperty("oracle.net.CONNECT_TIMEOUT", "10000");
        hikari.addDataSourceProperty("oracle.jdbc.ReadTimeout",    "30000");

        log.info("DataSource built: useCase={} host={} schema={} poolMax={}",
            useCase, dto.getHost(), dto.getSchema(), dto.getPoolMaxSize());

        return new HikariDataSource(hikari);
    }

    private String requireProperty(String key, String useCase) {
        String value = environment.getProperty(key);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(
                "Missing Spring Cloud Config property: '" + key +
                "' for use case '" + useCase +
                "' — check Config Server: " + useCase + ".properties");
        }
        return value;
    }
}
