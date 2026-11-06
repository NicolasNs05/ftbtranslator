package com.ftbtranslator.core.parser;

import com.ftbtranslator.core.GamePaths;
import com.ftbtranslator.core.LogLevel;
import com.ftbtranslator.core.TranslationCallback;
import com.ftbtranslator.core.TranslationScope;
import com.ftbtranslator.core.TranslatorConfigData;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipInputStream;

/**
 * Handles Front 4: scans every {@code .jar} inside {@code mods/} read-only with {@link ZipFile},
 * extracts {@code assets/<namespace>/lang/<locale>.json}, applies <b>Delta Translation</b> when
 * {@code skipAlreadyTranslatedMods} is active and the jar already ships {@code pt_br.json}, and writes
 * the merged result to {@code resourcepacks/AutoTranslated_Pack_PTBR/assets/<namespace>/lang/pt_br.json}.
 */
public final class JarModExtractor {

    private static final Pattern LANG_ENTRY_PATTERN = Pattern.compile(
            "^assets/([a-z0-9_.-]+)/lang/([a-z0-9_]+)\\.json$", Pattern.CASE_INSENSITIVE);

    /** Matches nested Jar-in-Jar entries used by Fabric/Quilt (META-INF/jars/) and NeoForge/Forge (META-INF/jarjar/). */
    private static final Pattern NESTED_JAR_PATTERN = Pattern.compile(
            "^META-INF/(?:jars|jarjar)/.+\\.jar$", Pattern.CASE_INSENSITIVE);

    /** Aggregated language entries for a single mod namespace across all jars in {@code mods/}. */
    public static final class NamespaceLangAccumulator {
        public final String namespace;
        public final Map<String, String> sourceEntries = new LinkedHashMap<>();
        public final Map<String, String> existingPtBrEntries = new LinkedHashMap<>();

        public NamespaceLangAccumulator(String namespace) {
            this.namespace = namespace;
        }
    }

    private JarModExtractor() {
    }

