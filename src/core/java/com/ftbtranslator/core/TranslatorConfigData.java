package com.ftbtranslator.core;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * Plain configuration object handed to the core by the platform adapter. No loader dependency.
 */
public final class TranslatorConfigData {

    public static final List<String> DEFAULT_SOURCE_LANGUAGES = List.of("en_us", "ja_jp", "zh_cn", "zh_tw", "ko_kr");

    private ProviderType provider = ProviderType.GEMINI;
    private String apiKey = "";
    private String model = "";
    private String baseUrl = "";
    private int requestsPerMinute = 15;
    private int batchSize = 40;
    private int maxBatchChars = 6000;
    private int maxRetries = 4;
    private int requestTimeoutSeconds = 120;
    private ContextPreset contextPreset = ContextPreset.DEFAULT;
    private String customContext = "";
    private boolean ftbQuestsEnabled = true;
    private boolean kubeJsEnabled = true;
    private boolean modsEnabled = true;
    private boolean patchouliEnabled = true;
    private String modFilter = "";
    private boolean skipAlreadyTranslatedMods = true;
    private boolean useOfficialGlossary = true;
    private String targetLanguage = "pt_br";
    private List<String> sourceLanguages = new ArrayList<>(DEFAULT_SOURCE_LANGUAGES);

    public TranslatorConfigData copy() {
        TranslatorConfigData c = new TranslatorConfigData();
        c.provider = provider;
        c.apiKey = apiKey;
        c.model = model;
        c.baseUrl = baseUrl;
        c.requestsPerMinute = requestsPerMinute;
        c.batchSize = batchSize;
        c.maxBatchChars = maxBatchChars;
        c.maxRetries = maxRetries;
        c.requestTimeoutSeconds = requestTimeoutSeconds;
        c.contextPreset = contextPreset;
        c.customContext = customContext;
        c.ftbQuestsEnabled = ftbQuestsEnabled;
        c.kubeJsEnabled = kubeJsEnabled;
        c.modsEnabled = modsEnabled;
        c.patchouliEnabled = patchouliEnabled;
        c.modFilter = modFilter;
        c.skipAlreadyTranslatedMods = skipAlreadyTranslatedMods;
        c.useOfficialGlossary = useOfficialGlossary;
        c.targetLanguage = targetLanguage;
        c.sourceLanguages = new ArrayList<>(sourceLanguages);
        return c;
    }

    // ------------------------------------------------------------------ derived values

    public String effectiveModel() {
        return model == null || model.isBlank() ? provider.defaultModel() : model.trim();
    }

    public String effectiveBaseUrl() {
        String url = baseUrl == null || baseUrl.isBlank() ? provider.defaultBaseUrl() : baseUrl.trim();
        while (url.endsWith("/")) {
            url = url.substring(0, url.length() - 1);
        }
        return url;
    }

    /** Text of the selected thematic profile (custom text when {@link ContextPreset#CUSTOM}). */
    public String effectiveContext() {
        if (contextPreset == ContextPreset.CUSTOM) {
            return customContext == null || customContext.isBlank()
                    ? ContextPreset.DEFAULT.prompt()
                    : customContext.trim();
        }
        return contextPreset.prompt();
    }

    /** Lower-cased, trimmed, non-empty tokens of the comma separated mod filter. */
    public List<String> modFilterTokens() {
        List<String> tokens = new ArrayList<>();
        if (modFilter == null) {
            return tokens;
        }
        for (String part : modFilter.split("[,;]")) {
            String t = part.trim().toLowerCase(Locale.ROOT);
            if (!t.isEmpty()) {
                tokens.add(t);
            }
        }
        return tokens;
    }

    /** Ordered source languages without the target language. */
    public List<String> effectiveSourceLanguages() {
        List<String> out = new ArrayList<>();
        for (String lang : sourceLanguages) {
            String l = lang.trim().toLowerCase(Locale.ROOT);
            if (!l.isEmpty() && !l.equals(targetLanguage) && !out.contains(l)) {
                out.add(l);
            }
        }
        if (out.isEmpty()) {
            out.add("en_us");
        }
        return out;
    }

    public boolean isScopeEnabled(TranslationScope scope) {
        return switch (scope) {
            case FTBQUESTS -> ftbQuestsEnabled;
            case KUBEJS -> kubeJsEnabled;
            case MODS -> modsEnabled;
            case PATCHOULI -> patchouliEnabled;
        };
    }

    public void setScopeEnabled(TranslationScope scope, boolean enabled) {
        switch (scope) {
            case FTBQUESTS -> ftbQuestsEnabled = enabled;
            case KUBEJS -> kubeJsEnabled = enabled;
            case MODS -> modsEnabled = enabled;
            case PATCHOULI -> patchouliEnabled = enabled;
        }
    }

