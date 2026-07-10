package com.integration.config;

import com.aerospike.client.AerospikeClient;
import com.aerospike.client.AerospikeException;
import com.aerospike.client.policy.ClientPolicy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * BaseAerospikeConfig — builds one AerospikeClient per use case.
 *
 * Mirrors BaseDBConfig pattern exactly:
 *   1. Reads connection details from Spring Cloud Config (via Environment)
 *      Keys: {usecase}.aerospike.host, .port, .namespace
 *   2. Reads credentials from Vault via VaultConfig
 *      Keys: {usecase}.aerospike.username, {usecase}.aerospike.password
 *   3. Builds AerospikeConnectionDto (Builder)
 *   4. Constructs AerospikeClient
 *
 * Only use cases with aerospike.host in their Config Server properties
 * get a client. DMT and CMS are skipped silently.
 *
 * Clients are built lazily and cached.
 * getClient() returns Optional.empty() for non-Aerospike use cases.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class BaseAerospikeConfig {

    private final Environment environment;
    private final VaultConfig vaultConfig;

    @Value("${integration.aerospike.timeout:3000}")
    private int timeout;

    @Value("${integration.aerospike.max-connections:50}")
    private int maxConnections;

    // Cache — one AerospikeClient per use case (only Aerospike-enabled use cases)
    private final Map<String, AerospikeClient> clients = new ConcurrentHashMap<>();

    /**
     * Returns AerospikeClient for a use case.
     * Returns Optional.empty() if this use case does not use Aerospike.
     * Built lazily on first access, cached for subsequent calls.
     */
    public Optional<AerospikeClient> getClient(String useCase) {
        String hostKey = useCase + ".aerospike.host";
        if (environment.getProperty(hostKey) == null) {
            // This use case does not have Aerospike configured — not an error
            return Optional.empty();
        }
        return Optional.of(clients.computeIfAbsent(useCase, this::buildClient));
    }

    /**
     * Returns the Aerospike namespace for a use case.
     * Called by executors to scope their Aerospike operations.
     */
    public String getNamespace(String useCase) {
        return environment.getProperty(useCase + ".aerospike.namespace",
            useCase + "_ns");
    }

    /**
     * Builds AerospikeConnectionDto for a use case by merging
     * Spring Cloud Config properties with Vault credentials.
     */
    public AerospikeConnectionDto buildConnectionDto(String useCase) {
        VaultConfig.AerospikeCredentials credentials =
            vaultConfig.getAerospikeCredentials(useCase);

        return AerospikeConnectionDto.builder()
            .useCase(useCase)
            .host(requireProperty(useCase + ".aerospike.host", useCase))
            .port(Integer.parseInt(
                environment.getProperty(useCase + ".aerospike.port", "3000")))
            .namespace(environment.getProperty(
                useCase + ".aerospike.namespace", useCase + "_ns"))
            .username(credentials.username())
            .password(credentials.password())
            .build();
    }

    // ── Private ───────────────────────────────────────────────────────

    private AerospikeClient buildClient(String useCase) {
        AerospikeConnectionDto dto = buildConnectionDto(useCase);

        ClientPolicy policy = new ClientPolicy();
        policy.timeout        = timeout;
        policy.maxConnsPerNode = maxConnections;

        // Enable authentication only if credentials are present in Vault
        if (!dto.getUsername().isBlank()) {
            policy.user     = dto.getUsername();
            policy.password = dto.getPassword();
        }

        try {
            AerospikeClient client = new AerospikeClient(
                policy, dto.getHost(), dto.getPort());

            log.info("AerospikeClient built: useCase={} host={} namespace={} auth={}",
                useCase, dto.getHost(), dto.getNamespace(),
                !dto.getUsername().isBlank());

            return client;
        } catch (AerospikeException e) {
            throw new IllegalStateException(
                "Failed to connect to Aerospike for use case '" + useCase +
                "' at " + dto.getHost() + ":" + dto.getPort(), e);
        }
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
