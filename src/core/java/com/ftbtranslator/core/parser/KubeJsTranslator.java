package com.ftbtranslator.core.parser;

import com.ftbtranslator.core.GamePaths;
import com.ftbtranslator.core.LogLevel;
import com.ftbtranslator.core.TranslationCallback;
import com.ftbtranslator.core.TranslationScope;
import com.ftbtranslator.core.TranslatorConfigData;
import com.ftbtranslator.core.cache.TranslationCache;
import com.ftbtranslator.core.text.TokenMasker;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.Stream;

/**
 * Handles Front 3: recursively scans {@code kubejs/assets/&lt;namespace&gt;/lang/}, reads the base language
 * JSON ({@code en_us.json}, {@code ja_jp.json}, {@code zh_cn.json}...) and writes {@code pt_br.json} in
 * the same folder.
 * <p>
 * Cross-deduplicates against {@link JarModExtractor.NamespaceLangAccumulator} and bridges any already-cached
 * translations by {@code (namespace, key)} so zero work or tokens are ever duplicated between KubeJS and
 * mod jars.
 */
public final class KubeJsTranslator {

    private static final TokenMasker TOKEN_MASKER = new TokenMasker();

    private KubeJsTranslator() {
    }

    public static List<PendingOutputTask> scan(GamePaths paths, TranslatorConfigData config,
                                               TranslationCallback callback) throws IOException {
        return scan(paths, config, Map.of(), null, callback);
    }

    public static List<PendingOutputTask> scan(GamePaths paths,
                                               TranslatorConfigData config,
                                               Map<String, JarModExtractor.NamespaceLangAccumulator> jarIndex,
                                               TranslationCache cache,
                                               TranslationCallback callback) throws IOException {
        List<PendingOutputTask> tasks = new ArrayList<>();
        List<Path> candidateRoots = new ArrayList<>();
        candidateRoots.add(paths.kubejsDir().resolve("assets"));
        candidateRoots.add(paths.configDir().resolve("paxi").resolve("resourcepacks"));
        candidateRoots.add(paths.configDir().resolve("openloader").resolve("resources"));
        candidateRoots.add(paths.gameDir().resolve("openloader").resolve("resources"));

        List<Path> langDirs = new ArrayList<>();
        for (Path root : candidateRoots) {
            if (!Files.isDirectory(root)) {
                continue;
            }
            try (Stream<Path> walk = Files.walk(root, 6)) {
                walk.filter(Files::isDirectory)
                        .filter(p -> p.getFileName() != null
                                && p.getFileName().toString().equalsIgnoreCase("lang"))
                        .sorted(Comparator.comparing(Path::toString))
                        .forEach(langDirs::add);
            }
        }
        if (langDirs.isEmpty()) {
            return tasks;
        }

        String targetLang = config.getTargetLanguage();
        List<String> priorityLocales = config.effectiveSourceLanguages();
        boolean deltaMode = config.isSkipAlreadyTranslatedMods();

        for (Path langDir : langDirs) {
            String namespace = extractNamespaceFromLangDir(langDir);
            List<Path> orderedSourceJsons = findOrderedSourceJsons(langDir, priorityLocales, targetLang);
            if (orderedSourceJsons.isEmpty()) {
                continue;
            }

            Map<String, String> kubeMap = new LinkedHashMap<>();
            Set<String> englishKeys = new LinkedHashSet<>();
            for (Path srcJson : orderedSourceJsons) {
                String srcName = srcJson.getFileName().toString().toLowerCase(Locale.ROOT);
                boolean isEn = srcName.equals("en_us.json");
                Map<String, String> parsed = JsonLangIO.readLangFile(srcJson);
                for (Map.Entry<String, String> e : parsed.entrySet()) {
                    String k = e.getKey();
                    String v = e.getValue();
                    if (v == null || v.isBlank()) {
                        continue;
                    }
                    String existing = kubeMap.get(k);
                    if (existing == null || existing.isBlank()) {
                        kubeMap.put(k, v);
                        if (isEn) {
                            englishKeys.add(k);
                        }
                    }
                }
            }

            if ("minecraft".equals(namespace) && englishKeys.isEmpty()) {
                continue;
            }
            if (kubeMap.isEmpty()) {
                continue;
            }

            Path outJson = langDir.resolve(targetLang + ".json");
            Map<String, String> existingLocalPtMap = Map.of();
            if (deltaMode && Files.isRegularFile(outJson)) {
                try {
                    existingLocalPtMap = JsonLangIO.readLangFile(outJson);
                } catch (IOException ignored) {
                }
            }

            JarModExtractor.NamespaceLangAccumulator jarAcc = jarIndex.get(namespace);
            Map<String, String> jarEnMap = jarAcc != null ? jarAcc.sourceEntries : Map.of();
            Map<String, String> jarPtMap = jarAcc != null ? jarAcc.existingPtBrEntries : Map.of();

            Map<String, String> effectiveSourceMap = new LinkedHashMap<>(kubeMap.size());
            Map<String, String> pretranslatedPtMap = new LinkedHashMap<>();
            Set<String> keysToTranslate = new LinkedHashSet<>();

            for (Map.Entry<String, String> e : kubeMap.entrySet()) {
                String key = e.getKey();
                String val = e.getValue();
                if (val == null || val.isBlank()) {
                    continue;
                }
                if (deltaMode) {
                    String existingLocal = existingLocalPtMap.get(key);
                    // A previous failure was written as the source text: retry it instead of keeping it.
                    if (existingLocal != null && !existingLocal.isBlank()
                            && !JarModExtractor.isUntranslatedCopy(existingLocal, val)) {
                        pretranslatedPtMap.put(key, existingLocal);
                        effectiveSourceMap.put(key, existingLocal);
                        if (jarAcc != null) {
                            jarAcc.existingPtBrEntries.putIfAbsent(key, existingLocal);
                        }
                        continue;
                    }
                    String existingPt = jarPtMap.get(key);
                    if (existingPt != null && !existingPt.isBlank()) {
                        pretranslatedPtMap.put(key, existingPt);
                        effectiveSourceMap.put(key, existingPt);
                        continue;
                    }
                }
                boolean isEnglishKubeKey = englishKeys.contains(key);
                if (!isEnglishKubeKey) {
                    // If the foreign kubejs string was already translated in cache, bridge that translation to the
                    // mod jar's existingPtBrEntries as well so the jar doesn't re-translate the English string!
                    String cachedFromKube = cache != null ? cache.resolve(val, TOKEN_MASKER) : null;
                    if (cachedFromKube != null && !cachedFromKube.isBlank()) {
                        pretranslatedPtMap.put(key, cachedFromKube);
                        effectiveSourceMap.put(key, cachedFromKube);
                        if (jarAcc != null) {
                            jarAcc.existingPtBrEntries.putIfAbsent(key, cachedFromKube);
                        }
                        continue;
                    }
                    String jarEn = jarEnMap.get(key);
                    if (jarEn != null && !jarEn.isBlank()) {
                        val = jarEn;
                    }
                }
                effectiveSourceMap.put(key, val);
                keysToTranslate.add(key);
            }

            if (effectiveSourceMap.isEmpty()) {
                continue;
            }
            tasks.add(new KubeJsLangTask(
                    paths.relativize(outJson),
                    outJson,
                    effectiveSourceMap,
                    pretranslatedPtMap,
                    keysToTranslate));
        }

        if (!tasks.isEmpty() && callback != null) {
            callback.onLog(LogLevel.INFO, "KubeJS: " + tasks.size() + " arquivo(s) de idioma preparado(s).");
        }
        return tasks;
    }

