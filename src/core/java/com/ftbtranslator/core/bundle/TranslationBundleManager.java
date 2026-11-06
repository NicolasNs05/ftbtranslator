package com.ftbtranslator.core.bundle;

import com.ftbtranslator.core.GamePaths;
import com.ftbtranslator.core.cache.TranslationCache;
import com.ftbtranslator.core.pack.ResourcePackGenerator;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

/**
 * Exports all generated translations into {@code exports/Traducao_Modpack_PTBR.zip} so a player can share
 * the translation with friends (who can import it with a single click without needing an API Key).
 */
public final class TranslationBundleManager {

    public static final String DEFAULT_BUNDLE_FILENAME = "Traducao_Modpack_PTBR.zip";

    public record ExportResult(Path zipPath, int filesExported, long sizeBytes) {
    }

    public record ImportResult(Path zipPath, int filesExtracted, int cacheEntriesMerged,
                               boolean questsTouched, boolean resourcePackTouched) {
    }

    private TranslationBundleManager() {
    }

    public static Path defaultBundlePath(GamePaths paths) {
        return paths.exportsDir().resolve(DEFAULT_BUNDLE_FILENAME);
    }

    /**
     * Collects all generated translation artifacts and compresses them into
     * {@code exports/Traducao_Modpack_PTBR.zip}.
     */
    public static ExportResult exportBundle(GamePaths paths, String targetLanguage) throws IOException {
        Set<Path> toInclude = new LinkedHashSet<>();

        // 1. FTB Quests lang/<target>.snbt and lang/<target>/**
        Path questsLangDir = paths.questsDir().resolve("lang");
        Path questsLangFile = questsLangDir.resolve(targetLanguage + ".snbt");
        if (Files.isRegularFile(questsLangFile)) {
            toInclude.add(questsLangFile);
        }
        Path questsLangSub = questsLangDir.resolve(targetLanguage);
        collectTreeIfExists(questsLangSub, toInclude);

        // 2. Translated FTB Quests chapters & reward_tables (when a backup exists, the corresponding quest file is translated)
        Path backupRoot = paths.questsBackupDir();
        if (Files.isDirectory(backupRoot)) {
            try (Stream<Path> walk = Files.walk(backupRoot)) {
                for (Path backupFile : walk.filter(Files::isRegularFile).toList()) {
                    Path rel = backupRoot.relativize(backupFile);
                    Path translatedQuestFile = paths.questsDir().resolve(rel);
                    if (Files.isRegularFile(translatedQuestFile)) {
                        toInclude.add(translatedQuestFile);
                    }
                }
            }
        }

        // 3. KubeJS kubejs/assets/*/lang/<target>.json
        Path kubeAssets = paths.kubejsDir().resolve("assets");
        if (Files.isDirectory(kubeAssets)) {
            try (Stream<Path> walk = Files.walk(kubeAssets, 5)) {
                walk.filter(Files::isRegularFile)
                        .filter(p -> p.getFileName().toString().equalsIgnoreCase(targetLanguage + ".json"))
                        .filter(p -> p.getParent() != null
                                && p.getParent().getFileName().toString().equalsIgnoreCase("lang"))
                        .forEach(toInclude::add);
            }
        }

        // 4. Generated Resource Pack (resourcepacks/AutoTranslated_Pack_PTBR/**)
        collectTreeIfExists(paths.generatedPackDir(), toInclude);

        // 5. Root patchouli_books/<book>/<target>/**
        Path rootBooks = paths.patchouliBooksDir();
        if (Files.isDirectory(rootBooks)) {
            try (Stream<Path> books = Files.list(rootBooks)) {
                for (Path bookDir : books.filter(Files::isDirectory).toList()) {
                    collectTreeIfExists(bookDir.resolve(targetLanguage), toInclude);
                }
            }
        }

        // 6. SHA-256 translation cache (config/ftbtranslator/cache_pt_br.json)
        Path cacheFile = paths.cacheFile(targetLanguage);
        if (Files.isRegularFile(cacheFile)) {
            toInclude.add(cacheFile);
        }

        if (toInclude.isEmpty()) {
            throw new IOException("Nenhum arquivo traduzido foi encontrado para exportar. Execute a tradução primeiro.");
        }

        Files.createDirectories(paths.exportsDir());
        Path targetZip = defaultBundlePath(paths);
        Path tmpZip = targetZip.resolveSibling(targetZip.getFileName() + ".tmp");

        List<Path> sorted = new ArrayList<>(toInclude);
        sorted.sort(Comparator.comparing(paths::relativize));

        try (OutputStream fos = Files.newOutputStream(tmpZip);
             ZipOutputStream zos = new ZipOutputStream(fos)) {
            for (Path file : sorted) {
                if (!Files.isRegularFile(file)) {
                    continue;
                }
                String entryName = paths.relativize(file);
                ZipEntry entry = new ZipEntry(entryName);
                entry.setTime(Files.getLastModifiedTime(file).toMillis());
                zos.putNextEntry(entry);
                Files.copy(file, zos);
                zos.closeEntry();
            }
        }

        Files.move(tmpZip, targetZip, StandardCopyOption.REPLACE_EXISTING);
        long size = Files.size(targetZip);
        return new ExportResult(targetZip, sorted.size(), size);
    }

