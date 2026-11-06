package com.ftbtranslator.core.parser;

import com.ftbtranslator.core.GamePaths;
import com.ftbtranslator.core.LogLevel;
import com.ftbtranslator.core.TranslationCallback;
import com.ftbtranslator.core.TranslatorConfigData;
import com.ftbtranslator.core.cache.TranslationGlossary;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Feeds the {@link TranslationGlossary} with translations that <b>already exist</b> in the instance but
 * whose source text is not stored next to them, aligning both sides by translation key:
 * <ul>
 *   <li>Third-party translation resource packs in {@code resourcepacks/} (folders or {@code .zip}, never
 *       our own generated pack): {@code assets/<ns>/lang/<target>.json} paired with the source entries
 *       read from {@code mods/*.jar}.</li>
 *   <li>An already installed FTB Quests translation ({@code quests/lang/<target>.snbt} and
 *       {@code quests/lang/<target>/*.snbt}) paired with the source locale files. It is harvested
 *       <i>before</i> the run rewrites it, so a community translation is reused instead of discarded.</li>
 * </ul>
 * Pairs whose placeholders differ are still rejected later, at lookup time, by the service.
 */
public final class ExistingTranslationHarvester {

    private static final Pattern PACK_LANG_ENTRY = Pattern.compile(
            "^assets/([a-z0-9_.-]+)/lang/([a-z0-9_]+)\\.json$", Pattern.CASE_INSENSITIVE);

    private ExistingTranslationHarvester() {
    }

    /** Result counters, used for logging. */
    public record HarvestStats(int resourcePacks, int resourcePackPairs, int questPairs) {
    }

    public static HarvestStats harvest(GamePaths paths,
                                       TranslatorConfigData config,
                                       Map<String, JarModExtractor.NamespaceLangAccumulator> jarIndex,
                                       TranslationGlossary glossary,
                                       TranslationCallback callback) {
        String targetLang = config.getTargetLanguage();
        int packs = 0;
        int packPairs = 0;
        try {
            Map<String, Map<String, String>> packLangs = new LinkedHashMap<>();
            packs = readResourcePackLangs(paths, targetLang, packLangs, callback);
            for (Map.Entry<String, Map<String, String>> ns : packLangs.entrySet()) {
                JarModExtractor.NamespaceLangAccumulator acc = jarIndex.get(ns.getKey());
                if (acc != null) {
                    packPairs += glossary.addLangPair(acc.sourceEntries, ns.getValue());
                }
            }
        } catch (IOException | RuntimeException e) {
            log(callback, LogLevel.WARN, "Não foi possível ler os pacotes de tradução em resourcepacks/: "
                    + e.getMessage());
        }

        int questPairs = 0;
        try {
            questPairs = harvestQuestLang(paths, config, glossary);
        } catch (IOException | RuntimeException e) {
            log(callback, LogLevel.WARN, "Não foi possível ler a tradução existente das missões: " + e.getMessage());
        }
        return new HarvestStats(packs, packPairs, questPairs);
    }

    /**
     * Reads {@code assets/<ns>/lang/<targetLang>.json} from every resource pack (folder or zip) except the
     * generated one. Packs are visited in name order; the first pack providing a key wins.
     *
     * @return number of packs that contributed at least one lang file
     */
    static int readResourcePackLangs(GamePaths paths, String targetLang,
                                     Map<String, Map<String, String>> out,
                                     TranslationCallback callback) throws IOException {
        Path dir = paths.resourcePacksDir();
        if (!Files.isDirectory(dir)) {
            return 0;
        }
        Path generated = paths.generatedPackDir().toAbsolutePath().normalize();
        List<Path> packs;
        try (Stream<Path> s = Files.list(dir)) {
            packs = s.filter(p -> !p.toAbsolutePath().normalize().equals(generated))
                    .filter(p -> Files.isDirectory(p)
                            || p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".zip"))
                    .sorted(Comparator.comparing(p -> p.getFileName().toString()))
                    .toList();
        }
        int contributing = 0;
        for (Path pack : packs) {
            int before = countEntries(out);
            try {
                if (Files.isDirectory(pack)) {
                    readPackFolder(pack, targetLang, out);
                } else {
                    readPackZip(pack, targetLang, out);
                }
            } catch (IOException | RuntimeException e) {
                log(callback, LogLevel.WARN, "Ignorando pacote inválido (" + pack.getFileName() + "): "
                        + e.getMessage());
                continue;
            }
            if (countEntries(out) > before) {
                contributing++;
                log(callback, LogLevel.INFO, "Reaproveitando traduções do pacote: " + pack.getFileName());
            }
        }
        return contributing;
    }

    private static void readPackZip(Path zipPath, String targetLang, Map<String, Map<String, String>> out)
            throws IOException {
        try (ZipFile zip = new ZipFile(zipPath.toFile())) {
            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                if (entry.isDirectory()) {
                    continue;
                }
                Matcher m = PACK_LANG_ENTRY.matcher(entry.getName());
                if (!m.matches() || !m.group(2).equalsIgnoreCase(targetLang)) {
                    continue;
                }
                try (InputStream in = zip.getInputStream(entry)) {
                    mergeFirstWins(out, m.group(1).toLowerCase(Locale.ROOT), JsonLangIO.readLangStream(in));
                } catch (IOException | RuntimeException ignored) {
                    // One malformed lang file must not discard the rest of the pack.
                }
            }
        }
    }

    private static void readPackFolder(Path packDir, String targetLang, Map<String, Map<String, String>> out)
            throws IOException {
        Path assets = packDir.resolve("assets");
        if (!Files.isDirectory(assets)) {
            return;
        }
        List<Path> namespaces;
        try (Stream<Path> s = Files.list(assets)) {
            namespaces = s.filter(Files::isDirectory).sorted().toList();
        }
        for (Path nsDir : namespaces) {
            Path lang = nsDir.resolve("lang").resolve(targetLang + ".json");
            if (Files.isRegularFile(lang)) {
                try {
                    mergeFirstWins(out, nsDir.getFileName().toString().toLowerCase(Locale.ROOT),
                            JsonLangIO.readLangFile(lang));
                } catch (IOException | RuntimeException ignored) {
                }
            }
        }
    }

    /**
     * Pairs the installed quest translation with the source locale files, key by key (array elements are
     * aligned by index and skipped when the arrays differ in size).
     *
     * @return number of pairs accepted by the glossary
     */
    static int harvestQuestLang(GamePaths paths, TranslatorConfigData config, TranslationGlossary glossary)
            throws IOException {
        Path langDir = paths.questsDir().resolve("lang");
        if (!Files.isDirectory(langDir)) {
            return 0;
        }
        String targetLang = config.getTargetLanguage();
        Map<String, List<String>> target = new LinkedHashMap<>();
        Map<String, List<String>> source = new LinkedHashMap<>();

        readSnbtLang(langDir.resolve(targetLang + ".snbt"), target);
        readSnbtLangFolder(langDir.resolve(targetLang), target);
        if (target.isEmpty()) {
            return 0;
        }
        for (String locale : config.effectiveSourceLanguages()) {
            if (locale.equalsIgnoreCase(targetLang)) {
                continue;
            }
            readSnbtLang(langDir.resolve(locale + ".snbt"), source);
            readSnbtLangFolder(langDir.resolve(locale), source);
        }

        Map<String, String> flatSource = new LinkedHashMap<>();
        Map<String, String> flatTarget = new LinkedHashMap<>();
        for (Map.Entry<String, List<String>> t : target.entrySet()) {
            List<String> src = source.get(t.getKey());
            List<String> tgt = t.getValue();
            if (src == null || src.size() != tgt.size()) {
                continue;
            }
            for (int i = 0; i < src.size(); i++) {
                String k = t.getKey() + "#" + i;
                flatSource.put(k, src.get(i));
                flatTarget.put(k, tgt.get(i));
            }
        }
        return glossary.addLangPair(flatSource, flatTarget);
    }

    /** Higher-priority files are read first; keys already present are kept (first wins). */
    private static void readSnbtLang(Path file, Map<String, List<String>> out) throws IOException {
        if (!Files.isRegularFile(file)) {
            return;
        }
        Map<String, List<String>> parsed = SnbtParser.extractLangValues(
                Files.readString(file, StandardCharsets.UTF_8));
        for (Map.Entry<String, List<String>> e : parsed.entrySet()) {
            out.putIfAbsent(e.getKey(), e.getValue());
        }
    }

    private static void readSnbtLangFolder(Path dir, Map<String, List<String>> out) throws IOException {
        if (!Files.isDirectory(dir)) {
            return;
        }
        List<Path> files;
        try (Stream<Path> s = Files.walk(dir)) {
            files = s.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".snbt"))
                    .sorted()
                    .toList();
        }
        for (Path f : files) {
            readSnbtLang(f, out);
        }
    }

    private static void mergeFirstWins(Map<String, Map<String, String>> out, String namespace,
                                       Map<String, String> entries) {
        Map<String, String> ns = out.computeIfAbsent(namespace, k -> new LinkedHashMap<>());
        for (Map.Entry<String, String> e : entries.entrySet()) {
            if (e.getValue() != null && !e.getValue().isBlank()) {
                ns.putIfAbsent(e.getKey(), e.getValue());
            }
        }
    }

    private static int countEntries(Map<String, Map<String, String>> map) {
        int n = 0;
        for (Map<String, String> m : map.values()) {
            n += m.size();
        }
        return n;
    }

    private static void log(TranslationCallback callback, LogLevel level, String msg) {
        if (callback != null) {
            callback.onLog(level, msg);
        }
    }
}
