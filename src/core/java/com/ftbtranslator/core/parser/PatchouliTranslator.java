package com.ftbtranslator.core.parser;

import com.ftbtranslator.core.GamePaths;
import com.ftbtranslator.core.LogLevel;
import com.ftbtranslator.core.TranslationCallback;
import com.ftbtranslator.core.TranslationScope;
import com.ftbtranslator.core.TranslatorConfigData;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
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

/**
 * Handles Front 5: Patchouli guide books from all 3 sources:
 * <ol>
 *   <li>Inside {@code mods/*.jar} ({@code assets/<namespace>/patchouli_books/<book>/...})</li>
 *   <li>Inside {@code kubejs/assets/<namespace>/patchouli_books/<book>/...}</li>
 *   <li>Inside the instance root {@code patchouli_books/<book>/...}</li>
 * </ol>
 * <p>
 * <b>Strict JSON key whitelist:</b>
 * <ul>
 *   <li>Top-level of {@code book.json}, {@code categories/*.json} and {@code entries/*.json}: ONLY
 *       {@code "name"}, {@code "subtitle"}, {@code "title"}, {@code "description"}, {@code "landing_text"}.</li>
 *   <li>Inside each element of the {@code "pages"} array: ONLY {@code "title"}, {@code "text"},
 *       {@code "header"}, {@code "name"}.</li>
 *   <li>Technical keys ({@code "type"}, {@code "icon"}, {@code "category"}, {@code "advancement"},
 *       {@code "flag"}, {@code "recipe"}, {@code "item"}, {@code "sortnum"}...) are NEVER touched.</li>
 * </ul>
 */
public final class PatchouliTranslator {

    /** Strict whitelist of translatable top-level JSON fields in Patchouli files. */
    public static final Set<String> TOP_LEVEL_TEXT_KEYS = Set.of(
            "name", "subtitle", "title", "description", "landing_text");

    /** Strict whitelist of translatable fields inside elements of the {@code "pages"} array. */
    public static final Set<String> PAGE_TEXT_KEYS = Set.of(
            "title", "text", "header", "name");

    private static final Pattern JAR_PATCHOULI_ENTRY = Pattern.compile(
            "^assets/([a-z0-9_.-]+)/patchouli_books/([a-z0-9_.-]+)/([a-z0-9_]+)/(.+\\.json)$",
            Pattern.CASE_INSENSITIVE);

    private PatchouliTranslator() {
    }

    public static List<PendingOutputTask> scan(GamePaths paths, TranslatorConfigData config,
                                               TranslationCallback callback) throws IOException {
        List<PendingOutputTask> tasks = new ArrayList<>();
        String targetLang = config.getTargetLanguage();
        List<String> priorityLocales = config.effectiveSourceLanguages();
        List<String> filterTokens = config.modFilterTokens();

        // 1. Inside mods/*.jar -> output into AutoTranslated_Pack_PTBR/assets/<ns>/patchouli_books/<book>/pt_br/...
        scanJarBooks(paths, priorityLocales, targetLang, filterTokens, callback, tasks);

        // 2. Inside kubejs/assets/<ns>/patchouli_books/<book>/<locale>/... -> AutoTranslated_Pack_PTBR/assets/<ns>/patchouli_books/<book>/pt_br/...
        Path kubeAssets = paths.kubejsDir().resolve("assets");
        if (Files.isDirectory(kubeAssets)) {
            scanAssetsTree(paths, kubeAssets, priorityLocales, targetLang, filterTokens, tasks);
        }

        // 3. Root patchouli_books/<book>/<locale>/... -> patchouli_books/<book>/pt_br/...
        Path rootBooks = paths.patchouliBooksDir();
        if (Files.isDirectory(rootBooks)) {
            scanRootBooksFolder(paths, rootBooks, priorityLocales, targetLang, tasks);
        }

        if (!tasks.isEmpty() && callback != null) {
            callback.onLog(LogLevel.INFO, "Patchouli: " + tasks.size() + " arquivo(s) de livro preparado(s).");
        }
        return tasks;
    }

