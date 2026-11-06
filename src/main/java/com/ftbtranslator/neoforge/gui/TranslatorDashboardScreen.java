package com.ftbtranslator.neoforge.gui;

import com.ftbtranslator.core.ProgressSnapshot;
import com.ftbtranslator.core.TranslationScope;
import com.ftbtranslator.core.TranslatorConfigData;
import com.ftbtranslator.core.bundle.TranslationBundleManager;
import com.ftbtranslator.core.parser.JarModExtractor;
import com.ftbtranslator.neoforge.ClientConfig;
import com.ftbtranslator.neoforge.FtbTranslatorMod;
import com.ftbtranslator.neoforge.ResourcePackApplier;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.util.FormattedCharSequence;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Main in-game dashboard ({@code F9} or NeoForge Mods -> Config), organised in four tabs:
 * <ol>
 *   <li><b>Início</b>: API key status, context profile, real-time efficiency metrics (cache / official
 *       glossary vs. AI) and the run controls.</li>
 *   <li><b>Conteúdo e Escopo</b>: translation fronts with plain-language hover tooltips, Delta Translation and
 *       the mod filter with a live counter.</li>
 *   <li><b>Motor de IA & Glossário</b>: provider, API key, connection test, free key guide and the official
 *       glossary switch.</li>
 *   <li><b>Pacotes & Comunidade</b>: bundle export/import, exports folder and multiplayer notes.</li>
 * </ol>
 * The progress bar and status line are shown on every tab. All configuration controls and conflicting
 * actions are locked while a translation job (or API connection test) is running.
 */
public final class TranslatorDashboardScreen extends Screen {

    private enum Tab {
        HOME("Início", "Início: status, contexto, métricas e botão de tradução."),
        CONTENT("Conteúdo", "Conteúdo e Escopo: o que traduzir, Delta Translation e filtro de mods."),
        ENGINE("IA & Glossário", "Motor de IA & Glossário: provedor, API Key, teste de conexão e glossário oficial."),
        PACKS("Pacotes (.zip)", "Pacotes & Comunidade: exportar e importar traduções prontas, sem API Key.");

        final String label;
        final String tooltip;

        Tab(String label, String tooltip) {
            this.label = label;
            this.tooltip = tooltip;
        }
    }

    private static final int PANEL_WIDTH = 420;
    private static final int CONTENT_Y = 48;
    private static final int BAR_Y = 168;
    private static final int STATUS_Y = 186;
    private static final int FOOTER_Y = 210;

    /** Remembered between openings so the player returns to the tab they were using. */
    private static Tab lastTab = Tab.HOME;

    private final Screen parent;
    private final TranslatorConfigData workingConfig;
    private Tab currentTab = lastTab;

    // Home
    private Button contextBtn;
    private Button startBtn;
    private Button pauseResumeBtn;
    private Button cancelBtn;
    private Button applyReloadBtn;
    // Content
    private Button ftbQuestsBtn;
    private Button kubeJsBtn;
    private Button modsBtn;
    private Button patchouliBtn;
    private Button deltaBtn;
    private EditBox modFilterBox;
    // Engine
    private Button providerBtn;
    private Button guideBtn;
    private EditBox apiKeyBox;
    private Button toggleMaskBtn;
    private Button pasteKeyBtn;
    private Button testConnBtn;
    private Button glossaryBtn;
    // Packs
    private Button exportZipBtn;
    private Button importZipBtn;

    private boolean maskApiKey = true;
    private boolean testingConnection = false;
    private boolean cancelRequested = false;
    private String bannerMessage = "Pronto. Configure sua API Key ou importe um pacote .zip.";
    private int bannerColor = 0xFFE0E0E0;

    /** Mods found in mods/ (loaded once, off the render thread) for the live filter counter. */
    private volatile List<JarModExtractor.ModJarInfo> modJars;
    private boolean modJarsRequested = false;

    public TranslatorDashboardScreen(Screen parent) {
        super(Component.literal("Universal Modpack & Quests Auto-Translator (PT-BR)"));
        this.parent = parent;
        this.workingConfig = ClientConfig.toCoreData();
    }

    // ------------------------------------------------------------------ layout

    private int panelLeft() {
        return this.width / 2 - PANEL_WIDTH / 2;
    }

