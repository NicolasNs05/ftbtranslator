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
        int start = config.effectiveRequestsPerMinute();
        // Automatic mode may grow up to twice the provider's entry-level rate on its own (more when the
        // provider reports the account limit); manual mode keeps the player's value fixed.
        RateLimiter limiter = config.isAutoRateLimit()
                ? new RateLimiter(start, true, start * 2)
                : new RateLimiter(start);
        return switch (config.getProvider().apiMode()) {
            case GEMINI -> new GeminiTranslationClient(config, limiter, callback);
            case ANTHROPIC -> new AnthropicTranslationClient(config, limiter, callback);
            case OPENAI_COMPATIBLE -> new OpenAiCompatibleClient(config, limiter, callback);
        };
    }
}
