package com.integration.api.dto;

import lombok.Builder;
import lombok.Getter;

import java.util.List;

/**
 * Builder pattern — response body for POST /api/upload/{useCase}.
 * Returned immediately after all rows in the uploaded file are processed.
 * Also serialised to JSON and included in the result CSV on SFTP.
 */
@Getter
@Builder
public class UploadResultDto {

    private final String           useCase;
    private final String           filename;
    private final int              totalRows;
    private final long             succeeded;
    private final long             failed;
    private final long             skipped;        // pre-validation failures
    private final String           reportPath;     // SFTP path of result CSV
    private final List<RowResultDto> results;      // one entry per row

    /**
     * Per-row result — included in the results list.
     */
    @Getter
    @Builder
    public static class RowResultDto {
        private final int    rowNumber;
        private final String status;          // SUCCESS | FAILED | SKIPPED
        private final String partnerCode;     // identifier from the row
        private final long   stepsExecuted;
        private final String failedStep;      // empty if SUCCESS
        private final String errorMessage;    // empty if SUCCESS
        private final long   executionMs;
    }
}