    @Override
    protected void init() {
        clearWidgetRefs();
        int left = panelLeft();

        // Tab bar
        int tabW = 102;
        int tabGap = 4;
        Tab[] tabs = Tab.values();
        for (int i = 0; i < tabs.length; i++) {
            Tab tab = tabs[i];
            boolean selected = tab == currentTab;
            Button tabBtn = this.addRenderableWidget(Button.builder(
                            Component.literal(selected ? "§e§n" + tab.label : tab.label),
                            b -> switchTab(tab))
                    .bounds(left + i * (tabW + tabGap), 22, tabW, 20)
                    .build());
            tabBtn.setTooltip(tip(tab.tooltip));
        }

        switch (currentTab) {
            case HOME -> initHomeTab(left);
            case CONTENT -> initContentTab(left);
            case ENGINE -> initEngineTab(left);
            case PACKS -> initPacksTab(left);
        }

        this.addRenderableWidget(Button.builder(Component.literal("Fechar"), b -> onClose())
                .bounds(left + PANEL_WIDTH - 82, FOOTER_Y, 82, 20)
                .build());

        refreshButtonStates();
    }

    private void switchTab(Tab tab) {
        if (tab == currentTab) {
            return;
        }
        currentTab = tab;
        lastTab = tab;
        this.rebuildWidgets();
    }

    private void clearWidgetRefs() {
        contextBtn = startBtn = pauseResumeBtn = cancelBtn = applyReloadBtn = null;
        ftbQuestsBtn = kubeJsBtn = modsBtn = patchouliBtn = deltaBtn = null;
        modFilterBox = null;
        providerBtn = guideBtn = toggleMaskBtn = pasteKeyBtn = testConnBtn = glossaryBtn = null;
        apiKeyBox = null;
        exportZipBtn = importZipBtn = null;
    }

    // ------------------------------------------------------------------ tab: Início

    private void initHomeTab(int left) {
        contextBtn = this.addRenderableWidget(Button.builder(
                        contextButtonLabel(),
                        b -> {
                            if (isBusy()) {
                                return;
                            }
                            workingConfig.setContextPreset(workingConfig.getContextPreset().next());
                            ClientConfig.markContextManuallySet();
                            b.setMessage(contextButtonLabel());
                            persistWorkingConfig();
                        })
                .bounds(left, CONTENT_Y + 16, 205, 20)
                .build());
        contextBtn.setTooltip(tip("Perfil temático da tradução. Ajuda a IA a escolher os termos certos: "
                + "Tensura (anime Slime), Tech (máquinas e energia), Magia (fantasia) ou Padrão. "
                + "É detectado automaticamente quando o mod Tensura está instalado."));

        int rowY = CONTENT_Y + 92;
        startBtn = this.addRenderableWidget(Button.builder(
                        Component.literal("▶ Iniciar Tradução"),
                        b -> triggerStartTranslation())
                .bounds(left, rowY, 108, 20)
                .build());
        startBtn.setTooltip(tip("Traduz tudo o que estiver marcado na aba Conteúdo. Pode fechar esta tela: "
                + "a tradução continua em segundo plano e o progresso fica salvo no cache."));

        pauseResumeBtn = this.addRenderableWidget(Button.builder(
                        Component.literal("⏸ Pausar"),
                        b -> triggerTogglePause())
                .bounds(left + 111, rowY, 96, 20)
                .build());

        cancelBtn = this.addRenderableWidget(Button.builder(
                        Component.literal("■ Cancelar Agora"),
                        b -> triggerCancel())
                .bounds(left + 210, rowY, 96, 20)
                .build());
        cancelBtn.setTooltip(tip("Para a tradução. Nada se perde: o que já foi traduzido fica no cache "
                + "e a próxima execução continua de onde parou."));

        applyReloadBtn = this.addRenderableWidget(Button.builder(
                        Component.literal("↻ Aplicar e Recarregar"),
                        b -> {
                            if (isBusy()) {
                                return;
                            }
                            ResourcePackApplier.applyOnMainThread(FtbTranslatorMod.service().paths(), true);
                            setBanner("Resource Pack ativado e recursos recarregados!", 0xFF55FFFF);
                        })
                .bounds(left + 309, rowY, 111, 20)
                .build());
        applyReloadBtn.setTooltip(tip("Ativa o pacote de tradução gerado e recarrega os textos do jogo, "
                + "para ver o resultado sem reiniciar."));
    }

