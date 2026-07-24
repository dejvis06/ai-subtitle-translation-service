package com.translate.application.port;

import com.translate.application.dto.FailedJobSnapshot;

import java.util.Optional;

/**
 * Application port for reporting translation progress back to the caller.
 *
 * Defined here in the application layer; implemented in infrastructure (SSE).
 */
public interface TranslationProgressPort {

    void reportProgress(String jobId, int percentage, int processedBatches, int totalBatches);

    void reportComplete(String jobId, String content, String outputFileName);

    void reportError(String jobId, String message);

    void saveFailedSnapshot(String jobId, FailedJobSnapshot snapshot);

    Optional<FailedJobSnapshot> getFailedSnapshot(String jobId);
}
