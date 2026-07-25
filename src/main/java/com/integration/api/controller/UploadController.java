package com.integration.api.controller;

import com.integration.api.dto.UploadResultDto;
import com.integration.api.service.UploadProcessingService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.Map;

/**
 * ═══════════════════════════════════════════════════════════════════
 * UploadController — File Upload Endpoint
 * ═══════════════════════════════════════════════════════════════════
 *
 * POST /api/upload/{useCase}
 *   Accepts a CSV or JSON file for a specific use case.
 *   Processes all rows immediately — returns result in HTTP response.
 *   Result CSV also uploaded to SFTP /results/ for audit trail.
 *
 * Access: OPERATOR and ADMIN only (enforced by SecurityConfig).
 *
 * Controller is intentionally thin:
 *   - Validates HTTP request only (file present, use case not blank)
 *   - Delegates all business logic to UploadProcessingService
 *   - Returns HTTP 200 with UploadResultDto on success
 *   - Returns HTTP 400 on validation failure
 *   - Returns HTTP 500 on processing error with error message
 */
@Slf4j
@RestController
@RequestMapping("/api/upload")
@RequiredArgsConstructor
public class UploadController {

    private final UploadProcessingService processingService;

    /**
     * Uploads and immediately processes a partner/biller data file.
     *
     * @param useCase use case name from URL path e.g. "dmt", "cms"
     * @param file    multipart file — CSV or JSON
     * @return        UploadResultDto with per-row results and report path
     */
    @PostMapping(
        value = "/{useCase}",
        consumes = MediaType.MULTIPART_FORM_DATA_VALUE,
        produces = MediaType.APPLICATION_JSON_VALUE
    )
    @PreAuthorize("hasAnyRole('OPERATOR', 'ADMIN')")
    public ResponseEntity<?> upload(
            @PathVariable String useCase,
            @RequestParam("file") MultipartFile file) {

        // ── HTTP-level validation — controller responsibility ──────────

        if (StringUtils.isBlank(useCase)) {
            return ResponseEntity.badRequest()
                .body(Map.of("error", "useCase path variable is required"));
        }

        if (file == null || file.isEmpty()) {
            return ResponseEntity.badRequest()
                .body(Map.of("error", "File is required and must not be empty"));
        }

        String filename = StringUtils.defaultIfBlank(
            file.getOriginalFilename(), "upload");

        log.info("Upload request: useCase={} file={} size={}",
            useCase, filename, file.getSize());

        // ── Delegate to service — no business logic here ──────────────

        try {
            UploadResultDto result = processingService.process(
                useCase.toLowerCase(), file);
            return ResponseEntity.ok(result);

        } catch (IllegalArgumentException e) {
            /*
             * Use case not found, unsupported file type, no steps configured.
             * These are operator errors — return 400.
             */
            log.warn("Upload bad request: useCase={} error={}", useCase, e.getMessage());
            return ResponseEntity.badRequest()
                .body(Map.of("error", e.getMessage()));

        } catch (Exception e) {
            /*
             * Unexpected processing error — return 500.
             */
            log.error("Upload error: useCase={} error={}", useCase, e.getMessage());
            return ResponseEntity.internalServerError()
                .body(Map.of("error", "Processing failed: " + e.getMessage()));
        }
    }
}
