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
    private final Integer    compensatesStepId;   // ROLLBACK steps only — which EXECUTE step this undoes
    private final String     expectedResult;      // POST_VALIDATE steps only

    /**
     * Substitutes :placeholder with actual values from the data row.
     * Returns the prepared query string ready for execution.
     * Original query template is never mutated.
     */
    public String prepareQuery(Map<String, String> row) {
        String prepared = query;
        for (Map.Entry<String, String> entry : row.entrySet()) {
            prepared = prepared.replace(":" + entry.getKey(), entry.getValue());
        }
        return prepared;
    }
}
