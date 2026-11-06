package com.ftbtranslator.core;

/**
 * Selectable AI providers. Every provider maps to one of the two {@link ApiMode wire protocols}.
 */
public enum ProviderType {
    GEMINI("Google Gemini", ApiMode.GEMINI,
            "https://generativelanguage.googleapis.com/v1beta", "gemini-2.5-flash", true,
            "https://aistudio.google.com/app/apikey", 15),
    CLAUDE("Anthropic Claude", ApiMode.ANTHROPIC,
            "https://api.anthropic.com/v1", "claude-opus-5-5", true,
            "https://platform.claude.com", 50),
    GROQ("Groq (Grátis/Rápido)", ApiMode.OPENAI_COMPATIBLE,
            "https://api.groq.com/openai/v1", "llama-3.3-70b-versatile", true,
            "https://console.groq.com/keys", 30),
    OPENROUTER("OpenRouter", ApiMode.OPENAI_COMPATIBLE,
            "https://openrouter.ai/api/v1", "meta-llama/llama-3.3-70b-instruct:free", true,
            "https://openrouter.ai/keys", 20),
    OPENAI("OpenAI", ApiMode.OPENAI_COMPATIBLE,
            "https://api.openai.com/v1", "gpt-4o-mini", true,
            "https://platform.openai.com/api-keys", 60),
    OLLAMA("Ollama Local", ApiMode.OPENAI_COMPATIBLE,
            "http://localhost:11434/v1", "qwen2.5:7b", false,
            "https://ollama.com/download", 600);

    private final String displayName;
    private final ApiMode apiMode;
    private final String defaultBaseUrl;
    private final String defaultModel;
    private final boolean requiresApiKey;
    private final String keyUrl;
    private final int defaultRequestsPerMinute;

    ProviderType(String displayName, ApiMode apiMode, String defaultBaseUrl, String defaultModel,
                 boolean requiresApiKey, String keyUrl, int defaultRequestsPerMinute) {
        this.displayName = displayName;
        this.apiMode = apiMode;
        this.defaultBaseUrl = defaultBaseUrl;
        this.defaultModel = defaultModel;
        this.requiresApiKey = requiresApiKey;
        this.keyUrl = keyUrl;
        this.defaultRequestsPerMinute = defaultRequestsPerMinute;
    }

    public String displayName() {
        return displayName;
    }

    public ApiMode apiMode() {
        return apiMode;
    }

    public String defaultBaseUrl() {
        return defaultBaseUrl;
    }

    public String defaultModel() {
        return defaultModel;
    }

    public boolean requiresApiKey() {
        return requiresApiKey;
    }

    public String keyUrl() {
        return keyUrl;
    }

    /**
     * Starting rate of the automatic limit: what the provider's entry-level account (free tier or first
     * paid tier) accepts. The {@link com.ftbtranslator.core.api.RateLimiter} then adapts it.
     */
    public int defaultRequestsPerMinute() {
        return defaultRequestsPerMinute;
    }

    public ProviderType next() {
        ProviderType[] all = values();
        return all[(ordinal() + 1) % all.length];
    }

    /** Lenient lookup accepting enum names and common aliases ("google", "local"...). */
    public static ProviderType parse(String raw) {
        if (raw == null) {
            return null;
        }
        String s = raw.trim().toUpperCase(java.util.Locale.ROOT).replace('-', '_').replace(' ', '_');
        for (ProviderType type : values()) {
            if (type.name().equals(s)) {
                return type;
            }
        }
        return switch (s) {
            case "GOOGLE", "AISTUDIO", "GEMINI_STUDIO" -> GEMINI;
            case "LOCAL", "OLLAMA_LOCAL" -> OLLAMA;
            case "ANTHROPIC", "ANTHROPIC_CLAUDE", "OPUS", "SONNET", "HAIKU" -> CLAUDE;
            case "OPENAI_COMPATIBLE", "CHATGPT", "GPT" -> OPENAI;
            default -> null;
        };
    }
}
