package com.integration.core;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Reads step files for a use case from paths declared in:
 *   {usecase}.steps=/path/step1.sql,/path/step2.sql,...
 * (served by Spring Cloud Config Server in {usecase}.properties)
 *
 * Called once per use case at application startup.
 * Results cached — no file I/O at runtime when partner files arrive.
 *
 * Fail-fast: any missing file, missing field, or duplicate STEP_ID
 * throws IllegalStateException at startup, not at runtime.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class StepLoader {

    private final Environment environment;

    // Cache — loaded once at startup per use case
    private final Map<String, List<StepCommand>> cache = new HashMap<>();

    /**
     * Returns sorted List<StepCommand> for a use case.
     * Loaded from files on first call, served from cache thereafter.
     */
    public List<StepCommand> loadSteps(String useCase) {
        return cache.computeIfAbsent(useCase, this::readAndParseSteps);
    }

    // ── Private ───────────────────────────────────────────────────────

    private List<StepCommand> readAndParseSteps(String useCase) {
        List<String> filePaths = resolveFilePaths(useCase);
        List<StepCommand> steps = new ArrayList<>();

        for (String filePath : filePaths) {
            StepCommand step = parseStepFile(filePath.trim());
            steps.add(step);
            log.debug("Parsed step: useCase={} file={} stepId={} type={} engine={}",
                useCase, filePath, step.getStepId(), step.getType(), step.getEngine());
        }

        // Sort by STEP_ID — execution order is governed by STEP_ID, not file declaration order
        steps.sort(Comparator.comparingInt(StepCommand::getStepId));

        // Validate no duplicate STEP_IDs within a use case
        validateNoDuplicateStepIds(steps, useCase);

        log.info("Steps loaded: useCase={} count={}", useCase, steps.size());
        return Collections.unmodifiableList(steps);
    }

    /**
     * Reads {usecase}.steps from Spring Environment (served by Config Server).
     * Splits on comma and validates each path exists on disk.
     */
    private List<String> resolveFilePaths(String useCase) {
        String propertyKey = useCase + ".steps";
        String propertyValue = environment.getProperty(propertyKey);

        if (propertyValue == null || propertyValue.isBlank()) {
            throw new IllegalStateException(
                "No step files configured for use case: '" + useCase +
                "' — add '" + propertyKey + "' to " + useCase +
                ".properties on Config Server");
        }

        List<String> paths = Arrays.stream(propertyValue.split(","))
            .map(String::trim)
            .filter(p -> !p.isBlank())
            .collect(Collectors.toList());

        // Validate every declared path exists — fail at startup, not at runtime
        for (String path : paths) {
            if (!Files.exists(Paths.get(path))) {
                throw new IllegalStateException(
                    "Step file not found: '" + path +
                    "' declared in " + useCase + ".properties");
            }
        }

        return paths;
    }

    /**
     * Parses a single step file in tagged properties format:
     *
     *   STEP_ID=1
     *   TYPE=EXECUTE
     *   ENGINE=SQL
     *   QUERY=INSERT INTO ... VALUES (:field1, :field2)
     *   COMPENSATES_STEP_ID=
     *
     * Multi-line QUERY supported — lines after QUERY= are appended
     * until the next tag or end of file.
     */
    private StepCommand parseStepFile(String filePath) {
        Integer    stepId             = null;
        StepType   type               = null;
        StepEngine engine             = StepEngine.SQL;
        Integer    compensatesStepId  = null;
        String     expectedResult     = null;
        List<String> queryLines       = new ArrayList<>();
        boolean    readingQuery       = false;

        try (BufferedReader reader = new BufferedReader(new FileReader(filePath))) {
            String line;
            while ((line = reader.readLine()) != null) {
                String trimmed = line.trim();

                // Skip blank lines and comments
                if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                    readingQuery = false;
                    continue;
                }

                String upper = trimmed.toUpperCase();

                if (upper.startsWith("STEP_ID=")) {
                    readingQuery = false;
                    stepId = parseIntValue("STEP_ID", trimmed, filePath);

                } else if (upper.startsWith("TYPE=")) {
                    readingQuery = false;
                    type = parseStepType(trimmed, filePath);

                } else if (upper.startsWith("ENGINE=")) {
                    readingQuery = false;
                    String val = trimmed.split("=", 2)[1].trim();
                    engine = val.isBlank() ? StepEngine.SQL : StepEngine.valueOf(val.toUpperCase());

                } else if (upper.startsWith("COMPENSATES_STEP_ID=")) {
                    readingQuery = false;
                    String val = trimmed.split("=", 2)[1].trim();
                    compensatesStepId = val.isBlank() ? null : Integer.parseInt(val);

                } else if (upper.startsWith("EXPECTED_RESULT=")) {
                    readingQuery = false;
                    String val = trimmed.split("=", 2)[1].trim();
                    expectedResult = val.isBlank() ? null : val;

                } else if (upper.startsWith("QUERY=")) {
                    readingQuery = true;
                    String firstLine = trimmed.split("=", 2)[1].trim();
                    if (!firstLine.isBlank()) {
                        queryLines.add(firstLine);
                    }

                } else if (readingQuery) {
                    // Continuation line of a multi-line QUERY
                    queryLines.add(trimmed);
                }
            }
        } catch (IOException e) {
            throw new IllegalStateException(
                "Failed to read step file: '" + filePath + "' — " + e.getMessage(), e);
        }

        return buildAndValidate(stepId, type, engine, queryLines,
            compensatesStepId, expectedResult, filePath);
    }

    /**
     * Validates all required fields are present and builds StepCommand.
     * Throws at startup if anything is missing.
     */
    private StepCommand buildAndValidate(Integer stepId, StepType type,
                                          StepEngine engine, List<String> queryLines,
                                          Integer compensatesStepId, String expectedResult,
                                          String filePath) {
        if (stepId == null) {
            throw new IllegalStateException("STEP_ID missing in: " + filePath);
        }
        if (type == null) {
            throw new IllegalStateException("TYPE missing in: " + filePath);
        }
        if (queryLines.isEmpty()) {
            throw new IllegalStateException("QUERY missing in: " + filePath);
        }
        if (type == StepType.ROLLBACK && compensatesStepId == null) {
            throw new IllegalStateException(
                "COMPENSATES_STEP_ID required for ROLLBACK step in: " + filePath);
        }

        return StepCommand.builder()
            .stepId(stepId)
            .name(Paths.get(filePath).getFileName().toString())
            .type(type)
            .engine(engine)
            .query(String.join(" ", queryLines).trim())
            .compensatesStepId(compensatesStepId)
            .expectedResult(expectedResult)
            .build();
    }

    private void validateNoDuplicateStepIds(List<StepCommand> steps, String useCase) {
        Set<Integer> seen = new HashSet<>();
        for (StepCommand step : steps) {
            if (!seen.add(step.getStepId())) {
                throw new IllegalStateException(
                    "Duplicate STEP_ID " + step.getStepId() +
                    " found in use case: '" + useCase + "'");
            }
        }
    }

    private Integer parseIntValue(String tag, String line, String filePath) {
        try {
            return Integer.parseInt(line.split("=", 2)[1].trim());
        } catch (NumberFormatException e) {
            throw new IllegalStateException(
                tag + " must be an integer in: " + filePath);
        }
    }

    private StepType parseStepType(String line, String filePath) {
        try {
            return StepType.valueOf(line.split("=", 2)[1].trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException(
                "TYPE must be one of " + Arrays.toString(StepType.values()) +
                " in: " + filePath);
        }
    }
}
