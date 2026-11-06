package com.ftbtranslator.core;

/**
 * Wire protocol spoken by a {@link ProviderType}.
 */
public enum ApiMode {
    /** Native Google AI Studio endpoint: {@code /v1beta/models/{model}:generateContent}. */
    GEMINI,
    /** OpenAI-style {@code /v1/chat/completions} (OpenAI, Groq, OpenRouter, DeepSeek, Ollama...). */
    OPENAI_COMPATIBLE
}
