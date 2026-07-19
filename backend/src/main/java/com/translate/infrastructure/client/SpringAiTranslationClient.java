package com.translate.infrastructure.client;

import com.translate.application.port.AiTranslationClient;
import com.translate.domain.model.TranslatedEntry;
import com.translate.domain.model.TranslationEntry;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Infrastructure implementation of AiTranslationClient using Spring AI's ChatClient.
 *
 * Sends a batch of TranslationEntry objects to the AI and receives
 * a structured list of TranslatedEntry objects using Spring AI structured output.
 */
@Component
public class SpringAiTranslationClient implements AiTranslationClient {

    private final ChatClient chatClient;

    public SpringAiTranslationClient(ChatClient chatClient) {
        this.chatClient = chatClient;
    }

    @Override
    public List<TranslatedEntry> translate(List<TranslationEntry> entries, String targetLanguage) {
        String userMessage = buildUserMessage(entries, targetLanguage);

        return chatClient.prompt()
                .user(userMessage)
                .call()
                .entity(new ParameterizedTypeReference<List<TranslatedEntry>>() {});
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
