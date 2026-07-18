package com.translate.domain.model;

/**
 * Represents a translated subtitle entry returned by the AI.
 * Contains the original placeholder and the translated text.
 */
public record TranslatedEntry(
        String placeholder,
        String translatedText
) {
}
