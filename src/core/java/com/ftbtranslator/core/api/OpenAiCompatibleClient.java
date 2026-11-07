package com.ftbtranslator.core.api;

import com.ftbtranslator.core.CancellationToken;
import com.ftbtranslator.core.ProviderType;
import com.ftbtranslator.core.TranslationCallback;
import com.ftbtranslator.core.TranslatorConfigData;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.net.URI;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Standard {@code /v1/chat/completions} client compatible with OpenAI, Groq, OpenRouter, DeepSeek and
 * local Ollama servers.
 */
public final class OpenAiCompatibleClient implements AiTranslationClient {

    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();

    private final ProviderType provider;
    private final String baseUrl;
    private final String model;
    private final String apiKey;
    private final HttpRetryHelper http;
    private final TokenUsage usage;

    public OpenAiCompatibleClient(TranslatorConfigData config, RateLimiter rateLimiter, TranslationCallback callback) {
        this(config, rateLimiter, callback, new TokenUsage());
    }

    public OpenAiCompatibleClient(TranslatorConfigData config, RateLimiter rateLimiter, TranslationCallback callback,
                                  TokenUsage usage) {
        this.usage = usage;
        this.provider = config.getProvider();
        this.baseUrl = config.effectiveBaseUrl();
        this.model = config.effectiveModel();
        this.apiKey = config.getApiKey() == null ? "" : config.getApiKey().trim();
        this.http = new HttpRetryHelper(rateLimiter, config.getMaxRetries(), config.getRequestTimeoutSeconds(), callback);
    }

    @Override
    public Map<String, String> translateBatch(Map<String, String> batch, String systemPrompt, CancellationToken token)
            throws IOException {
        if (batch.isEmpty()) {
            return Collections.emptyMap();
        }
        if (provider.requiresApiKey() && apiKey.isEmpty()) {
            throw new IOException("API Key para " + provider.displayName() + " não foi informada.");
        }

        JsonObject payloadJson = new JsonObject();
        for (Map.Entry<String, String> entry : batch.entrySet()) {
            payloadJson.addProperty(entry.getKey(), entry.getValue());
        }

        JsonObject root = new JsonObject();
        root.addProperty("model", model);
        root.addProperty("temperature", 0.2);

        JsonObject responseFormat = new JsonObject();
        responseFormat.addProperty("type", "json_object");
        root.add("response_format", responseFormat);

        JsonArray messages = new JsonArray();
        JsonObject sysMsg = new JsonObject();
        sysMsg.addProperty("role", "system");
        sysMsg.addProperty("content", systemPrompt);
        messages.add(sysMsg);

        JsonObject userMsg = new JsonObject();
        userMsg.addProperty("role", "user");
        userMsg.addProperty("content", GSON.toJson(payloadJson));
        messages.add(userMsg);
        root.add("messages", messages);

        Map<String, String> headers = new LinkedHashMap<>();
        if (!apiKey.isEmpty()) {
            headers.put("Authorization", "Bearer " + apiKey);
        }
        if (provider == ProviderType.OPENROUTER) {
            headers.put("HTTP-Referer", "https://github.com/ftbtranslator");
            headers.put("X-Title", "FTB Auto-Translator");
        }

        URI uri = buildEndpointUri();
        String responseBody = http.postJson(uri, headers, GSON.toJson(root), token);
        usage.addOpenAi(responseBody);
        String content = extractMessageContent(responseBody);
        return HttpRetryHelper.parseJsonStringMap(content);
    }

    @Override
    public String testConnection(CancellationToken token) throws IOException {
        long start = System.currentTimeMillis();
        Map<String, String> sample = new LinkedHashMap<>();
        sample.put("0", "Hello World");
        Map<String, String> result = translateBatch(
                sample,
                "Traduza os valores deste objeto JSON para Português do Brasil e retorne apenas o objeto JSON.",
                token);
        String translated = result.getOrDefault("0", "");
        if (translated.isBlank()) {
            throw new IOException(provider.displayName() + " respondeu sem tradução válida.");
        }
        long elapsed = System.currentTimeMillis() - start;
        return "Conexão OK com " + provider.displayName() + " (" + model + ") em " + elapsed + " ms -> \"" + translated + "\"";
    }

    private URI buildEndpointUri() {
        if (baseUrl.endsWith("/chat/completions")) {
            return URI.create(baseUrl);
        }
        return URI.create(baseUrl + "/chat/completions");
    }

    private static String extractMessageContent(String responseBody) throws IOException {
        try {
            JsonObject root = JsonParser.parseString(responseBody).getAsJsonObject();
            JsonArray choices = root.getAsJsonArray("choices");
            if (choices == null || choices.isEmpty()) {
                throw new IOException("Resposta sem campo choices: "
                        + HttpRetryHelper.summarizeErrorBody(responseBody));
            }
            JsonObject first = choices.get(0).getAsJsonObject();
            JsonObject message = first.getAsJsonObject("message");
            if (message == null || !message.has("content") || message.get("content").isJsonNull()) {
                throw new IOException("Resposta da API sem message.content.");
            }
            return message.get("content").getAsString();
        } catch (RuntimeException e) {
            throw new IOException("Falha ao interpretar resposta OpenAI-compatible: " + e.getMessage(), e);
        }
    }
}
