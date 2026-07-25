package com.translate.application.dto;

/**
 * Represents the AI provider to use for subtitle translation.
 * Supplied by the client on each request and stored in the job snapshot
 * so that restarts use the same provider.
 */
public enum AiProvider {
    OPENAI,
    GEMINI
}
