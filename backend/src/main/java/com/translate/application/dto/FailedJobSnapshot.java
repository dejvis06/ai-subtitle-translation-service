package com.translate.application.dto;

import com.translate.application.dto.AiProvider;
import com.translate.domain.model.SubtitleFile;
import com.translate.domain.model.TranslatedEntry;
import com.translate.domain.model.TranslationEntry;

import java.util.List;

/**
 * Captures the state of a partially completed translation job at the point of failure.
 * Stored by the infrastructure layer and used to resume processing from where it left off.
 */
public record FailedJobSnapshot(
        SubtitleFile subtitleFile,
        List<TranslatedEntry> completedTranslations,
        List<TranslationEntry> remainingEntries,
        String targetLanguage,
        String originalFileName,
        AiProvider aiProvider
) {}
