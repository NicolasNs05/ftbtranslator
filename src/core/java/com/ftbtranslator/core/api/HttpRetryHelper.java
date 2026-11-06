package com.ftbtranslator.core.api;

import com.ftbtranslator.core.CancellationToken;
import com.ftbtranslator.core.LogLevel;
import com.ftbtranslator.core.TranslationCallback;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Shared HTTP + Exponential Backoff (HTTP 429 / 529 / 5xx, honouring {@code retry-after}) + resilient JSON dictionary extraction
 * for AI translation providers.
 */
public final class HttpRetryHelper {

    private static final long INITIAL_BACKOFF_MS = 2_500L;
    private static final long MAX_RETRY_AFTER_MS = 60_000L;

    private final HttpClient httpClient;
    private final RateLimiter rateLimiter;
    private final int maxRetries;
    private final int timeoutSeconds;
    private final TranslationCallback callback;

    public HttpRetryHelper(RateLimiter rateLimiter, int maxRetries, int timeoutSeconds, TranslationCallback callback) {
        this.rateLimiter = rateLimiter;
        this.maxRetries = Math.max(1, maxRetries);
        this.timeoutSeconds = Math.max(10, timeoutSeconds);
        this.callback = callback;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(Math.min(30, this.timeoutSeconds)))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    public String postJson(URI uri, Map<String, String> headers, String jsonBody, CancellationToken token)
            throws IOException {
        int attempt = 0;
        while (true) {
            token.checkpoint();
            rateLimiter.acquire(token);

            HttpRequest.Builder builder = HttpRequest.newBuilder(uri)
                    .timeout(Duration.ofSeconds(timeoutSeconds))
                    .header("Content-Type", "application/json; charset=UTF-8")
                    .header("Accept", "application/json")
                    .header("User-Agent", "FTBAutoTranslator/1.0.0 (Minecraft 1.21.1; NeoForge)")
                    .POST(HttpRequest.BodyPublishers.ofString(jsonBody, StandardCharsets.UTF_8));
            for (Map.Entry<String, String> h : headers.entrySet()) {
                builder.header(h.getKey(), h.getValue());
            }

            HttpResponse<String> response;
            try {
                response = httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                token.cancel();
                token.checkpoint();
                throw new IOException("Requisição HTTP interrompida", e);
            } catch (IOException netEx) {
                attempt++;
                if (attempt >= maxRetries) {
                    throw new IOException("Falha de rede após " + attempt + " tentativas: " + netEx.getMessage(), netEx);
                }
                long delay = backoffDelayMillis(attempt);
                log(LogLevel.WARN, "Oscilação de rede (" + netEx.getMessage() + "). Tentativa "
                        + (attempt + 1) + "/" + maxRetries + " em " + (delay / 1000.0) + "s...");
                token.sleep(delay);
                continue;
            }

            int status = response.statusCode();
            String body = response.body() == null ? "" : response.body();
            if (status >= 200 && status < 300) {
                return body;
            }

            if (isRetryableStatus(status)) {
                attempt++;
                if (attempt >= maxRetries) {
                    throw new IOException("HTTP " + status + " persistente após " + attempt
                            + " tentativas: " + summarizeErrorBody(body));
                }
                long delay = Math.max(backoffDelayMillis(attempt),
                        retryAfterMillis(response.headers().firstValue("retry-after").orElse(null)));
                log(LogLevel.WARN, "Servidor retornou HTTP " + status + " (Rate-Limit / Sobrecarga). "
                        + "Exponential Backoff: aguardando " + (delay / 1000.0) + "s (tentativa "
                        + (attempt + 1) + "/" + maxRetries + ")...");
                token.sleep(delay);
                continue;
            }

            throw new IOException("Erro HTTP " + status + ": " + summarizeErrorBody(body));
        }
    }

