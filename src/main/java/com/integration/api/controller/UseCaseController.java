package com.integration.api.controller;

import com.integration.core.StepCommand;
import com.integration.core.StepLoader;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * ═══════════════════════════════════════════════════════════════════
 * UseCaseController — Use Case Information Endpoints
 * ═══════════════════════════════════════════════════════════════════
 *
 * GET /api/usecases
 *   Lists all active use cases from integration.usecases property.
 *   Access: OPERATOR, VIEWER, ADMIN.
 *
 * GET /api/usecases/{useCase}/steps
 *   Returns step definitions for a specific use case.
 *   Access: ADMIN only — exposes SQL/AQL query templates.
 */
@Slf4j
@RestController
@RequestMapping("/api/usecases")
@RequiredArgsConstructor
public class UseCaseController {

    private final StepLoader stepLoader;

    @Value("${integration.usecases}")
    private String useCasesProperty;

    /**
     * Lists all active use cases.
     *
     * Response:
     * {
     *   "useCases": ["dmt", "cms", "fastag", "internal"]
     * }
     */
    @GetMapping
    @PreAuthorize("hasAnyRole('OPERATOR', 'VIEWER', 'ADMIN')")
    public ResponseEntity<Map<String, List<String>>> listUseCases() {
        List<String> useCases = Arrays.stream(useCasesProperty.split(","))
            .map(String::trim)
            .filter(s -> !s.isBlank())
            .collect(Collectors.toList());

        log.debug("Use cases listed: {}", useCases);
        return ResponseEntity.ok(Map.of("useCases", useCases));
    }

    /**
     * Returns step definitions for a use case.
     * ADMIN only — step files contain SQL/AQL query templates.
     *
     * Response:
     * {
     *   "useCase": "dmt",
     *   "steps": [
     *     {
     *       "stepId": 1,
     *       "name": "step1.sql",
     *       "type": "EXECUTE",
     *       "engine": "SQL",
     *       "compensatesStepId": null
     *     }
     *   ]
     * }
     *
     * Note: query and fieldMap are intentionally excluded from response —
     * they contain SQL templates which are internal implementation details.
     */
    @GetMapping("/{useCase}/steps")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<?> getSteps(@PathVariable String useCase) {
        try {
            List<StepCommand> steps = stepLoader.loadSteps(useCase.toLowerCase());

            /*
             * Map steps to a safe response shape.
             * Query and fieldMap excluded — internal SQL/AQL not exposed.
             */
            List<Map<String, Object>> stepSummaries = steps.stream()
                .map(step -> Map.of(
                    "stepId",           (Object) step.getStepId(),
                    "name",             step.getName(),
                    "type",             step.getType().name(),
                    "engine",           step.getEngine().name(),
                    "compensatesStepId", step.getCompensatesStepId() != null
                                         ? step.getCompensatesStepId() : ""
                ))
                .collect(Collectors.toList());

            return ResponseEntity.ok(Map.of(
                "useCase", useCase,
                "steps",   stepSummaries
            ));

        } catch (IllegalStateException e) {
            log.warn("Steps not found: useCase={} error={}", useCase, e.getMessage());
            return ResponseEntity.badRequest()
                .body(Map.of("error", e.getMessage()));
        }
    }
}
