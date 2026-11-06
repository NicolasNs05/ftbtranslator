package com.ftbtranslator.core.parser;

import com.ftbtranslator.core.GamePaths;
import com.ftbtranslator.core.LogLevel;
import com.ftbtranslator.core.TranslationCallback;
import com.ftbtranslator.core.TranslationScope;
import com.ftbtranslator.core.TranslatorConfigData;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
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
 * Handles Front 1 ({@code config/ftbquests/quests/lang/*.snbt}) and Front 2
 * ({@code config/ftbquests/quests/chapters/*.snbt} + {@code reward_tables/*.snbt} hardcoded texts with
 * one-time original backup in {@code config/ftbquests/quests_backup_original/}).
 */
public final class FtbQuestsTranslator {

    private FtbQuestsTranslator() {
    }

    public static List<PendingOutputTask> scan(GamePaths paths, TranslatorConfigData config,
                                               TranslationCallback callback) throws IOException {
        List<PendingOutputTask> tasks = new ArrayList<>();
        Path questsDir = paths.questsDir();
        if (!Files.isDirectory(questsDir)) {
            return tasks;
        }

        // Front 1: config/ftbquests/quests/lang/<source>.snbt -> pt_br.snbt
        Path langDir = questsDir.resolve("lang");
        if (Files.isDirectory(langDir)) {
            scanLangFolder(paths, config, callback, langDir, tasks);
        }

        // Front 2: hardcoded chapters/*.snbt and reward_tables/*.snbt (plus top-level data.snbt if present)
        Path backupRoot = paths.questsBackupDir();
        for (String subFolder : List.of("chapters", "reward_tables")) {
            Path dir = questsDir.resolve(subFolder);
            if (!Files.isDirectory(dir)) {
                continue;
            }
            List<Path> snbtFiles;
            try (Stream<Path> stream = Files.walk(dir)) {
                snbtFiles = stream
                        .filter(Files::isRegularFile)
                        .filter(p -> p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".snbt"))
                        .sorted(Comparator.comparing(Path::toString))
                        .toList();
            }
            for (Path file : snbtFiles) {
                Path relativeInsideQuests = questsDir.relativize(file);
                Path backupFile = backupRoot.resolve(relativeInsideQuests);
                // Always read from the untouched backup when it already exists so re-runs are idempotent.
                Path sourceToParse = Files.isRegularFile(backupFile) ? backupFile : file;
                String rawSnbt = Files.readString(sourceToParse, StandardCharsets.UTF_8);
                List<SnbtParser.StringSpan> spans = SnbtParser.extractHardcodedQuestSpans(rawSnbt);
                if (spans.isEmpty()) {
                    continue;
                }
                String display = paths.relativize(file);
                tasks.add(new HardcodedSnbtTask(display, file, backupFile, rawSnbt, spans));
            }
        }

        if (!tasks.isEmpty() && callback != null) {
            callback.onLog(LogLevel.INFO, "FTB Quests: " + tasks.size() + " arquivo(s) .snbt preparado(s).");
        }
        return tasks;
    }

    private static void scanLangFolder(GamePaths paths, TranslatorConfigData config, TranslationCallback callback,
                                       Path langDir, List<PendingOutputTask> tasks) throws IOException {
        String targetLang = config.getTargetLanguage();
        List<String> priorityLocales = config.effectiveSourceLanguages();

        List<Path> orderedSnbtFiles = findOrderedLangSnbtFiles(langDir, priorityLocales, targetLang);
        if (!orderedSnbtFiles.isEmpty()) {
            List<String> snbtDocs = new ArrayList<>(orderedSnbtFiles.size());
            for (Path src : orderedSnbtFiles) {
                snbtDocs.add(Files.readString(src, StandardCharsets.UTF_8));
            }
            String mergedSnbt = SnbtParser.mergeLangSnbtDocuments(snbtDocs);
            List<SnbtParser.StringSpan> spans = SnbtParser.extractLangValueSpans(mergedSnbt);
            if (!spans.isEmpty()) {
                Path outFile = langDir.resolve(targetLang + ".snbt");
                tasks.add(new LangSnbtTask(paths.relativize(outFile), outFile, mergedSnbt, spans));
            }
        }

        // Newer FTB Quests versions also support per-locale subfolders: lang/en_us/*.snbt -> lang/pt_br/*.snbt
        List<Path> orderedSubDirs = findOrderedLangSubfolders(langDir, priorityLocales, targetLang);
        if (!orderedSubDirs.isEmpty()) {
            Path targetSubDir = langDir.resolve(targetLang);
            Map<Path, List<String>> relFileToSnbtDocs = new LinkedHashMap<>();
            for (Path subDir : orderedSubDirs) {
                List<Path> children;
                try (Stream<Path> s = Files.walk(subDir)) {
                    children = s.filter(Files::isRegularFile)
                            .filter(p -> p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".snbt"))
                            .sorted(Comparator.comparing(Path::toString))
                            .toList();
                }
                for (Path child : children) {
                    Path rel = subDir.relativize(child);
                    relFileToSnbtDocs.computeIfAbsent(rel, k -> new ArrayList<>())
                            .add(Files.readString(child, StandardCharsets.UTF_8));
                }
            }
            for (Map.Entry<Path, List<String>> entry : relFileToSnbtDocs.entrySet()) {
                String mergedSnbt = SnbtParser.mergeLangSnbtDocuments(entry.getValue());
                List<SnbtParser.StringSpan> spans = SnbtParser.extractLangValueSpans(mergedSnbt);
                if (spans.isEmpty()) {
                    continue;
                }
                Path outFile = targetSubDir.resolve(entry.getKey());
                tasks.add(new LangSnbtTask(paths.relativize(outFile), outFile, mergedSnbt, spans));
            }
        }
    }

    private static List<Path> findOrderedLangSnbtFiles(Path langDir, List<String> priorityLocales, String targetLang)
            throws IOException {
        List<Path> ordered = new ArrayList<>();
        Set<Path> seen = new LinkedHashSet<>();
        for (String locale : priorityLocales) {
            if (locale.equalsIgnoreCase(targetLang)) {
                continue;
            }
            Path candidate = langDir.resolve(locale + ".snbt");
            if (Files.isRegularFile(candidate) && seen.add(candidate.toAbsolutePath().normalize())) {
                ordered.add(candidate);
            }
        }
        try (Stream<Path> s = Files.list(langDir)) {
            s.filter(Files::isRegularFile)
                    .filter(p -> {
                        String name = p.getFileName().toString().toLowerCase(Locale.ROOT);
                        return name.endsWith(".snbt") && !name.equals(targetLang.toLowerCase(Locale.ROOT) + ".snbt");
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

    private static List<Path> findOrderedLangSubfolders(Path langDir, List<String> priorityLocales, String targetLang)
            throws IOException {
        List<Path> ordered = new ArrayList<>();
        Set<Path> seen = new LinkedHashSet<>();
        for (String locale : priorityLocales) {
            if (locale.equalsIgnoreCase(targetLang)) {
                continue;
            }
            Path candidate = langDir.resolve(locale);
            if (Files.isDirectory(candidate) && seen.add(candidate.toAbsolutePath().normalize())) {
                ordered.add(candidate);
            }
        }
        try (Stream<Path> s = Files.list(langDir)) {
            s.filter(Files::isDirectory)
                    .filter(p -> !p.getFileName().toString().equalsIgnoreCase(targetLang))
                    .sorted(Comparator.comparing(Path::toString))
                    .forEach(p -> {
                        if (seen.add(p.toAbsolutePath().normalize())) {
                            ordered.add(p);
                        }
                    });
        }
        return ordered;
    }

    private record LangSnbtTask(
            String displayPath,
            Path outputPath,
            String rawSnbt,
            List<SnbtParser.StringSpan> spans) implements PendingOutputTask {

        @Override
        public TranslationScope scope() {
            return TranslationScope.FTBQUESTS;
        }

        @Override
        public void collectStrings(Consumer<String> collector) {
            for (SnbtParser.StringSpan span : spans) {
                collector.accept(span.unescapedValue());
            }
        }

        @Override
        public void writeOutput(Function<String, String> translator) throws IOException {
            Path parent = outputPath.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            String rewritten = SnbtParser.rewriteSpans(rawSnbt, spans, translator);
            Files.writeString(outputPath, rewritten, StandardCharsets.UTF_8);
        }
    }

    private record HardcodedSnbtTask(
            String displayPath,
            Path targetFile,
            Path backupFile,
            String rawSnbt,
            List<SnbtParser.StringSpan> spans) implements PendingOutputTask {

        @Override
        public TranslationScope scope() {
            return TranslationScope.FTBQUESTS;
        }

        @Override
        public Path outputPath() {
            return targetFile;
        }

        @Override
        public void collectStrings(Consumer<String> collector) {
            for (SnbtParser.StringSpan span : spans) {
                collector.accept(span.unescapedValue());
            }
        }

        @Override
        public void writeOutput(Function<String, String> translator) throws IOException {
            // Single-time automatic backup before modifying any hardcoded quest file.
            if (!Files.exists(backupFile) && Files.isRegularFile(targetFile)) {
                Path backupParent = backupFile.getParent();
                if (backupParent != null) {
                    Files.createDirectories(backupParent);
                }
                Files.copy(targetFile, backupFile, StandardCopyOption.COPY_ATTRIBUTES);
            }
            Path parent = targetFile.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            String rewritten = SnbtParser.rewriteSpans(rawSnbt, spans, translator);
            Files.writeString(targetFile, rewritten, StandardCharsets.UTF_8);
        }
    }
}
