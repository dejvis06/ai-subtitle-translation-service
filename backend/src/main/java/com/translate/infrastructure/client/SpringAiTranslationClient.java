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
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Infrastructure implementation of AiTranslationClient using Spring AI's ChatClient.
 *
 * Sends a batch of TranslationEntry objects to the AI and receives
 * a structured list of TranslatedEntry objects using Spring AI structured output.
 *
 * Logging policy: to avoid noisy output on large files, outgoing/incoming
 * messages are logged once every 5 batches (= every 100 entries at batch size 20).
 * The first batch is always logged.
 */
@Component
public class SpringAiTranslationClient implements AiTranslationClient {

    private static final Logger log = LoggerFactory.getLogger(SpringAiTranslationClient.class);

    /** Log every N batches. With BATCH_SIZE=20 this equates to every 100 entries. */
    private static final int LOG_EVERY_N_BATCHES = 5;

    private final ChatClient chatClient;
    private final AtomicInteger batchCounter = new AtomicInteger(0);

    public SpringAiTranslationClient(ChatClient chatClient) {
        this.chatClient = chatClient;
    }

    @Override
    public List<TranslatedEntry> translate(List<TranslationEntry> entries, String targetLanguage) {
        int batchNum = batchCounter.incrementAndGet();
        boolean shouldLog = (batchNum == 1) || (batchNum % LOG_EVERY_N_BATCHES == 0);

        if (shouldLog) {
            log.info("[AI Batch #{}] Sending {} entries for translation into {}",
                    batchNum, entries.size(), targetLanguage);
        }

        String userMessage = buildUserMessage(entries, targetLanguage);

        List<TranslatedEntry> response = chatClient.prompt()
                .user(userMessage)
                .call()
                .entity(new ParameterizedTypeReference<List<TranslatedEntry>>() {});

        if (shouldLog) {
            log.info("[AI Batch #{}] Received {} translated entries", batchNum, response.size());
        }

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
