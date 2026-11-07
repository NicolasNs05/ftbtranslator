package com.ftbtranslator.neoforge;

import com.ftbtranslator.core.ContextPreset;
import com.ftbtranslator.core.GamePaths;
import com.ftbtranslator.core.ProviderType;
import com.ftbtranslator.core.TranslatorConfigData;
import net.neoforged.neoforge.common.ModConfigSpec;

import java.util.List;

/**
 * NeoForge client configuration ({@code config/ftbtranslator-client.toml}) bridged to the pure-Java
 * {@link TranslatorConfigData}.
 */
public final class ClientConfig {

    public static final ModConfigSpec SPEC;

    public static final ModConfigSpec.EnumValue<ProviderType> PROVIDER;
    public static final ModConfigSpec.ConfigValue<String> API_KEY;
    public static final ModConfigSpec.ConfigValue<String> MODEL;
    public static final ModConfigSpec.ConfigValue<String> BASE_URL;
    public static final ModConfigSpec.IntValue REQUESTS_PER_MINUTE;
    public static final ModConfigSpec.BooleanValue AUTO_RATE_LIMIT;
    public static final ModConfigSpec.ConfigValue<String> CLAUDE_EFFORT;
    public static final ModConfigSpec.IntValue BATCH_SIZE;
    public static final ModConfigSpec.IntValue MAX_BATCH_CHARS;
    public static final ModConfigSpec.IntValue MAX_RETRIES;
    public static final ModConfigSpec.IntValue REQUEST_TIMEOUT_SECONDS;

    public static final ModConfigSpec.EnumValue<ContextPreset> CONTEXT_PRESET;
    public static final ModConfigSpec.BooleanValue CONTEXT_MANUALLY_SET;
    public static final ModConfigSpec.ConfigValue<String> CUSTOM_CONTEXT;

    public static final ModConfigSpec.BooleanValue FTB_QUESTS_ENABLED;
    public static final ModConfigSpec.BooleanValue KUBEJS_ENABLED;
    public static final ModConfigSpec.BooleanValue MODS_ENABLED;
    public static final ModConfigSpec.BooleanValue PATCHOULI_ENABLED;
    public static final ModConfigSpec.ConfigValue<String> MOD_FILTER;
    public static final ModConfigSpec.BooleanValue SKIP_ALREADY_TRANSLATED_MODS;
    public static final ModConfigSpec.ConfigValue<String> TARGET_LANGUAGE;
    public static final ModConfigSpec.ConfigValue<List<? extends String>> SOURCE_LANGUAGES;

    public static final ModConfigSpec.BooleanValue USE_OFFICIAL_GLOSSARY;
    public static final ModConfigSpec.BooleanValue SHOW_HUD_BAR;

