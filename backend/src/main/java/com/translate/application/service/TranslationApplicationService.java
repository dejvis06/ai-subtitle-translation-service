package com.translate.application.service;

import com.translate.application.dto.AiProvider;
import com.translate.application.dto.FailedJobSnapshot;
import com.translate.application.port.AiTranslationClient;
import com.translate.application.port.TranslationProgressPort;
import com.translate.domain.model.SubtitleFile;
import com.translate.domain.model.TranslatedEntry;
import com.translate.domain.model.TranslationEntry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.concurrent.CompletableFuture;

/**
 * Application service orchestrating the subtitle translation use case.
 *
 * Flow:
 * 1. Parse the SRT file bytes into a SubtitleFile aggregate
 * 2. Send translation entries to the AI in batches of 20
 * 3. Apply translated text back into the file content
 * 4. Report completion (or errors) through the progress port
 */
@Service
public class TranslationApplicationService {

    private static final Logger log = LoggerFactory.getLogger(TranslationApplicationService.class);
    private static final int BATCH_SIZE = 20;

    private final AiTranslationClient openaiClient;
    private final AiTranslationClient geminiClient;
    private final TranslationProgressPort progressPort;

    public TranslationApplicationService(
            @Qualifier("openaiTranslationClient") AiTranslationClient openaiClient,
            @Qualifier("geminiTranslationClient") AiTranslationClient geminiClient,
            TranslationProgressPort progressPort) {
        this.openaiClient = openaiClient;
        this.geminiClient = geminiClient;
        this.progressPort = progressPort;
    }

    private AiTranslationClient selectClient(AiProvider provider) {
        return switch (provider) {
            case OPENAI -> openaiClient;
            case GEMINI -> geminiClient;
        };
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
                                                   String targetLanguage,
                                                   AiProvider aiProvider) {
        log.info("[Job {}] Starting translation of '{}' into {} via {}", jobId, originalFileName, targetLanguage, aiProvider);

        try {
            String rawContent = readStrippingBom(fileBytes);
            SubtitleFile subtitleFile = SubtitleFile.parse(originalFileName, rawContent);

            List<TranslationEntry> allEntries = subtitleFile.getEntries();
            int totalBatches = (int) Math.ceil((double) allEntries.size() / BATCH_SIZE);

            log.info("[Job {}] Parsed {} subtitle entries → {} batch(es)", jobId, allEntries.size(), totalBatches);

            AiTranslationClient client = selectClient(aiProvider);
            List<TranslatedEntry> allTranslated = translateInBatches(jobId, allEntries, targetLanguage, totalBatches, client);
            subtitleFile.applyTranslations(allTranslated);

            String outputFileName = buildOutputFileName(originalFileName, targetLanguage);
            progressPort.reportComplete(jobId, subtitleFile.getContent(), outputFileName);

            log.info("[Job {}] Translation complete → {}", jobId, outputFileName);

        } catch (PartialTranslationException e) {
            log.error("[Job {}] Translation failed at entry index {}: {}", jobId, e.getFailedAtIndex(), e.getMessage(), e);
            saveSnapshot(jobId, fileBytes, originalFileName, targetLanguage, aiProvider, e);
            progressPort.reportError(jobId, e.getMessage());

        } catch (Exception e) {
            log.error("[Job {}] Translation failed: {}", jobId, e.getMessage(), e);
            progressPort.reportError(jobId, e.getMessage());
        }

        return CompletableFuture.completedFuture(null);
    }

