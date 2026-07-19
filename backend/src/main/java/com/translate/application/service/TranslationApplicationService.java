package com.translate.application.service;

import com.translate.application.port.AiTranslationClient;
import com.translate.application.port.TranslationProgressPort;
import com.translate.domain.model.SubtitleFile;
import com.translate.domain.model.TranslatedEntry;
import com.translate.domain.model.TranslationEntry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Application service orchestrating the subtitle translation use case.
 *
 * Flow:
 * 1. Save uploaded file bytes to the filesystem
 * 2. Parse the SRT file into a SubtitleFile aggregate
 * 3. Send translation entries to the AI in batches of 20
 * 4. Apply translated text back into the file content
 * 5. Report completion (or errors) through the progress port
 * 6. Delete the temp file
 */
@Service
public class TranslationApplicationService {

    private static final Logger log = LoggerFactory.getLogger(TranslationApplicationService.class);
    private static final int BATCH_SIZE = 20;

    private final AiTranslationClient aiTranslationClient;
    private final TranslationProgressPort progressPort;

    public TranslationApplicationService(AiTranslationClient aiTranslationClient,
                                          TranslationProgressPort progressPort) {
        this.aiTranslationClient = aiTranslationClient;
        this.progressPort = progressPort;
    }

    /**
     * Starts an async translation job. Progress and completion are reported
     * through the {@link TranslationProgressPort} using the provided jobId.
     *
     * @param jobId            identifier of the job (used to route SSE events)
     * @param fileBytes        raw bytes of the uploaded .srt file
     * @param originalFileName original file name (used for output file naming)
     * @param targetLanguage   target language (e.g. "Albanian")
     */
    @Async("translationExecutor")
    public CompletableFuture<Void> translateAsync(String jobId,
                                                   byte[] fileBytes,
                                                   String originalFileName,
                                                   String targetLanguage) {
        log.info("[Job {}] Starting translation of '{}' into {}", jobId, originalFileName, targetLanguage);

        Path tempFile = null;
        try {
            tempFile = saveToFilesystem(fileBytes, originalFileName);

            String rawContent = readStrippingBom(fileBytes);
            SubtitleFile subtitleFile = SubtitleFile.parse(originalFileName, rawContent);

            List<TranslationEntry> allEntries = subtitleFile.getEntries();
            int totalBatches = (int) Math.ceil((double) allEntries.size() / BATCH_SIZE);

            log.info("[Job {}] Parsed {} subtitle entries → {} batch(es)", jobId, allEntries.size(), totalBatches);

            List<TranslatedEntry> allTranslated = translateInBatches(jobId, allEntries, targetLanguage, totalBatches);
            subtitleFile.applyTranslations(allTranslated);

            String outputFileName = buildOutputFileName(originalFileName, targetLanguage);
            progressPort.reportComplete(jobId, subtitleFile.getContent(), outputFileName);

            log.info("[Job {}] Translation complete → {}", jobId, outputFileName);

        } catch (Exception e) {
            log.error("[Job {}] Translation failed: {}", jobId, e.getMessage(), e);
            progressPort.reportError(jobId, e.getMessage());
        } finally {
            if (tempFile != null) {
                try {
                    Files.deleteIfExists(tempFile);
                } catch (IOException e) {
                    log.warn("[Job {}] Could not delete temp file: {}", jobId, e.getMessage());
                }
            }
        }

        return CompletableFuture.completedFuture(null);
    }

    /**
     * Kept for backward compatibility and unit testing.
     * Translates synchronously without progress reporting.
     */
    public String translate(MultipartFile file, String targetLanguage) throws IOException {
        byte[] fileBytes = file.getBytes();
        String originalFileName = file.getOriginalFilename() != null
                ? file.getOriginalFilename()
                : "subtitle.srt";

        Path tempFile = saveToFilesystem(fileBytes, originalFileName);
        try {
            String rawContent = readStrippingBom(fileBytes);
            SubtitleFile subtitleFile = SubtitleFile.parse(originalFileName, rawContent);

            List<TranslationEntry> allEntries = subtitleFile.getEntries();
            int totalBatches = (int) Math.ceil((double) allEntries.size() / BATCH_SIZE);

            List<TranslatedEntry> allTranslated = translateInBatches(null, allEntries, targetLanguage, totalBatches);
            subtitleFile.applyTranslations(allTranslated);

            return subtitleFile.getContent();
        } finally {
            Files.deleteIfExists(tempFile);
        }
    }

