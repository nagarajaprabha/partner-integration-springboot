package com.integration.config;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * Reads credentials from Hashicorp Vault per use case.
 *
 * Spring Cloud Vault binds Vault secrets into the Spring Environment
 * at bootstrap time under the path:
 *   secret/integration/{usecase} → {usecase}.db.username
 *                                   {usecase}.db.password
 *                                   {usecase}.aerospike.username (if applicable)
 *                                   {usecase}.aerospike.password (if applicable)
 *
 * This class reads those bound properties from Environment.
 * No direct Vault SDK calls — Spring Cloud Vault handles the Vault session.
 *
 * Credentials are NEVER logged.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class VaultConfig {

    private final Environment environment;

    /**
     * Returns DB credentials for a use case from Vault-bound properties.
     * Throws IllegalStateException if credentials are missing —
     * application must not start without valid credentials.
     */
    public DbCredentials getDbCredentials(String useCase) {
        String usernameKey = useCase + ".db.username";
        String passwordKey = useCase + ".db.password";

        String username = environment.getProperty(usernameKey);
        String password = environment.getProperty(passwordKey);

        if (username == null || username.isBlank()) {
            throw new IllegalStateException(
                "Vault credential missing: " + usernameKey +
                " — check Vault path: secret/integration/" + useCase);
        }
        if (password == null || password.isBlank()) {
            throw new IllegalStateException(
                "Vault credential missing: " + passwordKey +
                " — check Vault path: secret/integration/" + useCase);
        }

        log.info("DB credentials loaded from Vault: useCase={}", useCase);
        return DbCredentials.of(username, password);
    }

    /**
     * Returns Aerospike credentials for a use case from Vault-bound properties.
     * Returns empty credentials if not present — Aerospike auth is optional
     * depending on cluster configuration.
     */
    public AerospikeCredentials getAerospikeCredentials(String useCase) {
        String username = environment.getProperty(useCase + ".aerospike.username", "");
        String password = environment.getProperty(useCase + ".aerospike.password", "");

        log.info("Aerospike credentials loaded from Vault: useCase={} authEnabled={}",
            useCase, !username.isBlank());
        return AerospikeCredentials.of(username, password);
    }

    // ── Credential value objects — simple, immutable ─────────────────

    public record DbCredentials(String username, String password) {
        static DbCredentials of(String u, String p) { return new DbCredentials(u, p); }
    }

    public record AerospikeCredentials(String username, String password) {
        public boolean isAuthEnabled() { return !username.isBlank(); }
        static AerospikeCredentials of(String u, String p) {
            return new AerospikeCredentials(u, p);
        }
    }
}