    /** Returns an error message in Portuguese when the configuration cannot start a run, or null when OK. */
    public String validateForTranslation() {
        if (provider.requiresApiKey() && (apiKey == null || apiKey.isBlank())) {
            return "Nenhuma API Key configurada para " + provider.displayName()
                    + ". Use 'Colar Key' ou /ftbtranslate apikey <key>.";
        }
        if (effectiveModel().isBlank()) {
            return "Nenhum modelo configurado.";
        }
        if (!effectiveBaseUrl().startsWith("http://") && !effectiveBaseUrl().startsWith("https://")) {
            return "URL base inválida: " + effectiveBaseUrl();
        }
        return null;
    }

    // ------------------------------------------------------------------ accessors

    public ProviderType getProvider() {
        return provider;
    }

    public void setProvider(ProviderType provider) {
        this.provider = Objects.requireNonNull(provider);
    }

    public String getApiKey() {
        return apiKey;
    }

    public void setApiKey(String apiKey) {
        this.apiKey = apiKey == null ? "" : apiKey.trim();
    }

    public String getModel() {
        return model;
    }

    public void setModel(String model) {
        this.model = model == null ? "" : model.trim();
    }

    public String getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl == null ? "" : baseUrl.trim();
    }

    public int getRequestsPerMinute() {
        return requestsPerMinute;
    }

    public void setRequestsPerMinute(int requestsPerMinute) {
        this.requestsPerMinute = Math.max(0, requestsPerMinute);
    }

    public int getBatchSize() {
        return batchSize;
    }

    public void setBatchSize(int batchSize) {
        this.batchSize = Math.max(1, batchSize);
    }

    public int getMaxBatchChars() {
        return maxBatchChars;
    }

    public void setMaxBatchChars(int maxBatchChars) {
        this.maxBatchChars = Math.max(200, maxBatchChars);
    }

    public int getMaxRetries() {
        return maxRetries;
    }

    public void setMaxRetries(int maxRetries) {
        this.maxRetries = Math.max(0, maxRetries);
    }

    public int getRequestTimeoutSeconds() {
        return requestTimeoutSeconds;
    }

    public void setRequestTimeoutSeconds(int requestTimeoutSeconds) {
        this.requestTimeoutSeconds = Math.max(5, requestTimeoutSeconds);
    }

    public ContextPreset getContextPreset() {
        return contextPreset;
    }

    public void setContextPreset(ContextPreset contextPreset) {
        this.contextPreset = Objects.requireNonNull(contextPreset);
    }

    public String getCustomContext() {
        return customContext;
    }

    public void setCustomContext(String customContext) {
        this.customContext = customContext == null ? "" : customContext;
    }

    public boolean isFtbQuestsEnabled() {
        return ftbQuestsEnabled;
    }

    public void setFtbQuestsEnabled(boolean v) {
        this.ftbQuestsEnabled = v;
    }

    public boolean isKubeJsEnabled() {
        return kubeJsEnabled;
    }

    public void setKubeJsEnabled(boolean v) {
        this.kubeJsEnabled = v;
    }

    public boolean isModsEnabled() {
        return modsEnabled;
    }

    public void setModsEnabled(boolean v) {
        this.modsEnabled = v;
    }

    public boolean isPatchouliEnabled() {
        return patchouliEnabled;
    }

    public void setPatchouliEnabled(boolean v) {
        this.patchouliEnabled = v;
    }

    public String getModFilter() {
        return modFilter;
    }

    public void setModFilter(String modFilter) {
        this.modFilter = modFilter == null ? "" : modFilter;
    }

    public boolean isSkipAlreadyTranslatedMods() {
        return skipAlreadyTranslatedMods;
    }

    public void setSkipAlreadyTranslatedMods(boolean v) {
        this.skipAlreadyTranslatedMods = v;
    }

    /** Reuse the official pt_br files shipped inside mod jars as a glossary (exact match + prompt terms). */
    public boolean isUseOfficialGlossary() {
        return useOfficialGlossary;
    }

    public void setUseOfficialGlossary(boolean v) {
        this.useOfficialGlossary = v;
    }

    public String getTargetLanguage() {
        return targetLanguage;
    }

    public void setTargetLanguage(String targetLanguage) {
        String t = targetLanguage == null ? "" : targetLanguage.trim().toLowerCase(Locale.ROOT);
        this.targetLanguage = t.isEmpty() ? "pt_br" : t;
    }

    public List<String> getSourceLanguages() {
        return List.copyOf(sourceLanguages);
    }

    public void setSourceLanguages(List<String> sourceLanguages) {
        this.sourceLanguages = sourceLanguages == null || sourceLanguages.isEmpty()
                ? new ArrayList<>(DEFAULT_SOURCE_LANGUAGES)
                : new ArrayList<>(sourceLanguages);
    }
}
