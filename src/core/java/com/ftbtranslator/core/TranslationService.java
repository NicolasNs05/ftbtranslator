package com.ftbtranslator.core;

import com.ftbtranslator.core.api.AiTranslationClient;
import com.ftbtranslator.core.api.PromptBuilder;
import com.ftbtranslator.core.api.TranslationClientFactory;
import com.ftbtranslator.core.bundle.TranslationBundleManager;
import com.ftbtranslator.core.cache.TranslationCache;
import com.ftbtranslator.core.cache.TranslationGlossary;
import com.ftbtranslator.core.pack.ResourcePackGenerator;
import com.ftbtranslator.core.parser.FtbQuestsTranslator;
import com.ftbtranslator.core.parser.JarModExtractor;
import com.ftbtranslator.core.parser.KubeJsTranslator;
import com.ftbtranslator.core.parser.PatchouliTranslator;
import com.ftbtranslator.core.parser.PendingOutputTask;
import com.ftbtranslator.core.text.MaskedText;
import com.ftbtranslator.core.text.TokenMasker;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * Central orchestrator of the 5 translation fronts, caching, batching, token masking, connection tests
 * and bundle import/export.
 * <p>
 * Optimizations included:
 * <ul>
 *   <li>Cross-deduplication between {@code kubejs/assets/&lt;ns&gt;/lang/} and {@code mods/*.jar} so foreign
 *       overrides ({@code zh_cn.json}/{@code vi_vn.json}) reuse existing {@code pt_br.json} and canonical
 *       {@code en_us.json} strings instead of translating the same mod twice.</li>
 *   <li>Adaptive batch sizing: packs up to {@code 80} short item/skill names per batch (cutting system-prompt
 *       token overhead and HTTP calls in half) while keeping long Patchouli book pages in smaller batches.</li>
 *   <li>Incremental file flushing after every batch.</li>
 * </ul>
 */
public final class TranslationService {

    private static final int MAX_CONSECUTIVE_BATCH_FAILURES = 5;
    /** Maximum glossary terms injected into the system prompt of a single batch. */
    static final int MAX_GLOSSARY_TERMS_PER_BATCH = 25;

    private final GamePaths paths;
    private final TokenMasker tokenMasker = new TokenMasker();
    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "FTBTranslator-Worker");
        t.setDaemon(true);
        return t;
    });

    private final AtomicReference<CancellationToken> activeToken = new AtomicReference<>();
    private final AtomicReference<TranslationCache> activeCache = new AtomicReference<>();
    private volatile ProgressSnapshot latestSnapshot = ProgressSnapshot.idle();

    public TranslationService(Path gameDir) {
        this.paths = new GamePaths(gameDir);
    }

    public GamePaths paths() {
        return paths;
    }

    public ProgressSnapshot latestSnapshot() {
        return latestSnapshot;
    }

    public boolean isRunning() {
        return latestSnapshot.running();
    }

    public boolean isPaused() {
        CancellationToken token = activeToken.get();
        return token != null && token.isPaused();
    }

    /** Toggles pause state if a translation is currently running. Returns the new paused state. */
    public boolean togglePause(TranslationCallback callback) {
        CancellationToken token = activeToken.get();
        if (token == null) {
            return false;
        }
        boolean next = !token.isPaused();
        token.setPaused(next);
        if (next) {
            TranslationCache cache = activeCache.get();
            if (cache != null) {
                try {
                    cache.flush();
                } catch (IOException ignored) {
                }
            }
        }
        latestSnapshot = latestSnapshot.withPaused(next);
        if (callback != null) {
            callback.onProgress(latestSnapshot);
            callback.onLog(LogLevel.INFO, next ? "Tradução pausada." : "Tradução retomada.");
        }
        return next;
    }

    /** Cancels the active translation run if any. */
    public boolean cancel() {
        CancellationToken token = activeToken.get();
        if (token == null) {
            return false;
        }
        token.setPaused(false);
        token.cancel();
        return true;
    }

    /**
     * Starts an asynchronous translation run using the scopes enabled in {@code config}.
     */
    public CompletableFuture<TranslationReport> startAsync(TranslatorConfigData config,
                                                           TranslationCallback callback) {
        Set<TranslationScope> scopes = EnumSet.noneOf(TranslationScope.class);
        for (TranslationScope s : TranslationScope.values()) {
            if (config.isScopeEnabled(s)) {
                scopes.add(s);
            }
        }
        return startAsync(config, scopes, callback);
    }

    /**
     * Starts an asynchronous translation run for the specified {@code scopes}.
     */
    public CompletableFuture<TranslationReport> startAsync(TranslatorConfigData config,
                                                           Set<TranslationScope> scopes,
                                                           TranslationCallback callback) {
        TranslatorConfigData snapshotConfig = config.copy();
        Set<TranslationScope> requestedScopes = scopes == null || scopes.isEmpty()
                ? EnumSet.noneOf(TranslationScope.class)
                : EnumSet.copyOf(scopes);

        return CompletableFuture.supplyAsync(
                () -> runSynchronously(snapshotConfig, requestedScopes, callback),
                executor);
    }

    /**
     * Synchronous entry point (also handy for deterministic JUnit tests).
     */
    public TranslationReport runSynchronously(TranslatorConfigData config,
                                              Set<TranslationScope> scopes,
                                              TranslationCallback callback) {
        long startTime = System.currentTimeMillis();
        CancellationToken token = new CancellationToken();
        if (!activeToken.compareAndSet(null, token)) {
            String msg = "Já existe uma tradução em andamento.";
            if (callback != null) {
                callback.onError(msg, null);
            }
            return new TranslationReport(false, false, msg, scopes, 0, 0, 0, 0, 0, false, false, 0L);
        }

        int tasksTotal = 0;
        int tasksWritten = 0;
        int stringsTranslated = 0;
        int stringsFromCache = 0;
        int stringsFromGlossary = 0;
        int stringsFailed = 0;
        boolean questsChanged = false;
        boolean resourcePackChanged = false;

        List<PendingOutputTask> tasks = new ArrayList<>();
        boolean[] taskCompleted = new boolean[0];
        List<Set<String>> taskPendingStrings = new ArrayList<>();
        Map<String, String> resolvedTranslations = new ConcurrentHashMap<>();
        TranslationCache cache = new TranslationCache(
                paths.cacheFile(config.getTargetLanguage()),
                config.getContextPreset(),
                config.getCustomContext());
        activeCache.set(cache);
        TranslationGlossary glossary = new TranslationGlossary();

        try {
            if (scopes.isEmpty()) {
                String msg = "Nenhuma frente de tradução selecionada.";
                if (callback != null) {
                    callback.onError(msg, null);
                }
                return new TranslationReport(false, false, msg, scopes, 0, 0, 0, 0, 0, false, false, 0L);
            }

            String validationError = config.validateForTranslation();
            if (validationError != null) {
                if (callback != null) {
                    callback.onError(validationError, null);
                }
                return new TranslationReport(false, false, validationError, scopes, 0, 0, 0, 0, 0, false, false, 0L);
            }

            updateProgress(callback, new ProgressSnapshot(
                    "Escaneando arquivos", "Iniciando varredura...", 0, 0, 0, 0, 0, 0, 0, 0, true, false));
            log(callback, LogLevel.INFO, "Iniciando tradução (" + config.getProvider().displayName()
                    + " / " + config.effectiveModel() + " / Contexto: " + config.getContextPreset().displayName() + ")");

            cache.load();

            // Index mod jars once so KubeJS and Mods can share en_us/pt_br keys without duplicate translations.
            // Always indexed: the official pt_br files shipped inside the jars feed the human-made glossary
            // that every front (Quests, KubeJS, Patchouli, Mods) reuses before spending AI tokens.
            token.checkpoint();
            Map<String, JarModExtractor.NamespaceLangAccumulator> jarIndex =
                    JarModExtractor.indexModJars(paths, config, glossary, callback);
            if (!glossary.isEmpty()) {
                log(callback, LogLevel.INFO, "Glossário oficial: " + glossary.size()
                        + " termo(s) com tradução humana encontrados nos mods.");
            }

            // Phase 1: Scan enabled fronts in priority order (FTB Quests -> KubeJS -> Patchouli -> Mods)
            if (scopes.contains(TranslationScope.FTBQUESTS)) {
                token.checkpoint();
                tasks.addAll(FtbQuestsTranslator.scan(paths, config, callback));
            }
            if (scopes.contains(TranslationScope.KUBEJS)) {
                token.checkpoint();
                tasks.addAll(KubeJsTranslator.scan(paths, config, jarIndex, cache, callback));
            }
            if (scopes.contains(TranslationScope.PATCHOULI)) {
                token.checkpoint();
                tasks.addAll(PatchouliTranslator.scan(paths, config, callback));
            }
            if (scopes.contains(TranslationScope.MODS)) {
                token.checkpoint();
                tasks.addAll(JarModExtractor.buildTasksFromIndex(paths, config, jarIndex, callback));
            }

            tasksTotal = tasks.size();
            if (tasks.isEmpty()) {
                String msg = "Nenhum arquivo elegível encontrado para as frentes selecionadas.";
                log(callback, LogLevel.WARN, msg);
                TranslationReport report = new TranslationReport(
                        true, false, msg, scopes, 0, 0, 0, 0, 0, false, false,
                        System.currentTimeMillis() - startTime);
                updateProgress(callback, ProgressSnapshot.idle());
                if (callback != null) {
                    callback.onComplete(report);
                }
                return report;
            }

            // Collect unique strings in task order and deduplicate by masked template (__TK0__...) across all mods
            Set<String> uniqueStrings = new LinkedHashSet<>();
            Map<String, MaskedText> toTranslate = new LinkedHashMap<>();
            Map<String, List<Map.Entry<String, MaskedText>>> originalsByTemplate = new LinkedHashMap<>();
            taskCompleted = new boolean[tasks.size()];
            int[] glossaryHits = {0};

            for (PendingOutputTask task : tasks) {
                Set<String> pendingForTask = new LinkedHashSet<>();
                task.collectStrings(s -> {
                    if (s == null || s.isBlank()) {
                        return;
                    }
                    uniqueStrings.add(s);
                    if (resolvedTranslations.containsKey(s)) {
                        return;
                    }
                    String cached = cache.get(s);
                    if (cached != null && !cached.isBlank()) {
                        resolvedTranslations.put(s, cached);
                        return;
                    }
                    MaskedText masked = tokenMasker.mask(s);
                    if (!masked.hasTranslatableContent()) {
                        resolvedTranslations.put(s, s);
                        return;
                    }
                    String human = glossary.lookup(s);
                    if (human != null && placeholdersMatch(masked, human)) {
                        resolvedTranslations.put(s, human);
                        glossaryHits[0]++;
                        return;
                    }
                    String templateKey = masked.masked();
                    String cachedTemplate = cache.get("__TPL__" + templateKey);
                    if (cachedTemplate != null && !cachedTemplate.isBlank()) {
                        String restored = tokenMasker.unmask(cachedTemplate, masked);
                        if (restored != null) {
                            resolvedTranslations.put(s, restored);
                            return;
                        }
                    }
                    toTranslate.putIfAbsent(templateKey, masked);
                    List<Map.Entry<String, MaskedText>> group = originalsByTemplate.computeIfAbsent(
                            templateKey, k -> new ArrayList<>(1));
                    boolean alreadyInGroup = false;
                    for (Map.Entry<String, MaskedText> existing : group) {
                        if (existing.getKey().equals(s)) {
                            alreadyInGroup = true;
                            break;
                        }
                    }
                    if (!alreadyInGroup) {
                        group.add(Map.entry(s, masked));
                    }
                    pendingForTask.add(s);
                });
                taskPendingStrings.add(pendingForTask);
            }

            stringsFromCache = resolvedTranslations.size();
            stringsFromGlossary = glossaryHits[0];

            // Immediately write any tasks whose strings were 100% resolved from cache or pre-translated
            for (int tIdx = 0; tIdx < tasks.size(); tIdx++) {
                if (taskPendingStrings.get(tIdx).isEmpty()) {
                    PendingOutputTask task = tasks.get(tIdx);
                    task.writeOutput(src -> resolvedTranslations.getOrDefault(src, src));
                    taskCompleted[tIdx] = true;
                    tasksWritten++;
                    if (task.scope() == TranslationScope.FTBQUESTS) {
                        questsChanged = true;
                    }
                    if (task.outputPath().startsWith(paths.generatedPackDir())) {
                        resourcePackChanged = true;
                    }
                }
            }
            if (resourcePackChanged) {
                ResourcePackGenerator.ensurePackMcmeta(paths);
            }

            List<List<Map.Entry<String, MaskedText>>> batches = buildAdaptiveBatches(
                    toTranslate, config.getBatchSize(), config.getMaxBatchChars());
            int batchTotal = batches.size();
            int stringsTotal = toTranslate.size();

            log(callback, LogLevel.INFO, "Strings únicas: " + uniqueStrings.size()
                    + " | Já resolvidas/Cache: " + stringsFromCache
                    + (stringsFromGlossary > 0 ? " (glossário oficial: " + stringsFromGlossary + ")" : "")
                    + " | Templates p/ traduzir: " + stringsTotal + " em " + batchTotal + " lote(s).");

            // Phase 2: Translate uncached batches with AI and flush completed files after every batch
            if (!batches.isEmpty()) {
                AiTranslationClient client = TranslationClientFactory.create(config, callback);
                String systemPrompt = PromptBuilder.buildSystemPrompt(config);
                int consecutiveBatchFailures = 0;

                for (int bIdx = 0; bIdx < batches.size(); bIdx++) {
                    token.checkpoint();
                    List<Map.Entry<String, MaskedText>> batchEntries = batches.get(bIdx);
                    int currentBatchNum = bIdx + 1;

                    updateProgress(callback, new ProgressSnapshot(
                            "Traduzindo com IA",
                            "Lote " + currentBatchNum + "/" + batchTotal + " (" + batchEntries.size() + " textos)",
                            currentBatchNum, batchTotal, stringsTotal,
                            stringsTranslated + stringsFailed, stringsFromCache, stringsFailed,
                            tasksTotal, tasksWritten, true, token.isPaused()));

                    List<String> batchTexts = new ArrayList<>(batchEntries.size());
                    for (Map.Entry<String, MaskedText> be : batchEntries) {
                        batchTexts.add(be.getValue().masked());
                    }
                    String batchPrompt = PromptBuilder.withGlossary(systemPrompt,
                            glossary.termsFor(batchTexts, MAX_GLOSSARY_TERMS_PER_BATCH));

                    Map<String, String> responseMap;
                    try {
                        responseMap = translateBatchResilient(
                                client, batchEntries, batchPrompt, token, callback);
                        consecutiveBatchFailures = 0;
                    } catch (IOException batchEx) {
                        String errMsg = batchEx.getMessage() == null ? "" : batchEx.getMessage();
                        if (errMsg.contains("API Key") && errMsg.contains("inválida")) {
                            throw batchEx;
                        }
                        consecutiveBatchFailures++;
                        log(callback, LogLevel.WARN, "Lote " + currentBatchNum + "/" + batchTotal
                                + " falhou após tentativas de recuperação (" + errMsg + ").");
                        if (consecutiveBatchFailures >= MAX_CONSECUTIVE_BATCH_FAILURES) {
                            throw new IOException("Múltiplos lotes seguidos falharam (" + errMsg
                                    + "). Todo o progresso anterior foi salvo no cache! Clique em 'Iniciar Tradução' para continuar de onde parou.",
                                    batchEx);
                        }
                        token.sleep(4_000L);
                        responseMap = Collections.emptyMap();
                    }

                    for (int i = 0; i < batchEntries.size(); i++) {
                        Map.Entry<String, MaskedText> item = batchEntries.get(i);
                        String templateKey = item.getKey();
                        MaskedText representativeMasked = item.getValue();
                        String rawTranslated = responseMap.get(Integer.toString(i));
                        String testUnmasked = tokenMasker.unmask(rawTranslated, representativeMasked);
                        List<Map.Entry<String, MaskedText>> originals = originalsByTemplate.getOrDefault(
                                templateKey, List.of(item));
                        if (testUnmasked != null) {
                            cache.put("__TPL__" + templateKey, rawTranslated.strip());
                            for (Map.Entry<String, MaskedText> origEntry : originals) {
                                String originalStr = origEntry.getKey();
                                String unmasked = tokenMasker.unmask(rawTranslated, origEntry.getValue());
                                if (unmasked != null) {
                                    resolvedTranslations.put(originalStr, unmasked);
                                    cache.put(originalStr, unmasked);
                                } else {
                                    resolvedTranslations.put(originalStr, originalStr);
                                }
                            }
                            stringsTranslated++;
                        } else {
                            for (Map.Entry<String, MaskedText> origEntry : originals) {
                                resolvedTranslations.put(origEntry.getKey(), origEntry.getKey());
                            }
                            stringsFailed++;
                        }
                    }

                    // Periodically flush SHA-256 cache (every 10 batches or 30 seconds) to prevent disk thrashing
                    cache.flushIfDueAfterBatch();

                    // Flush any file whose strings are now 100% ready
                    boolean newPackFileWritten = false;
                    for (int tIdx = 0; tIdx < tasks.size(); tIdx++) {
                        if (taskCompleted[tIdx]) {
                            continue;
                        }
                        Set<String> pending = taskPendingStrings.get(tIdx);
                        pending.removeIf(resolvedTranslations::containsKey);
                        if (pending.isEmpty()) {
                            PendingOutputTask task = tasks.get(tIdx);
                            task.writeOutput(src -> resolvedTranslations.getOrDefault(src, src));
                            taskCompleted[tIdx] = true;
                            tasksWritten++;
                            if (task.scope() == TranslationScope.FTBQUESTS) {
                                questsChanged = true;
                            }
                            if (task.outputPath().startsWith(paths.generatedPackDir())) {
                                resourcePackChanged = true;
                                newPackFileWritten = true;
                            }
                        }
                    }
                    if (newPackFileWritten) {
                        ResourcePackGenerator.ensurePackMcmeta(paths);
                    }

                    updateProgress(callback, new ProgressSnapshot(
                            "Traduzindo com IA",
                            "Lote " + currentBatchNum + "/" + batchTotal + " concluído ("
                                    + (stringsTranslated + stringsFailed) + "/" + stringsTotal + " strings)",
                            currentBatchNum, batchTotal, stringsTotal,
                            stringsTranslated + stringsFailed, stringsFromCache, stringsFailed,
                            tasksTotal, tasksWritten, true, token.isPaused()));
                }
            }

            // Phase 3: Write any remaining files
            for (int tIdx = 0; tIdx < tasks.size(); tIdx++) {
                if (taskCompleted[tIdx]) {
                    continue;
                }
                token.checkpoint();
                PendingOutputTask task = tasks.get(tIdx);
                updateProgress(callback, new ProgressSnapshot(
                        "Gravando arquivos",
                        task.displayPath(),
                        batchTotal, batchTotal, stringsTotal,
                        stringsTranslated + stringsFailed, stringsFromCache, stringsFailed,
                        tasksTotal, tasksWritten, true, token.isPaused()));

                task.writeOutput(src -> resolvedTranslations.getOrDefault(src, src));
                taskCompleted[tIdx] = true;
                tasksWritten++;

                if (task.scope() == TranslationScope.FTBQUESTS) {
                    questsChanged = true;
                }
                if (task.outputPath().startsWith(paths.generatedPackDir())) {
                    resourcePackChanged = true;
                }
            }

            if (resourcePackChanged) {
                ResourcePackGenerator.ensurePackMcmeta(paths);
            }
            cache.saveIfDirty();

            long duration = System.currentTimeMillis() - startTime;
            String summary = "Tradução concluída em " + (duration / 1000.0) + "s! "
                    + tasksWritten + " arquivo(s) gravado(s), "
                    + stringsTranslated + " traduzida(s), "
                    + stringsFromCache + " do cache"
                    + (stringsFailed > 0 ? " (" + stringsFailed + " mantida(s) no original)." : ".");

            log(callback, LogLevel.INFO, summary);
            TranslationReport report = new TranslationReport(
                    true, false, summary, scopes, tasksTotal, tasksWritten,
                    stringsTranslated, stringsFromCache, stringsFailed,
                    questsChanged, resourcePackChanged, duration);

            updateProgress(callback, new ProgressSnapshot(
                    "Concluído", summary, batchTotal, batchTotal, stringsTotal,
                    stringsTranslated + stringsFailed, stringsFromCache, stringsFailed,
                    tasksTotal, tasksWritten, false, false));

            if (callback != null) {
                callback.onComplete(report);
            }
            return report;

        } catch (TranslationCancelledException cancelled) {
            try {
                cache.saveIfDirty();
                if (stringsTranslated > 0 || stringsFromCache > 0) {
                    for (int tIdx = 0; tIdx < tasks.size(); tIdx++) {
                        if (!taskCompleted[tIdx]) {
                            PendingOutputTask task = tasks.get(tIdx);
                            task.writeOutput(src -> resolvedTranslations.getOrDefault(src, src));
                            taskCompleted[tIdx] = true;
                            tasksWritten++;
                            if (task.scope() == TranslationScope.FTBQUESTS) {
                                questsChanged = true;
                            }
                            if (task.outputPath().startsWith(paths.generatedPackDir())) {
                                resourcePackChanged = true;
                            }
                        }
                    }
                    if (resourcePackChanged) {
                        ResourcePackGenerator.ensurePackMcmeta(paths);
                    }
                }
            } catch (IOException ignored) {
            }

            long duration = System.currentTimeMillis() - startTime;
            String msg = "Tradução cancelada (" + stringsTranslated
                    + " novas traduções salvas no cache e aplicadas aos arquivos).";
            log(callback, LogLevel.WARN, msg);
            TranslationReport report = new TranslationReport(
                    false, true, msg, scopes, tasksTotal, tasksWritten,
                    stringsTranslated, stringsFromCache, stringsFailed,
                    questsChanged, resourcePackChanged, duration);
            updateProgress(callback, new ProgressSnapshot(
                    "Cancelado", msg, 0, 0, 0, stringsTranslated, stringsFromCache, stringsFailed,
                    tasksTotal, tasksWritten, false, false));
            if (callback != null) {
                callback.onComplete(report);
            }
            return report;

        } catch (Exception ex) {
            try {
                cache.saveIfDirty();
                if (stringsTranslated > 0 || stringsFromCache > 0) {
                    for (int tIdx = 0; tIdx < tasks.size(); tIdx++) {
                        if (!taskCompleted[tIdx]) {
                            PendingOutputTask task = tasks.get(tIdx);
                            task.writeOutput(src -> resolvedTranslations.getOrDefault(src, src));
                            taskCompleted[tIdx] = true;
                            tasksWritten++;
                            if (task.scope() == TranslationScope.FTBQUESTS) {
                                questsChanged = true;
                            }
                            if (task.outputPath().startsWith(paths.generatedPackDir())) {
                                resourcePackChanged = true;
                            }
                        }
                    }
                }
                if (resourcePackChanged) {
                    ResourcePackGenerator.ensurePackMcmeta(paths);
                }
            } catch (IOException ignored) {
            }
            long duration = System.currentTimeMillis() - startTime;
            String msg = "Erro durante a tradução: " + ex.getMessage();
            log(callback, LogLevel.ERROR, msg);
            updateProgress(callback, new ProgressSnapshot(
                    "Erro", msg, 0, 0, 0, stringsTranslated, stringsFromCache, stringsFailed,
                    tasksTotal, tasksWritten, false, false));
            if (callback != null) {
                callback.onError(msg, ex);
            }
            return new TranslationReport(
                    false, false, msg, scopes, tasksTotal, tasksWritten,
                    stringsTranslated, stringsFromCache, stringsFailed,
                    questsChanged, resourcePackChanged, duration);
        } finally {
            try {
                cache.flush();
            } catch (IOException ignored) {
            }
            activeCache.set(null);
            activeToken.set(null);
        }
    }

    /**
     * Translates a batch, automatically splitting it into two smaller halves if a large batch encounters
     * a network timeout, HTTP 503 overload or truncated JSON response.
     */
    private Map<String, String> translateBatchResilient(AiTranslationClient client,
                                                        List<Map.Entry<String, MaskedText>> batchEntries,
                                                        String systemPrompt,
                                                        CancellationToken token,
                                                        TranslationCallback callback) throws IOException {
        Map<String, String> requestMap = new LinkedHashMap<>(batchEntries.size());
        for (int i = 0; i < batchEntries.size(); i++) {
            requestMap.put(Integer.toString(i), batchEntries.get(i).getValue().masked());
        }
        try {
            return client.translateBatch(requestMap, systemPrompt, token);
        } catch (IOException ex) {
            String msg = ex.getMessage() == null ? "" : ex.getMessage();
            boolean splittableError = msg.contains("JSON")
                    || msg.contains("timed out")
                    || msg.contains("503")
                    || msg.contains("504");
            if (batchEntries.size() >= 10 && splittableError) {
                int mid = batchEntries.size() / 2;
                log(callback, LogLevel.INFO, "Dividindo lote de " + batchEntries.size()
                        + " textos em 2 sub-lotes menores (" + mid + " + " + (batchEntries.size() - mid)
                        + ") para evitar timeout...");
                token.sleep(2_000L);
                Map<String, String> left = translateBatchResilient(
                        client, batchEntries.subList(0, mid), systemPrompt, token, callback);
                Map<String, String> right = translateBatchResilient(
                        client, batchEntries.subList(mid, batchEntries.size()), systemPrompt, token, callback);
                Map<String, String> combined = new LinkedHashMap<>(batchEntries.size());
                combined.putAll(left);
                for (Map.Entry<String, String> e : right.entrySet()) {
                    int idx = Integer.parseInt(e.getKey()) + mid;
                    combined.put(Integer.toString(idx), e.getValue());
                }
                return combined;
            }
            throw ex;
        }
    }

    /**
     * Runs an asynchronous API ping test and delivers the result to {@code onSuccess} / {@code onFailure}.
     */
    public CompletableFuture<String> testConnectionAsync(TranslatorConfigData config,
                                                         Consumer<String> onSuccess,
                                                         Consumer<String> onFailure) {
        TranslatorConfigData snapshot = config.copy();
        return CompletableFuture.supplyAsync(() -> {
            String validation = snapshot.validateForTranslation();
            if (validation != null) {
                throw new RuntimeException(validation);
            }
            AiTranslationClient client = TranslationClientFactory.create(snapshot, null);
            try {
                return client.testConnection(new CancellationToken());
            } catch (IOException e) {
                throw new RuntimeException(e.getMessage(), e);
            }
        }).whenComplete((ok, err) -> {
            if (err != null) {
                Throwable cause = err.getCause() != null ? err.getCause() : err;
                if (onFailure != null) {
                    onFailure.accept(cause.getMessage());
                }
            } else if (onSuccess != null) {
                onSuccess.accept(ok);
            }
        });
    }

    public TranslationBundleManager.ExportResult exportBundle(String targetLanguage) throws IOException {
        return TranslationBundleManager.exportBundle(paths, targetLanguage);
    }

    /**
     * Imports a translation bundle. Refused while a translation is running: the running job keeps its own
     * in-memory cache and its next flush would overwrite the merged cache file, silently losing the import.
     * The run slot is held during the import so a translation cannot start halfway through it either.
     */
    public TranslationBundleManager.ImportResult importBundle(String targetLanguage) throws IOException {
        CancellationToken importGuard = new CancellationToken();
        if (!activeToken.compareAndSet(null, importGuard)) {
            throw new IOException("Não é possível importar enquanto uma tradução está em andamento. "
                    + "Aguarde terminar ou cancele a tradução e tente novamente.");
        }
        try {
            return TranslationBundleManager.importBundle(paths, targetLanguage);
        } finally {
            activeToken.compareAndSet(importGuard, null);
        }
    }

    /**
     * True when {@code translation} carries exactly the same protected tokens (placeholders, formatting
     * codes...) as the source, in any order, so a human translation never breaks a {@code %s} or {@code §a}.
     */
    private boolean placeholdersMatch(MaskedText source, String translation) {
        List<String> expected = new ArrayList<>(source.tokens());
        List<String> actual = new ArrayList<>(tokenMasker.mask(translation).tokens());
        Collections.sort(expected);
        Collections.sort(actual);
        return expected.equals(actual);
    }

    /**
     * Adaptive batch builder: packs up to {@code 80} short/medium strings into a single request (up to
     * {@code ~4200} chars) to cut system-prompt token overhead and HTTP round-trips in half, while capping
     * batches that contain multi-sentence book pages (> 300 chars) so responses stay fast (~1s).
     */
    private static List<List<Map.Entry<String, MaskedText>>> buildAdaptiveBatches(
            Map<String, MaskedText> items, int configuredBatchSize, int maxCharsPerBatch) {
        List<List<Map.Entry<String, MaskedText>>> batches = new ArrayList<>();
        if (items.isEmpty()) {
            return batches;
        }
        int shortItemCap = Math.max(configuredBatchSize, 80);
        int longItemCap = Math.min(configuredBatchSize, 25);
        int effectiveCharCap = Math.min(maxCharsPerBatch, 4200);

        List<Map.Entry<String, MaskedText>> current = new ArrayList<>();
        int currentChars = 0;
        boolean hasHeavyPage = false;

        for (Map.Entry<String, MaskedText> entry : items.entrySet()) {
            int textLen = entry.getValue().masked().length();
            int entryCost = textLen + 8;
            boolean isHeavyPage = textLen > 300;
            int itemCap = (hasHeavyPage || isHeavyPage) ? longItemCap : shortItemCap;

            if (!current.isEmpty()
                    && (current.size() >= itemCap || currentChars + entryCost > effectiveCharCap)) {
                batches.add(current);
                current = new ArrayList<>();
                currentChars = 0;
                hasHeavyPage = false;
            }
            current.add(entry);
            currentChars += entryCost;
            if (isHeavyPage) {
                hasHeavyPage = true;
            }
        }
        if (!current.isEmpty()) {
            batches.add(current);
        }
        return batches;
    }

    private void updateProgress(TranslationCallback callback, ProgressSnapshot snapshot) {
        this.latestSnapshot = snapshot;
        if (callback != null) {
            callback.onProgress(snapshot);
        }
    }

    private static void log(TranslationCallback callback, LogLevel level, String msg) {
        if (callback != null) {
            callback.onLog(level, msg);
        }
    }
}
