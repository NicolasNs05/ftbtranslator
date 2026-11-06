package com.ftbtranslator.core;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.stream.Stream;

/**
 * Well-known locations inside the Minecraft instance directory. Pure value object (no loader APIs),
 * the platform adapter only supplies the game directory.
 */
public record GamePaths(Path gameDir) {

    public static final String RESOURCE_PACK_NAME = "AutoTranslated_Pack_PTBR";

    public GamePaths {
        gameDir = gameDir.toAbsolutePath().normalize();
    }

    public Path configDir() {
        return gameDir.resolve("config");
    }

    public Path translatorConfigDir() {
        return configDir().resolve("ftbtranslator");
    }

    public Path cacheFile(String targetLanguage) {
        return translatorConfigDir().resolve("cache_" + targetLanguage + ".json");
    }

    public Path generatedRegistryFile() {
        return translatorConfigDir().resolve("generated_files.json");
    }

    public Path questsDir() {
        return configDir().resolve("ftbquests").resolve("quests");
    }

    public Path questsBackupDir() {
        return configDir().resolve("ftbquests").resolve("quests_backup_original");
    }

    public Path kubejsDir() {
        return gameDir.resolve("kubejs");
    }

    public Path modsDir() {
        return gameDir.resolve("mods");
    }

    public Path resourcePacksDir() {
        return gameDir.resolve("resourcepacks");
    }

    public Path generatedPackDir() {
        return resourcePacksDir().resolve(RESOURCE_PACK_NAME);
    }

    public Path patchouliBooksDir() {
        return gameDir.resolve("patchouli_books");
    }

    public Path patchouliBackupDir() {
        return gameDir.resolve("patchouli_books_backup_original");
    }

    public Path exportsDir() {
        return gameDir.resolve("exports");
    }

    /**
     * Detects whether this Minecraft instance contains the Tensura modpack / mod (by inspecting
     * {@code mods/*.jar}, {@code kubejs/assets/tensura} and the instance directory name).
     */
    public boolean containsTensuraMod() {
        Path fileName = gameDir.getFileName();
        if (fileName != null && fileName.toString().toLowerCase(Locale.ROOT).contains("tensura")) {
            return true;
        }
        if (Files.isDirectory(kubejsDir().resolve("assets").resolve("tensura"))) {
            return true;
        }
        Path mods = modsDir();
        if (Files.isDirectory(mods)) {
            try (Stream<Path> s = Files.list(mods)) {
                return s.filter(Files::isRegularFile)
                        .map(p -> p.getFileName().toString().toLowerCase(Locale.ROOT))
                        .anyMatch(name -> name.endsWith(".jar") && name.contains("tensura"));
            } catch (IOException ignored) {
            }
        }
        return false;
    }

    /** Path relative to the game dir using forward slashes (stable across operating systems). */
    public String relativize(Path absolute) {
        Path rel = gameDir.relativize(absolute.toAbsolutePath().normalize());
        return rel.toString().replace('\\', '/');
    }

    /** Resolves a forward-slash relative path, refusing anything that escapes the game directory. */
    public Path resolveSafe(String relative) {
        Path resolved = gameDir.resolve(relative.replace('\\', '/')).toAbsolutePath().normalize();
        if (!resolved.startsWith(gameDir)) {
            throw new IllegalArgumentException("Caminho fora da pasta do jogo: " + relative);
        }
        return resolved;
    }
}
