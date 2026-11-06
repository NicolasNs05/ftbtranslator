package com.ftbtranslator.core.api;

import com.ftbtranslator.core.ApiMode;
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
        if (config.getProvider().apiMode() == ApiMode.GEMINI) {
            return new GeminiTranslationClient(config, limiter, callback);
        }
        return new OpenAiCompatibleClient(config, limiter, callback);
    }
}