    private static String extractNamespaceFromLangDir(Path langDir) {
        Path parent = langDir.getParent();
        if (parent != null && parent.getFileName() != null) {
            return parent.getFileName().toString().toLowerCase(Locale.ROOT);
        }
        return "";
    }

    private static List<Path> findOrderedSourceJsons(Path langDir, List<String> priorityLocales, String targetLang)
            throws IOException {
        List<Path> ordered = new ArrayList<>();
        Set<Path> seen = new LinkedHashSet<>();
        for (String locale : priorityLocales) {
            if (locale.equalsIgnoreCase(targetLang)) {
                continue;
            }
            Path candidate = langDir.resolve(locale + ".json");
            if (Files.isRegularFile(candidate) && seen.add(candidate.toAbsolutePath().normalize())) {
                ordered.add(candidate);
            }
        }
        try (Stream<Path> s = Files.list(langDir)) {
            s.filter(Files::isRegularFile)
                    .filter(p -> {
                        String name = p.getFileName().toString().toLowerCase(Locale.ROOT);
                        return name.endsWith(".json") && !name.equals(targetLang + ".json");
                    })
                    .sorted(Comparator.comparing(Path::toString))
                    .forEach(p -> {
                        if (seen.add(p.toAbsolutePath().normalize())) {
                            ordered.add(p);
                        }
                    });
        }
        return ordered;
    }

    private record KubeJsLangTask(
            String displayPath,
            Path outputPath,
            Map<String, String> sourceEntries,
            Map<String, String> pretranslatedEntries,
            Set<String> keysToTranslate) implements PendingOutputTask {

        @Override
        public TranslationScope scope() {
            return TranslationScope.KUBEJS;
        }

        @Override
        public void collectStrings(Consumer<String> collector) {
            for (String key : keysToTranslate) {
                String value = sourceEntries.get(key);
                if (value != null && !value.isBlank()) {
                    collector.accept(value);
                }
            }
        }

        @Override
        public void writeOutput(Function<String, String> translator) throws IOException {
            Map<String, String> translatedMap = new LinkedHashMap<>(sourceEntries.size());
            for (Map.Entry<String, String> e : sourceEntries.entrySet()) {
                String key = e.getKey();
                String pre = pretranslatedEntries.get(key);
                if (pre != null) {
                    translatedMap.put(key, pre);
                    continue;
                }
                String src = e.getValue();
                String dst = (src == null || src.isBlank()) ? src : translator.apply(src);
                translatedMap.put(key, dst == null ? src : dst);
            }
            JsonLangIO.writeLangFile(outputPath, translatedMap);
        }
    }
}
