package com.integration.core;

import lombok.Builder;
import lombok.Getter;

import java.util.Map;

/**
 * Command pattern — represents one step loaded from an external step file.
 *
 * Built exclusively by StepLoader via StepCommand.builder().
 * Use case code never constructs StepCommand directly.
 *
 * Step file format (tagged properties):
 *   STEP_ID=1
 *   TYPE=EXECUTE
 *   ENGINE=SQL
 *   QUERY=INSERT INTO dmt_partner_master ...
 *   COMPENSATES_STEP_ID=
 *
 * prepareQuery() substitutes :placeholder values from the data row
 * into the query template before execution.
 */
@Getter
@Builder
public class StepCommand {

    private final int        stepId;
    private final String     name;
    private final StepType   type;
    private final StepEngine engine;
    private final String     query;               // SQL/AQL template with :placeholder syntax
    private final Integer             compensatesStepId; // ROLLBACK steps only
    private final String              expectedResult;    // POST_VALIDATE steps only

    /**
     * Maps CSV column names → :placeholder names for this step.
     * Declared in step file as:
     *   FIELD_MAP=partner_code:partnerCode,partner_name:partnerName
     *
     * Key   = CSV column name  e.g. "partner_code"
     * Value = placeholder name e.g. "partnerCode"
     *
     * Each step declares only the columns it needs — other steps
     * declare their own mappings from the same CSV row.
     *
     * Empty if no FIELD_MAP tag in step file — raw row keys used as-is.
     */
    @Builder.Default
    private final Map<String, String> fieldMap = new java.util.LinkedHashMap<>();

    /**
     * Applies FIELD_MAP to translate CSV column keys to placeholder keys,
     * then substitutes :placeholder values into the query template.
     *
     * If fieldMap is empty — substitutes directly from raw row (passthrough).
     * Original query template and row are never mutated.
     *
     * @param row raw CSV/JSON row with original column names as keys
     * @return    query string with all mapped placeholders substituted
     */
    public String prepareQuery(Map<String, String> row) {
        /*
         * Apply FIELD_MAP: translate CSV column names → placeholder names.
         * If no FIELD_MAP defined, use raw row keys directly (passthrough).
         */
        Map<String, String> mappedRow = fieldMap.isEmpty()
            ? row
            : applyFieldMap(row);

        /*
         * Substitute :placeholderName with value from mapped row.
         * Unmatched placeholders left as-is — flagged as next enhancement.
         */
        String prepared = query;
        for (Map.Entry<String, String> entry : mappedRow.entrySet()) {
            prepared = prepared.replace(":" + entry.getKey(), entry.getValue());
        }
        return prepared;
    }

    /**
     * Transforms raw CSV row using this step's FIELD_MAP.
     * Only columns declared in FIELD_MAP are included in the result.
     * Columns absent from FIELD_MAP are not visible to this step —
     * other steps may map those same columns under their own FIELD_MAP.
     */
    private Map<String, String> applyFieldMap(Map<String, String> row) {
        Map<String, String> mapped = new java.util.LinkedHashMap<>();
        for (Map.Entry<String, String> mapping : fieldMap.entrySet()) {
            String csvColumn   = mapping.getKey();   // e.g. "partner_code"
            String placeholder = mapping.getValue(); // e.g. "partnerCode"
            mapped.put(placeholder, row.getOrDefault(csvColumn, ""));
        }
        return mapped;
    }
}
