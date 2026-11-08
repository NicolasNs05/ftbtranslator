package com.ftbtranslator.core.editor;

import com.ftbtranslator.core.ContextPreset;
import com.ftbtranslator.core.GamePaths;
import com.ftbtranslator.core.TranslatorConfigData;
import com.ftbtranslator.core.cache.TranslationCache;
import com.ftbtranslator.core.parser.JsonLangIO;
import com.ftbtranslator.core.parser.SnbtParser;
import com.ftbtranslator.core.text.MaskedText;
import com.ftbtranslator.core.text.TokenMasker;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Loads generated translations from disk (FTB Quests {@code .snbt}, KubeJS {@code .json}, and the
 * generated Resource Pack {@code AutoTranslated_Pack_PTBR}) so the player can search and edit any
 * translation in-game, persisting changes both to the generated file and to the SHA-256 cache.
 */
public final class TranslationOverrideManager {

    public record EditableEntry(
            String sourceLabel,
            Path filePath,
            boolean snbtFormat,
            String keyOrIndex,
            String originalText,
            String translatedText) {

        public EditableEntry withTranslatedText(String updated) {
            return new EditableEntry(sourceLabel, filePath, snbtFormat, keyOrIndex, originalText, updated);
        }

        public boolean matchesQuery(String lowerQuery) {
            if (lowerQuery == null || lowerQuery.isBlank()) {
                return true;
            }
            return (translatedText != null && translatedText.toLowerCase(Locale.ROOT).contains(lowerQuery))
                    || (originalText != null && originalText.toLowerCase(Locale.ROOT).contains(lowerQuery))
                    || (keyOrIndex != null && keyOrIndex.toLowerCase(Locale.ROOT).contains(lowerQuery))
                    || (sourceLabel != null && sourceLabel.toLowerCase(Locale.ROOT).contains(lowerQuery));
        }
    }

    private TranslationOverrideManager() {
    }

    /**
     * Loads all editable translation entries currently generated on disk for {@code targetLanguage}.
     */
    public static List<EditableEntry> loadAllEntries(GamePaths paths, String targetLanguage) throws IOException {
        String target = (targetLanguage == null || targetLanguage.isBlank())
                ? "pt_br"
                : targetLanguage.trim().toLowerCase(Locale.ROOT);
        List<EditableEntry> entries = new ArrayList<>();

        // 1. FTB Quests lang/<target>.snbt
        Path questsLangDir = paths.questsDir().resolve("lang");
        Path questsTargetSnbt = questsLangDir.resolve(target + ".snbt");
        if (Files.isRegularFile(questsTargetSnbt)) {
            loadSnbtFileEntries("FTB Quests", questsTargetSnbt, questsLangDir.resolve("en_us.snbt"), entries);
        }

        // 2. KubeJS kubejs/assets/*/lang/<target>.json
        Path kubeAssets = paths.kubejsDir().resolve("assets");
        if (Files.isDirectory(kubeAssets)) {
            loadJsonLangTree("KubeJS", kubeAssets, target, entries);
        }

        // 3. Generated Resource Pack resourcepacks/AutoTranslated_Pack_PTBR/assets/*/lang/<target>.json
        Path packAssets = paths.generatedPackDir().resolve("assets");
        if (Files.isDirectory(packAssets)) {
            loadJsonLangTree("Mod", packAssets, target, entries);
        }

        return entries;
    }

    private static void loadSnbtFileEntries(String prefix, Path targetSnbt, Path enUsSnbt,
                                            List<EditableEntry> out) throws IOException {
        String ptContent = Files.readString(targetSnbt, StandardCharsets.UTF_8);
        List<SnbtParser.StringSpan> ptSpans = SnbtParser.extractLangValueSpans(ptContent);
        List<SnbtParser.StringSpan> enSpans = List.of();
        if (enUsSnbt != null && Files.isRegularFile(enUsSnbt)) {
            try {
                enSpans = SnbtParser.extractLangValueSpans(Files.readString(enUsSnbt, StandardCharsets.UTF_8));
            } catch (IOException ignored) {
            }
        }
        boolean aligned = enSpans.size() == ptSpans.size();
        for (int i = 0; i < ptSpans.size(); i++) {
            String ptVal = ptSpans.get(i).unescapedValue();
            String enVal = aligned ? enSpans.get(i).unescapedValue() : ("Trecho #" + (i + 1));
            out.add(new EditableEntry(
                    prefix,
                    targetSnbt,
                    true,
                    Integer.toString(i),
                    enVal,
                    ptVal));
        }
    }