    private void renderHomeTab(GuiGraphics g, int left) {
        TranslatorConfigData cfg = workingConfig;
        String keyStatus;
        if (!cfg.getProvider().requiresApiKey()) {
            keyStatus = "§a✔ " + cfg.getProvider().displayName() + " §7(não precisa de API Key)";
        } else if (cfg.getApiKey() == null || cfg.getApiKey().isBlank()) {
            keyStatus = "§c✘ API Key não configurada §7— abra a aba §fIA & Glossário";
        } else {
            keyStatus = "§a✔ API Key configurada §7(" + cfg.getProvider().displayName() + ")";
        }
        g.drawString(this.font, keyStatus, left, CONTENT_Y + 4, 0xFFFFFFFF);

        // Efficiency metrics of the current / last run
        ProgressSnapshot snap = FtbTranslatorMod.service().latestSnapshot();
        int metricsY = CONTENT_Y + 42;
        g.fill(left, metricsY, left + PANEL_WIDTH, metricsY + 44, 0x80101828);
        g.renderOutline(left, metricsY, PANEL_WIDTH, 44, 0xFF2A3A5A);
        g.drawString(this.font, "§7Eficiência da tradução " + (snap.running() ? "atual" : "mais recente") + ":",
                left + 6, metricsY + 5, 0xFFA0A0A0);

        int fromCache = snap.stringsFromCacheOnly();
        int fromGlossary = snap.stringsFromGlossary();
        int byAi = snap.stringsTranslatedByAi();
        int failed = snap.stringsFailed();
        int colW = PANEL_WIDTH / 4;
        drawMetric(g, left + 6, metricsY + 18, "Cache", fromCache, 0xFF55FF55);
        drawMetric(g, left + 6 + colW, metricsY + 18, "Glossário", fromGlossary, 0xFF55FFFF);
        drawMetric(g, left + 6 + colW * 2, metricsY + 18, "IA", byAi, 0xFFFFFF55);
        drawMetric(g, left + 6 + colW * 3, metricsY + 18, "Falhas", failed, failed > 0 ? 0xFFFF5555 : 0xFF808080);

        int total = fromCache + fromGlossary + byAi + failed;
        String saving = total == 0
                ? "§8Inicie uma tradução para ver quanto foi resolvido sem gastar tokens."
                : "§7Resolvido sem IA: §a" + Math.round(100.0 * (fromCache + fromGlossary) / total) + "%";
        g.drawString(this.font, saving, left + 6, metricsY + 32, 0xFFFFFFFF);
    }

    private void drawMetric(GuiGraphics g, int x, int y, String label, int value, int color) {
        g.drawString(this.font, "§7" + label + ": ", x, y, 0xFFFFFFFF);
        g.drawString(this.font, String.valueOf(value), x + this.font.width(label + ": "), y, color);
    }

    // ------------------------------------------------------------------ tab: Conteúdo e Escopo

    private void initContentTab(int left) {
        int scopeW = 102;
        int scopeGap = 4;
        int rowY = CONTENT_Y + 14;
        ftbQuestsBtn = scopeButton(TranslationScope.FTBQUESTS, left, rowY, scopeW,
                "Livro de missões (FTB Quests): títulos, descrições e recompensas das missões do modpack.");
        kubeJsBtn = scopeButton(TranslationScope.KUBEJS, left + (scopeW + scopeGap), rowY, scopeW,
                "Textos criados pelo autor do modpack (KubeJS, Paxi, OpenLoader): itens e mensagens "
                        + "personalizados que não vêm de nenhum mod.");
        modsBtn = scopeButton(TranslationScope.MODS, left + (scopeW + scopeGap) * 2, rowY, scopeW,
                "Nomes de itens, blocos, mobs e menus de cada mod instalado (os arquivos .jar da pasta mods). "
                        + "Gera um pacote de recursos com a tradução.");
        patchouliBtn = scopeButton(TranslationScope.PATCHOULI, left + (scopeW + scopeGap) * 3, rowY, scopeW,
                "Livros de guia dentro do jogo (Patchouli), aqueles manuais com páginas e capítulos "
                        + "que muitos mods dão ao jogador.");

        deltaBtn = this.addRenderableWidget(Button.builder(
                        deltaLabel(),
                        b -> {
                            if (isBusy()) {
                                return;
                            }
                            workingConfig.setSkipAlreadyTranslatedMods(!workingConfig.isSkipAlreadyTranslatedMods());
                            b.setMessage(deltaLabel());
                            persistWorkingConfig();
                        })
                .bounds(left, CONTENT_Y + 42, 260, 20)
                .build());
        deltaBtn.setTooltip(tip("Delta Translation: quando um mod já vem com tradução em português, "
                + "traduz só as linhas que faltam e mantém a tradução oficial. Recomendado: ligado."));

        modFilterBox = new EditBox(this.font, left + 82, CONTENT_Y + 74, 200, 20, Component.literal("Filtro Mods"));
        modFilterBox.setMaxLength(256);
        modFilterBox.setValue(workingConfig.getModFilter());
        modFilterBox.setHint(Component.literal("§8tensura, manas (vazio = todos)"));
        modFilterBox.setResponder(val -> {
            if (!isBusy()) {
                workingConfig.setModFilter(val);
                persistWorkingConfig();
            }
        });
        modFilterBox.setTooltip(tip("Para traduzir só alguns mods, escreva parte do nome deles separado por "
                + "vírgula (ex: tensura, manas). Deixe vazio para traduzir todos."));
        this.addRenderableWidget(modFilterBox);

        requestModJarsOnce();
    }

