package com.integration.core;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * ═══════════════════════════════════════════════════════════════════
 * Validator — Platform Component
 * ═══════════════════════════════════════════════════════════════════
 *
 * Validates one data row before any DB or Aerospike writes occur.
 * Called by Pipeline at Stage 1. Never called by use case code.
 *
 * Two validation layers:
 *
 *   Layer 1 — Automatic (always runs, no config needed):
 *     Extracts all :placeholder names from EXECUTE step queries.
 *     Checks each placeholder field is present and non-empty in the row.
 *     Source of truth: step files. No separate field list to maintain.
 *
 *   Layer 2 — Optional rules (only if {usecase}.validations defined):
 *     Reads validation rules from {usecase}.validations in Config Server.
 *     Supports: required, min_length, max_length, allowed_values, pattern.
 *     If no rules defined → Layer 1 only. Not an error.
 *
 * Validation result:
 *   All errors collected first, then thrown together as one
 *   IllegalArgumentException — operator sees all issues at once,
 *   not just the first one.
 */
@Slf4j
@Component
public class Validator {

    /*
     * Pattern to extract :placeholder names from SQL/AQL query templates.
     * Matches :fieldName where fieldName starts with a letter or underscore,
     * followed by letters, digits, or underscores.
     */
    private static final Pattern PLACEHOLDER_PATTERN =
        Pattern.compile(":([a-zA-Z_][a-zA-Z0-9_]*)");

    /**
     * Validates a data row against step placeholder requirements
     * and optional validation rules.
     *
     * @param row   data row from CSV or JSON
     * @param steps pre-loaded steps for this use case
     * @param rules optional validation rules from Config Server (may be empty)
     * @throws IllegalArgumentException if any validation fails,
     *         message contains all errors joined by "; "
     */
    public void validate(Map<String, String> row,
                         List<StepCommand> steps,
                         List<ValidationRule> rules) {

        List<String> errors = new ArrayList<>();

        /*
         * Layer 1: Auto-derive required fields from :placeholder names
         * in all EXECUTE and POST_VALIDATE step queries.
         * Every placeholder must be present and non-empty in the row.
         */
        Set<String> requiredFields = extractPlaceholders(steps);
        for (String field : requiredFields) {
            String value = row.getOrDefault(field, "").trim();
            if (value.isEmpty()) {
                errors.add("Field '" + field + "' is required but missing or empty");
            }
        }

        /*
         * Layer 2: Optional rules from Config Server.
         * Applied only for fields explicitly listed in rules.
         * Skips fields not mentioned in rules — no false positives.
         */
        for (ValidationRule rule : rules) {
            String field = rule.getField();
            String value = row.getOrDefault(field, "").trim();

            /*
             * If field is marked not required and is empty,
             * skip further rule checks for this field.
             */
            if (!rule.isRequired() && value.isEmpty()) {
                continue;
            }

            // min_length check
            if (rule.getMinLength() > 0 && value.length() < rule.getMinLength()) {
                errors.add("Field '" + field + "' must be at least "
                    + rule.getMinLength() + " characters");
            }

            // max_length check
            if (rule.getMaxLength() > 0 && value.length() > rule.getMaxLength()) {
                errors.add("Field '" + field + "' must be at most "
                    + rule.getMaxLength() + " characters");
            }

            // allowed_values check
            if (!rule.getAllowedValues().isEmpty()
                    && !rule.getAllowedValues().contains(value)) {
                errors.add("Field '" + field + "' value '" + value
                    + "' not in allowed values: " + rule.getAllowedValues());
            }

            // pattern check
            if (rule.getPattern() != null && !rule.getPattern().isBlank()
                    && !value.matches(rule.getPattern())) {
                errors.add("Field '" + field + "' value '" + value
                    + "' does not match pattern: " + rule.getPattern());
            }
        }

        /*
         * Collect all errors before throwing — operator sees
         * every issue at once, not just the first failure.
         */
        if (!errors.isEmpty()) {
            String message = String.join("; ", errors);
            log.warn("VALIDATION FAILED: {} error(s) — {}", errors.size(), message);
            throw new IllegalArgumentException(message);
        }

        log.debug("VALIDATION PASSED: {} fields checked", requiredFields.size());
    }

    /**
     * Extracts all unique :placeholder names from EXECUTE and
     * POST_VALIDATE step queries.
     * Used by Pipeline to determine which fields are required in the row.
     */
    public Set<String> extractPlaceholders(List<StepCommand> steps) {
        return steps.stream()
            .filter(s -> s.getType() == StepType.EXECUTE
                      || s.getType() == StepType.POST_VALIDATE)
            .flatMap(s -> {
                List<String> fields = new ArrayList<>();
                Matcher m = PLACEHOLDER_PATTERN.matcher(s.getQuery());
                while (m.find()) {
                    fields.add(m.group(1));
                }
                return fields.stream();
            })
            .collect(Collectors.toSet());
    }
}
