package com.ftbtranslator.core;

import java.util.EnumSet;
import java.util.Locale;
import java.util.Set;

/**
 * The translation fronts that can be enabled independently.
 */
public enum TranslationScope {
    FTBQUESTS("FTB Quests"),
    KUBEJS("KubeJS"),
    MODS("Mods (.jar)"),
    PATCHOULI("Patchouli");

    private final String displayName;

    TranslationScope(String displayName) {
        this.displayName = displayName;
    }

    public String displayName() {
        return displayName;
    }

    /** Parses a command argument: {@code all|ftbquests|kubejs|mods|patchouli}. Returns null if unknown. */
    public static Set<TranslationScope> parseArgument(String raw) {
        if (raw == null) {
            return null;
        }
        return switch (raw.trim().toLowerCase(Locale.ROOT)) {
            case "all", "tudo", "todos" -> EnumSet.allOf(TranslationScope.class);
            case "ftbquests", "quests", "ftb" -> EnumSet.of(FTBQUESTS);
            case "kubejs", "kjs" -> EnumSet.of(KUBEJS);
            case "mods", "jar", "jars" -> EnumSet.of(MODS);
            case "patchouli", "books", "livros" -> EnumSet.of(PATCHOULI);
            default -> null;
        };
    }
}
