package com.integration.intake;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Map;

/**
 * ═══════════════════════════════════════════════════════════════════
 * FileReader — Generic File Reader Interface
 * ═══════════════════════════════════════════════════════════════════
 *
 * Abstracts file format parsing from the rest of the platform.
 * Implementations: CsvFileReader (Apache Commons CSV),
 *                  JsonFileReader (Jackson Databind).
 *
 * Adding a new format:
 *   1. Implement this interface
 *   2. Annotate with @Component
 *   3. Return a unique extension from supportedExtension()
 *   Zero other code changes.
 *
 * FileReaderResolver selects the correct implementation
 * by matching the uploaded file's extension to supportedExtension().
 *
 * Every implementation returns rows as List<Map<String, String>>.
 * All values are Strings — consistent with step file :placeholder
 * substitution which works on String values only.
 */
public interface FileReader {

    /**
     * File extension this reader handles.
     * Must include the dot — e.g. ".csv", ".json"
     */
    String supportedExtension();

    /**
     * Parses an InputStream into a list of data rows.
     * Each row is a Map of column name → String value.
     *
     * Contract:
     *   - First row in structured formats (CSV) is always the header
     *   - All keys and values are trimmed of whitespace
     *   - Completely blank rows are skipped
     *   - Caller is responsible for closing the InputStream
     *
     * @param inputStream file content stream
     * @return            list of data rows, empty list if file has no data rows
     * @throws IOException if the stream cannot be read or format is invalid
     */
    List<Map<String, String>> read(InputStream inputStream) throws IOException;
}
