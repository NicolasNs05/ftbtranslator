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

    static {
        ModConfigSpec.Builder b = new ModConfigSpec.Builder();

        b.comment("Configurações de Provedor de IA e Rede").push("provider");
        PROVIDER = b.comment("Provedor de IA: GEMINI, GROQ, OPENROUTER, OPENAI ou OLLAMA")
                .defineEnum("provider", ProviderType.GEMINI);
        API_KEY = b.comment("Chave de API do provedor selecionado (vazio para Ollama local)")
                .define("apiKey", "");
        MODEL = b.comment("Modelo de IA (deixe vazio para usar o padrão do provedor com auto-fallback)")
                .define("model", "");
        BASE_URL = b.comment("Endpoint HTTP base (deixe vazio para usar o padrão do provedor)")
                .define("baseUrl", "");
        REQUESTS_PER_MINUTE = b.comment("Limite de requisições por minuto (15 recomendado para o nível gratuito do Gemini)")
                .defineInRange("requestsPerMinute", 15, 1, 600);
        BATCH_SIZE = b.comment("Quantidade máxima de textos enviados por lote")
                .defineInRange("batchSize", 35, 1, 200);
        MAX_BATCH_CHARS = b.comment("Tamanho máximo aproximado em caracteres por lote")
                .defineInRange("maxBatchChars", 4500, 500, 30000);
        MAX_RETRIES = b.comment("Tentativas máximas com Exponential Backoff em caso de HTTP 429/503")
                .defineInRange("maxRetries", 4, 1, 10);
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
        return data;
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
        REQUESTS_PER_MINUTE.set(data.getRequestsPerMinute());
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
        SPEC.save();
    }
}
