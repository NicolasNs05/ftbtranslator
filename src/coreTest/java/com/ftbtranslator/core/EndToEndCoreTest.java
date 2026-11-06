package com.ftbtranslator.core;

import com.ftbtranslator.core.api.GeminiTranslationClient;
import com.ftbtranslator.core.api.RateLimiter;
import com.ftbtranslator.core.bundle.TranslationBundleManager;
import com.ftbtranslator.core.cache.TranslationCache;
import com.ftbtranslator.core.cache.TranslationGlossary;
import com.ftbtranslator.core.pack.ResourcePackGenerator;
import com.ftbtranslator.core.parser.PatchouliTranslator;
import com.ftbtranslator.core.parser.SnbtParser;
import com.ftbtranslator.core.text.MaskedText;
import com.ftbtranslator.core.text.TokenMasker;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * End-to-end validation of the pure Java 21 {@code core} layer:
 * <ul>
 *   <li>Deterministic {@link TokenMasker} across FTB Quests tags, Patchouli macros, Java format specifiers and MC color/hex codes</li>
 *   <li>{@link SnbtParser} lang & hardcoded quest chapters (skipping {@code {translation.key}} references & escaping {@code \"})</li>
 *   <li>All 5 translation fronts + Delta Translation for partially-translated mod jars + Patchouli strict key whitelist</li>
 *   <li>Per-batch SHA-256 cache persistence + {@code pack.mcmeta} ({@code pack_format: 34})</li>
 *   <li>{@link TranslationBundleManager} export & import round-trip</li>
 *   <li>Automatic Tensura modpack detection + Gemini multi-model fallback on HTTP 503</li>
 *   <li>Native Anthropic Messages API client (headers, structured output, refusal, 529 + retry-after)</li>
 *   <li>Official mod translations reused as a glossary (exact match + prompt terms) and import guard</li>
 * </ul>
 */
class EndToEndCoreTest {

    @Test
    void tokenMaskerProtectsAndRestoresAllTechnicalPatterns() {
        TokenMasker masker = new TokenMasker();
        String original = "&6Welcome $(item)Great Sage$()! Deal %1$d damage (%s) {@pagebreak} {image:tensura:textures/gui/slime.png} &#FF00AA§aEnd";
        MaskedText masked = masker.mask(original);

        assertTrue(masked.hasTranslatableContent());
        assertFalse(masked.masked().contains("{@pagebreak}"));
        assertFalse(masked.masked().contains("$(item)"));
        assertFalse(masked.masked().contains("%1$d"));
        assertFalse(masked.masked().contains("&#FF00AA"));

        String simulatedAiOutput = masked.masked()
                .replace("Welcome", "Bem-vindo")
                .replace("Deal", "Cause")
                .replace("damage", "de dano")
                .replace("End", "Fim");

        String restored = masker.unmask(simulatedAiOutput, masked);
        assertNotNull(restored);
        assertEquals("&6Bem-vindo $(item)Great Sage$()! Cause %1$d de dano (%s) {@pagebreak} {image:tensura:textures/gui/slime.png} &#FF00AA§aFim", restored);

        // Dropping a placeholder must reject the translation (returns null)
        assertNull(masker.unmask("Texto sem os marcadores obrigatorios", masked));
    }

    @Test
    void tokenMaskerConvertsMultipleUnindexedPlaceholdersToPositionalAndSurvivesInversion() {
        TokenMasker masker = new TokenMasker();

        // Case 1: two unindexed %s placeholders where AI reverses grammatical order in Portuguese
        MaskedText maskedTwo = masker.mask("%s killed %s");
        assertEquals("__TK0__ killed __TK1__", maskedTwo.masked());
        assertEquals(List.of("%1$s", "%2$s"), maskedTwo.tokens());

        String invertedByAi = "__TK1__ foi derrotado por __TK0__";
        String restoredTwo = masker.unmask(invertedByAi, maskedTwo);
        assertEquals("%2$s foi derrotado por %1$s", restoredTwo);
        assertEquals("Goblin foi derrotado por Rimuru", String.format(restoredTwo, "Rimuru", "Goblin"));

        // Case 2: three mixed unindexed specifiers (%s, %d, %s) reordered by AI
        MaskedText maskedThree = masker.mask("%s gave %d items to %s");
        assertEquals("__TK0__ gave __TK1__ items to __TK2__", maskedThree.masked());
        assertEquals(List.of("%1$s", "%2$d", "%3$s"), maskedThree.tokens());

        String reorderedThree = "__TK2__ recebeu __TK1__ itens de __TK0__";
        String restoredThree = masker.unmask(reorderedThree, maskedThree);
        assertEquals("%3$s recebeu %2$d itens de %1$s", restoredThree);

        // Case 3: single unindexed placeholder remains unchanged (%d)
        MaskedText maskedSingle = masker.mask("Absorb %d enemies");
        assertEquals(List.of("%d"), maskedSingle.tokens());
    }

    @Test
    void defaultContextIsStandardAndAutoSwitchesToTensuraWhenTensuraModPresent(@TempDir Path tempRoot) throws IOException {
        TranslatorConfigData defaultCfg = new TranslatorConfigData();
        assertEquals(ContextPreset.DEFAULT, defaultCfg.getContextPreset());

        Path genericInstance = tempRoot.resolve("VanillaPlusModpack");
        Files.createDirectories(genericInstance.resolve("mods"));
        Files.writeString(genericInstance.resolve("mods/jei-1.21.1.jar"), "dummy");
        assertFalse(new GamePaths(genericInstance).containsTensuraMod());

        Path tensuraInstance = tempRoot.resolve("CustomPack");
        Files.createDirectories(tensuraInstance.resolve("mods"));
        Files.writeString(tensuraInstance.resolve("mods/tensura-neoforge-2.0.1.4.jar"), "dummy");
        assertTrue(new GamePaths(tensuraInstance).containsTensuraMod());
    }

    @Test
    void geminiClientAutomaticallyFallsBackWhenFirstModelReturns503AndHandlesThinkingConfig400() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1beta/models/", exchange -> {
            String path = exchange.getRequestURI().getPath();
            String reqBody = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            // Simulate first model in pool returning HTTP 503
            if (path.contains("gemini-2.5-flash:")) {
                byte[] err = "{\"error\":{\"code\":503,\"message\":\"This model is currently experiencing high demand.\"}}"
                        .getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(503, err.length);
                exchange.getResponseBody().write(err);
                exchange.close();
                return;
            }
            // Simulate second model rejecting thinkingConfig with HTTP 400 until retried without thinkingConfig
            if (path.contains("gemini-2.5-flash-lite") && reqBody.contains("thinkingConfig")) {
                byte[] err400 = "{\"error\":{\"code\":400,\"message\":\"thinkingConfig is not supported for this model\"}}"
                        .getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(400, err400.length);
                exchange.getResponseBody().write(err400);
                exchange.close();
                return;
            }
            byte[] ok = """
                    {
                      "candidates": [
                        {
                          "content": {
                            "parts": [
                              { "text": "{\\"0\\": \\"Olá Mundo\\"}" }
                            ]
                          }
                        }
                      ]
                    }
                    """.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, ok.length);
            exchange.getResponseBody().write(ok);
            exchange.close();
        });
        server.start();
        try {
            TranslatorConfigData cfg = new TranslatorConfigData();
            cfg.setProvider(ProviderType.GEMINI);
            cfg.setApiKey("test-key");
            cfg.setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort() + "/v1beta");
            cfg.setMaxRetries(1);
            cfg.setRequestsPerMinute(600);
            cfg.setAutoRateLimit(false);

            GeminiTranslationClient client = new GeminiTranslationClient(cfg, new RateLimiter(600), null);
            Map<String, String> batch = new LinkedHashMap<>();
            batch.put("0", "Hello World");
            Map<String, String> result = client.translateBatch(batch, "Translate to PT-BR", new CancellationToken());
            assertEquals("Olá Mundo", result.get("0"));
            assertTrue(client.isThinkingConfigDisabledFor("gemini-2.5-flash-lite"));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void translationCacheIsolatesContextsAndFlushesPeriodically(@TempDir Path tempRoot) throws IOException {
        Path cacheFile = tempRoot.resolve("cache_pt_br.json");
        TranslationCache cache = new TranslationCache(cacheFile, ContextPreset.DEFAULT, "");
        cache.put("Unique Skill", "Habilidade Única (Genérico)");
        assertEquals("Habilidade Única (Genérico)", cache.get("Unique Skill"));

        // Switching to TENSURA context must not return the generic DEFAULT cached value
        cache.setContext(ContextPreset.TENSURA, "");
        assertNull(cache.get("Unique Skill"));
        cache.put("Unique Skill", "Unique Skill (Tensura)");
        assertEquals("Unique Skill (Tensura)", cache.get("Unique Skill"));

        // Switching back to DEFAULT still preserves its own isolated entry
        cache.setContext(ContextPreset.DEFAULT, "");
        assertEquals("Habilidade Única (Genérico)", cache.get("Unique Skill"));

        // Periodic batch flush: before 10 batches (and < 30s), file is not yet written
        assertFalse(Files.exists(cacheFile));
        for (int i = 1; i < TranslationCache.FLUSH_BATCH_INTERVAL; i++) {
            assertFalse(cache.flushIfDueAfterBatch());
        }
        assertFalse(Files.exists(cacheFile));
        // On the 10th batch, flushIfDueAfterBatch writes atomically to disk
        assertTrue(cache.flushIfDueAfterBatch());
        assertTrue(Files.isRegularFile(cacheFile));
        assertEquals(0, cache.dirtyCount());
    }

    @Test
    void snbtParserIgnoresTranslationKeyReferencesAndEscapesInternalQuotes() {
        String chapterSnbt = """
                {
                    id: "0A1B2C3D"
                    title: "The Demon Lord's Awakening"
                    subtitle: "{ftbquests.chapter.awakening.subtitle}"
                    quests: [
                        {
                            id: "11223344"
                            title: "{ftbquests.quest.skip_me.title}"
                            subtitle: "Defeat the Orc Disaster"
                            description: [
                                "Use your \\"Unique Skill\\" wisely."
                                "{ftbquests.quest.skip_me.desc}"
                                "{@pagebreak}"
                                "Reward: 1000 Magicules"
                            ]
                            tasks: [
                                {
                                    id: "99887766"
                                    type: "item"
                                    item: "tensura:magic_ore"
                                }
                            ]
                        }
                    ]
                }
                """;

        List<SnbtParser.StringSpan> spans = SnbtParser.extractHardcodedQuestSpans(chapterSnbt);
        List<String> extracted = spans.stream().map(SnbtParser.StringSpan::unescapedValue).toList();

        assertTrue(extracted.contains("The Demon Lord's Awakening"));
        assertTrue(extracted.contains("Defeat the Orc Disaster"));
        assertTrue(extracted.contains("Use your \"Unique Skill\" wisely."));
        assertTrue(extracted.contains("Reward: 1000 Magicules"));
        assertFalse(extracted.contains("{ftbquests.chapter.awakening.subtitle}"));
        assertFalse(extracted.contains("{ftbquests.quest.skip_me.title}"));
        assertFalse(extracted.contains("{ftbquests.quest.skip_me.desc}"));
        assertFalse(extracted.contains("item"));
        assertFalse(extracted.contains("tensura:magic_ore"));

        String rewritten = SnbtParser.rewriteSpans(chapterSnbt, spans, s -> "[PT] " + s);
        assertTrue(rewritten.contains("title: \"[PT] The Demon Lord's Awakening\""));
        assertTrue(rewritten.contains("subtitle: \"{ftbquests.chapter.awakening.subtitle}\""));
        assertTrue(rewritten.contains("\"[PT] Use your \\\"Unique Skill\\\" wisely.\""));
    }

    @Test
    void patchouliStrictFilterNeverTouchesTechnicalKeys() {
        String rawEntry = """
                {
                  "name": "Magicule Condenser",
                  "category": "tensura:machines",
                  "icon": "tensura:magicule_condenser",
                  "advancement": "tensura:root",
                  "flag": "mod:loaded",
                  "pages": [
                    {
                      "type": "patchouli:text",
                      "title": "Condensing Magicules",
                      "text": "Connect $(l)pipes$() to collect magicules."
                    },
                    {
                      "type": "patchouli:crafting",
                      "recipe": "tensura:condenser_recipe",
                      "item": "tensura:magicule_condenser",
                      "header": "Crafting Guide",
                      "text": "Requires 4 Magic Steel."
                    }
                  ]
                }
                """;
        JsonObject source = JsonParser.parseString(rawEntry).getAsJsonObject();
        JsonObject translated = PatchouliTranslator.translatePatchouliObject(source, s -> "PT:" + s);

        assertEquals("PT:Magicule Condenser", translated.get("name").getAsString());
        JsonObject page0 = translated.getAsJsonArray("pages").get(0).getAsJsonObject();
        assertEquals("PT:Condensing Magicules", page0.get("title").getAsString());
        assertEquals("PT:Connect $(l)pipes$() to collect magicules.", page0.get("text").getAsString());

        JsonObject page1 = translated.getAsJsonArray("pages").get(1).getAsJsonObject();
        assertEquals("PT:Crafting Guide", page1.get("header").getAsString());
        assertEquals("PT:Requires 4 Magic Steel.", page1.get("text").getAsString());

        assertEquals("tensura:machines", translated.get("category").getAsString());
        assertEquals("tensura:magicule_condenser", translated.get("icon").getAsString());
        assertEquals("tensura:root", translated.get("advancement").getAsString());
        assertEquals("mod:loaded", translated.get("flag").getAsString());
        assertEquals("patchouli:text", page0.get("type").getAsString());
        assertEquals("patchouli:crafting", page1.get("type").getAsString());
        assertEquals("tensura:condenser_recipe", page1.get("recipe").getAsString());
        assertEquals("tensura:magicule_condenser", page1.get("item").getAsString());
    }

    @Test
    void fullPipelineAcrossAllFiveFrontsAndBundleExportImport(@TempDir Path tempRoot) throws Exception {
        Path instanceA = tempRoot.resolve("instanceA");
        Files.createDirectories(instanceA);

        Path questsLangDir = instanceA.resolve("config/ftbquests/quests/lang");
        Files.createDirectories(questsLangDir);
        Files.writeString(questsLangDir.resolve("en_us.snbt"), """
                {
                    quest.slime.title: "Reincarnated as a Slime"
                    quest.slime.desc: ["Absorb %d enemies", "{@pagebreak}", "Unlock Great Sage"]
                }
                """, StandardCharsets.UTF_8);
        // Secondary language file (ja_jp.snbt) containing a key missing from en_us.snbt
        Files.writeString(questsLangDir.resolve("ja_jp.snbt"), """
                {
                    quest.slime.title: "Ignored because en_us has it"
                    quest.slime.secret_jp_only: "Awaken True Demon Lord"
                }
                """, StandardCharsets.UTF_8);

        Path chaptersDir = instanceA.resolve("config/ftbquests/quests/chapters");
        Files.createDirectories(chaptersDir);
        String originalChapter = """
                {
                    id: "ABCD"
                    title: "Prologue Chapter"
                    subtitle: "{quest.slime.title}"
                    description: ["Begin your journey in Tensura"]
                }
                """;
        Files.writeString(chaptersDir.resolve("prologue.snbt"), originalChapter, StandardCharsets.UTF_8);

        Path kubeLangDir = instanceA.resolve("kubejs/assets/kubejs/lang");
        Files.createDirectories(kubeLangDir);
        Files.writeString(kubeLangDir.resolve("en_us.json"), """
                {
                  "item.kubejs.magic_crystal": "Magic Crystal"
                }
                """, StandardCharsets.UTF_8);
        Files.writeString(kubeLangDir.resolve("zh_cn.json"), """
                {
                  "item.kubejs.magic_crystal": "Ignored",
                  "item.kubejs.dragon_core": "Dragon Core From ZH"
                }
                """, StandardCharsets.UTF_8);

        Path modsDir = instanceA.resolve("mods");
        Files.createDirectories(modsDir);
        Path sampleJar = modsDir.resolve("tensura_sample.jar");
        try (OutputStream fos = Files.newOutputStream(sampleJar);
             ZipOutputStream zos = new ZipOutputStream(fos)) {
            writeZipText(zos, "assets/tensura/lang/en_us.json", """
                    {
                      "item.tensura.already_done": "Already Translated Sword",
                      "item.tensura.new_skill": "Predator Unique Skill"
                    }
                    """);
            writeZipText(zos, "assets/tensura/lang/ja_jp.json", """
                    {
                      "item.tensura.new_skill": "Ignored",
                      "item.tensura.jp_exclusive_skill": "Void God Azathoth"
                    }
                    """);
            writeZipText(zos, "assets/tensura/lang/pt_br.json", """
                    {
                      "item.tensura.already_done": "Espada Já Traduzida Oficial"
                    }
                    """);
            writeZipText(zos, "assets/tensura/patchouli_books/guide/en_us/entries/skills.json", """
                    {
                      "name": "Skills Overview",
                      "category": "tensura:basics",
                      "icon": "tensura:skill_scroll",
                      "pages": [
                        {
                          "type": "patchouli:text",
                          "title": "Unique Skills",
                          "text": "Press $(k:tensura.activate) to activate."
                        }
                      ]
                    }
                    """);
            writeZipText(zos, "assets/tensura/patchouli_books/guide/ja_jp/entries/secret.json", """
                    {
                      "name": "Secret Arts",
                      "category": "tensura:basics",
                      "icon": "tensura:scroll",
                      "pages": [
                        {
                          "type": "patchouli:text",
                          "title": "Battlewill",
                          "text": "Master the aura."
                        }
                      ]
                    }
                    """);
        }

        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            String reqBody = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            JsonObject reqJson = JsonParser.parseString(reqBody).getAsJsonObject();
            String userContent = reqJson.getAsJsonArray("messages").get(1).getAsJsonObject().get("content").getAsString();
            JsonObject inputMap = JsonParser.parseString(userContent).getAsJsonObject();
            JsonObject translatedMap = new JsonObject();
            for (Map.Entry<String, JsonElement> e : inputMap.entrySet()) {
                translatedMap.addProperty(e.getKey(), "[PT-BR] " + e.getValue().getAsString());
            }
            JsonObject resp = new JsonObject();
            com.google.gson.JsonArray choices = new com.google.gson.JsonArray();
            JsonObject choice = new JsonObject();
            JsonObject msg = new JsonObject();
            msg.addProperty("role", "assistant");
            msg.addProperty("content", translatedMap.toString());
            choice.add("message", msg);
            choices.add(choice);
            resp.add("choices", choices);

            byte[] bytes = resp.toString().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();

        try {
            int port = server.getAddress().getPort();
            TranslatorConfigData cfg = new TranslatorConfigData();
            cfg.setProvider(ProviderType.OLLAMA);
            cfg.setBaseUrl("http://127.0.0.1:" + port + "/v1");
            cfg.setModel("mock-translator");
            cfg.setRequestsPerMinute(600);
            cfg.setAutoRateLimit(false);
            cfg.setContextPreset(ContextPreset.TENSURA);

            TranslationService service = new TranslationService(instanceA);
            TranslationReport report = service.runSynchronously(cfg, EnumSet.allOf(TranslationScope.class), null);

            assertTrue(report.success(), report.message());
            assertTrue(report.questsChanged());
            assertTrue(report.resourcePackChanged());
            assertEquals(6, report.tasksWritten());

            Path ptBrSnbt = questsLangDir.resolve("pt_br.snbt");
            assertTrue(Files.isRegularFile(ptBrSnbt));
            String ptBrSnbtText = Files.readString(ptBrSnbt, StandardCharsets.UTF_8);
            assertTrue(ptBrSnbtText.contains("[PT-BR] Reincarnated as a Slime"));
            assertTrue(ptBrSnbtText.contains("[PT-BR] Absorb %d enemies"));
            assertTrue(ptBrSnbtText.contains("[PT-BR] Awaken True Demon Lord"));

            Path backupChapter = instanceA.resolve("config/ftbquests/quests_backup_original/chapters/prologue.snbt");
            assertTrue(Files.isRegularFile(backupChapter));
            assertEquals(originalChapter, Files.readString(backupChapter, StandardCharsets.UTF_8));
            String translatedChapter = Files.readString(chaptersDir.resolve("prologue.snbt"), StandardCharsets.UTF_8);
            assertTrue(translatedChapter.contains("\"[PT-BR] Prologue Chapter\""));
            assertTrue(translatedChapter.contains("\"{quest.slime.title}\""));

            Path kubePtBr = kubeLangDir.resolve("pt_br.json");
            assertTrue(Files.isRegularFile(kubePtBr));
            String kubePtBrText = Files.readString(kubePtBr);
            assertTrue(kubePtBrText.contains("[PT-BR] Magic Crystal"));
            assertTrue(kubePtBrText.contains("[PT-BR] Dragon Core From ZH"));

            Path modPackLang = instanceA.resolve("resourcepacks/AutoTranslated_Pack_PTBR/assets/tensura/lang/pt_br.json");
            assertTrue(Files.isRegularFile(modPackLang));
            String modPackLangText = Files.readString(modPackLang, StandardCharsets.UTF_8);
            assertTrue(modPackLangText.contains("Espada Já Traduzida Oficial"));
            assertTrue(modPackLangText.contains("[PT-BR] Predator Unique Skill"));
            assertTrue(modPackLangText.contains("[PT-BR] Void God Azathoth"));

            Path packMcmeta = instanceA.resolve("resourcepacks/AutoTranslated_Pack_PTBR/pack.mcmeta");
            assertTrue(Files.isRegularFile(packMcmeta));
            JsonObject mcmetaObj = JsonParser.parseString(Files.readString(packMcmeta)).getAsJsonObject();
            assertEquals(ResourcePackGenerator.PACK_FORMAT_1_21_1,
                    mcmetaObj.getAsJsonObject("pack").get("pack_format").getAsInt());

            Path patchouliOut = instanceA.resolve(
                    "resourcepacks/AutoTranslated_Pack_PTBR/assets/tensura/patchouli_books/guide/pt_br/entries/skills.json");
            assertTrue(Files.isRegularFile(patchouliOut));
            JsonObject patchouliJson = JsonParser.parseString(Files.readString(patchouliOut)).getAsJsonObject();
            assertEquals("[PT-BR] Skills Overview", patchouliJson.get("name").getAsString());
            assertEquals("tensura:skill_scroll", patchouliJson.get("icon").getAsString());

            Path patchouliFallbackOut = instanceA.resolve(
                    "resourcepacks/AutoTranslated_Pack_PTBR/assets/tensura/patchouli_books/guide/pt_br/entries/secret.json");
            assertTrue(Files.isRegularFile(patchouliFallbackOut));
            JsonObject patchouliFallbackJson = JsonParser.parseString(Files.readString(patchouliFallbackOut)).getAsJsonObject();
            assertEquals("[PT-BR] Secret Arts", patchouliFallbackJson.get("name").getAsString());

            TranslationCache cache = new TranslationCache(service.paths().cacheFile("pt_br"), ContextPreset.TENSURA, "");
            cache.load();
            assertTrue(cache.size() >= 6);
            assertEquals("[PT-BR] Predator Unique Skill", cache.resolve("Predator Unique Skill", new TokenMasker()));

            TranslationBundleManager.ExportResult exported = service.exportBundle("pt_br");
            assertTrue(Files.isRegularFile(exported.zipPath()));

            Path instanceB = tempRoot.resolve("instanceB");
            Files.createDirectories(instanceB.resolve("exports"));
            Files.copy(exported.zipPath(), instanceB.resolve("exports").resolve(TranslationBundleManager.DEFAULT_BUNDLE_FILENAME));

            TranslationService serviceB = new TranslationService(instanceB);
            TranslationBundleManager.ImportResult imported = serviceB.importBundle("pt_br");
            assertTrue(imported.filesExtracted() >= 6);
            assertTrue(imported.questsTouched());
            assertTrue(imported.resourcePackTouched());
            assertTrue(Files.isRegularFile(
                    instanceB.resolve("resourcepacks/AutoTranslated_Pack_PTBR/assets/tensura/lang/pt_br.json")));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void glossaryVotesSkipsUntranslatedCopiesAndFindsTermsInsideTexts() {
        TranslationGlossary glossary = new TranslationGlossary();

        // Two mods agree on "Energia", one disagrees: majority wins.
        assertEquals(2, glossary.addLangPair(
                Map.of("a", "Energy", "b", "Iron Plate"),
                Map.of("a", "Energia", "b", "Placa de Ferro")));
        glossary.addLangPair(Map.of("a", "Energy", "x", "Copper"), Map.of("a", "Energia", "x", "Cobre"));
        glossary.addLangPair(Map.of("a", "Energy", "x", "Tin"), Map.of("a", "Energía", "x", "Estanho"));
        assertEquals("Energia", glossary.lookup("Energy"));
        assertEquals("Placa de Ferro", glossary.lookup("Iron Plate"));
        assertNull(glossary.lookup("Unknown"));

        // A pt_br file that is just a copy of en_us is not a human translation: ignored entirely.
        assertEquals(0, glossary.addLangPair(
                Map.of("a", "Steel Gear", "b", "Steel Rod", "c", "Gold"),
                Map.of("a", "Steel Gear", "b", "Steel Rod", "c", "Ouro")));
        assertNull(glossary.lookup("Gold"));

        // Terms are found as whole words, case-insensitive, never as the whole text itself.
        Map<String, String> terms = glossary.termsFor(
                List.of("Craft an iron plate using __TK0__ energy", "Iron Plate", "Ironplated"), 10);
        assertEquals(Map.of("Iron Plate", "Placa de Ferro", "Energy", "Energia"), terms);
        assertEquals(List.of("Iron Plate", "Energy"), new ArrayList<>(terms.keySet()));
        assertEquals(1, glossary.termsFor(List.of("iron plate and energy"), 1).size());
    }

    @Test
    void officialModTranslationsResolveOtherFrontsWithoutAiAndFeedThePrompt(@TempDir Path tempRoot) throws Exception {
        Path instance = tempRoot.resolve("instance");
        Path questsLangDir = instance.resolve("config/ftbquests/quests/lang");
        Files.createDirectories(questsLangDir);
        Files.writeString(questsLangDir.resolve("en_us.snbt"), """
                {
                    quest.power.title: "Energy"
                    quest.power.desc: "Craft an Iron Plate"
                    quest.power.stored: "Stored: %s FE"
                }
                """, StandardCharsets.UTF_8);

        Path modsDir = instance.resolve("mods");
        Files.createDirectories(modsDir);
        try (OutputStream fos = Files.newOutputStream(modsDir.resolve("techmod.jar"));
             ZipOutputStream zos = new ZipOutputStream(fos)) {
            writeZipText(zos, "assets/techmod/lang/en_us.json", """
                    {
                      "gui.techmod.energy": "Energy",
                      "item.techmod.iron_plate": "Iron Plate",
                      "gui.techmod.stored": "Stored: %s FE"
                    }
                    """);
            // Official human translation; the "stored" one dropped its %s and must not be trusted.
            writeZipText(zos, "assets/techmod/lang/pt_br.json", """
                    {
                      "gui.techmod.energy": "Energia",
                      "item.techmod.iron_plate": "Placa de Ferro",
                      "gui.techmod.stored": "Armazenado FE"
                    }
                    """);
        }

        List<String> systemPrompts = Collections.synchronizedList(new ArrayList<>());
        List<String> sentTexts = Collections.synchronizedList(new ArrayList<>());
        HttpServer server = mockOpenAiServer(systemPrompts, sentTexts, null);
        server.start();
        try {
            TranslatorConfigData cfg = mockConfig(server);
            TranslationService service = new TranslationService(instance);
            TranslationReport report = service.runSynchronously(
                    cfg, EnumSet.of(TranslationScope.FTBQUESTS), null);
            assertTrue(report.success(), report.message());

            String ptBr = Files.readString(questsLangDir.resolve("pt_br.snbt"), StandardCharsets.UTF_8);
            assertTrue(ptBr.contains("\"Energia\""), ptBr);
            assertTrue(ptBr.contains("[PT-BR] Craft an Iron Plate"), ptBr);
            assertTrue(ptBr.contains("[PT-BR] Stored: %s FE"), ptBr);

            // "Energy" was resolved by the glossary: never sent to the AI.
            assertFalse(sentTexts.contains("Energy"), sentTexts.toString());
            assertTrue(sentTexts.contains("Craft an Iron Plate"), sentTexts.toString());

            // The human term reached the prompt of the batch that mentions it.
            assertFalse(systemPrompts.isEmpty());
            assertTrue(systemPrompts.stream().anyMatch(p -> p.contains("Iron Plate = Placa de Ferro")),
                    systemPrompts.toString());

            // Dashboard metrics: the glossary hit is reported separately from cache and AI.
            ProgressSnapshot done = service.latestSnapshot();
            assertEquals(1, done.stringsFromGlossary());
            assertTrue(done.stringsFromCache() >= done.stringsFromGlossary());
            assertEquals(2, done.stringsTranslatedByAi());

            // With the glossary disabled, the same text goes to the AI.
            Files.delete(questsLangDir.resolve("pt_br.snbt"));
            Files.delete(service.paths().cacheFile("pt_br"));
            sentTexts.clear();
            cfg.setUseOfficialGlossary(false);
            assertTrue(service.runSynchronously(cfg, EnumSet.of(TranslationScope.FTBQUESTS), null).success());
            assertTrue(sentTexts.contains("Energy"), sentTexts.toString());
            assertEquals(0, service.latestSnapshot().stringsFromGlossary());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void importBundleIsRefusedWhileTranslationIsRunning(@TempDir Path tempRoot) throws Exception {
        Path instance = tempRoot.resolve("instance");
        Path kubeLangDir = instance.resolve("kubejs/assets/kubejs/lang");
        Files.createDirectories(kubeLangDir);
        Files.writeString(kubeLangDir.resolve("en_us.json"), """
                { "item.kubejs.gem": "Shiny Gem" }
                """, StandardCharsets.UTF_8);

        CountDownLatch requestArrived = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        HttpServer server = mockOpenAiServer(
                Collections.synchronizedList(new ArrayList<>()),
                Collections.synchronizedList(new ArrayList<>()),
                () -> {
                    requestArrived.countDown();
                    try {
                        release.await(10, TimeUnit.SECONDS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                });
        server.start();
        try {
            TranslationService service = new TranslationService(instance);
            CompletableFuture<TranslationReport> run = service.startAsync(
                    mockConfig(server), EnumSet.of(TranslationScope.KUBEJS), null);
            assertTrue(requestArrived.await(10, TimeUnit.SECONDS), "translation never reached the AI");

            IOException refused = assertThrows(IOException.class, () -> service.importBundle("pt_br"));
            assertTrue(refused.getMessage().contains("tradução está em andamento"), refused.getMessage());

            release.countDown();
            assertTrue(run.get(10, TimeUnit.SECONDS).success());

            // Once the run is over the guard is released (missing bundle is the expected error now).
            IOException afterRun = assertThrows(IOException.class, () -> service.importBundle("pt_br"));
            assertFalse(afterRun.getMessage().contains("tradução está em andamento"), afterRun.getMessage());
        } finally {
            release.countDown();
            server.stop(0);
        }
    }

    @Test
    void cacheStoresOnlyTemplatesAndStillReadsLegacyEntries(@TempDir Path tempRoot) throws Exception {
        Path instance = tempRoot.resolve("instance");
        Path kubeLangDir = instance.resolve("kubejs/assets/kubejs/lang");
        Files.createDirectories(kubeLangDir);
        Files.writeString(kubeLangDir.resolve("en_us.json"), """
                {
                  "item.kubejs.ingot_gold": "&6Iron Ingot",
                  "item.kubejs.ingot_red": "&cIron Ingot",
                  "item.kubejs.plain": "Magic Dust"
                }
                """, StandardCharsets.UTF_8);

        List<String> sentTexts = Collections.synchronizedList(new ArrayList<>());
        HttpServer server = mockOpenAiServer(Collections.synchronizedList(new ArrayList<>()), sentTexts, null);
        server.start();
        try {
            TranslationService service = new TranslationService(instance);
            assertTrue(service.runSynchronously(mockConfig(server), EnumSet.of(TranslationScope.KUBEJS), null)
                    .success());
            // Two colors of the same sentence share one template: one AI text for them.
            assertEquals(List.of("Magic Dust", "__TK0__Iron Ingot"), sentTexts.stream().sorted().toList());

            TokenMasker masker = new TokenMasker();
            TranslationCache cache = new TranslationCache(service.paths().cacheFile("pt_br"));
            cache.load();
            assertEquals(2, cache.size(), "one entry per template, no duplicate per original text");
            assertNull(cache.get("&6Iron Ingot"));
            assertEquals("[PT-BR] &6Iron Ingot", cache.resolve("&6Iron Ingot", masker));
            assertEquals("[PT-BR] &cIron Ingot", cache.resolve("&cIron Ingot", masker));
            assertEquals("[PT-BR] §aIron Ingot", cache.resolve("§aIron Ingot", masker));
            assertEquals("[PT-BR] Magic Dust", cache.resolve("Magic Dust", masker));

            // Caches written by older versions (one entry per original text) keep working.
            cache.put("Legacy Sword", "Espada Antiga");
            assertEquals("Espada Antiga", cache.resolve("Legacy Sword", masker));

            // A second run is fully served by the cache: nothing new reaches the AI.
            sentTexts.clear();
            Files.delete(kubeLangDir.resolve("pt_br.json"));
            assertTrue(service.runSynchronously(mockConfig(server), EnumSet.of(TranslationScope.KUBEJS), null)
                    .success());
            assertTrue(sentTexts.isEmpty(), sentTexts.toString());
            String ptBr = Files.readString(kubeLangDir.resolve("pt_br.json"), StandardCharsets.UTF_8);
            assertTrue(ptBr.contains("[PT-BR] &cIron Ingot"), ptBr);
        } finally {
            server.stop(0);
        }
    }

    @Test
    void kubeJsForeignOverrideBridgesTemplateOnlyCacheToModJar(@TempDir Path tempRoot) throws Exception {
        Path instance = tempRoot.resolve("instance");
        Path modsDir = instance.resolve("mods");
        Files.createDirectories(modsDir);
        try (OutputStream fos = Files.newOutputStream(modsDir.resolve("gems.jar"));
             ZipOutputStream zos = new ZipOutputStream(fos)) {
            writeZipText(zos, "assets/gems/lang/en_us.json", """
                    { "item.gems.ruby": "Ruby Shard" }
                    """);
        }
        // KubeJS override in another language for the same mod key (no English kubejs file).
        Path kubeLangDir = instance.resolve("kubejs/assets/gems/lang");
        Files.createDirectories(kubeLangDir);
        Files.writeString(kubeLangDir.resolve("zh_cn.json"), """
                { "item.gems.ruby": "&c红宝石碎片" }
                """, StandardCharsets.UTF_8);

        TranslationService service = new TranslationService(instance);
        TranslationCache seeded = new TranslationCache(service.paths().cacheFile("pt_br"));
        seeded.putTemplate("__TK0__红宝石碎片", "__TK0__Fragmento de Rubi");
        seeded.saveForce();

        List<String> sentTexts = Collections.synchronizedList(new ArrayList<>());
        HttpServer server = mockOpenAiServer(Collections.synchronizedList(new ArrayList<>()), sentTexts, null);
        server.start();
        try {
            assertTrue(service.runSynchronously(mockConfig(server),
                    EnumSet.of(TranslationScope.KUBEJS, TranslationScope.MODS), null).success());
            // The cached template translation is bridged to the jar: the English text is not re-translated.
            assertFalse(sentTexts.contains("Ruby Shard"), sentTexts.toString());
            String kubePtBr = Files.readString(kubeLangDir.resolve("pt_br.json"), StandardCharsets.UTF_8);
            assertTrue(kubePtBr.contains("&cFragmento de Rubi"), kubePtBr);
        } finally {
            server.stop(0);
        }
    }

    @Test
    void modFilterCounterMatchesJarNamesAndNamespacesLikeTheRun(@TempDir Path tempRoot) throws Exception {
        Path instance = tempRoot.resolve("instance");
        Path modsDir = instance.resolve("mods");
        Files.createDirectories(modsDir);
        try (ZipOutputStream zos = new ZipOutputStream(Files.newOutputStream(modsDir.resolve("tensura-2.0.jar")))) {
            writeZipText(zos, "assets/tensura/lang/en_us.json", "{}");
            writeZipText(zos, "assets/manascore/lang/en_us.json", "{}");
        }
        try (ZipOutputStream zos = new ZipOutputStream(Files.newOutputStream(modsDir.resolve("jei-19.jar")))) {
            writeZipText(zos, "assets/jei/lang/en_us.json", "{}");
        }
        Files.writeString(modsDir.resolve("broken.jar"), "not a zip");
        Files.writeString(modsDir.resolve("ftbtranslator-1.0.0.jar"), "self");

        List<com.ftbtranslator.core.parser.JarModExtractor.ModJarInfo> jars =
                com.ftbtranslator.core.parser.JarModExtractor.listModJars(new GamePaths(instance));
        assertEquals(3, jars.size(), "own jar is skipped, unreadable jar still listed");
        assertEquals(3, com.ftbtranslator.core.parser.JarModExtractor.countMatchingJars(jars, List.of()));
        assertEquals(1, com.ftbtranslator.core.parser.JarModExtractor.countMatchingJars(jars, List.of("manas")));
        assertEquals(2, com.ftbtranslator.core.parser.JarModExtractor.countMatchingJars(jars, List.of("tensura", "jei")));
        assertEquals(1, com.ftbtranslator.core.parser.JarModExtractor.countMatchingJars(jars, List.of("broken")));
        assertEquals(0, com.ftbtranslator.core.parser.JarModExtractor.countMatchingJars(jars, List.of("create")));
    }

    @Test
    void anthropicClientSendsNativeMessagesRequestAndParsesStructuredOutput() throws Exception {
        List<JsonObject> requests = Collections.synchronizedList(new ArrayList<>());
        List<Map<String, String>> requestHeaders = Collections.synchronizedList(new ArrayList<>());
        int[] calls = {0};
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/messages", exchange -> {
            calls[0]++;
            JsonObject req = JsonParser.parseString(new String(
                    exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)).getAsJsonObject();
            requests.add(req);
            Map<String, String> h = new LinkedHashMap<>();
            for (String name : List.of("x-api-key", "anthropic-version", "anthropic-beta")) {
                String v = exchange.getRequestHeaders().getFirst(name);
                if (v != null) {
                    h.put(name, v);
                }
            }
            requestHeaders.add(h);

            String body;
            int status;
            if (calls[0] == 1) {
                // First attempt: overloaded, must be retried after retry-after.
                status = 529;
                exchange.getResponseHeaders().add("retry-after", "0");
                body = "{\"type\":\"error\",\"error\":{\"type\":\"overloaded_error\",\"message\":\"Overloaded\"}}";
            } else {
                JsonObject input = JsonParser.parseString(
                        req.getAsJsonArray("messages").get(0).getAsJsonObject().get("content").getAsString())
                        .getAsJsonObject();
                com.google.gson.JsonArray items = new com.google.gson.JsonArray();
                for (Map.Entry<String, JsonElement> e : input.entrySet()) {
                    JsonObject item = new JsonObject();
                    item.addProperty("id", e.getKey());
                    item.addProperty("text", "[PT-BR] " + e.getValue().getAsString());
                    items.add(item);
                }
                JsonObject structured = new JsonObject();
                structured.add("translations", items);
                JsonObject thinking = new JsonObject();
                thinking.addProperty("type", "thinking");
                thinking.addProperty("thinking", "");
                JsonObject text = new JsonObject();
                text.addProperty("type", "text");
                text.addProperty("text", structured.toString());
                com.google.gson.JsonArray content = new com.google.gson.JsonArray();
                content.add(thinking);
                content.add(text);
                JsonObject resp = new JsonObject();
                resp.addProperty("type", "message");
                resp.addProperty("stop_reason", "end_turn");
                resp.add("content", content);
                status = 200;
                body = resp.toString();
            }
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        try {
            TranslatorConfigData cfg = new TranslatorConfigData();
            cfg.setProvider(ProviderType.CLAUDE);
            cfg.setApiKey("sk-ant-test");
            cfg.setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort() + "/v1");
            cfg.setRequestsPerMinute(600);
            cfg.setAutoRateLimit(false);
            assertEquals("claude-opus-5-5", cfg.effectiveModel());

            Map<String, String> batch = new LinkedHashMap<>();
            batch.put("0", "Iron Ingot");
            batch.put("1", "Hello __TK0__");
            Map<String, String> out = com.ftbtranslator.core.api.TranslationClientFactory.create(cfg, null)
                    .translateBatch(batch, "Traduza para pt-BR.", new CancellationToken());

            assertEquals(Map.of("0", "[PT-BR] Iron Ingot", "1", "[PT-BR] Hello __TK0__"), out);
            assertEquals(2, calls[0], "529 must be retried");

            Map<String, String> h = requestHeaders.get(1);
            assertEquals("sk-ant-test", h.get("x-api-key"));
            assertEquals("2026-10-21", h.get("anthropic-version"));
            assertEquals("server-side-fallback-2026-07-01", h.get("anthropic-beta"));

            JsonObject req = requests.get(1);
            assertEquals("claude-opus-5-5", req.get("model").getAsString());
            assertFalse(req.has("temperature"), "sampling params are rejected by this API version");
            assertEquals("default", req.get("fallbacks").getAsString());
            assertEquals("medium", req.getAsJsonObject("output_config").get("effort").getAsString());
            JsonObject format = req.getAsJsonObject("output_config").getAsJsonObject("format");
            assertEquals("json_schema", format.get("type").getAsString());
            assertFalse(format.getAsJsonObject("schema").get("additionalProperties").getAsBoolean());

            // Haiku: no server-side fallback (not offered for it).
            requests.clear();
            requestHeaders.clear();
            cfg.setModel("claude-haiku-5-5");
            com.ftbtranslator.core.api.TranslationClientFactory.create(cfg, null)
                    .translateBatch(Map.of("0", "Gold"), "Traduza.", new CancellationToken());
            assertFalse(requests.get(0).has("fallbacks"));
            assertNull(requestHeaders.get(0).get("anthropic-beta"));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void anthropicRefusalAndTruncationAreReportedAsErrors() {
        String refusal = "{\"stop_reason\":\"refusal\",\"stop_details\":{\"type\":\"refusal\",\"category\":\"cyber\"},"
                + "\"content\":[]}";
        IOException refused = assertThrows(IOException.class,
                () -> parseAnthropic(refusal));
        assertTrue(refused.getMessage().contains("recusou") && refused.getMessage().contains("cyber"),
                refused.getMessage());

        String truncated = "{\"stop_reason\":\"max_tokens\",\"content\":[{\"type\":\"text\",\"text\":\"{\\\"transl\"}]}";
        IOException cut = assertThrows(IOException.class, () -> parseAnthropic(truncated));
        assertTrue(cut.getMessage().contains("JSON"), "service splits batches whose error mentions JSON");
    }

    private static Map<String, String> parseAnthropic(String body) throws IOException {
        try {
            java.lang.reflect.Method m = com.ftbtranslator.core.api.AnthropicTranslationClient.class
                    .getDeclaredMethod("parseTranslations", String.class);
            m.setAccessible(true);
            @SuppressWarnings("unchecked")
            Map<String, String> r = (Map<String, String>) m.invoke(null, body);
            return r;
        } catch (java.lang.reflect.InvocationTargetException e) {
            if (e.getCause() instanceof IOException io) {
                throw io;
            }
            throw new RuntimeException(e.getCause());
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    void automaticRateLimitStartsPerProviderAndAdaptsToTheAccount() {
        TranslatorConfigData cfg = new TranslatorConfigData();
        assertTrue(cfg.isAutoRateLimit(), "automatic by default");
        cfg.setProvider(ProviderType.GEMINI);
        assertEquals(15, cfg.effectiveRequestsPerMinute());
        cfg.setProvider(ProviderType.CLAUDE);
        assertEquals(50, cfg.effectiveRequestsPerMinute());
        cfg.setAutoRateLimit(false);
        cfg.setRequestsPerMinute(7);
        assertEquals(7, cfg.effectiveRequestsPerMinute(), "manual value wins when automatic is off");

        com.ftbtranslator.core.api.RateLimiter auto = new com.ftbtranslator.core.api.RateLimiter(15, true, 30);
        // Grows ~20% after every 10 successes, never above the ceiling.
        int last = -1;
        for (int i = 0; i < 9; i++) {
            assertEquals(-1, auto.onSuccess(name -> null));
        }
        last = auto.onSuccess(name -> null);
        assertEquals(18, last);
        for (int i = 0; i < 200; i++) {
            auto.onSuccess(name -> null);
        }
        assertEquals(30, auto.requestsPerMinute());
        // A 429 halves it.
        assertEquals(15, auto.onRateLimited());
        assertEquals(4000L, auto.minIntervalMillis());
        // An account limit reported by the provider wins (90% of it) and stops blind growth.
        assertEquals(90, auto.onSuccess(name -> name.equals("anthropic-ratelimit-requests-limit") ? "100" : null));
        for (int i = 0; i < 50; i++) {
            auto.onSuccess(name -> null);
        }
        assertEquals(90, auto.requestsPerMinute());

        com.ftbtranslator.core.api.RateLimiter manual = new com.ftbtranslator.core.api.RateLimiter(15);
        for (int i = 0; i < 50; i++) {
            assertEquals(-1, manual.onSuccess(name -> "500"));
        }
        assertEquals(-1, manual.onRateLimited());
        assertEquals(15, manual.requestsPerMinute(), "manual mode never changes the player's value");
    }

    private static TranslatorConfigData mockConfig(HttpServer server) {
        TranslatorConfigData cfg = new TranslatorConfigData();
        cfg.setProvider(ProviderType.OLLAMA);
        cfg.setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort() + "/v1");
        cfg.setModel("mock-translator");
        cfg.setRequestsPerMinute(600);
        cfg.setAutoRateLimit(false);
        return cfg;
    }

    /** OpenAI-compatible mock that prefixes every value with "[PT-BR] " and records what it received. */
    private static HttpServer mockOpenAiServer(List<String> systemPrompts, List<String> sentTexts,
                                               Runnable beforeReply) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            String reqBody = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            com.google.gson.JsonArray messages = JsonParser.parseString(reqBody).getAsJsonObject()
                    .getAsJsonArray("messages");
            systemPrompts.add(messages.get(0).getAsJsonObject().get("content").getAsString());
            JsonObject inputMap = JsonParser.parseString(
                    messages.get(1).getAsJsonObject().get("content").getAsString()).getAsJsonObject();
            JsonObject translatedMap = new JsonObject();
            for (Map.Entry<String, JsonElement> e : inputMap.entrySet()) {
                sentTexts.add(e.getValue().getAsString());
                translatedMap.addProperty(e.getKey(), "[PT-BR] " + e.getValue().getAsString());
            }
            if (beforeReply != null) {
                beforeReply.run();
            }
            JsonObject msg = new JsonObject();
            msg.addProperty("role", "assistant");
            msg.addProperty("content", translatedMap.toString());
            JsonObject choice = new JsonObject();
            choice.add("message", msg);
            com.google.gson.JsonArray choices = new com.google.gson.JsonArray();
            choices.add(choice);
            JsonObject resp = new JsonObject();
            resp.add("choices", choices);
            byte[] bytes = resp.toString().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        return server;
    }

    private static void writeZipText(ZipOutputStream zos, String name, String content) throws IOException {
        ZipEntry entry = new ZipEntry(name);
        zos.putNextEntry(entry);
        zos.write(content.getBytes(StandardCharsets.UTF_8));
        zos.closeEntry();
    }
}
