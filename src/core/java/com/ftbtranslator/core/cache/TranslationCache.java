package com.ftbtranslator.core.cache;

import com.ftbtranslator.core.ContextPreset;
import com.ftbtranslator.core.text.MaskedText;
import com.ftbtranslator.core.text.TokenMasker;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Persistent SHA-256 translation cache stored in {@code config/ftbtranslator/cache_pt_br.json}.
 * <p>
 * Supports context-isolated hashing ({@code SHA-256(contextPreset|customPromptHash|sourceText)}) so
 * changing thematic presets produces context-appropriate translations, and periodic batch flushing
 * (every {@link #FLUSH_BATCH_INTERVAL} batches or {@link #FLUSH_TIME_INTERVAL_MS} ms, plus mandatory
 * {@link #flush()} on completion/pause/cancellation) to prevent disk thrashing on large modpacks.
 */
public final class TranslationCache {

    public static final int FLUSH_BATCH_INTERVAL = 10;
    public static final long FLUSH_TIME_INTERVAL_MS = 30_000L;

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    private final Path cacheFile;
    private final Map<String, String> entries = new LinkedHashMap<>();
    private ContextPreset contextPreset;
    private String customPromptHash;
    private boolean dirty;
    private int dirtyCount;
    private int batchesSinceLastSave;
    private long lastSaveTimestamp;

    public TranslationCache(Path cacheFile) {
        this(cacheFile, ContextPreset.DEFAULT, "");
    }

    public TranslationCache(Path cacheFile, ContextPreset contextPreset, String customPrompt) {
        this.cacheFile = cacheFile;
        setContext(contextPreset, customPrompt);
        this.lastSaveTimestamp = System.currentTimeMillis();
    }

    public synchronized void setContext(ContextPreset preset, String customPrompt) {
        this.contextPreset = preset == null ? ContextPreset.DEFAULT : preset;
        String cleanCustom = customPrompt == null ? "" : customPrompt.trim();
        this.customPromptHash = (this.contextPreset == ContextPreset.CUSTOM && !cleanCustom.isEmpty())
                ? sha256(cleanCustom)
                : "";
    }

    public synchronized ContextPreset contextPreset() {
        return contextPreset;
    }

    public Path file() {
        return cacheFile;
    }

    public synchronized int size() {
        return entries.size();
    }

    public synchronized int dirtyCount() {
        return dirtyCount;
    }

    public synchronized long lastSaveTimestamp() {
        return lastSaveTimestamp;
    }

    public synchronized void load() throws IOException {
        entries.clear();
        dirty = false;
        dirtyCount = 0;
        batchesSinceLastSave = 0;
        lastSaveTimestamp = System.currentTimeMillis();
        if (!Files.isRegularFile(cacheFile)) {
            return;
        }
        try (Reader reader = Files.newBufferedReader(cacheFile, StandardCharsets.UTF_8)) {
            JsonElement rootEl = JsonParser.parseReader(reader);
            if (rootEl == null || !rootEl.isJsonObject()) {
                return;
            }
            JsonObject root = rootEl.getAsJsonObject();
            JsonObject mapObj = root.has("entries") && root.get("entries").isJsonObject()
                    ? root.getAsJsonObject("entries")
                    : root;
            for (Map.Entry<String, JsonElement> entry : mapObj.entrySet()) {
                if (entry.getValue() != null && entry.getValue().isJsonPrimitive()) {
                    entries.put(entry.getKey(), entry.getValue().getAsString());
                }
            }
        } catch (RuntimeException e) {
            // Corrupted cache file: start fresh rather than crashing the mod.
            entries.clear();
        }
    }

    public synchronized String computeCacheKey(String sourceText) {
        return sha256(contextPreset.name() + "|" + customPromptHash + "|" + sourceText);
    }

    public synchronized String get(String sourceText) {
        if (sourceText == null) {
            return null;
        }
        return entries.get(computeCacheKey(sourceText));
    }

    /** Prefix of the cache entries keyed by masked template ({@code __TK0__} placeholders). */
    public static final String TEMPLATE_PREFIX = "__TPL__";

    /**
     * Resolves a cached translation for {@code sourceText}: first by the exact text (entries written by
     * older versions, kept for compatibility), then by its masked template, which is the only form stored
     * since the template entry already serves every color/macro variation of the same sentence.
     *
     * @return the translation with the original tokens restored, or {@code null} on a miss
     */
    public String resolve(String sourceText, TokenMasker masker) {
        if (sourceText == null) {
            return null;
        }
        String direct = get(sourceText);
        if (direct != null && !direct.isBlank()) {
            return direct;
        }
        MaskedText masked = masker.mask(sourceText);
        return resolveTemplate(masked, masker);
    }

    /** Template-only lookup for callers that already masked the text. */
    public String resolveTemplate(MaskedText masked, TokenMasker masker) {
        String template = get(TEMPLATE_PREFIX + masked.masked());
        if (template == null || template.isBlank()) {
            return null;
        }
        return masker.unmask(template, masked);
    }

    /** Stores the AI translation of a masked template (still containing {@code __TKn__} placeholders). */
    public void putTemplate(String maskedTemplate, String translatedTemplate) {
        put(TEMPLATE_PREFIX + maskedTemplate, translatedTemplate == null ? null : translatedTemplate.strip());
    }

    public synchronized void put(String sourceText, String translatedText) {
        if (sourceText == null || translatedText == null || translatedText.isEmpty()) {
            return;
        }
        String key = computeCacheKey(sourceText);
        String previous = entries.put(key, translatedText);
        if (!translatedText.equals(previous)) {
            dirty = true;
            dirtyCount++;
        }
    }

    /** Merges entries from an imported cache file into this cache. */
    public synchronized int mergeFrom(Path otherCacheFile) throws IOException {
        if (!Files.isRegularFile(otherCacheFile)) {
            return 0;
        }
        TranslationCache other = new TranslationCache(otherCacheFile, this.contextPreset, "");
        other.load();
        int added = 0;
        for (Map.Entry<String, String> e : other.entries.entrySet()) {
            String prev = entries.put(e.getKey(), e.getValue());
            if (!e.getValue().equals(prev)) {
                dirty = true;
                dirtyCount++;
                added++;
            }
        }
        return added;
    }

    /**
     * Called at the end of each batch loop iteration. Flushes to disk only when at least
     * {@link #FLUSH_BATCH_INTERVAL} batches have completed or {@link #FLUSH_TIME_INTERVAL_MS} ms have
     * elapsed since the last save, preventing disk thrashing on 15-30 MB cache files.
     *
     * @return {@code true} if the cache was flushed to disk on this call
     */
    public synchronized boolean flushIfDueAfterBatch() throws IOException {
        batchesSinceLastSave++;
        if (!dirty || dirtyCount <= 0) {
            return false;
        }
        long now = System.currentTimeMillis();
        if (batchesSinceLastSave >= FLUSH_BATCH_INTERVAL
                || (now - lastSaveTimestamp) >= FLUSH_TIME_INTERVAL_MS) {
            flush();
            return true;
        }
        return false;
    }

    /**
     * Flushes any pending in-memory cache updates to disk immediately. Safe to call in {@code finally}
     * blocks upon normal completion, pause, cancellation, or error.
     */
    public synchronized void flush() throws IOException {
        if (!dirty && dirtyCount == 0) {
            return;
        }
        saveForce();
    }

    public synchronized void saveIfDirty() throws IOException {
        flush();
    }

    public synchronized void saveForce() throws IOException {
        Path parent = cacheFile.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        JsonObject root = new JsonObject();
        root.addProperty("version", 2);
        root.addProperty("algorithm", "SHA-256");
        root.addProperty("count", entries.size());
        JsonObject entriesObj = new JsonObject();
        for (Map.Entry<String, String> e : entries.entrySet()) {
            entriesObj.addProperty(e.getKey(), e.getValue());
        }
        root.add("entries", entriesObj);

        Path tmp = cacheFile.resolveSibling(cacheFile.getFileName() + ".tmp");
        try (Writer writer = Files.newBufferedWriter(tmp, StandardCharsets.UTF_8)) {
            GSON.toJson(root, writer);
        }
        Files.move(tmp, cacheFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        dirty = false;
        dirtyCount = 0;
        batchesSinceLastSave = 0;
        lastSaveTimestamp = System.currentTimeMillis();
    }

    public static String sha256(String text) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(text.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 indisponível na JVM", e);
        }
    }
}