    static {
        ModConfigSpec.Builder b = new ModConfigSpec.Builder();

        b.comment("Configurações de Provedor de IA e Rede").push("provider");
        PROVIDER = b.comment("Provedor de IA: GEMINI, CLAUDE, GROQ, OPENROUTER, OPENAI ou OLLAMA")
                .defineEnum("provider", ProviderType.GEMINI);
        API_KEY = b.comment("Chave de API do provedor selecionado (vazio para Ollama local)")
                .define("apiKey", "");
        MODEL = b.comment("Modelo de IA (deixe vazio para usar o padrão do provedor com auto-fallback)")
                .define("model", "");
        BASE_URL = b.comment("Endpoint HTTP base (deixe vazio para usar o padrão do provedor)")
                .define("baseUrl", "");
        AUTO_RATE_LIMIT = b.comment("Limite automático: começa no padrão do provedor e se ajusta à sua conta "
                        + "(sobe enquanto não há erros, cai pela metade a cada HTTP 429). false = usa requestsPerMinute")
                .define("autoRateLimit", true);
        REQUESTS_PER_MINUTE = b.comment("Limite manual de requisições por minuto (usado quando autoRateLimit = false)")
                .defineInRange("requestsPerMinute", 15, 1, 600);
        BATCH_SIZE = b.comment("Quantidade máxima de textos enviados por lote")
                .defineInRange("batchSize", 35, 1, 200);
        MAX_BATCH_CHARS = b.comment("Tamanho máximo aproximado em caracteres por lote")
                .defineInRange("maxBatchChars", 4500, 500, 30000);
        MAX_RETRIES = b.comment("Tentativas máximas com Exponential Backoff em caso de HTTP 429/503")
                .defineInRange("maxRetries", 4, 1, 10);
        CLAUDE_EFFORT = b.comment("Só para o Claude: quanto o modelo raciocina antes de responder (low, medium, high, "
                        + "xhigh, max). O raciocínio é cobrado como tokens de saída: low gasta menos e costuma bastar "
                        + "para tradução; compare na linha de Tokens da aba Início.")
                .define("claudeEffort", "medium",
                        o -> o instanceof String s && TranslatorConfigData.CLAUDE_EFFORT_LEVELS.contains(s));
        REQUEST_TIMEOUT_SECONDS = b.comment("Timeout em segundos de cada requisição HTTP")
                .defineInRange("requestTimeoutSeconds", 75, 15, 600);
        b.pop();

        b.comment("Contexto Temático do System Prompt").push("context");
        CONTEXT_PRESET = b.comment("Perfil de contexto: DEFAULT (Padrão), TENSURA, TECH, MAGIC ou CUSTOM")
                .defineEnum("contextPreset", ContextPreset.DEFAULT);
        CONTEXT_MANUALLY_SET = b.comment("Indica se o usuário escolheu manualmente o contexto (false = auto-detecta Tensura)")
                .define("contextManuallySet", false);
        CUSTOM_CONTEXT = b.comment("Prompt de contexto personalizado quando contextPreset = CUSTOM")
                .define("customContext", "");
        b.pop();

        b.comment("Escopo e Filtros de Tradução").push("scopes");
        FTB_QUESTS_ENABLED = b.comment("Traduzir FTB Quests (lang/*.snbt + capítulos hardcoded)")
                .define("ftbQuestsEnabled", true);
        KUBEJS_ENABLED = b.comment("Traduzir KubeJS (kubejs/assets/*/lang/)")
                .define("kubeJsEnabled", true);
        MODS_ENABLED = b.comment("Extrair e traduzir arquivos de idioma dos mods (.jar)")
                .define("modsEnabled", true);
        PATCHOULI_ENABLED = b.comment("Traduzir livros de guia do Patchouli")
                .define("patchouliEnabled", true);
        MOD_FILTER = b.comment("Filtro opcional de mods separado por vírgula (ex: 'tensura, manas'; vazio = todos)")
                .define("modFilter", "");
        SKIP_ALREADY_TRANSLATED_MODS = b.comment("Delta Translation: traduzir apenas chaves ausentes no pt_br.json original do .jar")
                .define("skipAlreadyTranslatedMods", true);
        TARGET_LANGUAGE = b.comment("Código do idioma de destino")
                .define("targetLanguage", "pt_br");
        SOURCE_LANGUAGES = b.comment("Ordem de prioridade dos idiomas de origem")
                .defineListAllowEmpty("sourceLanguages",
                        TranslatorConfigData.DEFAULT_SOURCE_LANGUAGES,
                        () -> "en_us",
                        o -> o instanceof String s && !s.isBlank());
        b.pop();

        b.comment("Interface").push("interface");
        SHOW_HUD_BAR = b.comment("Mostrar a barra de progresso da tradução no topo da tela, no estilo da barra de chefe")
                .define("showHudProgressBar", true);
        b.pop();

        b.comment("Glossário Oficial").push("glossary");
        USE_OFFICIAL_GLOSSARY = b.comment("Reaproveitar as traduções pt_br que os próprios mods já trazem: textos "
                        + "idênticos são resolvidos sem IA e os termos oficiais entram no prompt")
                .define("useOfficialGlossary", true);
        b.pop();

        SPEC = b.build();
    }

    private ClientConfig() {
    }

    public static TranslatorConfigData toCoreData() {
        return toCoreData(FtbTranslatorMod.service().paths());
    }

