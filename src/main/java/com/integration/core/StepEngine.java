package com.integration.core;

/**
 * Defines which connector executes a step.
 * SQL      — routed to BaseDBConfig DataSource via JdbcTemplate
 * AEROSPIKE — routed to BaseAerospikeConfig AerospikeClient
 */
public enum StepEngine {
    SQL,
    AEROSPIKE
}
