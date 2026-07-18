package com.translate.application.port;

import com.translate.domain.model.TranslatedEntry;
import com.translate.domain.model.TranslationEntry;

import java.util.List;

/**
 * Port defining the contract for AI-based subtitle translation.
 * Implemented in the infrastructure layer by SpringAiTranslationClient.
 */
public interface AiTranslationClient {

    /**
     * Translates a batch of subtitle entries into the target language.
     *
     * @param entries        list of TranslationEntry objects to translate
     * @param targetLanguage the language to translate into (e.g. "Albanian")
     * @return list of TranslatedEntry objects in the same order as the input
     */
    List<TranslatedEntry> translate(List<TranslationEntry> entries, String targetLanguage);
}