    private Button scopeButton(TranslationScope scope, int x, int y, int w, String help) {
        Button btn = this.addRenderableWidget(Button.builder(
                        scopeLabel(scope),
                        b -> toggleScope(scope, b))
                .bounds(x, y, w, 20)
                .build());
        btn.setTooltip(tip(help));
        return btn;
    }

    private void renderContentTab(GuiGraphics g, int left) {
        g.drawString(this.font, "§7O que traduzir §8(passe o mouse sobre cada opção para entender):",
                left, CONTENT_Y + 2, 0xFFA0A0A0);
        g.drawString(this.font, "§fFiltro de mods:", left, CONTENT_Y + 80, 0xFFE0E0E0);

        List<JarModExtractor.ModJarInfo> jars = modJars;
        String counter;
        if (jars == null) {
            counter = "§8contando mods...";
        } else {
            int matching = JarModExtractor.countMatchingJars(jars, workingConfig.modFilterTokens());
            String color = matching == 0 ? "§c" : (matching == jars.size() ? "§a" : "§e");
            counter = color + matching + " §7de §f" + jars.size() + " §7mods";
        }
        g.drawString(this.font, counter, left + 290, CONTENT_Y + 80, 0xFFFFFFFF);
    }

    private void requestModJarsOnce() {
        if (modJarsRequested) {
            return;
        }
        modJarsRequested = true;
        CompletableFuture
                .supplyAsync(() -> {
                    try {
                        return JarModExtractor.listModJars(FtbTranslatorMod.service().paths());
                    } catch (IOException e) {
                        return List.<JarModExtractor.ModJarInfo>of();
                    }
                })
                .thenAccept(list -> modJars = list);
    }

    // ------------------------------------------------------------------ tab: Motor de IA & Glossário