    /**
     * Returns the output file name: original name with target language appended before extension.
     * Example: movie.srt + Albanian → movie.Albanian.srt
     */
    public String buildOutputFileName(String originalFileName, String targetLanguage) {
        if (originalFileName == null || originalFileName.isBlank()) {
            return "translated." + targetLanguage + ".srt";
        }

        int dotIndex = originalFileName.lastIndexOf('.');
        if (dotIndex < 0) {
            return originalFileName + "." + targetLanguage + ".srt";
        }

        String nameWithoutExt = originalFileName.substring(0, dotIndex);
        String ext = originalFileName.substring(dotIndex);
        return nameWithoutExt + "." + targetLanguage + ext;
    }

    // ---------------------------------------------------------------------------
    // Private
    // ---------------------------------------------------------------------------

    /**
     * Splits entries into batches, translates each, and reports progress.
     * When jobId is null (sync/test path) progress reporting is skipped.
     */
    private List<TranslatedEntry> translateInBatches(String jobId,
                                                      List<TranslationEntry> entries,
                                                      String targetLanguage,
                                                      int totalBatches) {
        List<TranslatedEntry> allTranslated = new ArrayList<>();
        int totalSent = 0;
        int totalReceived = 0;

        for (int i = 0; i < entries.size(); i += BATCH_SIZE) {
            int end = Math.min(i + BATCH_SIZE, entries.size());
            List<TranslationEntry> batch = entries.subList(i, end);
            int batchNumber = (i / BATCH_SIZE) + 1;

            totalSent += batch.size();
            log.info("[Batch {}/{}] Sending {} entries (total sent so far: {})",
                    batchNumber, totalBatches, batch.size(), totalSent);

            List<TranslatedEntry> translated = aiTranslationClient.translate(batch, targetLanguage);
            allTranslated.addAll(translated);

            totalReceived += translated.size();
            log.info("[Batch {}/{}] Received {} translations (total received so far: {})",
                    batchNumber, totalBatches, translated.size(), totalReceived);

            if (jobId != null) {
                int percentage = (int) Math.round((double) batchNumber / totalBatches * 100);
                progressPort.reportProgress(jobId, percentage, batchNumber, totalBatches);
            }
        }

        return allTranslated;
    }

    private Path saveToFilesystem(byte[] fileBytes, String originalFileName) throws IOException {
        Path tempDir = Files.createTempDirectory("subtitle-translations");
        String fileName = (originalFileName != null && !originalFileName.isBlank())
                ? originalFileName
                : "subtitle.srt";
        Path destination = tempDir.resolve(fileName);
        Files.write(destination, fileBytes);
        return destination;
    }

    /**
     * Decodes file bytes stripping any leading BOM (UTF-8, UTF-16 LE/BE).
     */
    private String readStrippingBom(byte[] bytes) {
        if (bytes.length >= 3
                && bytes[0] == (byte) 0xEF
                && bytes[1] == (byte) 0xBB
                && bytes[2] == (byte) 0xBF) {
            return new String(bytes, 3, bytes.length - 3, StandardCharsets.UTF_8);
        }
        if (bytes.length >= 2
                && bytes[0] == (byte) 0xFF
                && bytes[1] == (byte) 0xFE) {
            return new String(bytes, 2, bytes.length - 2, StandardCharsets.UTF_16LE);
        }
        if (bytes.length >= 2
                && bytes[0] == (byte) 0xFE
                && bytes[1] == (byte) 0xFF) {
            return new String(bytes, 2, bytes.length - 2, StandardCharsets.UTF_16BE);
        }
        return new String(bytes, StandardCharsets.UTF_8);
    }
}
