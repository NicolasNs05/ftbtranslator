package com.ftbtranslator.core.api;

import com.ftbtranslator.core.CancellationToken;

import java.io.IOException;
import java.util.Map;

/**
 * Abstraction implemented by every AI translation backend ({@link GeminiTranslationClient} and
 * {@link OpenAiCompatibleClient}).
 */
public interface AiTranslationClient {

    /**
     * Translates a batch of masked strings.
     *
     * @param batch        ordered map {@code id ("0", "1", ...) -> masked source string}
     * @param systemPrompt complete system instruction including context preset and formatting rules
     * @param token        cancellation / pause token
     * @return map {@code id -> translated string (still containing __TKx__ placeholders)}
     */
    Map<String, String> translateBatch(Map<String, String> batch, String systemPrompt, CancellationToken token)
            throws IOException;

    /**
     * Performs a lightweight ping request to validate connectivity and API key.
     *
     * @return human-readable success message in Portuguese (including model name and latency)
     * @throws IOException with a clear Portuguese message if the connection or key fails
     */
    String testConnection(CancellationToken token) throws IOException;
}