    private void initEngineTab(int left) {
        int row1Y = CONTENT_Y + 14;
        providerBtn = this.addRenderableWidget(Button.builder(
                        providerButtonLabel(),
                        b -> {
                            if (isBusy()) {
                                return;
                            }
                            workingConfig.setProvider(workingConfig.getProvider().next());
                            b.setMessage(providerButtonLabel());
                            persistWorkingConfig();
                        })
                .bounds(left, row1Y, 205, 20)
                .build());
        providerBtn.setTooltip(tip("Serviço de IA que faz a tradução. Gemini e Groq têm plano gratuito; "
                + "Claude (Anthropic) é pago e tem alta qualidade; "
                + "Ollama roda no seu PC sem internet e sem chave."));

        guideBtn = this.addRenderableWidget(Button.builder(
                        Component.literal("§6★ Como pegar uma API Key Gratuita?"),
                        b -> {
                            if (isBusy()) {
                                return;
                            }
                            persistWorkingConfig();
                            if (this.minecraft != null) {
                                this.minecraft.setScreen(new ApiKeyGuideScreen(this));
                            }
                        })
                .bounds(left + 210, row1Y, 210, 20)
                .build());

        int row2Y = CONTENT_Y + 38;
        apiKeyBox = new EditBox(this.font, left + 54, row2Y, 178, 20, Component.literal("API Key"));
        apiKeyBox.setMaxLength(512);
        apiKeyBox.setValue(workingConfig.getApiKey());
        apiKeyBox.setHint(Component.literal("§8Cole sua API Key aqui..."));
        apiKeyBox.setResponder(val -> {
            if (!isBusy()) {
                workingConfig.setApiKey(val);
                persistWorkingConfig();
            }
        });
        applyKeyMaskFormatter();
        apiKeyBox.setTooltip(tip("Chave pessoal do provedor de IA. Fica salva só no seu computador "
                + "(config/ftbtranslator-client.toml). Não compartilhe essa pasta com outras pessoas."));
        this.addRenderableWidget(apiKeyBox);

        toggleMaskBtn = this.addRenderableWidget(Button.builder(
                        Component.literal(maskApiKey ? "👁" : "🔒"),
                        b -> {
                            maskApiKey = !maskApiKey;
                            b.setMessage(Component.literal(maskApiKey ? "👁" : "🔒"));
                            applyKeyMaskFormatter();
                        })
                .bounds(left + 234, row2Y, 22, 20)
                .build());
        toggleMaskBtn.setTooltip(tip("Mostrar / esconder a chave."));

        pasteKeyBtn = this.addRenderableWidget(Button.builder(
                        Component.literal("Colar Key"),
                        b -> {
                            if (isBusy()) {
                                return;
                            }
                            String clip = Minecraft.getInstance().keyboardHandler.getClipboard();
                            if (clip != null && !clip.isBlank()) {
                                apiKeyBox.setValue(clip.trim());
                                setBanner("API Key colada da área de transferência!", 0xFF55FF55);
                            } else {
                                setBanner("Área de transferência está vazia.", 0xFFFFAA00);
                            }
                        })
                .bounds(left + 258, row2Y, 68, 20)
                .build());

        testConnBtn = this.addRenderableWidget(Button.builder(
                        Component.literal("Testar Conexão"),
                        b -> runConnectionTest())
                .bounds(left + 328, row2Y, 92, 20)
                .build());
        testConnBtn.setTooltip(tip("Faz uma chamada rápida para confirmar que a chave e o provedor funcionam."));

        glossaryBtn = this.addRenderableWidget(Button.builder(
                        glossaryLabel(),
                        b -> {
                            if (isBusy()) {
                                return;
                            }
                            workingConfig.setUseOfficialGlossary(!workingConfig.isUseOfficialGlossary());
                            b.setMessage(glossaryLabel());
                            persistWorkingConfig();
                        })
                .bounds(left, CONTENT_Y + 72, 260, 20)
                .build());
        glossaryBtn.setTooltip(tip("Muitos mods já vêm com tradução em português feita por pessoas. "
                + "Com o glossário ligado, textos iguais são traduzidos na hora sem gastar a IA, e os "
                + "termos oficiais são ensinados à IA para manter tudo consistente. Recomendado: ligado."));
    }

    private void renderEngineTab(GuiGraphics g, int left) {
        g.drawString(this.font, "§7Provedor de IA e chave de acesso:", left, CONTENT_Y + 2, 0xFFA0A0A0);
        g.drawString(this.font, "§fAPI Key:", left, CONTENT_Y + 44, 0xFFE0E0E0);
        g.drawString(this.font, "§7Glossário oficial dos mods:", left, CONTENT_Y + 62, 0xFFA0A0A0);
        g.drawString(this.font, "§8Modelo: §7" + workingConfig.effectiveModel()
                        + " §8| Limite: §7" + workingConfig.getRequestsPerMinute() + " req/min",
                left, CONTENT_Y + 100, 0xFF909090);
    }

    // ------------------------------------------------------------------ tab: Pacotes & Comunidade

    private void initPacksTab(int left) {
        int rowY = CONTENT_Y + 14;
        exportZipBtn = this.addRenderableWidget(Button.builder(
                        Component.literal("Exportar"),
                        b -> triggerExportZip())
                .bounds(left, rowY, 136, 20)
                .build());
        exportZipBtn.setTooltip(tip("Junta todas as traduções feitas (missões, mods, livros e o cache) em "
                + "exports/" + TranslationBundleManager.DEFAULT_BUNDLE_FILENAME + " para enviar a amigos."));

        importZipBtn = this.addRenderableWidget(Button.builder(
                        Component.literal("Importar"),
                        b -> triggerImportZip())
                .bounds(left + 142, rowY, 136, 20)
                .build());
        importZipBtn.setTooltip(tip("Instala um pacote recebido de outra pessoa. Coloque o arquivo "
                + TranslationBundleManager.DEFAULT_BUNDLE_FILENAME + " na pasta exports e clique aqui. "
                + "Não precisa de API Key."));

        Button openFolderBtn = this.addRenderableWidget(Button.builder(
                        Component.literal("Abrir pasta exports"),
                        b -> openExportsFolder())
                .bounds(left + 284, rowY, 136, 20)
                .build());
        openFolderBtn.setTooltip(tip("Abre a pasta onde os pacotes .zip são salvos e procurados."));
    }

