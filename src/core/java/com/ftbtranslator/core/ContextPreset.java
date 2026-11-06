package com.ftbtranslator.core;

import java.util.Locale;

/**
 * Thematic context profiles injected in the system prompt so terminology stays consistent.
 */
public enum ContextPreset {
    TENSURA("Tensura",
            "Traduza para Português do Brasil mantendo a terminologia consagrada de That Time I Got "
                    + "Reincarnated as a Slime (Tensura), como Unique Skill, Ultimate Skill, Magicules, "
                    + "Demon Lord, Great Sage, Arts e Battlewill de forma consistente."),
    DEFAULT("Padrão",
            "Modpack geral de Minecraft. Mantenha nomes próprios de mods e termos técnicos consistentes."),
    TECH("Tech",
            "Modpack técnico/industrial. Preserve nomes de máquinas, minérios químicos e unidades de "
                    + "energia (FE/t, EU/t, mB)."),
    MAGIC("Magia",
            "Modpack de magia e RPG. Use vocabulário de fantasia medieval/arcana."),
    CUSTOM("Custom", "");

    private final String displayName;
    private final String prompt;

    ContextPreset(String displayName, String prompt) {
        this.displayName = displayName;
        this.prompt = prompt;
    }

    public String displayName() {
        return displayName;
    }

    /** Built-in prompt text (empty for {@link #CUSTOM}, whose text comes from the configuration). */
    public String prompt() {
        return prompt;
    }

    public ContextPreset next() {
        ContextPreset[] all = values();
        return all[(ordinal() + 1) % all.length];
    }

    /** Accepts enum names and the Portuguese display names ("padrao", "magia"...). */
    public static ContextPreset parse(String raw) {
        if (raw == null) {
            return null;
        }
        String s = raw.trim().toUpperCase(Locale.ROOT);
        for (ContextPreset preset : values()) {
            if (preset.name().equals(s)) {
                return preset;
            }
        }
        return switch (s) {
            case "PADRAO", "PADRÃO", "GERAL", "GENERAL" -> DEFAULT;
            case "MAGIA", "RPG" -> MAGIC;
            case "TECNICO", "TÉCNICO", "INDUSTRIAL" -> TECH;
            case "SLIME" -> TENSURA;
            case "PERSONALIZADO" -> CUSTOM;
            default -> null;
        };
    }
}