    /**
     * Imports a translation bundle {@code .zip} from {@code exports/} (preferring
     * {@code exports/Traducao_Modpack_PTBR.zip}, or any {@code .zip} found inside {@code exports/}).
     */
    public static ImportResult importBundle(GamePaths paths, String targetLanguage) throws IOException {
        Path bundle = resolveBundleToImport(paths);
        return importBundleFromFile(paths, bundle, targetLanguage);
    }

    public static ImportResult importBundleFromFile(GamePaths paths, Path zipPath, String targetLanguage)
            throws IOException {
        if (zipPath == null || !Files.isRegularFile(zipPath)) {
            throw new IOException("Arquivo .zip não encontrado em: "
                    + (zipPath == null ? paths.exportsDir() : zipPath));
        }

        int extracted = 0;
        int cacheMerged = 0;
        boolean questsTouched = false;
        boolean resourcePackTouched = false;

        try (ZipFile zip = new ZipFile(zipPath.toFile())) {
            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                if (entry.isDirectory()) {
                    continue;
                }
                String normName = entry.getName().replace('\\', '/');
                while (normName.startsWith("/")) {
                    normName = normName.substring(1);
                }
                if (!isAllowedBundleEntry(normName)) {
                    continue;
                }
                Path dest = paths.resolveSafe(normName);

                // If importing a hardcoded chapter/reward_table, create a one-time backup of the local original first.
                if (normName.startsWith("config/ftbquests/quests/chapters/")
                        || normName.startsWith("config/ftbquests/quests/reward_tables/")) {
                    Path relInsideQuests = paths.questsDir().relativize(dest);
                    Path backupFile = paths.questsBackupDir().resolve(relInsideQuests);
                    if (!Files.exists(backupFile) && Files.isRegularFile(dest)) {
                        Files.createDirectories(backupFile.getParent());
                        Files.copy(dest, backupFile, StandardCopyOption.COPY_ATTRIBUTES);
                    }
                }

                // Merge cache entries instead of blindly overwriting local cache
                if (normName.startsWith("config/ftbtranslator/cache_")) {
                    Path tmpCache = Files.createTempFile("ftbtranslator_cache_import_", ".json");
                    try (InputStream in = zip.getInputStream(entry)) {
                        Files.copy(in, tmpCache, StandardCopyOption.REPLACE_EXISTING);
                    }
                    try {
                        TranslationCache localCache = new TranslationCache(paths.cacheFile(targetLanguage));
                        localCache.load();
                        cacheMerged += localCache.mergeFrom(tmpCache);
                        localCache.saveIfDirty();
                    } finally {
                        Files.deleteIfExists(tmpCache);
                    }
                    extracted++;
                    continue;
                }

                Path parent = dest.getParent();
                if (parent != null) {
                    Files.createDirectories(parent);
                }
                try (InputStream in = zip.getInputStream(entry)) {
                    Files.copy(in, dest, StandardCopyOption.REPLACE_EXISTING);
                }
                extracted++;

                if (normName.startsWith("config/ftbquests/")) {
                    questsTouched = true;
                }
                if (normName.startsWith("resourcepacks/" + GamePaths.RESOURCE_PACK_NAME)) {
                    resourcePackTouched = true;
                }
            }
        }

        if (extracted == 0) {
            throw new IOException("O arquivo " + zipPath.getFileName()
                    + " não contém arquivos de tradução reconhecidos.");
        }
        if (resourcePackTouched) {
            ResourcePackGenerator.ensurePackMcmeta(paths);
        }
        return new ImportResult(zipPath, extracted, cacheMerged, questsTouched, resourcePackTouched);
    }

    private static Path resolveBundleToImport(GamePaths paths) throws IOException {
        Path preferred = defaultBundlePath(paths);
        if (Files.isRegularFile(preferred)) {
            return preferred;
        }
        if (Files.isDirectory(paths.exportsDir())) {
            try (Stream<Path> s = Files.list(paths.exportsDir())) {
                Path anyZip = s.filter(Files::isRegularFile)
                        .filter(p -> p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".zip"))
                        .sorted(Comparator.comparing(Path::toString))
                        .findFirst()
                        .orElse(null);
                if (anyZip != null) {
                    return anyZip;
                }
            }
        }
        throw new IOException("Nenhum arquivo .zip encontrado na pasta exports/. Coloque "
                + DEFAULT_BUNDLE_FILENAME + " dentro de " + paths.exportsDir());
    }

    private static boolean isAllowedBundleEntry(String normName) {
        if (normName.contains("..")) {
            return false;
        }
        return normName.startsWith("config/ftbquests/quests/")
                || normName.startsWith("config/ftbtranslator/cache_")
                || normName.startsWith("kubejs/assets/")
                || normName.startsWith("resourcepacks/" + GamePaths.RESOURCE_PACK_NAME + "/")
                || normName.startsWith("patchouli_books/");
    }

    private static void collectTreeIfExists(Path dir, Set<Path> target) throws IOException {
        if (!Files.isDirectory(dir)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(dir)) {
            walk.filter(Files::isRegularFile).forEach(target::add);
        }
    }
}
