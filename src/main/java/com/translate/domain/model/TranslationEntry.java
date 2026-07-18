package com.translate.domain.model;

/**
 * Represents a subtitle entry to be translated.
 * Contains the placeholder that identifies the entry in the file
 * and the original text to be translated.
 */
public record TranslationEntry(
        String placeholder,
        String originalText
) {
}
