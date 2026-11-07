package com.ftbtranslator.core.api;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Thread-safe counter of the tokens a translation run consumed, read from each provider's response
 * ({@code usage} / {@code usageMetadata}). Input = what was sent (prompt), output = what the model generated
 * (including internal reasoning, which providers bill as output). Shown to the player as token counts only.
 */
public final class TokenUsage {

    private final AtomicLong input = new AtomicLong();
    private final AtomicLong output = new AtomicLong();

    public long input() {
        return input.get();
    }

    public long output() {
        return output.get();
    }

    public long total() {
        return input.get() + output.get();
    }

    public void add(long inputTokens, long outputTokens) {
        if (inputTokens > 0) {
            input.addAndGet(inputTokens);
        }
        if (outputTokens > 0) {
            output.addAndGet(outputTokens);
        }
    }

    /**
     * Anthropic Messages API: {@code usage.input_tokens} (+ cache creation / read input tokens, billed as
     * input too) and {@code usage.output_tokens} (includes thinking).
     */
    public void addAnthropic(String responseBody) {
        JsonObject usage = object(root(responseBody), "usage");
        if (usage != null) {
            add(num(usage, "input_tokens") + num(usage, "cache_creation_input_tokens")
                            + num(usage, "cache_read_input_tokens"),
                    num(usage, "output_tokens"));
        }
    }

    /** OpenAI-compatible APIs (OpenAI, Groq, OpenRouter, Ollama): {@code usage.prompt_tokens / completion_tokens}. */
    public void addOpenAi(String responseBody) {
        JsonObject usage = object(root(responseBody), "usage");
        if (usage != null) {
            add(num(usage, "prompt_tokens"), num(usage, "completion_tokens"));
        }
    }

    /** Gemini: {@code usageMetadata.promptTokenCount} and {@code candidatesTokenCount + thoughtsTokenCount}. */
    public void addGemini(String responseBody) {
        JsonObject usage = object(root(responseBody), "usageMetadata");
        if (usage != null) {
            add(num(usage, "promptTokenCount"), num(usage, "candidatesTokenCount") + num(usage, "thoughtsTokenCount"));
        }
    }

    private static JsonObject root(String body) {
        if (body == null || body.isBlank()) {
            return null;
        }
        try {
            JsonElement el = JsonParser.parseString(body);
            return el.isJsonObject() ? el.getAsJsonObject() : null;
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static JsonObject object(JsonObject parent, String key) {
        if (parent == null || !parent.has(key) || !parent.get(key).isJsonObject()) {
            return null;
        }
        return parent.getAsJsonObject(key);
    }

    private static long num(JsonObject obj, String key) {
        try {
            return obj.has(key) && !obj.get(key).isJsonNull() ? obj.get(key).getAsLong() : 0L;
        } catch (RuntimeException e) {
            return 0L;
        }
    }
}
