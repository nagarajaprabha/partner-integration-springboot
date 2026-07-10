package com.integration.config;

import lombok.Builder;
import lombok.Getter;

/**
 * Builder pattern — carries all Oracle DB connection details for one use case.
 *
 * Sources:
 *   host, port, schema, serviceName, poolMin, poolMax
 *     → Spring Cloud Config ({usecase}.properties)
 *   username, password
 *     → Hashicorp Vault (secret/integration/{usecase})
 *
 * Built by BaseDBConfig per use case at application startup.
 */
@Getter
@Builder
public class DbConnectionDto {

    private final String useCase;       // e.g. "dmt", "cms"

    // From Spring Cloud Config
    private final String host;
    private final int    port;
    private final String schema;
    private final String serviceName;
    private final int    poolMinIdle;
    private final int    poolMaxSize;

    // From Hashicorp Vault — never logged, never stored in Config Server
    private final String username;
    private final String password;

    /**
     * Builds Oracle JDBC URL in thin driver format.
     * Pattern: jdbc:oracle:thin:@//host:port/serviceName
     */
    public String jdbcUrl() {
        return String.format("jdbc:oracle:thin:@//%s:%d/%s", host, port, serviceName);
    }
}