    private static void loadJsonLangTree(String prefix, Path assetsRoot, String target,
                                         List<EditableEntry> out) throws IOException {
        String targetFileName = target + ".json";
        List<Path> langFiles;
        try (Stream<Path> walk = Files.walk(assetsRoot, 4)) {
            langFiles = walk.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().equalsIgnoreCase(targetFileName))
                    .filter(p -> p.getParent() != null
                            && p.getParent().getFileName().toString().equalsIgnoreCase("lang"))
                    .sorted(Comparator.comparing(Path::toString))
                    .toList();
        }
        for (Path ptFile : langFiles) {
            Path langDir = ptFile.getParent();
            Path nsDir = langDir != null ? langDir.getParent() : null;
            String namespace = nsDir != null ? nsDir.getFileName().toString() : "default";
            String label = prefix + " (" + namespace + ")";

            Map<String, String> ptMap = JsonLangIO.readLangFile(ptFile);
            Map<String, String> enMap = Map.of();
            if (langDir != null) {
                Path enFile = langDir.resolve("en_us.json");
                if (Files.isRegularFile(enFile)) {
                    try {
                        enMap = JsonLangIO.readLangFile(enFile);
                    } catch (IOException ignored) {
                    }
                }
            }
            for (Map.Entry<String, String> e : ptMap.entrySet()) {
                String key = e.getKey();
                String ptVal = e.getValue();
                String enVal = enMap.getOrDefault(key, key);
                out.add(new EditableEntry(label, ptFile, false, key, enVal, ptVal));
            }
        }
    }

    /**
     * Updates the translation of {@code entry} both in its generated file on disk and inside the SHA-256
     * translation cache so future runs preserve the manual edit.
     */
    public static EditableEntry saveOverride(GamePaths paths,
                                             TranslatorConfigData config,
                                             EditableEntry entry,
                                             String newTranslation) throws IOException {
        if (entry == null || newTranslation == null || newTranslation.isBlank()) {
            throw new IOException("A tradução não pode ficar vazia.");
        }
        String trimmed = newTranslation.trim();

        if (entry.snbtFormat()) {
            int targetIdx = Integer.parseInt(entry.keyOrIndex());
            String rawSnbt = Files.readString(entry.filePath(), StandardCharsets.UTF_8);
            List<SnbtParser.StringSpan> spans = SnbtParser.extractLangValueSpans(rawSnbt);
            if (targetIdx < 0 || targetIdx >= spans.size()) {
                throw new IOException("Índice SNBT fora do intervalo: " + targetIdx);
            }
            SnbtParser.StringSpan span = spans.get(targetIdx);
            String updatedSnbt = rawSnbt.substring(0, span.tokenStart())
                    + "\"" + SnbtParser.escapeSnbtDoubleQuoted(trimmed) + "\""
                    + rawSnbt.substring(span.tokenEnd());
            Path tmp = entry.filePath().resolveSibling(entry.filePath().getFileName() + ".tmp");
            Files.writeString(tmp, updatedSnbt, StandardCharsets.UTF_8);
            Files.move(tmp, entry.filePath(), StandardCopyOption.REPLACE_EXISTING);
        } else {
            Map<String, String> map = JsonLangIO.readLangFile(entry.filePath());
            map.put(entry.keyOrIndex(), trimmed);
            JsonLangIO.writeLangFile(entry.filePath(), map);
        }

        // Also update the SHA-256 translation cache if we know the source text (not just a raw key id)
        String orig = entry.originalText();
        if (orig != null && !orig.isBlank() && !orig.equals(entry.keyOrIndex())) {
            TokenMasker masker = new TokenMasker();
            String targetLang = config != null ? config.getTargetLanguage() : "pt_br";
            ContextPreset activePreset = config != null ? config.getContextPreset() : ContextPreset.DEFAULT;
            String customCtx = config != null ? config.getCustomContext() : "";

            TranslationCache cache = new TranslationCache(paths.cacheFile(targetLang), activePreset, customCtx);
            cache.load();
            cache.put(orig, trimmed);

            MaskedText maskedOrig = masker.mask(orig);
            MaskedText maskedNew = masker.mask(trimmed);
            if (maskedOrig.tokens().equals(maskedNew.tokens())) {
                cache.putTemplate(maskedOrig.masked(), maskedNew.masked());
            }
            cache.saveIfDirty();
        }

        return entry.withTranslatedText(trimmed);
    }
}
