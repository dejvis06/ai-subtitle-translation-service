package com.translate.infrastructure.client;

import com.google.genai.errors.ApiException;
import com.google.genai.errors.GenAiIOException;
import com.translate.application.port.AiTranslationClient;
import com.translate.domain.model.TranslatedEntry;
import com.translate.domain.model.TranslationEntry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;

import java.util.List;

/**
 * Infrastructure implementation of AiTranslationClient using Spring AI's ChatClient
 * backed by Google Gemini via the Google GenAI API.
 * <p>
 * Sends a batch of TranslationEntry objects to Gemini and receives
 * a structured list of TranslatedEntry objects using Spring AI structured output.
 * <p>
 */
@Component("geminiTranslationClient")
public class GeminiTranslationClient implements AiTranslationClient {

    private static final Logger log = LoggerFactory.getLogger(GeminiTranslationClient.class);

    private static final int MAX_RETRIES = 3;
    private static final long RETRY_DELAY_MS = 2_000;

    private final ChatClient chatClient;

    public GeminiTranslationClient(@Qualifier("geminiAssistant") ChatClient chatClient) {
        this.chatClient = chatClient;
    }

    @Override
    public List<TranslatedEntry> translate(List<TranslationEntry> entries, String targetLanguage) {
        return translate(entries, targetLanguage, 1);
    }

    private List<TranslatedEntry> translate(List<TranslationEntry> entries, String targetLanguage, int attempt) {
        String userMessage = buildUserMessage(entries, targetLanguage);

        try {
            return chatClient.prompt()
                    .user(userMessage)
                    .call()
                    .entity(new ParameterizedTypeReference<List<TranslatedEntry>>() {
                    });
        } catch (JacksonException e) {
            log.error("Failed to parse Gemini response (attempt {}/{}): {}", attempt, MAX_RETRIES, e.getMessage(), e);
            if (attempt >= MAX_RETRIES)
                throw new RuntimeException("Gemini returned unparseable response after " + MAX_RETRIES + " attempts", e);
            return translate(entries, targetLanguage, attempt + 1);
        } catch (RuntimeException e) {
            log.error("Gemini unexpected error (attempt {}/{}): {}", attempt, MAX_RETRIES, e.getMessage(), e);
            if (attempt >= MAX_RETRIES) throw e;
            sleep();
            return translate(entries, targetLanguage, attempt + 1);
        }
    }

    private void sleep() {
        try {
            Thread.sleep(RETRY_DELAY_MS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Translation retry interrupted", e);
        }
    }

    private String buildUserMessage(List<TranslationEntry> entries, String targetLanguage) {
        StringBuilder sb = new StringBuilder();
        sb.append("Translate the following subtitle entries into: ").append(targetLanguage).append("\n\n");
        sb.append("Entries:\n");

        for (TranslationEntry entry : entries) {
            sb.append("- placeholder: \"").append(entry.placeholder()).append("\"\n");
            sb.append("  originalText: \"").append(entry.originalText()).append("\"\n");
        }

        return sb.toString();
    }
}