    public static TranslatorConfigData toCoreData(GamePaths paths) {
        TranslatorConfigData data = new TranslatorConfigData();
        boolean hasTensura = paths != null && paths.containsTensuraMod();
        if (!SPEC.isLoaded()) {
            data.setContextPreset(hasTensura ? ContextPreset.TENSURA : ContextPreset.DEFAULT);
            return data;
        }
        data.setProvider(PROVIDER.get());
        data.setApiKey(API_KEY.get());
        data.setModel(MODEL.get());
        data.setBaseUrl(BASE_URL.get());
        data.setRequestsPerMinute(REQUESTS_PER_MINUTE.get());
        data.setAutoRateLimit(AUTO_RATE_LIMIT.get());
        data.setClaudeEffort(CLAUDE_EFFORT.get());
        data.setBatchSize(BATCH_SIZE.get());
        data.setMaxBatchChars(MAX_BATCH_CHARS.get());
        data.setMaxRetries(MAX_RETRIES.get());
        data.setRequestTimeoutSeconds(REQUEST_TIMEOUT_SECONDS.get());

        if (CONTEXT_MANUALLY_SET.get()) {
            data.setContextPreset(CONTEXT_PRESET.get());
        } else {
            ContextPreset autoPreset = hasTensura ? ContextPreset.TENSURA : ContextPreset.DEFAULT;
            data.setContextPreset(autoPreset);
            if (CONTEXT_PRESET.get() != autoPreset) {
                CONTEXT_PRESET.set(autoPreset);
                SPEC.save();
            }
        }

        data.setCustomContext(CUSTOM_CONTEXT.get());
        data.setFtbQuestsEnabled(FTB_QUESTS_ENABLED.get());
        data.setKubeJsEnabled(KUBEJS_ENABLED.get());
        data.setModsEnabled(MODS_ENABLED.get());
        data.setPatchouliEnabled(PATCHOULI_ENABLED.get());
        data.setModFilter(MOD_FILTER.get());
        data.setSkipAlreadyTranslatedMods(SKIP_ALREADY_TRANSLATED_MODS.get());
        data.setTargetLanguage(TARGET_LANGUAGE.get());
        List<String> srcLangs = SOURCE_LANGUAGES.get().stream().map(String::valueOf).toList();
        data.setSourceLanguages(srcLangs);
        data.setUseOfficialGlossary(USE_OFFICIAL_GLOSSARY.get());
        return data;
    }

    /** Whether the boss-bar style progress bar is drawn on the HUD (true when the config is not loaded yet). */
    public static boolean showHudBar() {
        return !SPEC.isLoaded() || SHOW_HUD_BAR.get();
    }

    public static void setShowHudBar(boolean show) {
        if (SPEC.isLoaded()) {
            SHOW_HUD_BAR.set(show);
            SPEC.save();
        }
    }

    public static void markContextManuallySet() {
        if (SPEC.isLoaded()) {
            CONTEXT_MANUALLY_SET.set(true);
        }
    }

    public static void saveFromCoreData(TranslatorConfigData data) {
        if (!SPEC.isLoaded()) {
            return;
        }
        PROVIDER.set(data.getProvider());
        API_KEY.set(data.getApiKey());
        MODEL.set(data.getModel());
        BASE_URL.set(data.getBaseUrl());
        REQUESTS_PER_MINUTE.set(Math.max(1, Math.min(600, data.getRequestsPerMinute())));
        AUTO_RATE_LIMIT.set(data.isAutoRateLimit());
        CLAUDE_EFFORT.set(data.getClaudeEffort());
        BATCH_SIZE.set(data.getBatchSize());
        MAX_BATCH_CHARS.set(data.getMaxBatchChars());
        MAX_RETRIES.set(data.getMaxRetries());
        REQUEST_TIMEOUT_SECONDS.set(data.getRequestTimeoutSeconds());
        CONTEXT_PRESET.set(data.getContextPreset());
        CUSTOM_CONTEXT.set(data.getCustomContext());
        FTB_QUESTS_ENABLED.set(data.isFtbQuestsEnabled());
        KUBEJS_ENABLED.set(data.isKubeJsEnabled());
        MODS_ENABLED.set(data.isModsEnabled());
        PATCHOULI_ENABLED.set(data.isPatchouliEnabled());
        MOD_FILTER.set(data.getModFilter());
        SKIP_ALREADY_TRANSLATED_MODS.set(data.isSkipAlreadyTranslatedMods());
        TARGET_LANGUAGE.set(data.getTargetLanguage());
        SOURCE_LANGUAGES.set(data.getSourceLanguages());
        USE_OFFICIAL_GLOSSARY.set(data.isUseOfficialGlossary());
        SPEC.save();
    }
}
