package com.ftbtranslator.core.api;

import com.ftbtranslator.core.CancellationToken;
import com.ftbtranslator.core.ProviderType;
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
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Native Anthropic Messages API client ({@code POST /v1/messages}) for Claude models.
 * <p>
 * Raw HTTP over {@link java.net.http.HttpClient} instead of the official Java SDK because the {@code core}
 * layer must stay pure Java 21 + Gson (the SDK would add OkHttp/Jackson to the mod jar).
 * <ul>
 *   <li>Structured outputs ({@code output_config.format} with a JSON schema) guarantee a parseable
 *       {@code {"translations":[{"id","text"}]}} reply, converted back to the {@code id -> text} map.</li>
 *   <li>No sampling parameters ({@code temperature}...): API version {@value #API_VERSION} rejects them.</li>
 *   <li>Server-side refusal fallback ({@code fallbacks: "default"}) on the models that support it; a
 *       {@code refusal} or {@code max_tokens} stop is reported as an error so the service can split or skip
 *       the batch.</li>
 *   <li>HTTP 429 / 529 / 5xx are retried by {@link HttpRetryHelper}, honouring {@code retry-after}.</li>
 * </ul>
 */
public final class AnthropicTranslationClient implements AiTranslationClient {

    static final String API_VERSION = "2026-10-21";
    static final String FALLBACK_BETA = "server-side-fallback-2026-07-01";
    /** Room for adaptive thinking plus the translated batch; non-streaming stays under HTTP timeouts. */
    static final long MAX_TOKENS = 16_000L;
    /** Explicit effort (Claude Opus 5.5 defaults to medium; set it so a model swap does not change it). */
    static final String EFFORT = "medium";

    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();

    private final ProviderType provider;
    private final String baseUrl;
    private final String model;
    private final String apiKey;
    private final HttpRetryHelper http;
    private final TokenUsage usage;

    public AnthropicTranslationClient(TranslatorConfigData config, RateLimiter rateLimiter,
                                      TranslationCallback callback) {
        this(config, rateLimiter, callback, new TokenUsage());
    }

    public AnthropicTranslationClient(TranslatorConfigData config, RateLimiter rateLimiter,
                                      TranslationCallback callback, TokenUsage usage) {
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
        if (apiKey.isEmpty()) {
            throw new IOException("API Key para " + provider.displayName() + " não foi informada.");
        }

        JsonObject payloadJson = new JsonObject();
        for (Map.Entry<String, String> entry : batch.entrySet()) {
            payloadJson.addProperty(entry.getKey(), entry.getValue());
        }

        JsonObject root = buildRequest(systemPrompt, GSON.toJson(payloadJson));

        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("x-api-key", apiKey);
        headers.put("anthropic-version", API_VERSION);
        if (supportsServerFallback(model)) {
            headers.put("anthropic-beta", FALLBACK_BETA);
        }

        String responseBody;
        try {
            responseBody = http.postJson(endpointUri(), headers, GSON.toJson(root), token);
        } catch (IOException ex) {
            String msg = ex.getMessage() == null ? "" : ex.getMessage();
            if (msg.startsWith("Erro HTTP 401")) {
                throw new IOException("API Key da Anthropic inválida: confira a chave criada no Claude Console.", ex);
            }
            throw ex;
        }
        usage.addAnthropic(responseBody);
        return parseTranslations(responseBody);
    }

    JsonObject buildRequest(String systemPrompt, String userJson) {
        JsonObject root = new JsonObject();
        root.addProperty("model", model);
        root.addProperty("max_tokens", MAX_TOKENS);
        root.addProperty("system", systemPrompt
                + "\nFormato da resposta: {\"translations\":[{\"id\":\"0\",\"text\":\"...\"}]}, "
                + "um item por chave recebida, com o id igual à chave.");

        JsonArray messages = new JsonArray();
        JsonObject userMsg = new JsonObject();
        userMsg.addProperty("role", "user");
        userMsg.addProperty("content", userJson);
        messages.add(userMsg);
        root.add("messages", messages);

        JsonObject outputConfig = new JsonObject();
        if (supportsEffort(model)) {
            outputConfig.addProperty("effort", EFFORT);
        }
        JsonObject format = new JsonObject();
        format.addProperty("type", "json_schema");
        format.add("schema", translationsSchema());
        outputConfig.add("format", format);
        root.add("output_config", outputConfig);

        if (supportsServerFallback(model)) {
            root.addProperty("fallbacks", "default");
        }
        return root;
    }

    /** {@code {"translations":[{"id":string,"text":string}]}} with {@code additionalProperties:false}. */
    static JsonObject translationsSchema() {
        JsonObject item = new JsonObject();
        item.addProperty("type", "object");
        JsonObject itemProps = new JsonObject();
        itemProps.add("id", typeOnly("string"));
        itemProps.add("text", typeOnly("string"));
        item.add("properties", itemProps);
        JsonArray itemRequired = new JsonArray();
        itemRequired.add("id");
        itemRequired.add("text");
        item.add("required", itemRequired);
        item.addProperty("additionalProperties", false);

        JsonObject list = new JsonObject();
        list.addProperty("type", "array");
        list.add("items", item);

        JsonObject schema = new JsonObject();
        schema.addProperty("type", "object");
        JsonObject props = new JsonObject();
        props.add("translations", list);
        schema.add("properties", props);
        JsonArray required = new JsonArray();
        required.add("translations");
        schema.add("required", required);
        schema.addProperty("additionalProperties", false);
        return schema;
    }

    private static JsonObject typeOnly(String type) {
        JsonObject o = new JsonObject();
        o.addProperty("type", type);
        return o;
    }

    /**
     * Reads the text blocks of a Messages API response (thinking / fallback blocks are skipped) and converts
     * the structured {@code translations} array into an {@code id -> text} map.
     */
    static Map<String, String> parseTranslations(String responseBody) throws IOException {
        JsonObject root;
        try {
            root = JsonParser.parseString(responseBody).getAsJsonObject();
        } catch (RuntimeException e) {
            throw new IOException("Resposta da Anthropic não é JSON: " + HttpRetryHelper.summarizeErrorBody(responseBody), e);
        }
        String stopReason = root.has("stop_reason") && !root.get("stop_reason").isJsonNull()
                ? root.get("stop_reason").getAsString() : "";
        if ("refusal".equals(stopReason)) {
            String category = "";
            if (root.has("stop_details") && root.get("stop_details").isJsonObject()) {
                JsonObject details = root.getAsJsonObject("stop_details");
                if (details.has("category") && !details.get("category").isJsonNull()) {
                    category = " (" + details.get("category").getAsString() + ")";
                }
            }
            throw new IOException("Claude recusou traduzir este lote" + category + ".");
        }
        if ("max_tokens".equals(stopReason)) {
            // Contains "JSON" on purpose: the service splits such batches in two and retries.
            throw new IOException("Resposta truncada (max_tokens): JSON incompleto, o lote será dividido.");
        }

        StringBuilder text = new StringBuilder();
        JsonArray content = root.has("content") && root.get("content").isJsonArray()
                ? root.getAsJsonArray("content") : new JsonArray();
        for (JsonElement block : content) {
            if (block.isJsonObject()) {
                JsonObject b = block.getAsJsonObject();
                if (b.has("type") && "text".equals(b.get("type").getAsString()) && b.has("text")) {
                    text.append(b.get("text").getAsString());
                }
            }
        }
        if (text.length() == 0) {
            throw new IOException("Resposta da Anthropic sem texto (stop_reason: " + stopReason + ").");
        }

        try {
            JsonObject parsed = JsonParser.parseString(text.toString()).getAsJsonObject();
            JsonArray items = parsed.getAsJsonArray("translations");
            Map<String, String> out = new LinkedHashMap<>();
            for (JsonElement el : items) {
                JsonObject item = el.getAsJsonObject();
                out.put(item.get("id").getAsString(), item.get("text").getAsString());
            }
            return out;
        } catch (RuntimeException e) {
            throw new IOException("JSON inválido retornado pelo Claude: " + e.getMessage(), e);
        }
    }

    /** Server-side refusal fallback is offered on the Opus 5 / Fable 5 families and Sonnet 5.5 (not Haiku). */
    static boolean supportsServerFallback(String model) {
        String m = model.toLowerCase(Locale.ROOT);
        return m.startsWith("claude-opus-5") || m.startsWith("claude-fable-5") || m.startsWith("claude-sonnet-5-5");
    }

    /** {@code output_config.effort} exists on Opus 4.5+ and the 5.x generation; older models reject it. */
    static boolean supportsEffort(String model) {
        String m = model.toLowerCase(Locale.ROOT);
        return m.matches("claude-(opus|sonnet|haiku|fable|mythos)-5.*") || m.matches("claude-opus-4-[5-8].*");
    }

    @Override
    public String testConnection(CancellationToken token) throws IOException {
        long start = System.currentTimeMillis();
        Map<String, String> sample = new LinkedHashMap<>();
        sample.put("0", "Hello World");
        Map<String, String> result = translateBatch(
                sample,
                "Traduza os valores deste objeto JSON para Português do Brasil.",
                token);
        String translated = result.getOrDefault("0", "");
        if (translated.isBlank()) {
            throw new IOException(provider.displayName() + " respondeu sem tradução válida.");
        }
        long elapsed = System.currentTimeMillis() - start;
        return "Conexão OK com " + provider.displayName() + " (" + model + ") em " + elapsed + " ms -> \"" + translated + "\"";
    }

    private URI endpointUri() {
        return URI.create(baseUrl.endsWith("/messages") ? baseUrl : baseUrl + "/messages");
    }
}