    private static boolean isRetryableStatus(int status) {
        // 529 = Anthropic "overloaded_error"; 500 = transient server error.
        return status == 429 || status == 529 || status == 500 || status == 503 || status == 502
                || status == 504 || status == 408;
    }

    /** Parses a {@code retry-after} header given in seconds; 0 when absent or unparseable. */
    static long retryAfterMillis(String headerValue) {
        if (headerValue == null || headerValue.isBlank()) {
            return 0L;
        }
        try {
            double seconds = Double.parseDouble(headerValue.trim());
            return Math.max(0L, Math.min(MAX_RETRY_AFTER_MS, (long) Math.ceil(seconds * 1000.0)));
        } catch (NumberFormatException e) {
            return 0L;
        }
    }

    private static long backoffDelayMillis(int attempt) {
        long exp = INITIAL_BACKOFF_MS * (1L << Math.min(5, attempt - 1));
        long jitter = (long) (Math.random() * 600L);
        return Math.min(45_000L, exp + jitter);
    }

    private void log(LogLevel level, String msg) {
        if (callback != null) {
            callback.onLog(level, msg);
        }
    }

    public static String summarizeErrorBody(String body) {
        if (body == null || body.isBlank()) {
            return "(resposta vazia)";
        }
        try {
            JsonElement el = JsonParser.parseString(body);
            if (el.isJsonObject()) {
                JsonObject obj = el.getAsJsonObject();
                if (obj.has("error")) {
                    JsonElement err = obj.get("error");
                    if (err.isJsonObject() && err.getAsJsonObject().has("message")) {
                        return err.getAsJsonObject().get("message").getAsString();
                    }
                    if (err.isJsonPrimitive()) {
                        return err.getAsString();
                    }
                }
            }
        } catch (RuntimeException ignored) {
        }
        String compact = body.replaceAll("\\s+", " ").trim();
        return compact.length() > 260 ? compact.substring(0, 260) + "..." : compact;
    }

    /**
     * Parses a JSON object mapping string IDs to translated strings from the model's response text,
     * stripping optional markdown code fences ({@code ```json ... ```}) if the provider added them.
     */
    public static Map<String, String> parseJsonStringMap(String modelOutputText) throws IOException {
        if (modelOutputText == null || modelOutputText.isBlank()) {
            throw new IOException("Resposta da IA veio vazia.");
        }
        String cleaned = stripCodeFence(modelOutputText.trim());
        int firstBrace = cleaned.indexOf('{');
        int lastBrace = cleaned.lastIndexOf('}');
        if (firstBrace >= 0 && lastBrace > firstBrace) {
            cleaned = cleaned.substring(firstBrace, lastBrace + 1);
        }
        try {
            JsonElement parsed = JsonParser.parseString(cleaned);
            if (!parsed.isJsonObject()) {
                throw new IOException("A resposta da IA não é um objeto JSON: " + cleaned);
            }
            JsonObject obj = parsed.getAsJsonObject();
            if (obj.size() == 1 && obj.has("translations") && obj.get("translations").isJsonObject()) {
                obj = obj.getAsJsonObject("translations");
            }
            Map<String, String> out = new LinkedHashMap<>();
            for (Map.Entry<String, JsonElement> entry : obj.entrySet()) {
                JsonElement val = entry.getValue();
                if (val != null && val.isJsonPrimitive()) {
                    out.put(entry.getKey(), val.getAsString());
                }
            }
            return out;
        } catch (RuntimeException e) {
            throw new IOException("JSON inválido retornado pela IA: " + e.getMessage(), e);
        }
    }

    private static String stripCodeFence(String text) {
        if (!text.startsWith("```")) {
            return text;
        }
        int firstNewline = text.indexOf('\n');
        String body = firstNewline >= 0 ? text.substring(firstNewline + 1) : text.substring(3);
        if (body.endsWith("```")) {
            body = body.substring(0, body.length() - 3);
        }
        return body.trim();
    }
}
