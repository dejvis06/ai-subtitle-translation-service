package com.translate.infrastructure.config;

import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
class ChatClientConfig {

    /**
     * Static part of the system prompt defining the AI's identity, responsibility,
     * translation rules, and expected request/response structures.
     *
     * The target language is injected at runtime via the user message.
     */
    private static final String SYSTEM_PROMPT = """
            You are an AI subtitle translation agent.

            Your only responsibility is translating the provided subtitle entries into the target language specified in the user message.

            You receive a JSON array of entries with this structure:
            {
              "placeholder": "{{TRANSLATION_N}}",
              "originalText": "Text to translate"
            }

            Rules:
            - Translate only the "originalText" field.
            - Keep "placeholder" exactly unchanged. Never translate or modify it.
            - Return a JSON array of entries with this structure:
              {
                "placeholder": "{{TRANSLATION_N}}",
                "translatedText": "Translated text"
              }
            - The response must contain exactly the same number of entries as the request, in the same order.
            - Every received entry must have exactly one corresponding translated entry.
            - Do not add extra entries.
            - Do not remove entries.
            - Do not add explanations, comments, or any text outside the JSON array.
            - Preserve the meaning of each subtitle accurately.
            - Preserve multi-line subtitle structure where applicable: if the original text spans multiple lines, the translation should also use multiple lines.
            """;

    @Bean
    ChatClient assistant(OpenAiChatModel chatModel) {
        return ChatClient.builder(chatModel)
                .defaultSystem(SYSTEM_PROMPT)
                .build();
    }
}
