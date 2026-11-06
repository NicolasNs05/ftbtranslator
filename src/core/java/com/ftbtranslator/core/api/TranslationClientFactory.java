package com.ftbtranslator.core.api;

import com.ftbtranslator.core.TranslationCallback;
import com.ftbtranslator.core.TranslatorConfigData;

/**
 * Instantiates the right {@link AiTranslationClient} for the active {@link TranslatorConfigData}.
 */
public final class TranslationClientFactory {

    private TranslationClientFactory() {
    }

    public static AiTranslationClient create(TranslatorConfigData config, TranslationCallback callback) {
        RateLimiter limiter = new RateLimiter(config.getRequestsPerMinute());
        return switch (config.getProvider().apiMode()) {
            case GEMINI -> new GeminiTranslationClient(config, limiter, callback);
            case ANTHROPIC -> new AnthropicTranslationClient(config, limiter, callback);
            case OPENAI_COMPATIBLE -> new OpenAiCompatibleClient(config, limiter, callback);
        };
    }
}
