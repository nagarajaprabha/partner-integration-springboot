package com.integration.core;

/**
 * Defines the four permitted step types.
 * No new type may be added without a platform-level change.
 *
 * EXECUTE      — forward DB or Aerospike write
 * POST_VALIDATE — verify DB/Aerospike state after all EXECUTE steps succeed
 * ROLLBACK     — compensating action, run in reverse on any EXECUTE failure
 * CONFIG_WRITE — write to an external config file, treated same as EXECUTE
 */
public enum StepType {
    EXECUTE,
    POST_VALIDATE,
    ROLLBACK,
    CONFIG_WRITE
}