    private void renderPacksTab(GuiGraphics g, int left) {
        g.drawString(this.font, "§7Compartilhe traduções prontas com outros jogadores:", left, CONTENT_Y + 2,
                0xFFA0A0A0);
        String info = "§fQuem recebe o pacote não precisa de API Key nem gastar tokens: basta importar. "
                + "§eEm servidores multiplayer dedicados, §fos capítulos do FTB Quests com texto fixo "
                + "precisam que o pacote também seja aplicado no servidor; a tradução dos mods e dos "
                + "livros funciona só no seu cliente.";
        int y = CONTENT_Y + 44;
        for (FormattedCharSequence line : this.font.split(Component.literal(info), PANEL_WIDTH)) {
            g.drawString(this.font, line, left, y, 0xFFE0E0E0);
            y += 11;
        }
    }

    private void openExportsFolder() {
        Path dir = FtbTranslatorMod.service().paths().exportsDir();
        try {
            Files.createDirectories(dir);
            Util.getPlatform().openUri(dir.toUri());
        } catch (IOException | RuntimeException e) {
            setBanner("Não foi possível abrir a pasta: " + dir, 0xFFFF5555);
        }
    }

    // ------------------------------------------------------------------ helpers

    private static Tooltip tip(String text) {
        return Tooltip.create(Component.literal(text));
    }

    private boolean isBusy() {
        return FtbTranslatorMod.service().isRunning() || testingConnection;
    }

    private void applyKeyMaskFormatter() {
        if (apiKeyBox == null) {
            return;
        }
        if (maskApiKey) {
            apiKeyBox.setFormatter((text, firstCharIndex) ->
                    FormattedCharSequence.forward("*".repeat(text.length()), Style.EMPTY));
        } else {
            apiKeyBox.setFormatter((text, firstCharIndex) ->
                    FormattedCharSequence.forward(text, Style.EMPTY));
        }
    }

    private Component providerButtonLabel() {
        return Component.literal("Provedor: §b" + workingConfig.getProvider().displayName());
    }

    private Component contextButtonLabel() {
        return Component.literal("Contexto: §e" + workingConfig.getContextPreset().displayName());
    }

    private Component scopeLabel(TranslationScope scope) {
        return checkboxLabel(workingConfig.isScopeEnabled(scope), scope.displayName());
    }

    private Component deltaLabel() {
        return checkboxLabel(workingConfig.isSkipAlreadyTranslatedMods(), "Só o que falta (Delta Translation)");
    }

    private Component glossaryLabel() {
        return checkboxLabel(workingConfig.isUseOfficialGlossary(), "Usar glossário oficial dos mods");
    }

    private static Component checkboxLabel(boolean on, String text) {
        return Component.literal((on ? "§a[x] §f" : "§7[ ] §8") + text);
    }

    private void toggleScope(TranslationScope scope, Button btn) {
        if (isBusy()) {
            return;
        }
        workingConfig.setScopeEnabled(scope, !workingConfig.isScopeEnabled(scope));
        btn.setMessage(scopeLabel(scope));
        persistWorkingConfig();
    }

    private void persistWorkingConfig() {
        ClientConfig.saveFromCoreData(workingConfig);
    }

    // ------------------------------------------------------------------ actions

    private void runConnectionTest() {
        if (isBusy()) {
            return;
        }
        persistWorkingConfig();
        testingConnection = true;
        refreshButtonStates();
        setBanner("Testando conexão com " + workingConfig.getProvider().displayName() + "...", 0xFFFFFF55);
        FtbTranslatorMod.service().testConnectionAsync(
                workingConfig,
                okMsg -> Minecraft.getInstance().execute(() -> {
                    testingConnection = false;
                    setBanner(okMsg, 0xFF55FF55);
                    refreshButtonStates();
                }),
                errMsg -> Minecraft.getInstance().execute(() -> {
                    testingConnection = false;
                    setBanner("Falha na conexão: " + errMsg, 0xFFFF5555);
                    refreshButtonStates();
                }));
    }