    /**
     * Indexes all {@code .jar} files in {@code mods/} (including nested Jar-in-Jar archives from Fabric,
     * Quilt, Forge, and NeoForge) by namespace so both {@link JarModExtractor} and {@link KubeJsTranslator}
     * can share existing {@code en_us.json} and {@code pt_br.json} entries without translating the same
     * mod keys twice.
     */
    public static Map<String, NamespaceLangAccumulator> indexModJars(GamePaths paths,
                                                                     TranslatorConfigData config,
                                                                     TranslationCallback callback) throws IOException {
        Map<String, NamespaceLangAccumulator> byNamespace = new LinkedHashMap<>();
        Path modsDir = paths.modsDir();
        if (!Files.isDirectory(modsDir)) {
            return byNamespace;
        }

        List<Path> jars;
        try (Stream<Path> s = Files.list(modsDir)) {
            jars = s.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".jar"))
                    .sorted(Comparator.comparing(Path::toString))
                    .toList();
        }

        List<String> filterTokens = config.modFilterTokens();
        List<String> priorityLocales = config.effectiveSourceLanguages();
        String targetLang = config.getTargetLanguage();

        for (Path jarPath : jars) {
            String jarFileName = jarPath.getFileName().toString().toLowerCase(Locale.ROOT);
            if (jarFileName.startsWith("ftbtranslator")) {
                continue;
            }
            try (ZipFile zip = new ZipFile(jarPath.toFile())) {
                List<String> nestedJarEntries = new ArrayList<>();
                Map<String, Map<String, String>> namespaceToLocalePaths = indexLangEntries(zip, nestedJarEntries);
                for (Map.Entry<String, Map<String, String>> nsEntry : namespaceToLocalePaths.entrySet()) {
                    String namespace = nsEntry.getKey();
                    if (!matchesFilter(filterTokens, jarFileName, namespace)) {
                        continue;
                    }
                    Map<String, String> localeToZipPath = nsEntry.getValue();
                    List<String> orderedSourcePaths = orderSourceLocaleEntries(
                            localeToZipPath, priorityLocales, targetLang);
                    if (orderedSourcePaths.isEmpty()) {
                        continue;
                    }
                    Map<String, String> sourceMap = readMergedZipJson(zip, orderedSourcePaths);
                    if (sourceMap.isEmpty()) {
                        continue;
                    }
                    Map<String, String> existingTargetMap = Map.of();
                    String targetEntryPath = localeToZipPath.get(targetLang);
                    if (targetEntryPath != null) {
                        existingTargetMap = readZipJson(zip, targetEntryPath);
                    }

                    NamespaceLangAccumulator acc = byNamespace.computeIfAbsent(
                            namespace, NamespaceLangAccumulator::new);
                    mergeMissingOrBlankEntries(acc.sourceEntries, sourceMap);
                    acc.existingPtBrEntries.putAll(existingTargetMap);
                }

                // Universal Multi-Loader support: inspect nested Jar-in-Jar (Fabric/Quilt META-INF/jars & NeoForge/Forge META-INF/jarjar)
                for (String nestedEntryName : nestedJarEntries) {
                    indexNestedJar(zip, nestedEntryName, jarFileName, filterTokens, priorityLocales, targetLang, byNamespace);
                }
            } catch (IOException | RuntimeException ex) {
                if (callback != null) {
                    callback.onLog(LogLevel.WARN, "Ignorando .jar inválido (" + jarPath.getFileName()
                            + "): " + ex.getMessage());
                }
            }
        }

        // Pre-seed existing target language entries from already-generated resource pack or KubeJS on disk
        if (config.isSkipAlreadyTranslatedMods()) {
            for (NamespaceLangAccumulator acc : byNamespace.values()) {
                Path generatedLang = paths.generatedPackDir()
                        .resolve("assets").resolve(acc.namespace).resolve("lang").resolve(targetLang + ".json");
                if (Files.isRegularFile(generatedLang)) {
                    try {
                        Map<String, String> onDisk = JsonLangIO.readLangFile(generatedLang);
                        for (Map.Entry<String, String> e : onDisk.entrySet()) {
                            if (e.getValue() != null && !e.getValue().isBlank()) {
                                acc.existingPtBrEntries.putIfAbsent(e.getKey(), e.getValue());
                            }
                        }
                    } catch (IOException ignored) {
                    }
                }
            }
        }

        return byNamespace;
    }

    public static List<PendingOutputTask> scan(GamePaths paths, TranslatorConfigData config,
                                               TranslationCallback callback) throws IOException {
        Map<String, NamespaceLangAccumulator> byNamespace = indexModJars(paths, config, callback);
        return buildTasksFromIndex(paths, config, byNamespace, callback);
    }

    public static List<PendingOutputTask> buildTasksFromIndex(GamePaths paths,
                                                              TranslatorConfigData config,
                                                              Map<String, NamespaceLangAccumulator> byNamespace,
                                                              TranslationCallback callback) {
        List<PendingOutputTask> tasks = new ArrayList<>();
        String targetLang = config.getTargetLanguage();
        boolean deltaMode = config.isSkipAlreadyTranslatedMods();

        for (NamespaceLangAccumulator acc : byNamespace.values()) {
            Set<String> keysToTranslate = new LinkedHashSet<>();
            for (Map.Entry<String, String> e : acc.sourceEntries.entrySet()) {
                String key = e.getKey();
                String val = e.getValue();
                if (val == null || val.isBlank()) {
                    continue;
                }
                if (deltaMode) {
                    String existing = acc.existingPtBrEntries.get(key);
                    if (existing != null && !existing.isBlank()) {
                        continue;
                    }
                }
                keysToTranslate.add(key);
            }
            if (keysToTranslate.isEmpty()) {
                continue;
            }
            Path outFile = paths.generatedPackDir()
                    .resolve("assets")
                    .resolve(acc.namespace)
                    .resolve("lang")
                    .resolve(targetLang + ".json");
            tasks.add(new ModNamespaceLangTask(
                    paths.relativize(outFile),
                    outFile,
                    acc.sourceEntries,
                    acc.existingPtBrEntries,
                    keysToTranslate,
                    deltaMode));
        }

        if (!tasks.isEmpty() && callback != null) {
            callback.onLog(LogLevel.INFO, "Mods (.jar): " + tasks.size()
                    + " namespace(s) com chaves pendentes de tradução.");
        }
        return tasks;
    }

    public static boolean matchesFilter(List<String> filterTokens, String jarFileNameLower, String namespaceLower) {
        if (filterTokens == null || filterTokens.isEmpty()) {
            return true;
        }
        for (String token : filterTokens) {
            if (namespaceLower.contains(token) || jarFileNameLower.contains(token)) {
                return true;
            }
        }
        return false;
    }

    private static Map<String, Map<String, String>> indexLangEntries(ZipFile zip, List<String> nestedJarEntries) {
        Map<String, Map<String, String>> result = new LinkedHashMap<>();
        Enumeration<? extends ZipEntry> entries = zip.entries();
        while (entries.hasMoreElements()) {
            ZipEntry entry = entries.nextElement();
            if (entry.isDirectory()) {
                continue;
            }
            String entryName = entry.getName();
            if (nestedJarEntries != null && NESTED_JAR_PATTERN.matcher(entryName).matches()) {
                nestedJarEntries.add(entryName);
                continue;
            }
            Matcher m = LANG_ENTRY_PATTERN.matcher(entryName);
            if (!m.matches()) {
                continue;
            }
            String namespace = m.group(1).toLowerCase(Locale.ROOT);
            String locale = m.group(2).toLowerCase(Locale.ROOT);
            result.computeIfAbsent(namespace, k -> new LinkedHashMap<>()).put(locale, entryName);
        }
        return result;
    }

    private static void indexNestedJar(ZipFile outerZip,
                                       String nestedEntryName,
                                       String outerJarNameLower,
                                       List<String> filterTokens,
                                       List<String> priorityLocales,
                                       String targetLang,
                                       Map<String, NamespaceLangAccumulator> byNamespace) {
        ZipEntry nestedEntry = outerZip.getEntry(nestedEntryName);
        if (nestedEntry == null || nestedEntry.getSize() > 64L * 1024L * 1024L) {
            return;
        }
        Map<String, Map<String, Map<String, String>>> nsToLocaleMaps = new LinkedHashMap<>();
        try (InputStream rawIn = outerZip.getInputStream(nestedEntry);
             ZipInputStream zis = new ZipInputStream(rawIn)) {
            ZipEntry inner;
            while ((inner = zis.getNextEntry()) != null) {
                if (inner.isDirectory()) {
                    continue;
                }
                Matcher m = LANG_ENTRY_PATTERN.matcher(inner.getName());
                if (!m.matches()) {
                    continue;
                }
                String namespace = m.group(1).toLowerCase(Locale.ROOT);
                if (!matchesFilter(filterTokens, outerJarNameLower, namespace)) {
                    continue;
                }
                String locale = m.group(2).toLowerCase(Locale.ROOT);
                byte[] bytes = zis.readAllBytes();
                Map<String, String> parsed = JsonLangIO.readLangStream(new ByteArrayInputStream(bytes));
                if (!parsed.isEmpty()) {
                    nsToLocaleMaps
                            .computeIfAbsent(namespace, k -> new LinkedHashMap<>())
                            .put(locale, parsed);
                }
            }
        } catch (IOException | RuntimeException ignored) {
            return;
        }

        for (Map.Entry<String, Map<String, Map<String, String>>> nsEntry : nsToLocaleMaps.entrySet()) {
            String namespace = nsEntry.getKey();
            Map<String, Map<String, String>> localeMaps = nsEntry.getValue();
            Map<String, String> mergedSourceMap = new LinkedHashMap<>();
            for (String locale : priorityLocales) {
                if (locale.equals(targetLang)) {
                    continue;
                }
                Map<String, String> candidate = localeMaps.get(locale);
                if (candidate != null) {
                    mergeMissingOrBlankEntries(mergedSourceMap, candidate);
                }
            }
            for (Map.Entry<String, Map<String, String>> e : localeMaps.entrySet()) {
                if (!e.getKey().equals(targetLang) && !priorityLocales.contains(e.getKey())) {
                    mergeMissingOrBlankEntries(mergedSourceMap, e.getValue());
                }
            }
            if (mergedSourceMap.isEmpty()) {
                continue;
            }
            NamespaceLangAccumulator acc = byNamespace.computeIfAbsent(
                    namespace, NamespaceLangAccumulator::new);
            mergeMissingOrBlankEntries(acc.sourceEntries, mergedSourceMap);
            Map<String, String> targetMap = localeMaps.get(targetLang);
            if (targetMap != null) {
                for (Map.Entry<String, String> e : targetMap.entrySet()) {
                    acc.existingPtBrEntries.putIfAbsent(e.getKey(), e.getValue());
                }
            }
        }
    }

    /**
     * Copies entries from {@code incoming} into {@code target} whenever the key is absent or blank in
     * {@code target}, preserving higher-priority non-blank values.
     */
    public static void mergeMissingOrBlankEntries(Map<String, String> target, Map<String, String> incoming) {
        if (incoming == null || incoming.isEmpty()) {
            return;
        }
        for (Map.Entry<String, String> e : incoming.entrySet()) {
            String key = e.getKey();
            String val = e.getValue();
            if (val == null || val.isBlank()) {
                continue;
            }
            String current = target.get(key);
            if (current == null || current.isBlank()) {
                target.put(key, val);
            }
        }
    }

    private static List<String> orderSourceLocaleEntries(Map<String, String> localeToEntry,
                                                         List<String> priorityLocales,
                                                         String targetLang) {
        List<String> ordered = new ArrayList<>();
        Set<String> seenLocales = new LinkedHashSet<>();
        for (String locale : priorityLocales) {
            if (locale.equals(targetLang)) {
                continue;
            }
            String found = localeToEntry.get(locale);
            if (found != null && seenLocales.add(locale)) {
                ordered.add(found);
            }
        }
        for (Map.Entry<String, String> e : localeToEntry.entrySet()) {
            if (!e.getKey().equals(targetLang) && seenLocales.add(e.getKey())) {
                ordered.add(e.getValue());
            }
        }
        return ordered;
    }

    private static Map<String, String> readMergedZipJson(ZipFile zip, List<String> orderedEntryNames) {
        Map<String, String> merged = new LinkedHashMap<>();
        for (String entryName : orderedEntryNames) {
            Map<String, String> map = readZipJson(zip, entryName);
            mergeMissingOrBlankEntries(merged, map);
        }
        return merged;
    }

    private static Map<String, String> readZipJson(ZipFile zip, String entryName) {
        ZipEntry entry = zip.getEntry(entryName);
        if (entry == null) {
            return Map.of();
        }
        try (InputStream in = zip.getInputStream(entry)) {
            return JsonLangIO.readLangStream(in);
        } catch (IOException | RuntimeException e) {
            return Map.of();
        }
    }

    private record ModNamespaceLangTask(
            String displayPath,
            Path outputPath,
            Map<String, String> sourceEntries,
            Map<String, String> existingPtBrEntries,
            Set<String> keysToTranslate,
            boolean deltaMode) implements PendingOutputTask {

        @Override
        public TranslationScope scope() {
            return TranslationScope.MODS;
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
            Map<String, String> merged = new LinkedHashMap<>(sourceEntries.size());
            for (Map.Entry<String, String> entry : sourceEntries.entrySet()) {
                String key = entry.getKey();
                String src = entry.getValue();
                if (deltaMode) {
                    String existing = existingPtBrEntries.get(key);
                    if (existing != null && !existing.isBlank()) {
                        merged.put(key, existing);
                        continue;
                    }
                }
                if (src == null || src.isBlank()) {
                    merged.put(key, src);
                } else {
                    String translated = translator.apply(src);
                    merged.put(key, translated == null ? src : translated);
                }
            }
            for (Map.Entry<String, String> existing : existingPtBrEntries.entrySet()) {
                merged.putIfAbsent(existing.getKey(), existing.getValue());
            }
            JsonLangIO.writeLangFile(outputPath, merged);
        }
    }
}
