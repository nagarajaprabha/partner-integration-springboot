package com.integration.core;

import com.aerospike.client.AerospikeClient;
import com.aerospike.client.Bin;
import com.aerospike.client.Key;
import com.aerospike.client.Record;
import com.aerospike.client.policy.WritePolicy;
import com.integration.config.BaseAerospikeConfig;
import com.integration.config.BaseDBConfig;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * ═══════════════════════════════════════════════════════════════════
 * StepExecutor — Platform Component
 * ═══════════════════════════════════════════════════════════════════
 *
 * Executes one StepCommand against the correct connector.
 * Routes by StepCommand.engine:
 *   SQL       → JdbcTemplate using DataSource from BaseDBConfig
 *   AEROSPIKE → AerospikeClient from BaseAerospikeConfig
 *
 * Called by Pipeline for every step type:
 *   EXECUTE, CONFIG_WRITE, POST_VALIDATE — forward execution
 *   ROLLBACK — via RollbackEngine which passes this::execute
 *
 * Use case teams never call or extend this class.
 * Adding a new use case requires zero changes here.
 *
 * ── SQL Execution ────────────────────────────────────────────────────
 *   Substitutes :placeholder values from row into query template.
 *   Executes via JdbcTemplate.update() for DML (INSERT/UPDATE/DELETE).
 *   Executes via JdbcTemplate.queryForObject() for POST_VALIDATE (SELECT COUNT).
 *   JdbcTemplate cached per use case — one per DataSource.
 *
 * ── Aerospike Execution ──────────────────────────────────────────────
 *   Parses AQL-style statements:
 *     INSERT INTO ns.set (PK, bin1, bin2) VALUES (:pk, :val1, :val2)
 *     DELETE FROM ns.set WHERE PK = :pk
 *   First column in INSERT is always the record PK.
 *   AerospikeClient cached via BaseAerospikeConfig.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class StepExecutor {

    private final BaseDBConfig         dbConfig;
    private final BaseAerospikeConfig  aerospikeConfig;

    /*
     * JdbcTemplate cache — one per use case.
     * Built lazily on first SQL step execution for a use case.
     * Reuses the DataSource built by BaseDBConfig (which is also cached).
     */
    private final Map<String, JdbcTemplate> jdbcTemplates = new ConcurrentHashMap<>();

    /**
     * Executes one step for a use case.
     * Routes to SQL or Aerospike based on step.getEngine().
     *
     * @param useCase use case name e.g. "dmt"
     * @param step    step to execute
     * @param row     data row — values substituted into query placeholders
     * @return        true if step succeeded
     */
    public boolean execute(String useCase, StepCommand step, Map<String, String> row) {
        String preparedQuery = step.prepareQuery(row);

        return switch (step.getEngine()) {
            case SQL       -> executeSql(useCase, step, preparedQuery);
            case AEROSPIKE -> executeAerospike(useCase, step, preparedQuery);
        };
    }

    // ── SQL ───────────────────────────────────────────────────────────

    /**
     * Executes a SQL statement via JdbcTemplate.
     *
     * POST_VALIDATE steps use SELECT COUNT(*) — result compared to
     * expectedResult declared in step file (EXPECTED_RESULT= tag).
     *
     * All other steps (EXECUTE, ROLLBACK, CONFIG_WRITE) use update().
     */
    private boolean executeSql(String useCase, StepCommand step, String sql) {
        JdbcTemplate jdbc = jdbcTemplates.computeIfAbsent(useCase,
            uc -> new JdbcTemplate(dbConfig.getDataSource(uc)));

        log.debug("SQL EXECUTE: useCase={} stepId={} sql={}", useCase, step.getStepId(), sql);

        if (step.getType() == StepType.POST_VALIDATE) {
            /*
             * POST_VALIDATE: execute SELECT COUNT(*) and compare
             * result to expectedResult declared in step file.
             * Returns true only if counts match.
             */
            Integer count = jdbc.queryForObject(sql, Integer.class);
            boolean matches = String.valueOf(count)
                .equals(step.getExpectedResult());

            log.info("POST-VALIDATE SQL: useCase={} stepId={} expected={} actual={} match={}",
                useCase, step.getStepId(), step.getExpectedResult(), count, matches);

            return matches;
        }

        /*
         * EXECUTE / ROLLBACK / CONFIG_WRITE:
         * execute DML statement. rowsAffected >= 0 means success.
         */
        int rowsAffected = jdbc.update(sql);
        log.info("SQL DML: useCase={} stepId={} rowsAffected={}",
            useCase, step.getStepId(), rowsAffected);
        return rowsAffected >= 0;
    }

    // ── Aerospike ─────────────────────────────────────────────────────

    /**
     * Executes an AQL-style statement against Aerospike.
     *
     * Supported statements:
     *   INSERT INTO ns.set (PK, bin1, bin2) VALUES (val0, val1, val2)
     *   DELETE FROM ns.set WHERE PK = val
     */
    private boolean executeAerospike(String useCase, StepCommand step, String aql) {
        AerospikeClient client = aerospikeConfig.getClient(useCase)
            .orElseThrow(() -> new IllegalStateException(
                "Use case '" + useCase + "' has no Aerospike config but " +
                "step " + step.getStepId() + " declares ENGINE=AEROSPIKE"));

        String namespace = aerospikeConfig.getNamespace(useCase);
        String aqlUpper  = aql.trim().toUpperCase();

        log.debug("AQL EXECUTE: useCase={} stepId={} aql={}", useCase, step.getStepId(), aql);

        if (aqlUpper.startsWith("INSERT")) {
            return aqlInsert(client, namespace, aql, useCase, step.getStepId());
        } else if (aqlUpper.startsWith("DELETE")) {
            return aqlDelete(client, namespace, aql, useCase, step.getStepId());
        } else {
            throw new IllegalArgumentException(
                "Unsupported AQL operation in step " + step.getStepId()
                + " for use case '" + useCase + "': " + aql.substring(0, Math.min(50, aql.length())));
        }
    }

    /**
     * Parses and executes:
     *   INSERT INTO ns.set (PK, bin1, bin2) VALUES (val0, val1, val2)
     * First column is always the Aerospike record PK.
     * Remaining columns become Aerospike bins.
     */
    private boolean aqlInsert(AerospikeClient client, String namespace,
                               String aql, String useCase, int stepId) {
        /*
         * Extract set name from: INSERT INTO {ns}.{set} (...)
         */
        Matcher setMatcher = Pattern.compile(
            "INTO\\s+\\S+\\.(\\w+)\\s*\\(", Pattern.CASE_INSENSITIVE).matcher(aql);
        if (!setMatcher.find()) {
            throw new IllegalArgumentException(
                "Cannot parse set name from AQL INSERT in step " + stepId);
        }
        String setName = setMatcher.group(1);

        /*
         * Extract column names and values.
         * Columns: between first ( and ) before VALUES keyword.
         * Values:  between ( and ) after VALUES keyword.
         */
        Matcher colsMatcher = Pattern.compile(
            "\\(([^)]+)\\)\\s+VALUES", Pattern.CASE_INSENSITIVE).matcher(aql);
        Matcher valsMatcher = Pattern.compile(
            "VALUES\\s*\\(([^)]+)\\)", Pattern.CASE_INSENSITIVE).matcher(aql);

        if (!colsMatcher.find() || !valsMatcher.find()) {
            throw new IllegalArgumentException(
                "Cannot parse columns/values from AQL INSERT in step " + stepId);
        }

        String[] cols = colsMatcher.group(1).split(",");
        String[] vals = valsMatcher.group(1).split(",");

        if (cols.length != vals.length) {
            throw new IllegalArgumentException(
                "Column/value count mismatch in AQL INSERT step " + stepId);
        }

        /*
         * First column = PK. Remaining columns = Aerospike bins.
         */
        String pk = vals[0].trim().replaceAll("^['\"]|['\"]$", "");

        Bin[] bins = new Bin[cols.length - 1];
        for (int i = 1; i < cols.length; i++) {
            String binName  = cols[i].trim();
            String binValue = vals[i].trim().replaceAll("^['\"]|['\"]$", "");
            bins[i - 1] = new Bin(binName, binValue);
        }

        Key key = new Key(namespace, setName, pk);
        client.put(new WritePolicy(), key, bins);

        log.info("Aerospike INSERT: useCase={} stepId={} set={} pk={}",
            useCase, stepId, setName, pk);
        return true;
    }

    /**
     * Parses and executes:
     *   DELETE FROM ns.set WHERE PK = val
     */
    private boolean aqlDelete(AerospikeClient client, String namespace,
                               String aql, String useCase, int stepId) {
        Matcher setMatcher = Pattern.compile(
            "FROM\\s+\\S+\\.(\\w+)\\s+WHERE", Pattern.CASE_INSENSITIVE).matcher(aql);
        Matcher pkMatcher  = Pattern.compile(
            "PK\\s*=\\s*['\"]?([^'\"\\s;]+)['\"]?", Pattern.CASE_INSENSITIVE).matcher(aql);

        if (!setMatcher.find() || !pkMatcher.find()) {
            throw new IllegalArgumentException(
                "Cannot parse set/PK from AQL DELETE in step " + stepId);
        }

        String setName = setMatcher.group(1);
        String pk      = pkMatcher.group(1);

        Key key = new Key(namespace, setName, pk);
        client.delete(new WritePolicy(), key);

        log.info("Aerospike DELETE: useCase={} stepId={} set={} pk={}",
            useCase, stepId, setName, pk);
        return true;
    }
}