    /**
     * Resumes a failed job from its saved snapshot.
     * The job must have been registered and reset in the job store before calling this.
     */
    @Async("translationExecutor")
    public CompletableFuture<Void> restartTranslation(String jobId) {
        FailedJobSnapshot snapshot = progressPort.getFailedSnapshot(jobId)
                .orElseThrow(() -> new NoSuchElementException("No restart snapshot found for job: " + jobId));

        log.info("[Job {}] Restarting from entry index {} ({} remaining, {} already completed) via {}",
                jobId, snapshot.subtitleFile().getEntries().size() - snapshot.remainingEntries().size(),
                snapshot.remainingEntries().size(), snapshot.completedTranslations().size(), snapshot.aiProvider());

        try {
            List<TranslationEntry> remaining = snapshot.remainingEntries();
            int totalBatches = (int) Math.ceil((double) remaining.size() / BATCH_SIZE);

            AiTranslationClient client = selectClient(snapshot.aiProvider());
            List<TranslatedEntry> newTranslations = translateInBatches(jobId, remaining, snapshot.targetLanguage(), totalBatches, client);

            List<TranslatedEntry> allTranslated = new ArrayList<>(snapshot.completedTranslations());
            allTranslated.addAll(newTranslations);

            SubtitleFile subtitleFile = snapshot.subtitleFile();
            subtitleFile.applyTranslations(allTranslated);

            String outputFileName = buildOutputFileName(snapshot.originalFileName(), snapshot.targetLanguage());
            progressPort.reportComplete(jobId, subtitleFile.getContent(), outputFileName);

            log.info("[Job {}] Restart complete → {}", jobId, outputFileName);

        } catch (PartialTranslationException e) {
            log.error("[Job {}] Restart failed again at index {}: {}", jobId, e.getFailedAtIndex(), e.getMessage(), e);

            // Merge already-completed translations with new partial progress and update snapshot
            List<TranslatedEntry> mergedCompleted = new ArrayList<>(snapshot.completedTranslations());
            mergedCompleted.addAll(e.getCompletedTranslations());
            List<TranslationEntry> newRemaining = snapshot.remainingEntries()
                    .subList(e.getFailedAtIndex(), snapshot.remainingEntries().size());

            progressPort.saveFailedSnapshot(jobId, new FailedJobSnapshot(
                    snapshot.subtitleFile(), List.copyOf(mergedCompleted), List.copyOf(newRemaining),
                    snapshot.targetLanguage(), snapshot.originalFileName(), snapshot.aiProvider()));

            progressPort.reportError(jobId, e.getMessage());

        } catch (Exception e) {
            log.error("[Job {}] Restart failed: {}", jobId, e.getMessage(), e);
            progressPort.reportError(jobId, e.getMessage());
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

        String rawContent = readStrippingBom(fileBytes);
        SubtitleFile subtitleFile = SubtitleFile.parse(originalFileName, rawContent);

        List<TranslationEntry> allEntries = subtitleFile.getEntries();
        int totalBatches = (int) Math.ceil((double) allEntries.size() / BATCH_SIZE);

        List<TranslatedEntry> allTranslated = translateInBatches(null, allEntries, targetLanguage, totalBatches, openaiClient);
        subtitleFile.applyTranslations(allTranslated);

        return subtitleFile.getContent();
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
     * Throws {@link PartialTranslationException} on failure, carrying all translations
     * completed before the failing batch and the index into {@code entries} where it failed.
     */
    private List<TranslatedEntry> translateInBatches(String jobId,
                                                      List<TranslationEntry> entries,
                                                      String targetLanguage,
                                                      int totalBatches,
                                                      AiTranslationClient client) {
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

            try {
                List<TranslatedEntry> translated = client.translate(batch, targetLanguage);
                allTranslated.addAll(translated);

                totalReceived += translated.size();
                log.info("[Batch {}/{}] Received {} translations (total received so far: {})",
                        batchNumber, totalBatches, translated.size(), totalReceived);

                if (jobId != null) {
                    int percentage = (int) Math.round((double) batchNumber / totalBatches * 100);
                    progressPort.reportProgress(jobId, percentage, batchNumber, totalBatches);
                }
            } catch (Exception e) {
                throw new PartialTranslationException(e.getMessage(), e, List.copyOf(allTranslated), i);
            }
        }

        return allTranslated;
    }

    /**
     * Re-parses the original file bytes and saves a {@link FailedJobSnapshot}
     * so the job can be restarted from the failing entry index.
     */
    private void saveSnapshot(String jobId, byte[] fileBytes, String originalFileName,
                               String targetLanguage, AiProvider aiProvider, PartialTranslationException e) {
        try {
            String rawContent = readStrippingBom(fileBytes);
            SubtitleFile subtitleFile = SubtitleFile.parse(originalFileName, rawContent);
            List<TranslationEntry> remaining = subtitleFile.getEntries()
                    .subList(e.getFailedAtIndex(), subtitleFile.getEntries().size());

            progressPort.saveFailedSnapshot(jobId, new FailedJobSnapshot(
                    subtitleFile, List.copyOf(e.getCompletedTranslations()),
                    List.copyOf(remaining), targetLanguage, originalFileName, aiProvider));

            log.info("[Job {}] Saved restart snapshot: {} completed, {} remaining",
                    jobId, e.getCompletedTranslations().size(), remaining.size());
        } catch (Exception snapshotEx) {
            log.error("[Job {}] Failed to save restart snapshot: {}", jobId, snapshotEx.getMessage(), snapshotEx);
        }
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
