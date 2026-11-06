package com.ftbtranslator.core;

import com.ftbtranslator.core.api.GeminiTranslationClient;
import com.ftbtranslator.core.api.RateLimiter;
import com.ftbtranslator.core.bundle.TranslationBundleManager;
import com.ftbtranslator.core.cache.TranslationCache;
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
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
            assertEquals("[PT-BR] Predator Unique Skill", cache.get("Predator Unique Skill"));

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

    private static void writeZipText(ZipOutputStream zos, String name, String content) throws IOException {
        ZipEntry entry = new ZipEntry(name);
        zos.putNextEntry(entry);
        zos.write(content.getBytes(StandardCharsets.UTF_8));
        zos.closeEntry();
    }
}
