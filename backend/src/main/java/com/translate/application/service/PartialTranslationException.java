package com.translate.application.service;

import com.translate.domain.model.TranslatedEntry;

import java.util.List;

/**
 * Thrown by translateInBatches when a batch fails after retries are exhausted.
 * Carries the translations already completed and the index in the original entry list
 * where processing broke, so callers can save a restart snapshot.
 */
public class PartialTranslationException extends RuntimeException {

    private final List<TranslatedEntry> completedTranslations;
    private final int failedAtIndex;

    public PartialTranslationException(String message, Throwable cause,
                                       List<TranslatedEntry> completedTranslations,
                                       int failedAtIndex) {
        super(message, cause);
        this.completedTranslations = completedTranslations;
        this.failedAtIndex = failedAtIndex;
    }

    public List<TranslatedEntry> getCompletedTranslations() {
        return completedTranslations;
    }

    /**
     * Index into the original List<TranslationEntry> where the failed batch started.
     * remainingEntries = allEntries.subList(failedAtIndex, allEntries.size())
     */
    public int getFailedAtIndex() {
        return failedAtIndex;
    }
}
