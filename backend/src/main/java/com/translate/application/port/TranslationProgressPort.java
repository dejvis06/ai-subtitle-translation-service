package com.translate.application.port;

/**
 * Application port for reporting translation progress back to the caller.
 *
 * Defined here in the application layer; implemented in infrastructure (SSE).
 */
public interface TranslationProgressPort {

    void reportProgress(String jobId, int percentage, int processedBatches, int totalBatches);

    void reportComplete(String jobId, String content, String outputFileName);

    void reportError(String jobId, String message);
}
