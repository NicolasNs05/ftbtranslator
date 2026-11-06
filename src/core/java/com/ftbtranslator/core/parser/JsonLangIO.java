package com.ftbtranslator.core.parser;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Helpers for reading and writing Minecraft JSON language files ({@code en_us.json}, {@code pt_br.json})
 * while keeping key order and escaping quotes properly.
 */
public final class JsonLangIO {

    private static final Gson PRETTY_GSON = new GsonBuilder()
            .setPrettyPrinting()
            .disableHtmlEscaping()
            .create();

    private JsonLangIO() {
    }

    public static Map<String, String> readLangFile(Path path) throws IOException {
        try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            return parseLangReader(reader);
        }
    }

    public static Map<String, String> readLangStream(InputStream in) throws IOException {
        try (Reader reader = new InputStreamReader(in, StandardCharsets.UTF_8)) {
            return parseLangReader(reader);
        }
    }

    public static Map<String, String> parseLangReader(Reader reader) {
        Map<String, String> map = new LinkedHashMap<>();
        JsonElement rootEl = JsonParser.parseReader(reader);
        if (rootEl == null || !rootEl.isJsonObject()) {
            return map;
        }
        JsonObject obj = rootEl.getAsJsonObject();
        for (Map.Entry<String, JsonElement> entry : obj.entrySet()) {
            JsonElement val = entry.getValue();
            if (val != null && val.isJsonPrimitive() && val.getAsJsonPrimitive().isString()) {
                map.put(entry.getKey(), val.getAsString());
            }
        }
        return map;
    }

    public static void writeLangFile(Path target, Map<String, String> entries) throws IOException {
        Path parent = target.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        JsonObject obj = new JsonObject();
        for (Map.Entry<String, String> entry : entries.entrySet()) {
            obj.addProperty(entry.getKey(), entry.getValue());
        }
        writeJsonObjectAtomic(target, obj);
    }

    public static void writeJsonObjectAtomic(Path target, JsonElement element) throws IOException {
        Path parent = target.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Path tmp = target.resolveSibling(target.getFileName() + ".tmp");
        try (Writer writer = Files.newBufferedWriter(tmp, StandardCharsets.UTF_8)) {
            PRETTY_GSON.toJson(element, writer);
        }
        Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
    }
}
