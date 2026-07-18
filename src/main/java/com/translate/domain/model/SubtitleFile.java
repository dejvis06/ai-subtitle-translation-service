package com.translate.domain.model;

import java.util.ArrayList;
import java.util.List;

/**
 * Aggregate root representing a subtitle file being processed for translation.
 *
 * Responsibilities:
 * - Parses the raw SRT content into subtitle blocks
 * - Extracts text entries for translation and replaces them with placeholders
 * - Applies translated text back into the file content
 */
public class SubtitleFile {

    private static final String PLACEHOLDER_FORMAT = "{{TRANSLATION_%d}}";

    private final String originalFileName;
    private String content;
    private final List<TranslationEntry> entries;

    private SubtitleFile(String originalFileName, String content, List<TranslationEntry> entries) {
        this.originalFileName = originalFileName;
        this.content = content;
        this.entries = entries;
    }

    /**
     * Parses the raw SRT file content, extracts subtitle texts,
     * replaces them with placeholders, and returns a SubtitleFile aggregate.
     *
     * @param originalFileName the name of the uploaded .srt file
     * @param rawContent       the raw text content of the .srt file
     * @return a SubtitleFile with placeholders and a list of TranslationEntry objects
     */
    public static SubtitleFile parse(String originalFileName, String rawContent) {
        List<TranslationEntry> entries = new ArrayList<>();
        StringBuilder processedContent = new StringBuilder();

        String[] lines = rawContent.split("\n");

        int i = 0;
        while (i < lines.length) {
            String line = lines[i].stripTrailing();

            // Skip empty lines between blocks (carry them through)
            if (line.isEmpty()) {
                processedContent.append("\n");
                i++;
                continue;
            }

            // Check if this is a subtitle number line (a line containing only digits)
            if (isSubtitleNumber(line)) {
                int subtitleNumber = Integer.parseInt(line.trim());
                processedContent.append(line).append("\n");
                i++;

                // Next line should be the timestamp
                if (i < lines.length) {
                    String timestampLine = lines[i].stripTrailing();
                    processedContent.append(timestampLine).append("\n");
                    i++;

                    // Collect text lines until empty line or end of file
                    List<String> textLines = new ArrayList<>();
                    while (i < lines.length && !lines[i].stripTrailing().isEmpty()) {
                        textLines.add(lines[i].stripTrailing());
                        i++;
                    }

                    if (!textLines.isEmpty()) {
                        String originalText = String.join("\n", textLines);
                        String placeholder = PLACEHOLDER_FORMAT.formatted(subtitleNumber);

                        entries.add(new TranslationEntry(placeholder, originalText));
                        processedContent.append(placeholder).append("\n");
                    }
                }
            } else {
                // Fallback: pass through any unexpected line
                processedContent.append(line).append("\n");
                i++;
            }
        }

        return new SubtitleFile(originalFileName, processedContent.toString(), entries);
    }

    /**
     * Applies translated entries to the file content by replacing each placeholder.
     *
     * @param translatedEntries list of TranslatedEntry objects from the AI
     */
    public void applyTranslations(List<TranslatedEntry> translatedEntries) {
        for (TranslatedEntry translated : translatedEntries) {
            content = content.replace(translated.placeholder(), translated.translatedText());
        }
    }

    public String getOriginalFileName() {
        return originalFileName;
    }

    public String getContent() {
        return content;
    }

    public List<TranslationEntry> getEntries() {
        return List.copyOf(entries);
    }

    private static boolean isSubtitleNumber(String line) {
        return line.trim().matches("\\d+");
    }
}
