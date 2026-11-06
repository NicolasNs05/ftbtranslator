package com.ftbtranslator.core.pack;

import com.ftbtranslator.core.GamePaths;
import com.ftbtranslator.core.parser.JsonLangIO;
import com.google.gson.JsonObject;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Ensures {@code resourcepacks/AutoTranslated_Pack_PTBR/pack.mcmeta} exists with
 * {@code "pack_format": 34} (Minecraft 1.21 / 1.21.1 resource pack format).
 */
public final class ResourcePackGenerator {

    /** Minecraft 1.21.1 resource pack format version. */
    public static final int PACK_FORMAT_1_21_1 = 34;

    private ResourcePackGenerator() {
    }

    public static Path ensurePackMcmeta(GamePaths paths) throws IOException {
        Path packDir = paths.generatedPackDir();
        Files.createDirectories(packDir);
        Path mcmeta = packDir.resolve("pack.mcmeta");

        JsonObject root = new JsonObject();
        JsonObject pack = new JsonObject();
        pack.addProperty("pack_format", PACK_FORMAT_1_21_1);
        JsonObject supportedFormats = new JsonObject();
        supportedFormats.addProperty("min_inclusive", 15);
        supportedFormats.addProperty("max_inclusive", 99);
        pack.add("supported_formats", supportedFormats);
        pack.addProperty("description", "§bTradução Automática PT-BR §7(FTB Auto-Translator)");
        root.add("pack", pack);

        JsonLangIO.writeJsonObjectAtomic(mcmeta, root);
        return mcmeta;
    }

    public static boolean hasGeneratedResourcePack(GamePaths paths) {
        return Files.isRegularFile(paths.generatedPackDir().resolve("pack.mcmeta"));
    }
}
