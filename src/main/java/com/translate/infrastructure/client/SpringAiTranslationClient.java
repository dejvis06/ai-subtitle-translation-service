package com.translate.infrastructure.client;

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
 *
 * Sends a batch of TranslationEntry objects to the AI and receives
 * a structured list of TranslatedEntry objects using Spring AI structured output.
 */
@Component
public class SpringAiTranslationClient implements AiTranslationClient {

    private static final Logger log = LoggerFactory.getLogger(SpringAiTranslationClient.class);

    private final ChatClient chatClient;

    public SpringAiTranslationClient(ChatClient chatClient) {
        this.chatClient = chatClient;
    }

    @Override
    public List<TranslatedEntry> translate(List<TranslationEntry> entries, String targetLanguage) {
        log.info("Sending {} entr{} to AI for translation into {}", entries.size(), entries.size() == 1 ? "y" : "ies", targetLanguage);

        String userMessage = buildUserMessage(entries, targetLanguage);

        List<TranslatedEntry> response = chatClient.prompt()
                .user(userMessage)
                .call()
                .entity(new ParameterizedTypeReference<List<TranslatedEntry>>() {});

        log.info("Received {} translated entr{} from AI", response.size(), response.size() == 1 ? "y" : "ies");

        return response;
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
