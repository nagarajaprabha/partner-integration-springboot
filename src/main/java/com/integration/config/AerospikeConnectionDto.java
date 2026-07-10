package com.integration.config;

import lombok.Builder;
import lombok.Getter;

/**
 * Builder pattern — carries Aerospike connection details for one use case.
 *
 * Sources:
 *   host, port, namespace
 *     → Spring Cloud Config ({usecase}.properties)
 *   username, password
 *     → Hashicorp Vault (secret/integration/{usecase})
 *
 * Only populated for use cases that declare Aerospike:
 *   fastag, internal — have Aerospike.
 *   dmt, cms         — do not. BaseAerospikeConfig skips them.
 *
 * Built by BaseAerospikeConfig per use case at application startup.
 */
@Getter
@Builder
public class AerospikeConnectionDto {

    private final String useCase;       // e.g. "fastag", "internal"

    // From Spring Cloud Config
    private final String host;
    private final int    port;
    private final String namespace;

    // From Hashicorp Vault — never logged, never stored in Config Server
    private final String username;
    private final String password;
}
