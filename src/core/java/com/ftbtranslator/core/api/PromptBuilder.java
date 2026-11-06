package com.ftbtranslator.core.api;

import com.ftbtranslator.core.TranslatorConfigData;

import java.util.Map;

/**
 * Concise, token-efficient system prompt combining the user's selected
 * {@link com.ftbtranslator.core.ContextPreset} with strict placeholder preservation rules.
 */
public final class PromptBuilder {

    private PromptBuilder() {
    }

    public static String buildSystemPrompt(TranslatorConfigData config) {
        return """
                Traduza os valores do JSON de modpack de Minecraft para Português do Brasil (pt-BR).
                Contexto: %s
                Regras:
                1. Retorne APENAS um objeto JSON válido com as mesmas chaves ("0","1",...).
                2. Mantenha todos os marcadores __TK0__, __TK1__, etc. EXATAMENTE como estão.
                3. Se já estiver em português ou for código técnico, mantenha igual."""
                .formatted(config.effectiveContext());
    }

    /**
     * Appends glossary terms (human translations from the mods themselves) to {@code basePrompt} so the AI
     * keeps the same terminology. The base prompt stays as an unchanged prefix, preserving provider-side
     * prompt caching. Returns {@code basePrompt} untouched when there are no terms.
     */
    public static String withGlossary(String basePrompt, Map<String, String> terms) {
        if (terms == null || terms.isEmpty()) {
            return basePrompt;
        }
        StringBuilder sb = new StringBuilder(basePrompt)
                .append("\n4. Use estas traduções oficiais dos mods para os termos abaixo:");
        for (Map.Entry<String, String> t : terms.entrySet()) {
            sb.append("\n").append(t.getKey()).append(" = ").append(t.getValue());
        }
        return sb.toString();
    }
}