    private static void scanJarBooks(GamePaths paths, List<String> priorityLocales, String targetLang,
                                     List<String> filterTokens, TranslationCallback callback,
                                     List<PendingOutputTask> tasks) throws IOException {
        Path modsDir = paths.modsDir();
        if (!Files.isDirectory(modsDir)) {
            return;
        }
        List<Path> jars;
        try (Stream<Path> s = Files.list(modsDir)) {
            jars = s.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".jar"))
                    .sorted(Comparator.comparing(Path::toString))
                    .toList();
        }

        for (Path jarPath : jars) {
            String jarNameLower = jarPath.getFileName().toString().toLowerCase(Locale.ROOT);
            if (jarNameLower.startsWith("ftbtranslator")) {
                continue;
            }
            try (ZipFile zip = new ZipFile(jarPath.toFile())) {
                // Discover available locales per (namespace, book)
                Enumeration<? extends ZipEntry> entries = zip.entries();
                List<JarBookEntry> allBookEntries = new ArrayList<>();
                while (entries.hasMoreElements()) {
                    ZipEntry ze = entries.nextElement();
                    if (ze.isDirectory()) {
                        continue;
                    }
                    Matcher m = JAR_PATCHOULI_ENTRY.matcher(ze.getName());
                    if (!m.matches()) {
                        continue;
                    }
                    String ns = m.group(1).toLowerCase(Locale.ROOT);
                    String book = m.group(2);
                    String locale = m.group(3).toLowerCase(Locale.ROOT);
                    String relInsideLocale = m.group(4);
                    if (!JarModExtractor.matchesFilter(filterTokens, jarNameLower, ns)) {
                        continue;
                    }
                    allBookEntries.add(new JarBookEntry(ns, book, locale, relInsideLocale, ze.getName()));
                }

                // Group by ns+book and merge across source locales in priority order
                allBookEntries.stream()
                        .map(e -> e.namespace + "/" + e.book)
                        .distinct()
                        .forEach(bookKey -> {
                            List<JarBookEntry> forBook = allBookEntries.stream()
                                    .filter(e -> (e.namespace + "/" + e.book).equals(bookKey))
                                    .toList();
                            List<String> orderedLocales = orderLocales(
                                    forBook.stream().map(e -> e.locale).distinct().toList(),
                                    priorityLocales,
                                    targetLang);
                            if (orderedLocales.isEmpty()) {
                                return;
                            }
                            Map<String, List<String>> relPathToRawJsons = new LinkedHashMap<>();
                            String ns = forBook.get(0).namespace;
                            String book = forBook.get(0).book;
                            for (String loc : orderedLocales) {
                                for (JarBookEntry entry : forBook) {
                                    if (!entry.locale.equals(loc)) {
                                        continue;
                                    }
                                    ZipEntry ze = zip.getEntry(entry.zipEntryName);
                                    if (ze == null) {
                                        continue;
                                    }
                                    try (InputStream in = zip.getInputStream(ze)) {
                                        String rawJson = new String(in.readAllBytes(), StandardCharsets.UTF_8);
                                        relPathToRawJsons.computeIfAbsent(entry.relPathInsideLocale, k -> new ArrayList<>())
                                                .add(rawJson);
                                    } catch (IOException ignored) {
                                    }
                                }
                            }
                            for (Map.Entry<String, List<String>> fileEntry : relPathToRawJsons.entrySet()) {
                                String mergedRawJson = mergePatchouliJsonDocuments(fileEntry.getValue());
                                Path outFile = paths.generatedPackDir()
                                        .resolve("assets")
                                        .resolve(ns)
                                        .resolve("patchouli_books")
                                        .resolve(book)
                                        .resolve(targetLang)
                                        .resolve(fileEntry.getKey());
                                maybeAddPatchouliTask(paths, mergedRawJson, outFile, tasks);
                            }
                        });
            } catch (IOException | RuntimeException ex) {
                if (callback != null) {
                    callback.onLog(LogLevel.WARN, "Erro lendo livros Patchouli de "
                            + jarPath.getFileName() + ": " + ex.getMessage());
                }
            }
        }
    }

    private static void scanAssetsTree(GamePaths paths, Path assetsRoot, List<String> priorityLocales,
                                       String targetLang, List<String> filterTokens,
                                       List<PendingOutputTask> tasks) throws IOException {
        List<Path> namespaces;
        try (Stream<Path> s = Files.list(assetsRoot)) {
            namespaces = s.filter(Files::isDirectory).sorted(Comparator.comparing(Path::toString)).toList();
        }
        for (Path nsDir : namespaces) {
            String ns = nsDir.getFileName().toString().toLowerCase(Locale.ROOT);
            if (!JarModExtractor.matchesFilter(filterTokens, ns, ns)) {
                continue;
            }
            Path booksRoot = nsDir.resolve("patchouli_books");
            if (!Files.isDirectory(booksRoot)) {
                continue;
            }
            List<Path> bookDirs;
            try (Stream<Path> s = Files.list(booksRoot)) {
                bookDirs = s.filter(Files::isDirectory).sorted(Comparator.comparing(Path::toString)).toList();
            }
            for (Path bookDir : bookDirs) {
                String bookName = bookDir.getFileName().toString();
                Path outBookBase = paths.generatedPackDir()
                        .resolve("assets")
                        .resolve(ns)
                        .resolve("patchouli_books")
                        .resolve(bookName);
                scanSingleBookDirectory(paths, bookDir, outBookBase, priorityLocales, targetLang, tasks);
            }
        }
    }

    private static void scanRootBooksFolder(GamePaths paths, Path rootBooks, List<String> priorityLocales,
                                            String targetLang, List<PendingOutputTask> tasks) throws IOException {
        List<Path> bookDirs;
        try (Stream<Path> s = Files.list(rootBooks)) {
            bookDirs = s.filter(Files::isDirectory).sorted(Comparator.comparing(Path::toString)).toList();
        }
        for (Path bookDir : bookDirs) {
            scanSingleBookDirectory(paths, bookDir, bookDir, priorityLocales, targetLang, tasks);
        }
    }

    private static void scanSingleBookDirectory(GamePaths paths, Path sourceBookDir, Path targetBookDir,
                                                List<String> priorityLocales, String targetLang,
                                                List<PendingOutputTask> tasks) throws IOException {
        // Optional book.json at the root of the book directory (when use_resource_pack is false)
        Path rootBookJson = sourceBookDir.resolve("book.json");
        if (Files.isRegularFile(rootBookJson)) {
            Path backupBookJson = paths.patchouliBackupDir()
                    .resolve(sourceBookDir.getFileName().toString())
                    .resolve("book.json");
            Path toRead = Files.isRegularFile(backupBookJson) ? backupBookJson : rootBookJson;
            String raw = Files.readString(toRead, StandardCharsets.UTF_8);
            // Output into targetBookDir/pt_br/book.json so the original book.json stays safe
            Path outBookJson = targetBookDir.resolve(targetLang).resolve("book.json");
            maybeAddPatchouliTask(paths, raw, outBookJson, tasks);
        }

        List<String> availableLocales;
        try (Stream<Path> s = Files.list(sourceBookDir)) {
            availableLocales = s.filter(Files::isDirectory)
                    .map(p -> p.getFileName().toString().toLowerCase(Locale.ROOT))
                    .toList();
        }
        List<String> orderedLocales = orderLocales(availableLocales, priorityLocales, targetLang);
        if (orderedLocales.isEmpty()) {
            return;
        }
        Map<Path, List<String>> relPathToRawJsons = new LinkedHashMap<>();
        for (String loc : orderedLocales) {
            Path localeDir = sourceBookDir.resolve(loc);
            if (!Files.isDirectory(localeDir)) {
                continue;
            }
            List<Path> jsonFiles;
            try (Stream<Path> walk = Files.walk(localeDir)) {
                jsonFiles = walk.filter(Files::isRegularFile)
                        .filter(p -> p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".json"))
                        .sorted(Comparator.comparing(Path::toString))
                        .toList();
            }
            for (Path jsonFile : jsonFiles) {
                String raw = Files.readString(jsonFile, StandardCharsets.UTF_8);
                Path rel = localeDir.relativize(jsonFile);
                relPathToRawJsons.computeIfAbsent(rel, k -> new ArrayList<>()).add(raw);
            }
        }
        for (Map.Entry<Path, List<String>> entry : relPathToRawJsons.entrySet()) {
            String mergedRawJson = mergePatchouliJsonDocuments(entry.getValue());
            Path outFile = targetBookDir.resolve(targetLang).resolve(entry.getKey());
            maybeAddPatchouliTask(paths, mergedRawJson, outFile, tasks);
        }
    }

    private static List<String> orderLocales(List<String> available, List<String> priority, String targetLang) {
        List<String> ordered = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (String p : priority) {
            if (!p.equalsIgnoreCase(targetLang) && available.contains(p) && seen.add(p)) {
                ordered.add(p);
            }
        }
        for (String a : available) {
            if (!a.equalsIgnoreCase(targetLang) && seen.add(a)) {
                ordered.add(a);
            }
        }
        return ordered;
    }

    /**
     * Merges multiple Patchouli JSON documents for the same entry/category across priority locales:
     * keeps the primary locale's structure while filling any missing or blank textual fields from
     * fallback locales.
     */
    public static String mergePatchouliJsonDocuments(List<String> orderedRawJsons) {
        if (orderedRawJsons == null || orderedRawJsons.isEmpty()) {
            return "";
        }
        if (orderedRawJsons.size() == 1) {
            return orderedRawJsons.get(0);
        }
        try {
            JsonObject merged = JsonParser.parseString(orderedRawJsons.get(0)).getAsJsonObject().deepCopy();
            boolean modified = false;
            for (int idx = 1; idx < orderedRawJsons.size(); idx++) {
                JsonElement el = JsonParser.parseString(orderedRawJsons.get(idx));
                if (!el.isJsonObject()) {
                    continue;
                }
                JsonObject fallback = el.getAsJsonObject();
                for (String key : TOP_LEVEL_TEXT_KEYS) {
                    if (isJsonFieldMissingOrBlank(merged, key) && !isJsonFieldMissingOrBlank(fallback, key)) {
                        merged.add(key, fallback.get(key).deepCopy());
                        modified = true;
                    }
                }
                if (merged.has("pages") && merged.get("pages").isJsonArray()
                        && fallback.has("pages") && fallback.get("pages").isJsonArray()) {
                    JsonArray mPages = merged.getAsJsonArray("pages");
                    JsonArray fPages = fallback.getAsJsonArray("pages");
                    int minLen = Math.min(mPages.size(), fPages.size());
                    for (int p = 0; p < minLen; p++) {
                        if (mPages.get(p).isJsonObject() && fPages.get(p).isJsonObject()) {
                            JsonObject mPage = mPages.get(p).getAsJsonObject();
                            JsonObject fPage = fPages.get(p).getAsJsonObject();
                            for (String pk : PAGE_TEXT_KEYS) {
                                if (isJsonFieldMissingOrBlank(mPage, pk) && !isJsonFieldMissingOrBlank(fPage, pk)) {
                                    mPage.add(pk, fPage.get(pk).deepCopy());
                                    modified = true;
                                }
                            }
                        }
                    }
                    for (int p = mPages.size(); p < fPages.size(); p++) {
                        mPages.add(fPages.get(p).deepCopy());
                        modified = true;
                    }
                }
            }
            return modified ? merged.toString() : orderedRawJsons.get(0);
        } catch (RuntimeException ex) {
            return orderedRawJsons.get(0);
        }
    }

    private static boolean isJsonFieldMissingOrBlank(JsonObject obj, String key) {
        if (!obj.has(key) || obj.get(key).isJsonNull()) {
            return true;
        }
        JsonElement el = obj.get(key);
        if (el.isJsonPrimitive() && el.getAsJsonPrimitive().isString()) {
            return el.getAsString().isBlank();
        }
        return false;
    }

    private static void maybeAddPatchouliTask(GamePaths paths, String rawJson, Path outFile,
                                              List<PendingOutputTask> tasks) {
        try {
            JsonElement parsed = JsonParser.parseString(rawJson);
            if (!parsed.isJsonObject()) {
                return;
            }
            List<String> collected = new ArrayList<>();
            collectPatchouliStrings(parsed.getAsJsonObject(), collected::add);
            if (collected.isEmpty()) {
                return;
            }
            tasks.add(new PatchouliJsonTask(paths.relativize(outFile), outFile, rawJson, collected));
        } catch (RuntimeException ignored) {
            // Skip malformed JSON
        }
    }

    /**
     * Collects strings strictly from the whitelisted Patchouli textual fields.
     */
    public static void collectPatchouliStrings(JsonObject root, Consumer<String> collector) {
        for (String key : TOP_LEVEL_TEXT_KEYS) {
            if (root.has(key)) {
                collectTextElement(root.get(key), collector);
            }
        }
        if (root.has("pages") && root.get("pages").isJsonArray()) {
            JsonArray pages = root.getAsJsonArray("pages");
            for (JsonElement pageEl : pages) {
                if (pageEl.isJsonPrimitive() && pageEl.getAsJsonPrimitive().isString()) {
                    String s = pageEl.getAsString();
                    if (isTranslatablePatchouliString(s)) {
                        collector.accept(s);
                    }
                } else if (pageEl.isJsonObject()) {
                    JsonObject pageObj = pageEl.getAsJsonObject();
                    for (String pageKey : PAGE_TEXT_KEYS) {
                        if (pageObj.has(pageKey)) {
                            collectTextElement(pageObj.get(pageKey), collector);
                        }
                    }
                }
            }
        }
    }

    private static void collectTextElement(JsonElement el, Consumer<String> collector) {
        if (el == null || el.isJsonNull()) {
            return;
        }
        if (el.isJsonPrimitive() && el.getAsJsonPrimitive().isString()) {
            String s = el.getAsString();
            if (isTranslatablePatchouliString(s)) {
                collector.accept(s);
            }
        } else if (el.isJsonArray()) {
            for (JsonElement child : el.getAsJsonArray()) {
                if (child != null && child.isJsonPrimitive() && child.getAsJsonPrimitive().isString()) {
                    String s = child.getAsString();
                    if (isTranslatablePatchouliString(s)) {
                        collector.accept(s);
                    }
                }
            }
        }
    }

    /**
     * Rewrites a Patchouli JSON object in place, touching ONLY the whitelisted textual keys.
     */
    public static JsonObject translatePatchouliObject(JsonObject sourceRoot, Function<String, String> translator) {
        JsonObject root = sourceRoot.deepCopy();
        for (String key : TOP_LEVEL_TEXT_KEYS) {
            if (root.has(key)) {
                root.add(key, translateTextElement(root.get(key), translator));
            }
        }
        if (root.has("pages") && root.get("pages").isJsonArray()) {
            JsonArray pages = root.getAsJsonArray("pages");
            for (int i = 0; i < pages.size(); i++) {
                JsonElement pageEl = pages.get(i);
                if (pageEl.isJsonPrimitive() && pageEl.getAsJsonPrimitive().isString()) {
                    pages.set(i, translateTextElement(pageEl, translator));
                } else if (pageEl.isJsonObject()) {
                    JsonObject pageObj = pageEl.getAsJsonObject();
                    for (String pageKey : PAGE_TEXT_KEYS) {
                        if (pageObj.has(pageKey)) {
                            pageObj.add(pageKey, translateTextElement(pageObj.get(pageKey), translator));
                        }
                    }
                }
            }
        }
        return root;
    }

    private static JsonElement translateTextElement(JsonElement el, Function<String, String> translator) {
        if (el == null || el.isJsonNull()) {
            return el;
        }
        if (el.isJsonPrimitive() && el.getAsJsonPrimitive().isString()) {
            String src = el.getAsString();
            if (!isTranslatablePatchouliString(src)) {
                return el;
            }
            String dst = translator.apply(src);
            return new JsonPrimitive(dst == null ? src : dst);
        }
        if (el.isJsonArray()) {
            JsonArray out = new JsonArray();
            for (JsonElement child : el.getAsJsonArray()) {
                out.add(translateTextElement(child, translator));
            }
            return out;
        }
        return el;
    }

    private static boolean isTranslatablePatchouliString(String s) {
        if (s == null || s.isBlank()) {
            return false;
        }
        // Ignore pure resource location / lang key references with no spaces like "patchouli:something"
        String trimmed = s.trim();
        if (trimmed.indexOf(' ') < 0 && trimmed.matches("^[a-z0-9_.-]+:[a-z0-9_/.-]+$")) {
            return false;
        }
        return true;
    }

    private record JarBookEntry(String namespace, String book, String locale,
                                String relPathInsideLocale, String zipEntryName) {
    }

    private record PatchouliJsonTask(
            String displayPath,
            Path outputPath,
            String rawJson,
            List<String> strings) implements PendingOutputTask {

        @Override
        public TranslationScope scope() {
            return TranslationScope.PATCHOULI;
        }

        @Override
        public void collectStrings(Consumer<String> collector) {
            for (String s : strings) {
                collector.accept(s);
            }
        }

        @Override
        public void writeOutput(Function<String, String> translator) throws IOException {
            JsonObject root = JsonParser.parseString(rawJson).getAsJsonObject();
            JsonObject translated = translatePatchouliObject(root, translator);
            JsonLangIO.writeJsonObjectAtomic(outputPath, translated);
        }
    }
}
