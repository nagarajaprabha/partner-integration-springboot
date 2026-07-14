package com.integration.intake;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * ═══════════════════════════════════════════════════════════════════
 * JsonFileReader — Jackson Databind Implementation
 * ═══════════════════════════════════════════════════════════════════
 *
 * Uses Jackson ObjectMapper — no custom JSON parsing logic.
 * Jackson handles:
 *   - JSON array of objects deserialization
 *   - Nested object flattening (not needed here — flat rows only)
 *   - Null value handling
 *   - Number and boolean coercion to String
 *
 * JSON format expectations:
 *   - Top-level structure must be a JSON array [ { }, { } ]
 *   - Each object in the array = one partner/biller record
 *   - Object keys must match FIELD_MAP left-hand side values in step files
 *   - All values coerced to String — consistent with CSV reader output
 *     and step file :placeholder substitution
 *
 * Spring autowires the shared ObjectMapper bean — consistent
 * Jackson configuration across the whole application.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class JsonFileReader implements FileReader {

    /*
     * Spring-managed ObjectMapper bean — shared across application.
     * Configured once in Spring context, reused here.
     * No new ObjectMapper() instantiation — follows Spring best practice.
     */
    private final ObjectMapper objectMapper;

    @Override
    public String supportedExtension() {
        return ".json";
    }

    /**
     * Parses JSON array InputStream using Jackson ObjectMapper.
     * Returns one Map per JSON object in the array.
     * All values coerced to String for consistency with CSV reader.
     * Rows where all values are blank after coercion are skipped.
     *
     * @throws IOException if stream is not valid JSON or is not a JSON array
     */
    @Override
    public List<Map<String, String>> read(InputStream inputStream) throws IOException {

        /*
         * Jackson reads the entire JSON array into a typed list.
         * TypeReference preserves generic type information at runtime.
         * Each element is Map<String, Object> — values may be
         * String, Number, Boolean, or null from JSON.
         */
        List<Map<String, Object>> rawList = objectMapper.readValue(
            inputStream,
            new TypeReference<List<Map<String, Object>>>() {}
        );

        if (rawList == null || rawList.isEmpty()) {
            log.info("JSON read complete: 0 data rows (empty array or null)");
            return List.of();
        }

        List<Map<String, String>> rows = new ArrayList<>();

        for (Map<String, Object> rawRow : rawList) {
            Map<String, String> row = new LinkedHashMap<>();

            rawRow.forEach((key, value) -> {
                /*
                 * Coerce all JSON value types to String:
                 *   null    → empty string ""
                 *   Number  → String e.g. "50000"
                 *   Boolean → String e.g. "true"
                 *   String  → trimmed String
                 * Consistent with CSV reader — downstream code
                 * always receives String values regardless of format.
                 */
                String strValue = value == null
                    ? StringUtils.EMPTY
                    : StringUtils.trimToEmpty(value.toString());

                row.put(
                    StringUtils.trimToEmpty(key),
                    strValue
                );
            });

            /*
             * Skip rows where every value is blank after coercion.
             * Handles JSON objects like {} or {"field": null, "other": null}.
             */
            boolean allBlank = row.values().stream()
                .allMatch(StringUtils::isBlank);
            if (allBlank) {
                log.debug("Skipping blank JSON object in array");
                continue;
            }

            rows.add(row);
        }

        log.info("JSON read complete: {} data rows parsed", rows.size());
        return rows;
    }
}
