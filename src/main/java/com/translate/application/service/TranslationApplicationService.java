package com.translate.application.service;

import com.translate.application.port.AiTranslationClient;
import com.translate.domain.model.SubtitleFile;
import com.translate.domain.model.TranslatedEntry;
import com.translate.domain.model.TranslationEntry;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Application service orchestrating the subtitle translation use case.
 *
 * Flow:
 * 1. Save uploaded file to the filesystem
 * 2. Read and parse the SRT file into a SubtitleFile aggregate
 * 3. Send translation entries to the AI in batches of 20
 * 4. Apply translated text back into the file content
 * 5. Delete the temp file and return the translated content
 */
@Service
public class TranslationApplicationService {

    private static final int BATCH_SIZE = 20;

    private final AiTranslationClient aiTranslationClient;

    public TranslationApplicationService(AiTranslationClient aiTranslationClient) {
        this.aiTranslationClient = aiTranslationClient;
    }

    /**
     * Translates an SRT subtitle file to the specified target language.
     *
     * @param file           the uploaded .srt file
     * @param targetLanguage the language to translate into (e.g. "Albanian")
     * @return the translated SRT content as a String
     */
    public String translate(MultipartFile file, String targetLanguage) throws IOException {
        String originalFileName = file.getOriginalFilename();

        // 1. Save file to filesystem
        Path tempFile = saveToFilesystem(file);

        try {
            // 2. Read file and parse into SubtitleFile aggregate
            String rawContent = readStippingBom(tempFile);
            SubtitleFile subtitleFile = SubtitleFile.parse(originalFileName, rawContent);

            // 3. Send entries to AI in batches
            List<TranslationEntry> allEntries = subtitleFile.getEntries();
            List<TranslatedEntry> allTranslated = translateInBatches(allEntries, targetLanguage);

            // 4. Apply translations back into the content
            subtitleFile.applyTranslations(allTranslated);

            return subtitleFile.getContent();

        } finally {
            // 5. Delete temp file
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

    /**
     * Reads file content, stripping any leading BOM so subtitle files created
     * on Windows (UTF-8 BOM, UTF-16 LE/BE) are decoded correctly.
     */
    private String readStippingBom(Path path) throws IOException {
        byte[] bytes = Files.readAllBytes(path);

        // UTF-8 BOM: EF BB BF
        if (bytes.length >= 3
                && bytes[0] == (byte) 0xEF
                && bytes[1] == (byte) 0xBB
                && bytes[2] == (byte) 0xBF) {
            return new String(bytes, 3, bytes.length - 3, StandardCharsets.UTF_8);
        }

        // UTF-16 LE BOM: FF FE
        if (bytes.length >= 2
                && bytes[0] == (byte) 0xFF
                && bytes[1] == (byte) 0xFE) {
            return new String(bytes, 2, bytes.length - 2, StandardCharsets.UTF_16LE);
        }

        // UTF-16 BE BOM: FE FF
        if (bytes.length >= 2
                && bytes[0] == (byte) 0xFE
                && bytes[1] == (byte) 0xFF) {
            return new String(bytes, 2, bytes.length - 2, StandardCharsets.UTF_16BE);
        }

        return new String(bytes, StandardCharsets.UTF_8);
    }

    private Path saveToFilesystem(MultipartFile file) throws IOException {
        Path tempDir = Files.createTempDirectory("subtitle-translations");
        String originalFileName = file.getOriginalFilename() != null
                ? file.getOriginalFilename()
                : "subtitle.srt";
        Path destination = tempDir.resolve(originalFileName);
        file.transferTo(destination);
        return destination;
    }

    private List<TranslatedEntry> translateInBatches(List<TranslationEntry> entries, String targetLanguage) {
        List<TranslatedEntry> allTranslated = new ArrayList<>();

        for (int i = 0; i < entries.size(); i += BATCH_SIZE) {
            int end = Math.min(i + BATCH_SIZE, entries.size());
            List<TranslationEntry> batch = entries.subList(i, end);
            List<TranslatedEntry> translated = aiTranslationClient.translate(batch, targetLanguage);
            allTranslated.addAll(translated);
        }

        return allTranslated;
    }
}
