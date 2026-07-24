package com.translate.infrastructure.client;

import com.openai.errors.OpenAIInvalidDataException;
import com.openai.errors.OpenAIIoException;
import com.translate.application.port.AiTranslationClient;
import com.translate.domain.model.TranslatedEntry;
import com.translate.domain.model.TranslationEntry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Infrastructure implementation of AiTranslationClient using Spring AI's ChatClient.
 * <p>
 * Sends a batch of TranslationEntry objects to the AI and receives
 * a structured list of TranslatedEntry objects using Spring AI structured output.
 */
@Component
public class SpringAiTranslationClient implements AiTranslationClient {

    private static final Logger log = LoggerFactory.getLogger(SpringAiTranslationClient.class);

    private static final int MAX_RETRIES = 3;
    private static final long RETRY_DELAY_MS = 2_000;

    private final ChatClient chatClient;

    public SpringAiTranslationClient(ChatClient chatClient) {
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
        } catch (OpenAIInvalidDataException e) {
            log.error("Invalid OpenAI response (attempt {}/{}): {}", attempt, MAX_RETRIES, e.getMessage(), e);
            if (attempt >= MAX_RETRIES) throw e;
            return translate(entries, targetLanguage, attempt + 1);
        } catch (OpenAIIoException e) {
            log.error("OpenAI IO error (attempt {}/{}): {}", attempt, MAX_RETRIES, e.getMessage(), e);
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