    private void triggerStartTranslation() {
        if (isBusy()) {
            return;
        }
        persistWorkingConfig();
        String validation = workingConfig.validateForTranslation();
        if (validation != null) {
            setBanner(validation, 0xFFFF5555);
            return;
        }
        cancelRequested = false;
        setBanner("Iniciando tradução em segundo plano...", 0xFF55FFFF);
        FtbTranslatorMod.startTranslationFromGui(workingConfig);
        refreshButtonStates();
    }

    private void triggerTogglePause() {
        var service = FtbTranslatorMod.service();
        if (!service.isRunning() || cancelRequested) {
            return;
        }
        boolean paused = service.togglePause(FtbTranslatorMod.uiCallback());
        if (paused) {
            setBanner("Tradução pausada. Clique em 'Retomar Tradução' ou 'Cancelar Agora'.", 0xFFFFAA00);
        } else {
            setBanner("Tradução retomada.", 0xFF55FF55);
        }
        refreshButtonStates();
    }

    private void triggerCancel() {
        var service = FtbTranslatorMod.service();
        if (!service.isRunning() || cancelRequested) {
            return;
        }
        cancelRequested = true;
        service.cancel();
        setBanner("Cancelando tradução e salvando progresso em cache...", 0xFFFFAA00);
        refreshButtonStates();
    }

    private void triggerExportZip() {
        if (isBusy()) {
            return;
        }
        try {
            TranslationBundleManager.ExportResult res = FtbTranslatorMod.service()
                    .exportBundle(workingConfig.getTargetLanguage());
            setBanner("Exportado (" + res.filesExported() + " arquivos) em exports/"
                    + res.zipPath().getFileName(), 0xFF55FF55);
        } catch (IOException e) {
            setBanner("Erro ao exportar: " + e.getMessage(), 0xFFFF5555);
        }
    }

    private void triggerImportZip() {
        if (isBusy()) {
            return;
        }
        try {
            TranslationBundleManager.ImportResult res = FtbTranslatorMod.service()
                    .importBundle(workingConfig.getTargetLanguage());
            ResourcePackApplier.applyOnMainThread(FtbTranslatorMod.service().paths(), res.questsTouched());
            setBanner("Importado " + res.filesExtracted() + " arquivo(s) de "
                    + res.zipPath().getFileName() + " e aplicado!", 0xFF55FF55);
        } catch (IOException e) {
            setBanner("Erro ao importar: " + e.getMessage(), 0xFFFF5555);
        }
    }

    public void setBanner(String message, int color) {
        this.bannerMessage = message;
        this.bannerColor = color;
    }

    // ------------------------------------------------------------------ state

    /**
     * Strictly locks / unlocks buttons and text boxes depending on whether the translator is currently
     * working or idle. Widgets of inactive tabs are {@code null} and skipped.
     */
    private void refreshButtonStates() {
        boolean running = FtbTranslatorMod.service().isRunning();
        if (!running) {
            cancelRequested = false;
        }
        boolean paused = FtbTranslatorMod.service().isPaused();
        boolean canEditConfig = !running && !testingConnection;

        for (Button b : new Button[]{contextBtn, ftbQuestsBtn, kubeJsBtn, modsBtn, patchouliBtn, deltaBtn,
                providerBtn, guideBtn, toggleMaskBtn, pasteKeyBtn, glossaryBtn}) {
            if (b != null) {
                b.active = canEditConfig;
            }
        }
        for (EditBox box : new EditBox[]{apiKeyBox, modFilterBox}) {
            if (box != null) {
                box.setEditable(canEditConfig);
                box.active = canEditConfig;
                if (!canEditConfig) {
                    box.setFocused(false);
                }
            }
        }
        if (testConnBtn != null) {
            testConnBtn.active = canEditConfig;
            testConnBtn.setMessage(Component.literal(testingConnection ? "Testando..." : "Testar Conexão"));
        }

        if (startBtn != null) {
            startBtn.active = canEditConfig;
            startBtn.setMessage(Component.literal(canEditConfig ? "§a▶ Iniciar Tradução" : "▶ Iniciar Tradução"));
        }
        if (pauseResumeBtn != null) {
            pauseResumeBtn.active = running && !cancelRequested;
            if (!running) {
                pauseResumeBtn.setMessage(Component.literal("⏸ Pausar"));
            } else if (paused) {
                pauseResumeBtn.setMessage(Component.literal("§a▶ Retomar Tradução"));
            } else {
                pauseResumeBtn.setMessage(Component.literal("§e⏸ Pausar"));
            }
        }
        if (cancelBtn != null) {
            cancelBtn.active = running && !cancelRequested;
            cancelBtn.setMessage(Component.literal(
                    cancelRequested ? "Cancelando..." : (running ? "§c■ Cancelar Agora" : "■ Cancelar Agora")));
        }
        if (applyReloadBtn != null) {
            applyReloadBtn.active = canEditConfig;
            applyReloadBtn.setMessage(Component.literal(
                    canEditConfig ? "§b↻ Aplicar e Recarregar" : "↻ Aplicar e Recarregar"));
        }
        if (exportZipBtn != null) {
            exportZipBtn.active = canEditConfig;
            exportZipBtn.setMessage(Component.literal(canEditConfig ? "§eExportar" : "Exportar"));
        }
        if (importZipBtn != null) {
            importZipBtn.active = canEditConfig;
            importZipBtn.setMessage(Component.literal(canEditConfig ? "§dImportar" : "Importar"));
        }
    }

