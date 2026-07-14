package com.integration.intake;

import lombok.extern.slf4j.Slf4j;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * ═══════════════════════════════════════════════════════════════════
 * CsvFileReader — Apache Commons CSV Implementation
 * ═══════════════════════════════════════════════════════════════════
 *
 * Uses Apache Commons CSV — no custom CSV parsing logic.
 * Apache Commons CSV handles:
 *   - Header row detection and mapping
 *   - Quoted fields (fields containing commas or newlines)
 *   - Escaped characters within quoted fields
 *   - Trailing whitespace in headers and values
 *   - BOM (Byte Order Mark) handling
 *
 * CSV format expectations:
 *   - First row is the header row — column names must match
 *     FIELD_MAP left-hand side values in step files
 *   - Subsequent rows are data rows — one row = one partner/biller record
 *   - Delimiter: comma (,)
 *   - Encoding: UTF-8
 *
 * Blank rows (all values empty after trimming) are silently skipped.
 */
@Slf4j
@Component
public class CsvFileReader implements FileReader {

    /*
     * Apache Commons CSV format:
     *   withHeader()           — first record is the header
     *   withIgnoreHeaderCase() — case-insensitive header matching
     *   withTrim()             — trims whitespace from all values
     *   withIgnoreEmptyLines() — skips empty lines in the file
     */
    private static final CSVFormat CSV_FORMAT = CSVFormat.DEFAULT
        .builder()
        .setHeader()
        .setSkipHeaderRecord(true)
        .setIgnoreHeaderCase(true)
        .setTrim(true)
        .setIgnoreEmptyLines(true)
        .build();

    @Override
    public String supportedExtension() {
        return ".csv";
    }

    /**
     * Parses CSV InputStream using Apache Commons CSV.
     * Returns one Map per data row with header names as keys.
     * Rows where all values are blank after trimming are skipped.
     */
    @Override
    public List<Map<String, String>> read(InputStream inputStream) throws IOException {
        List<Map<String, String>> rows = new ArrayList<>();

        /*
         * Apache Commons CSV handles all parsing including:
         * quoted fields, escape sequences, header mapping.
         * No manual split/parse logic needed.
         */
        try (Reader reader = new InputStreamReader(inputStream, StandardCharsets.UTF_8);
             CSVParser parser = CSV_FORMAT.parse(reader)) {

            for (CSVRecord record : parser) {
                Map<String, String> row = new LinkedHashMap<>();

                /*
                 * toMap() returns all header→value pairs for this record.
                 * Values already trimmed by CSVFormat.withTrim().
                 */
                record.toMap().forEach((key, value) ->
                    row.put(
                        StringUtils.trimToEmpty(key),
                        StringUtils.trimToEmpty(value)
                    )
                );

                /*
                 * Skip rows where every value is blank.
                 * Commons CSV's withIgnoreEmptyLines() skips fully empty lines,
                 * but a row with all commas (,,,,) still produces empty values.
                 */
                boolean allBlank = row.values().stream()
                    .allMatch(StringUtils::isBlank);
                if (allBlank) {
                    log.debug("Skipping blank row at line {}", parser.getCurrentLineNumber());
                    continue;
                }

                rows.add(row);
            }
        }

        log.info("CSV read complete: {} data rows parsed", rows.size());
        return rows;
    }
}
