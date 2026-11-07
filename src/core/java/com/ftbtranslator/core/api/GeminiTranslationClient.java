package com.ftbtranslator.core.api;

import com.ftbtranslator.core.CancellationToken;
import com.ftbtranslator.core.LogLevel;
import com.ftbtranslator.core.TranslationCallback;
import com.ftbtranslator.core.TranslatorConfigData;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Native Google AI Studio client calling
 * {@code https://generativelanguage.googleapis.com/v1beta/models/{model}:generateContent?key={apiKey}}
 * with {@code responseMimeType: "application/json"} and automatic {@code thinkingConfig} fallback.
 * <p>
 * <b>Zero-extra-token speed optimization:</b> Since Google AI Studio enforces RPM quotas
 * <i>per model</i> ({@code PerProjectPerModel}), when no single model is forced by the user this client
 * distributes batches in round-robin across the fast Flash models ({@code gemini-2.5-flash},
 * {@code gemini-2.5-flash-lite}, {@code gemini-3.1-flash-lite}, {@code gemini-3.5-flash-lite},
 * {@code gemini-3.5-flash}), each with its own per-model {@link RateLimiter} and automatic cooldown tracking.
 */
public final class GeminiTranslationClient implements AiTranslationClient {

    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();

    /** Fast primary Flash pool used for round-robin distribution, prioritizing official stable 2.5 models. */
    public static final List<String> FAST_POOL_MODELS = List.of(
            "gemini-2.5-flash",
            "gemini-2.5-flash-lite",
            "gemini-3.1-flash-lite",
            "gemini-3.5-flash-lite",
            "gemini-3.5-flash");

    /** Full fallback chain including secondary models if the primary pool is temporarily overloaded. */
    public static final List<String> FALLBACK_MODELS = List.of(
            "gemini-2.5-flash",
            "gemini-2.5-flash-lite",
            "gemini-3.1-flash-lite",
            "gemini-3.5-flash-lite",
            "gemini-3.5-flash",
            "gemini-2.0-flash",
            "gemma-4-26b-a4b-it");

    private final String baseUrl;
    private final List<String> candidateModels;
    private final int fastPoolSize;
    private final String apiKey;
    private final int requestsPerMinute;
    private final boolean autoRateLimit;
    private final TokenUsage usage;
    private final int perModelAttempts;
    private final int perAttemptTimeout;
    private final TranslationCallback callback;

    private final Map<String, HttpRetryHelper> perModelHttp = new ConcurrentHashMap<>();
    private final Map<String, Long> modelCooldownUntilMillis = new ConcurrentHashMap<>();
    private final Set<String> thinkingConfigDisabledModels = ConcurrentHashMap.newKeySet();
    private final AtomicInteger roundRobinCursor = new AtomicInteger(0);

    public GeminiTranslationClient(TranslatorConfigData config, RateLimiter sharedLimiter, TranslationCallback callback) {
        this(config, sharedLimiter, callback, new TokenUsage());
    }

    public GeminiTranslationClient(TranslatorConfigData config, RateLimiter sharedLimiter, TranslationCallback callback,
                                   TokenUsage usage) {
        this.usage = usage;
        this.baseUrl = config.effectiveBaseUrl();
        this.candidateModels = buildCandidateChain(config.getModel());
        boolean userPinnedCustomModel = config.getModel() != null && !config.getModel().isBlank();
        this.fastPoolSize = userPinnedCustomModel ? 1 : Math.min(FAST_POOL_MODELS.size(), candidateModels.size());
        this.apiKey = config.getApiKey() == null ? "" : config.getApiKey().trim();
        // Gemini quotas are per model, so each model keeps its own limiter (automatic or manual).
        this.requestsPerMinute = config.effectiveRequestsPerMinute();
        this.autoRateLimit = config.isAutoRateLimit();
        this.perModelAttempts = Math.min(2, Math.max(1, config.getMaxRetries()));
        this.perAttemptTimeout = Math.min(90, Math.max(25, config.getRequestTimeoutSeconds()));
        this.callback = callback;
    }

    /**
     * Builds the ordered model fallback list. Upgrades deprecated 2.5/2.0/1.5 identifiers automatically.
     */
    public static List<String> buildCandidateChain(String configuredModel) {
        List<String> chain = new ArrayList<>();
        if (configuredModel != null && !configuredModel.isBlank()) {
            String normalized = normalizeModel(configuredModel);
            chain.add(normalized);
        }
        for (String fallback : FALLBACK_MODELS) {
            if (!chain.contains(fallback)) {
                chain.add(fallback);
            }
        }
        return List.copyOf(chain);
    }

    public static String normalizeModel(String rawModel) {
        if (rawModel == null || rawModel.isBlank()) {
            return FALLBACK_MODELS.get(0);
        }
        String m = rawModel.trim();
        if (m.startsWith("models/")) {
            m = m.substring("models/".length());
        }
        if (m.startsWith("gemini-1.5")) {
            return FALLBACK_MODELS.get(0);
        }
        return m;
    }

    public boolean isThinkingConfigDisabledFor(String modelName) {
        return modelName != null && thinkingConfigDisabledModels.contains(modelName);
    }

    private boolean shouldSendThinkingConfig(String modelName) {
        if (modelName == null || thinkingConfigDisabledModels.contains(modelName)) {
            return false;
        }
        String lower = modelName.toLowerCase(Locale.ROOT);
        return lower.startsWith("gemini-2.5") || lower.startsWith("gemini-3");
    }

    @Override
    public Map<String, String> translateBatch(Map<String, String> batch, String systemPrompt, CancellationToken token)
            throws IOException {
        if (batch.isEmpty()) {
            return Collections.emptyMap();
        }
        if (apiKey.isEmpty()) {
            throw new IOException("API Key do Google Gemini não foi informada.");
        }

        IOException lastError = null;
        int startPoolIdx = Math.floorMod(roundRobinCursor.getAndIncrement(), fastPoolSize);
        long now = System.currentTimeMillis();

        for (int offset = 0; offset < candidateModels.size(); offset++) {
            token.checkpoint();
            int idx = offset < fastPoolSize
                    ? (startPoolIdx + offset) % fastPoolSize
                    : offset;
            String modelToTry = candidateModels.get(idx);

            // Skip a model that is currently in a short 429/503 cooldown unless it's our last resort
            long cooldownUntil = modelCooldownUntilMillis.getOrDefault(modelToTry, 0L);
            if (cooldownUntil > now && offset + 1 < candidateModels.size()) {
                continue;
            }

            boolean useThinkingConfig = shouldSendThinkingConfig(modelToTry);
            String requestBody = buildRequestBody(batch, systemPrompt, modelToTry, useThinkingConfig);
            URI uri = buildEndpointUri(modelToTry);
            HttpRetryHelper modelHttp = httpForModel(modelToTry);

            try {
                String responseBody;
                try {
                    responseBody = modelHttp.postJson(uri, Collections.emptyMap(), requestBody, token);
                } catch (IOException firstEx) {
                    String errMsg = firstEx.getMessage() == null ? "" : firstEx.getMessage();
                    // If the model rejected thinkingConfig with HTTP 400 Bad Request, disable thinkingConfig
                    // for this model for the rest of the session and retry immediately without it.
                    if (useThinkingConfig && errMsg.contains("HTTP 400")) {
                        thinkingConfigDisabledModels.add(modelToTry);
                        if (callback != null) {
                            callback.onLog(LogLevel.INFO, "Modelo " + modelToTry
                                    + " não suporta thinkingConfig; desativando flag e reenviando imediatamente...");
                        }
                        String retryBody = buildRequestBody(batch, systemPrompt, modelToTry, false);
                        responseBody = modelHttp.postJson(uri, Collections.emptyMap(), retryBody, token);
                    } else {
                        throw firstEx;
                    }
                }
                usage.addGemini(responseBody);
                String modelText = extractCandidateText(responseBody);
                Map<String, String> parsed = HttpRetryHelper.parseJsonStringMap(modelText);
                modelCooldownUntilMillis.remove(modelToTry);
                return parsed;
            } catch (IOException ex) {
                lastError = ex;
                String msg = ex.getMessage() == null ? "" : ex.getMessage();
                if (msg.contains("API_KEY_INVALID") || msg.contains("API key not valid") || msg.contains("HTTP 403")) {
                    throw new IOException("API Key do Gemini inválida: verifique se copiou a chave completa no Google AI Studio.", ex);
                }
                // Put this model on a cooldown (1h for 404 retired/unavailable models, 60s for 429 quota, 20s for 503/timeout)
                long cooldownMs = msg.contains("404") ? 3_600_000L : (msg.contains("429") ? 60_000L : 20_000L);
                modelCooldownUntilMillis.put(modelToTry, System.currentTimeMillis() + cooldownMs);

                if (offset + 1 < candidateModels.size()) {
                    int nextIdx = (offset + 1) < fastPoolSize
                            ? (startPoolIdx + offset + 1) % fastPoolSize
                            : (offset + 1);
                    String nextModel = candidateModels.get(nextIdx);
                    if (callback != null) {
                        callback.onLog(LogLevel.WARN, "Modelo " + modelToTry + " ocupado ("
                                + shorten(msg) + "). Alternando para " + nextModel + "...");
                    }
                }
            }
        }
        throw lastError != null ? lastError : new IOException("Todos os modelos Gemini estão temporariamente indisponíveis.");
    }

    @Override
    public String testConnection(CancellationToken token) throws IOException {
        long start = System.currentTimeMillis();
        Map<String, String> sample = new LinkedHashMap<>();
        sample.put("0", "Hello World");
        Map<String, String> result = translateBatch(
                sample,
                "Traduza os valores deste objeto JSON para Português do Brasil e retorne apenas o JSON.",
                token);
        String translated = result.getOrDefault("0", "");
        if (translated.isBlank()) {
            throw new IOException("Gemini respondeu sem tradução válida.");
        }
        long elapsed = System.currentTimeMillis() - start;
        return "API Key válida! (" + candidateModels.get(0) + ", " + elapsed + " ms) -> \"" + translated + "\"";
    }

    private HttpRetryHelper httpForModel(String modelName) {
        return perModelHttp.computeIfAbsent(modelName,
                m -> new HttpRetryHelper(autoRateLimit
                        ? new RateLimiter(requestsPerMinute, true, requestsPerMinute * 2)
                        : new RateLimiter(requestsPerMinute), perModelAttempts, perAttemptTimeout, callback));
    }

    private String buildRequestBody(Map<String, String> batch, String systemPrompt,
                                    String modelName, boolean includeThinkingConfig) {
        JsonObject payloadJson = new JsonObject();
        for (Map.Entry<String, String> entry : batch.entrySet()) {
            payloadJson.addProperty(entry.getKey(), entry.getValue());
        }

        JsonObject root = new JsonObject();

        JsonObject sysInstruction = new JsonObject();
        JsonArray sysParts = new JsonArray();
        JsonObject sysPart = new JsonObject();
        sysPart.addProperty("text", systemPrompt);
        sysParts.add(sysPart);
        sysInstruction.add("parts", sysParts);
        root.add("systemInstruction", sysInstruction);

        JsonArray contents = new JsonArray();
        JsonObject userContent = new JsonObject();
        userContent.addProperty("role", "user");
        JsonArray userParts = new JsonArray();
        JsonObject userPart = new JsonObject();
        userPart.addProperty("text", GSON.toJson(payloadJson));
        userParts.add(userPart);
        userContent.add("parts", userParts);
        contents.add(userContent);
        root.add("contents", contents);

        JsonObject generationConfig = new JsonObject();
        generationConfig.addProperty("responseMimeType", "application/json");
        generationConfig.addProperty("temperature", 0.2);

        if (includeThinkingConfig) {
            JsonObject thinkingConfig = new JsonObject();
            thinkingConfig.addProperty("thinkingLevel", "MINIMAL");
            generationConfig.add("thinkingConfig", thinkingConfig);
        }
        root.add("generationConfig", generationConfig);

        return GSON.toJson(root);
    }

    private URI buildEndpointUri(String modelName) {
        String encodedModel = URLEncoder.encode(modelName, StandardCharsets.UTF_8);
        String encodedKey = URLEncoder.encode(apiKey, StandardCharsets.UTF_8);
        String url = baseUrl + "/models/" + encodedModel + ":generateContent?key=" + encodedKey;
        return URI.create(url);
    }

    private static String shorten(String msg) {
        if (msg == null) {
            return "";
        }
        return msg.length() > 90 ? msg.substring(0, 90) + "..." : msg;
    }

    private static String extractCandidateText(String responseBody) throws IOException {
        try {
            JsonObject root = JsonParser.parseString(responseBody).getAsJsonObject();
            JsonArray candidates = root.getAsJsonArray("candidates");
            if (candidates == null || candidates.isEmpty()) {
                throw new IOException("Resposta do Gemini não contém candidates: "
                        + HttpRetryHelper.summarizeErrorBody(responseBody));
            }
            JsonObject first = candidates.get(0).getAsJsonObject();
            JsonObject content = first.getAsJsonObject("content");
            if (content == null) {
                throw new IOException("Candidate do Gemini sem campo content.");
            }
            JsonArray parts = content.getAsJsonArray("parts");
            if (parts == null || parts.isEmpty()) {
                throw new IOException("Candidate do Gemini sem parts.");
            }
            StringBuilder sb = new StringBuilder();
            for (JsonElement partEl : parts) {
                if (!partEl.isJsonObject()) {
                    continue;
                }
                JsonObject partObj = partEl.getAsJsonObject();
                if (partObj.has("thought") && partObj.get("thought").isJsonPrimitive()
                        && partObj.get("thought").getAsBoolean()) {
                    continue;
                }
                if (partObj.has("text") && !partObj.get("text").isJsonNull()) {
                    sb.append(partObj.get("text").getAsString());
                }
            }
            return sb.toString();
        } catch (RuntimeException e) {
            throw new IOException("Falha ao ler resposta do Gemini: " + e.getMessage(), e);
        }
    }
}