    // ------------------------------------------------------------------ render

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        refreshButtonStates();
        super.render(g, mouseX, mouseY, partialTick);

        int centerX = this.width / 2;
        int left = panelLeft();

        g.drawCenteredString(this.font, "§b§lUniversal Modpack & Quests Auto-Translator §7(PT-BR)", centerX, 8, 0xFFFFFF);

        switch (currentTab) {
            case HOME -> renderHomeTab(g, left);
            case CONTENT -> renderContentTab(g, left);
            case ENGINE -> renderEngineTab(g, left);
            case PACKS -> renderPacksTab(g, left);
        }

        renderProgressBar(g, left, centerX);
    }

    private void renderProgressBar(GuiGraphics g, int left, int centerX) {
        ProgressSnapshot snap = FtbTranslatorMod.service().latestSnapshot();
        int barX = left;
        int barW = PANEL_WIDTH;
        int barH = 14;

        g.fill(barX, BAR_Y, barX + barW, BAR_Y + barH, 0xFF1A1F2C);
        int filledW = (int) Math.round(barW * snap.fraction());
        if (snap.running() && filledW < 6) {
            // Minimum visible bar indicator as soon as a run is active
            filledW = 6;
        }
        if (filledW > 0) {
            int barColor = snap.paused() ? 0xFFD99B26 : (snap.running() ? 0xFF2EA043 : 0xFF388BFD);
            g.fill(barX, BAR_Y, barX + Math.min(barW, filledW), BAR_Y + barH, barColor);
        }
        // Animated activity pulse when actively translating a batch
        if (snap.running() && !snap.paused()) {
            int pulseW = 36;
            int cycle = (int) ((System.currentTimeMillis() / 12L) % (barW + pulseW)) - pulseW;
            int pStart = Math.max(barX, barX + cycle);
            int pEnd = Math.min(barX + barW, barX + cycle + pulseW);
            if (pEnd > pStart) {
                g.fill(pStart, BAR_Y + 1, pEnd, BAR_Y + barH - 1, 0x4455FF88);
            }
        }
        g.renderOutline(barX, BAR_Y, barW, barH, 0xFF4A5A80);

        int pct = (int) Math.round(snap.fraction() * 100.0);
        String barText = snap.phase() + " — " + pct + "% | Lote "
                + snap.batchIndex() + "/" + snap.batchTotal()
                + " | Gravados: " + snap.tasksWritten() + "/" + snap.tasksTotal();
        g.drawCenteredString(this.font, barText, centerX, BAR_Y + 3, 0xFFFFFFFF);

        String itemLine = snap.running() && !snap.currentItem().isEmpty()
                ? "Status: " + snap.currentItem()
                : bannerMessage;
        if (itemLine.length() > 78) {
            itemLine = itemLine.substring(0, 75) + "...";
        }
        g.drawString(this.font, itemLine, left, STATUS_Y, bannerColor);
    }

    @Override
    public void onClose() {
        if (!isBusy()) {
            persistWorkingConfig();
        }
        if (this.minecraft != null) {
            this.minecraft.setScreen(parent);
        }
    }
}
