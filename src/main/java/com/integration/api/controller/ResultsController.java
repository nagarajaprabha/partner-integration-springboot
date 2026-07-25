package com.integration.api.controller;

import com.jcraft.jsch.ChannelSftp;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.integration.sftp.session.SftpRemoteFileTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.InputStream;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * ═══════════════════════════════════════════════════════════════════
 * ResultsController — Result Report Endpoints
 * ═══════════════════════════════════════════════════════════════════
 *
 * GET /api/results/{useCase}
 *   Lists all result report files for a use case.
 *   Files read from SFTP /uploads/{useCase}/results/.
 *   Access: OPERATOR, VIEWER, ADMIN.
 *
 * GET /api/results/{useCase}/{filename}
 *   Downloads a specific result report CSV.
 *   Streamed directly from SFTP to browser — no local file created.
 *   Access: OPERATOR, VIEWER, ADMIN.
 */
@Slf4j
@RestController
@RequestMapping("/api/results")
@RequiredArgsConstructor
public class ResultsController {

    private final SftpRemoteFileTemplate sftpTemplate;

    @Value("${sftp.upload.root:/uploads}")
    private String sftpUploadRoot;

    /**
     * Lists available result reports for a use case.
     *
     * Response:
     * {
     *   "useCase": "dmt",
     *   "reports": [
     *     { "filename": "1752590000_dmt-partners.csv_result.csv",
     *       "size": 2048,
     *       "lastModified": 1752590000 }
     *   ]
     * }
     */
    @GetMapping("/{useCase}")
    @PreAuthorize("hasAnyRole('OPERATOR', 'VIEWER', 'ADMIN')")
    public ResponseEntity<?> listResults(@PathVariable String useCase) {
        String remotePath = sftpUploadRoot + "/" + useCase.toLowerCase() + "/results";

        try {
            /*
             * List files in SFTP results/ folder.
             * SftpRemoteFileTemplate handles session lifecycle.
             * Returns null if directory does not exist — handled below.
             */
            ChannelSftp.LsEntry[] entries = sftpTemplate.execute(session -> {
                try {
                    return session.list(remotePath + "/*");
                } catch (Exception e) {
                    // Directory does not exist yet — return empty array
                    return new ChannelSftp.LsEntry[0];
                }
            });

            /*
             * Map each SFTP entry to a report summary.
             * Sort by lastModified descending — newest first.
             */
            List<Map<String, Object>> reports = Arrays.stream(
                    entries != null ? entries : new ChannelSftp.LsEntry[0])
                .filter(e -> !e.getAttrs().isDir())
                .filter(e -> e.getFilename().endsWith("_result.csv"))
                .sorted(Comparator.comparingInt(
                    e -> -e.getAttrs().getMTime())) // newest first
                .map(e -> Map.of(
                    "filename",     (Object) e.getFilename(),
                    "size",         e.getAttrs().getSize(),
                    "lastModified", (long) e.getAttrs().getMTime() * 1000L
                ))
                .collect(Collectors.toList());

            log.debug("Results listed: useCase={} count={}", useCase, reports.size());
            return ResponseEntity.ok(Map.of(
                "useCase", useCase,
                "reports", reports
            ));

        } catch (Exception e) {
            log.error("Results list error: useCase={} error={}", useCase, e.getMessage());
            return ResponseEntity.internalServerError()
                .body(Map.of("error", "Could not list results: " + e.getMessage()));
        }
    }

    /**
     * Downloads a specific result report CSV file.
     * Streamed from SFTP directly to HTTP response — no local file.
     * Browser receives file as a download attachment.
     *
     * @param useCase  use case name e.g. "dmt"
     * @param filename result report filename e.g. "1752590000_dmt-partners.csv_result.csv"
     */
    @GetMapping("/{useCase}/{filename}")
    @PreAuthorize("hasAnyRole('OPERATOR', 'VIEWER', 'ADMIN')")
    public ResponseEntity<?> downloadResult(
            @PathVariable String useCase,
            @PathVariable String filename) {

        String remotePath = sftpUploadRoot + "/"
            + useCase.toLowerCase() + "/results/" + filename;

        log.info("Result download: useCase={} file={}", useCase, filename);

        try {
            /*
             * Stream file directly from SFTP to HTTP response.
             * SftpRemoteFileTemplate.get() returns InputStream.
             * InputStreamResource wraps it for Spring MVC streaming.
             * No local copy created — memory efficient for large reports.
             */
            InputStream fileStream = sftpTemplate.execute(session ->
                session.readRaw(remotePath)
            );

            return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                    "attachment; filename=\"" + filename + "\"")
                .contentType(MediaType.parseMediaType("text/csv"))
                .body(new InputStreamResource(fileStream));

        } catch (Exception e) {
            log.error("Result download error: useCase={} file={} error={}",
                useCase, filename, e.getMessage());
            return ResponseEntity.notFound().build();
        }
    }
}
