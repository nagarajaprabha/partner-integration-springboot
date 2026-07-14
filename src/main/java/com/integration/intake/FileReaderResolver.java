package com.integration.intake;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.io.FilenameUtils;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * ═══════════════════════════════════════════════════════════════════
 * FileReaderResolver — Selects FileReader by File Extension
 * ═══════════════════════════════════════════════════════════════════
 *
 * Uses Apache Commons IO FilenameUtils — no custom extension parsing.
 *
 * Spring injects all FileReader @Component implementations.
 * Resolver picks the one whose supportedExtension() matches
 * the uploaded file's extension.
 *
 * Adding a new format:
 *   Implement FileReader + @Component — auto-discovered by Spring.
 *   No changes here.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class FileReaderResolver {

    /*
     * All FileReader @Component implementations injected by Spring.
     * Currently: CsvFileReader (.csv), JsonFileReader (.json).
     */
    private final List<FileReader> readers;

    /**
     * Returns the FileReader for the given file path.
     * Uses Apache Commons IO FilenameUtils to extract extension safely.
     *
     * @param filePath full file path e.g. "/uploads/dmt/partners.csv"
     * @return         matching FileReader implementation
     * @throws IllegalArgumentException if no reader supports this extension
     */
    public FileReader resolve(String filePath) {
        /*
         * Apache Commons IO FilenameUtils.getExtension() handles:
         *   - Files with no extension → returns ""
         *   - Files with multiple dots → returns last extension
         *   - Path separators on any OS
         * No custom string splitting needed.
         */
        String extension = "." + FilenameUtils.getExtension(filePath).toLowerCase();

        return readers.stream()
            .filter(r -> r.supportedExtension().equalsIgnoreCase(extension))
            .findFirst()
            .orElseThrow(() -> new IllegalArgumentException(
                "No FileReader registered for extension '" + extension +
                "' — supported: " + readers.stream()
                    .map(FileReader::supportedExtension)
                    .toList()));
    }
}
