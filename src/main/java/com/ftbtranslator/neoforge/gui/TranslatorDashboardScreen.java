package com.ftbtranslator.neoforge.gui;

import com.ftbtranslator.core.ModpackScanSummary;
import com.ftbtranslator.core.ProgressSnapshot;
import com.ftbtranslator.core.ProviderType;
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
 * Main in-game dashboard ({@code F9}, Pause/Title Screen button, or NeoForge Mods -> Config), organised in four tabs:
 * <ol>
 *   <li><b>Início</b>: quick-onboarding actions (when API key is missing), context profile, HUD bar toggle,
 *       real-time efficiency metrics, per-front Modpack X-Ray preview, and the run controls.</li>
 *   <li><b>Conteúdo e Escopo</b>: translation fronts with plain-language hover tooltips, Delta Translation,
 *       mod filter with live counter, and custom context prompt editor.</li>
 *   <li><b>Motor de IA & Glossário</b>: provider, API key, connection test, free key guide, official
 *       glossary switch, adaptive/manual rate limit, custom model override and Claude effort selector.</li>
 *   <li><b>Pacotes & Comunidade</b>: bundle export/import, exports folder and multiplayer notes.</li>
 * </ol>
 * The progress bar (with active front + live ETA), status line, in-game Translation Editor button, and
 * Reset button are shown on every tab.
 */
public final class TranslatorDashboardScreen extends Screen {

    private enum Tab {
        HOME("Início", "Início: status, Raio-X por frente, métricas, contexto e botão de tradução."),
        CONTENT("Conteúdo", "Conteúdo e Escopo: o que traduzir, Delta Translation, filtro de mods e prompt customizado."),
        ENGINE("IA & Glossário", "Motor de IA & Glossário: provedor, API Key, modelo, teste de conexão e glossário oficial."),
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
    private Button quickPasteKeyBtn;
    private Button quickGuideBtn;
    private Button quickImportBtn;
    private Button contextBtn;
    private Button hudBarBtn;
    private Button scanPreviewBtn;
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
    private Button clearFilterBtn;
    private EditBox customContextBox;
    // Engine
    private Button providerBtn;
    private Button guideBtn;
    private EditBox apiKeyBox;
    private Button toggleMaskBtn;
    private Button pasteKeyBtn;
    private Button testConnBtn;
    private Button glossaryBtn;
    private EditBox modelBox;
    private Button rateModeBtn;
    private EditBox rateBox;
    private Button claudeEffortBtn;
    // Packs
    private Button exportZipBtn;
    private Button importZipBtn;
    // Footer
    private Button resetBtn;
    private Button editorBtn;

    private boolean maskApiKey = true;
    private boolean testingConnection = false;
    private boolean scanningPreview = false;
    private boolean cancelRequested = false;
    private String bannerMessage = "Pronto. Configure sua API Key, faça um Raio-X ou importe um pacote .zip.";
    private int bannerColor = 0xFFE0E0E0;

    /** Mods found in mods/ (loaded once, off the render thread) for the live filter counter. */
    private volatile List<JarModExtractor.ModJarInfo> modJars;
    private boolean modJarsRequested = false;
    private boolean autoScanRequested = false;

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

        resetBtn = this.addRenderableWidget(Button.builder(
                        Component.literal("§c🗑 Resetar Cache e Traduções"),
                        b -> openResetConfirmationScreen())
                .bounds(left, FOOTER_Y, 168, 20)
                .build());
        resetBtn.setTooltip(tip("Abre uma tela de confirmação explicando como apagar o cache de tradução "
                + "e remover todas as traduções geradas pelo mod, restaurando o modpack para o original."));

        editorBtn = this.addRenderableWidget(Button.builder(
                        Component.literal("§b✏ Editor de Traduções"),
                        b -> openTranslationEditorScreen())
                .bounds(left + 174, FOOTER_Y, 158, 20)
                .build());
        editorBtn.setTooltip(tip("Pesquise qualquer texto traduzido (FTB Quests, KubeJS ou Mods) e edite a "
                + "tradução em português diretamente dentro do jogo."));

        this.addRenderableWidget(Button.builder(Component.literal("Fechar"), b -> onClose())
                .bounds(left + PANEL_WIDTH - 82, FOOTER_Y, 82, 20)
                .build());

        if (!autoScanRequested && FtbTranslatorMod.service().latestScanSummary() == null
                && !FtbTranslatorMod.service().isRunning()) {
            autoScanRequested = true;
            runScanPreview(true);
        }

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
        quickPasteKeyBtn = quickGuideBtn = quickImportBtn = null;
        contextBtn = hudBarBtn = scanPreviewBtn = startBtn = pauseResumeBtn = cancelBtn = applyReloadBtn = null;
        ftbQuestsBtn = kubeJsBtn = modsBtn = patchouliBtn = deltaBtn = clearFilterBtn = null;
        modFilterBox = customContextBox = null;
        providerBtn = guideBtn = toggleMaskBtn = pasteKeyBtn = testConnBtn = glossaryBtn = rateModeBtn = claudeEffortBtn = null;
        apiKeyBox = rateBox = modelBox = null;
        exportZipBtn = importZipBtn = null;
        resetBtn = editorBtn = null;
    }

    // ------------------------------------------------------------------ tab: Início

    private void initHomeTab(int left) {
        // Quick 1-click onboarding shortcuts right on the Home tab when API Key is missing
        quickPasteKeyBtn = this.addRenderableWidget(Button.builder(
                        Component.literal("§a🔑 Colar Key"),
                        b -> {
                            if (isBusy()) {
                                return;
                            }
                            String clip = Minecraft.getInstance().keyboardHandler.getClipboard();
                            if (clip != null && !clip.isBlank()) {
                                workingConfig.setApiKey(clip.trim());
                                persistWorkingConfig();
                                setBanner("API Key colada com sucesso! Clique em '▶ Iniciar Tradução'.", 0xFF55FF55);
                                refreshButtonStates();
                            } else {
                                setBanner("Área de transferência vazia. Copie sua API Key primeiro!", 0xFFFFAA00);
                            }
                        })
                .bounds(left + 210, CONTENT_Y - 2, 68, 16)
                .build());
        quickPasteKeyBtn.setTooltip(tip("Cola a API Key diretamente da sua área de transferência sem precisar mudar de aba."));

        quickGuideBtn = this.addRenderableWidget(Button.builder(
                        Component.literal("§6★ Key Grátis"),
                        b -> {
                            if (isBusy() || this.minecraft == null) {
                                return;
                            }
                            persistWorkingConfig();
                            this.minecraft.setScreen(new ApiKeyGuideScreen(this));
                        })
                .bounds(left + 280, CONTENT_Y - 2, 70, 16)
                .build());
        quickGuideBtn.setTooltip(tip("Passo a passo de 1 minuto para pegar uma API Key gratuita (Google Gemini ou Groq)."));

        quickImportBtn = this.addRenderableWidget(Button.builder(
                        Component.literal("§d📦 Importar .zip"),
                        b -> triggerImportZip())
                .bounds(left + 352, CONTENT_Y - 2, 68, 16)
                .build());
        quickImportBtn.setTooltip(tip("Se um amigo já traduziu o modpack e te mandou o arquivo .zip na pasta exports/, clique aqui para aplicar sem precisar de API Key."));

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
                .bounds(left, CONTENT_Y + 16, 162, 20)
                .build());
        contextBtn.setTooltip(tip("Perfil temático da tradução. Ajuda a IA a escolher os termos certos: "
                + "Tensura (anime Slime), Tech (máquinas e energia), Magia (fantasia) ou Padrão. "
                + "É detectado automaticamente quando o mod Tensura está instalado."));

        hudBarBtn = this.addRenderableWidget(Button.builder(
                        hudBarLabel(),
                        b -> {
                            ClientConfig.setShowHudBar(!ClientConfig.showHudBar());
                            b.setMessage(hudBarLabel());
                        })
                .bounds(left + 166, CONTENT_Y + 16, 168, 20)
                .build());
        hudBarBtn.setTooltip(tip("Mostra o progresso da tradução no topo da tela enquanto você joga, no estilo "
                + "da barra de chefe (como a do Wither), com frente ativa, ETA e tokens gastos."));

        scanPreviewBtn = this.addRenderableWidget(Button.builder(
                        Component.literal("§b🔍 Raio-X"),
                        b -> runScanPreview(false))
                .bounds(left + 338, CONTENT_Y + 16, 82, 20)
                .build());
        scanPreviewBtn.setTooltip(tip("Analisa o modpack em 1 segundo (sem usar IA e sem gastar tokens) para "
                + "mostrar quantos textos faltam traduzir em cada frente (Missões, KubeJS, Mods e Livros) e a "
                + "estimativa de lotes/tokens."));

        int rowY = CONTENT_Y + 98;
        startBtn = this.addRenderableWidget(Button.builder(
                        Component.literal("▶ Iniciar Tradução"),
                        b -> triggerStartTranslation())
                .bounds(left, rowY, 108, 20)
                .build());
        startBtn.setTooltip(tip("Traduz tudo o que estiver marcado na aba Conteúdo. Pode fechar esta tela: "
                + "a tradução continua em segundo plano e o FTB Quests é aplicado automaticamente assim que fica pronto."));

        pauseResumeBtn = this.addRenderableWidget(Button.builder(
                        Component.literal("⏸ Pausar"),
                        b -> {
                            if (FtbTranslatorMod.service().isRunning()) {
                                triggerTogglePause();
                            } else {
                                triggerRetryFailures();
                            }
                        })
                .bounds(left + 111, rowY, 96, 20)
                .build());
        pauseResumeBtn.setTooltip(tip("Durante a tradução: pausa e retoma. Depois de uma tradução com falhas "
                + "(textos que ficaram em inglês porque a resposta da IA veio estragada): vira 'Tentar falhas' "
                + "e reenvia só esses textos; todo o resto vem do cache, sem gastar IA."));

        cancelBtn = this.addRenderableWidget(Button.builder(
                        Component.literal("■ Cancelar Agora"),
                        b -> triggerCancel())
                .bounds(left + 210, rowY, 96, 20)
                .build());
        cancelBtn.setTooltip(tip("Para a tradução. Nada se perde: o que já foi traduzido fica no cache "
                + "e a próxima execução continua de onde parou."));

        applyReloadBtn = this.addRenderableWidget(Button.builder(
                        Component.literal("↻ Aplicar"),
                        b -> {
                            if (isBusy()) {
                                return;
                            }
                            ResourcePackApplier.applyOnMainThread(FtbTranslatorMod.service().paths(), true);
                            setBanner("Resource Pack ativado e recursos recarregados!", 0xFF55FFFF);
                        })
                .bounds(left + 309, rowY, 111, 20)
                .build());
        applyReloadBtn.setTooltip(tip("Ativa o pacote de tradução gerado e recarrega os textos do jogo e das "
                + "missões, para ver o resultado sem reiniciar."));
    }

    private void renderHomeTab(GuiGraphics g, int left) {
        TranslatorConfigData cfg = workingConfig;
        String keyStatus;
        if (!cfg.getProvider().requiresApiKey()) {
            keyStatus = "§a✔ " + cfg.getProvider().displayName() + " §7(local, sem API Key)";
        } else if (cfg.getApiKey() == null || cfg.getApiKey().isBlank()) {
            keyStatus = "§c✘ Sem API Key §7— use os atalhos ao lado →";
        } else {
            keyStatus = "§a✔ API Key pronta §7(" + cfg.getProvider().displayName()
                    + " · " + cfg.effectiveModel() + ")";
        }
        g.drawString(this.font, keyStatus, left, CONTENT_Y + 2, 0xFFFFFFFF);

        // Combined Efficiency + Per-Scope X-Ray Card
        ProgressSnapshot snap = FtbTranslatorMod.service().latestSnapshot();
        ModpackScanSummary xray = FtbTranslatorMod.service().latestScanSummary();
        int metricsY = CONTENT_Y + 39;
        g.fill(left, metricsY, left + PANEL_WIDTH, metricsY + 56, 0x80101828);
        g.renderOutline(left, metricsY, PANEL_WIDTH, 56, 0xFF2A3A5A);

        int fromCache = snap.stringsFromCacheOnly();
        int fromGlossary = snap.stringsFromGlossary();
        int byAi = snap.stringsTranslatedByAi();
        int failed = snap.stringsFailed();
        int runTotal = fromCache + fromGlossary + byAi + failed;

        if (runTotal == 0 && xray != null) {
            fromCache = xray.resolvedFromCache();
            fromGlossary = xray.resolvedFromGlossary();
        }

        int colW = PANEL_WIDTH / 4;
        drawMetric(g, left + 6, metricsY + 4, "Cache", fromCache, 0xFF55FF55);
        drawMetric(g, left + 6 + colW, metricsY + 4, "Glossário", fromGlossary, 0xFF55FFFF);
        drawMetric(g, left + 6 + colW * 2, metricsY + 4, "Traduzidos IA", byAi, 0xFFFFFF55);
        drawMetric(g, left + 6 + colW * 3, metricsY + 4, "Falhas", failed, failed > 0 ? 0xFFFF5555 : 0xFF808080);

        // Row 2: Raio-X por Frente (FTB Quests | KubeJS | Mods | Patchouli)
        String xrayLine;
        if (scanningPreview) {
            xrayLine = "§e🔍 Escaneando frentes do modpack...";
        } else if (xray != null) {
            xrayLine = "§7Pendentes p/ IA — §fQuests: " + formatPending(xray.pendingStringsFor(TranslationScope.FTBQUESTS))
                    + " §8| §fKubeJS: " + formatPending(xray.pendingStringsFor(TranslationScope.KUBEJS))
                    + " §8| §fMods: " + formatPending(xray.pendingStringsFor(TranslationScope.MODS))
                    + " §8| §fLivros: " + formatPending(xray.pendingStringsFor(TranslationScope.PATCHOULI));
        } else {
            xrayLine = "§8Clique em '🔍 Raio-X' para ver quantos textos faltam em cada frente.";
        }
        g.drawString(this.font, xrayLine, left + 6, metricsY + 18, 0xFFFFFFFF);

        // Row 3: Economy % + Estimated or Actual Batches
        int denom = runTotal > 0 ? runTotal : (xray != null ? xray.totalUniqueStrings() : 0);
        String saving = denom <= 0
                ? "§7Economia local (Cache + Glossário): §8aguardando leitura"
                : "§7Resolvido sem gastar IA: §a" + Math.round(100.0 * (fromCache + fromGlossary) / denom) + "%"
                + (xray != null && !snap.running()
                ? " §8· §7Restam §e" + xray.stringsToTranslateWithAi() + " §7templates (§f"
                + xray.estimatedBatches() + " §7lotes)"
                : "");
        g.drawString(this.font, saving, left + 6, metricsY + 31, 0xFFFFFFFF);

        // Row 4: Tokens spent (or estimated before starting)
        String tokens;
        if (snap.totalTokens() > 0) {
            tokens = "§7Tokens gastos: §f" + ProgressSnapshot.formatCount(snap.totalTokens())
                    + " §8(entrada §7" + ProgressSnapshot.formatCount(snap.inputTokens())
                    + "§8 · saída §7" + ProgressSnapshot.formatCount(snap.outputTokens()) + "§8)";
        } else if (xray != null && xray.estimatedTokens() > 0) {
            tokens = "§7Estimativa p/ o que falta: §e~" + ProgressSnapshot.formatCount(xray.estimatedTokens())
                    + " tokens §8(varia conforme o modelo escolhido)";
        } else if (xray != null && xray.stringsToTranslateWithAi() == 0 && xray.totalUniqueStrings() > 0) {
            tokens = "§a✔ Tudo já está traduzido no cache/glossário! (0 tokens necessários)";
        } else {
            tokens = "§7Tokens: §80 (o provedor informa em tempo real durante a tradução)";
        }
        g.drawString(this.font, tokens, left + 6, metricsY + 43, 0xFFFFFFFF);
    }

    private static String formatPending(int count) {
        return count == 0 ? "§a✔ 0" : "§e" + ProgressSnapshot.formatCount(count);
    }

    private static Component hudBarLabel() {
        return checkboxLabel(ClientConfig.showHudBar(), "Barra no topo (HUD)");
    }

    private void drawMetric(GuiGraphics g, int x, int y, String label, int value, int color) {
        g.drawString(this.font, "§7" + label + ": ", x, y, 0xFFFFFFFF);
        g.drawString(this.font, ProgressSnapshot.formatCount(value), x + this.font.width(label + ": "), y, color);
    }

    // ------------------------------------------------------------------ tab: Conteúdo e Escopo

    private void initContentTab(int left) {
        int scopeW = 102;
        int scopeGap = 4;
        int rowY = CONTENT_Y + 12;
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
                .bounds(left, CONTENT_Y + 38, 260, 20)
                .build());
        deltaBtn.setTooltip(tip("Delta Translation: quando um mod já vem com tradução em português, "
                + "traduz só as linhas que faltam e mantém a tradução oficial. Recomendado: ligado."));

        modFilterBox = new EditBox(this.font, left + 82, CONTENT_Y + 64, 180, 20, Component.literal("Filtro Mods"));
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

        clearFilterBtn = this.addRenderableWidget(Button.builder(
                        Component.literal("Limpar"),
                        b -> {
                            if (!isBusy() && modFilterBox != null) {
                                modFilterBox.setValue("");
                            }
                        })
                .bounds(left + 266, CONTENT_Y + 64, 48, 20)
                .build());
        clearFilterBtn.setTooltip(tip("Limpa o filtro para selecionar todos os mods instalados novamente."));

        customContextBox = new EditBox(this.font, left + 118, CONTENT_Y + 90, 302, 20,
                Component.literal("Contexto Personalizado"));
        customContextBox.setMaxLength(500);
        customContextBox.setValue(workingConfig.getCustomContext());
        customContextBox.setHint(Component.literal("§8Instruções extras p/ a IA (usado no Contexto: Personalizado)"));
        customContextBox.setResponder(val -> {
            if (!isBusy()) {
                workingConfig.setCustomContext(val);
                persistWorkingConfig();
            }
        });
        customContextBox.setTooltip(tip("Quando você escolhe 'Contexto: Personalizado' na aba Início, a IA segue "
                + "exatamente as instruções de estilo/universo que você digitar aqui."));
        this.addRenderableWidget(customContextBox);

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
                left, CONTENT_Y + 1, 0xFFA0A0A0);
        g.drawString(this.font, "§fFiltro de mods:", left, CONTENT_Y + 70, 0xFFE0E0E0);

        List<JarModExtractor.ModJarInfo> jars = modJars;
        String counter;
        if (jars == null) {
            counter = "§8contando...";
        } else {
            int matching = JarModExtractor.countMatchingJars(jars, workingConfig.modFilterTokens());
            String color = matching == 0 ? "§c" : (matching == jars.size() ? "§a" : "§e");
            counter = color + matching + " §7de §f" + jars.size() + " §7mods";
        }
        g.drawString(this.font, counter, left + 320, CONTENT_Y + 70, 0xFFFFFFFF);

        g.drawString(this.font, "§fPrompt customizado:", left, CONTENT_Y + 96, 0xFFE0E0E0);
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
        int row1Y = CONTENT_Y + 12;
        providerBtn = this.addRenderableWidget(Button.builder(
                        providerButtonLabel(),
                        b -> {
                            if (isBusy()) {
                                return;
                            }
                            workingConfig.setProvider(workingConfig.getProvider().next());
                            b.setMessage(providerButtonLabel());
                            persistWorkingConfig();
                            refreshButtonStates();
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

        int row2Y = CONTENT_Y + 36;
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

        int row3Y = CONTENT_Y + 62;
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
                .bounds(left, row3Y, 215, 20)
                .build());
        glossaryBtn.setTooltip(tip("Muitos mods já vêm com tradução em português feita por pessoas. "
                + "Com o glossário ligado, textos iguais são traduzidos na hora sem gastar a IA, e os "
                + "termos oficiais são ensinados à IA para manter tudo consistente. Recomendado: ligado."));

        modelBox = new EditBox(this.font, left + 266, row3Y, 154, 20, Component.literal("Modelo"));
        modelBox.setMaxLength(128);
        modelBox.setValue(workingConfig.getModel());
        modelBox.setHint(Component.literal("§8Auto (" + workingConfig.getProvider().defaultModel() + ")"));
        modelBox.setResponder(val -> {
            if (!isBusy()) {
                workingConfig.setModel(val);
                persistWorkingConfig();
            }
        });
        modelBox.setTooltip(tip("Deixe vazio para usar o modelo automático recomendado do provedor "
                + "(no Gemini, vazio ativa o rodízio automático entre os modelos Flash para maior velocidade). "
                + "Ou digite um modelo específico se preferir."));
        this.addRenderableWidget(modelBox);

        int row4Y = CONTENT_Y + 88;
        String rateHelp = "Quantos pedidos por minuto o mod pode enviar para a IA (cada pedido leva um lote "
                + "de dezenas de textos). Todo provedor tem um limite por conta: passar dele não quebra nada, "
                + "a IA só pede para esperar (erro 429) e o mod aguarda e tenta de novo sozinho.";
        rateModeBtn = this.addRenderableWidget(Button.builder(
                        rateModeLabel(),
                        b -> {
                            if (isBusy()) {
                                return;
                            }
                            workingConfig.setAutoRateLimit(!workingConfig.isAutoRateLimit());
                            b.setMessage(rateModeLabel());
                            persistWorkingConfig();
                            refreshButtonStates();
                        })
                .bounds(left, row4Y, 142, 20)
                .build());
        rateModeBtn.setTooltip(tip(rateHelp + " Automático (recomendado): ajusta sozinho à sua conta. "
                + "Manual: usa o valor fixo ao lado."));

        rateBox = new EditBox(this.font, left + 146, row4Y, 38, 20, Component.literal("req/min"));
        rateBox.setMaxLength(3);
        rateBox.setFilter(s -> s.matches("\\d{0,3}"));
        rateBox.setValue(String.valueOf(workingConfig.getRequestsPerMinute()));
        rateBox.setResponder(val -> {
            if (isBusy() || workingConfig.isAutoRateLimit() || val.isEmpty()) {
                return;
            }
            int rpm = Math.max(1, Math.min(600, Integer.parseInt(val)));
            workingConfig.setRequestsPerMinute(rpm);
            persistWorkingConfig();
        });
        rateBox.setTooltip(tip(rateHelp + " Digite de 1 a 600 (só vale no modo Manual)."));
        this.addRenderableWidget(rateBox);

        claudeEffortBtn = this.addRenderableWidget(Button.builder(
                        claudeEffortLabel(),
                        b -> {
                            if (isBusy()) {
                                return;
                            }
                            workingConfig.setClaudeEffort(nextClaudeEffort(workingConfig.getClaudeEffort()));
                            b.setMessage(claudeEffortLabel());
                            persistWorkingConfig();
                        })
                .bounds(left + 266, row4Y, 154, 20)
                .build());
        claudeEffortBtn.setTooltip(tip("Exclusivo do provedor Claude (Anthropic): controla quanto o modelo raciocina "
                + "antes de responder. 'low' gasta menos tokens e é mais rápido; 'medium' é o equilíbrio padrão."));
    }

    private static String nextClaudeEffort(String current) {
        List<String> levels = List.of("low", "medium", "high");
        int idx = levels.indexOf(current == null ? "medium" : current.toLowerCase());
        return levels.get((idx + 1) % levels.size());
    }

    private Component claudeEffortLabel() {
        return Component.literal("Esforço Claude: §e" + workingConfig.getClaudeEffort());
    }

    private Component rateModeLabel() {
        return Component.literal("Limite: " + (workingConfig.isAutoRateLimit() ? "§aAutomático" : "§eManual"));
    }

    private void renderEngineTab(GuiGraphics g, int left) {
        g.drawString(this.font, "§7Provedor de IA, chave e modelo:", left, CONTENT_Y + 1, 0xFFA0A0A0);
        g.drawString(this.font, "§fAPI Key:", left, CONTENT_Y + 42, 0xFFE0E0E0);
        g.drawString(this.font, "§fModelo:", left + 222, CONTENT_Y + 68, 0xFFE0E0E0);
        g.drawString(this.font, "§7req/min", left + 188, CONTENT_Y + 94, 0xFFA0A0A0);
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
        return FtbTranslatorMod.service().isRunning() || testingConnection || scanningPreview;
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
        runScanPreview(true);
    }

    private void persistWorkingConfig() {
        ClientConfig.saveFromCoreData(workingConfig);
    }

    // ------------------------------------------------------------------ actions

    private void runScanPreview(boolean silent) {
        if (isBusy()) {
            return;
        }
        persistWorkingConfig();
        scanningPreview = true;
        refreshButtonStates();
        if (!silent) {
            setBanner("Analisando arquivos e cache do modpack (Raio-X sem gastar IA)...", 0xFFFFFF55);
        }
        FtbTranslatorMod.service().scanPreviewAsync(
                workingConfig,
                summary -> Minecraft.getInstance().execute(() -> {
                    scanningPreview = false;
                    if (!silent) {
                        setBanner("Raio-X concluído: " + summary.stringsToTranslateWithAi()
                                + " texto(s) pendente(s) em " + summary.estimatedBatches()
                                + " lote(s) (~" + ProgressSnapshot.formatCount(summary.estimatedTokens())
                                + " tokens).", 0xFF55FFFF);
                    }
                    refreshButtonStates();
                }),
                errMsg -> Minecraft.getInstance().execute(() -> {
                    scanningPreview = false;
                    if (!silent) {
                        setBanner("Falha ao escanear modpack: " + errMsg, 0xFFFF5555);
                    }
                    refreshButtonStates();
                }));
    }

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

    private void triggerRetryFailures() {
        if (isBusy()) {
            return;
        }
        int failures = FtbTranslatorMod.service().latestSnapshot().stringsFailed();
        triggerStartTranslation();
        if (workingConfig.validateForTranslation() == null) {
            setBanner("Reenviando " + failures + " texto(s) que falharam; o resto vem do cache.", 0xFF55FFFF);
        }
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
            runScanPreview(true);
        } catch (IOException e) {
            setBanner("Erro ao importar: " + e.getMessage(), 0xFFFF5555);
        }
    }

    private void openResetConfirmationScreen() {
        if (isBusy()) {
            return;
        }
        persistWorkingConfig();
        if (this.minecraft != null) {
            this.minecraft.setScreen(new ResetConfirmScreen(this));
        }
    }

    private void openTranslationEditorScreen() {
        if (isBusy()) {
            return;
        }
        persistWorkingConfig();
        if (this.minecraft != null) {
            this.minecraft.setScreen(new TranslationEditorScreen(this, workingConfig));
        }
    }

    /** Called by {@link ResetConfirmScreen} when the user confirms the reset action. */
    void performConfirmedReset() {
        if (isBusy()) {
            return;
        }
        try {
            TranslationBundleManager.ResetResult res = FtbTranslatorMod.service()
                    .resetAll(workingConfig.getTargetLanguage());
            ResourcePackApplier.removeOnMainThread(FtbTranslatorMod.service().paths(), res.questsTouched());
            String msg = "Reset concluído! " + res.filesRemoved() + " arquivo(s) removido(s)"
                    + (res.backupsRestored() > 0
                    ? " e " + res.backupsRestored() + " backup(s) original(is) restaurado(s)."
                    : ".")
                    + " Recursos recarregados!";
            setBanner(msg, 0xFF55FF55);
            runScanPreview(true);
        } catch (IOException e) {
            setBanner("Erro ao resetar traduções: " + e.getMessage(), 0xFFFF5555);
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
        boolean canEditConfig = !running && !testingConnection && !scanningPreview;

        boolean missingApiKey = workingConfig.getProvider().requiresApiKey()
                && (workingConfig.getApiKey() == null || workingConfig.getApiKey().isBlank());
        for (Button qb : new Button[]{quickPasteKeyBtn, quickGuideBtn, quickImportBtn}) {
            if (qb != null) {
                qb.visible = missingApiKey;
                qb.active = missingApiKey && canEditConfig;
            }
        }

        for (Button b : new Button[]{contextBtn, hudBarBtn, ftbQuestsBtn, kubeJsBtn, modsBtn, patchouliBtn,
                deltaBtn, clearFilterBtn, providerBtn, guideBtn, toggleMaskBtn, pasteKeyBtn, glossaryBtn,
                rateModeBtn}) {
            if (b != null) {
                b.active = canEditConfig;
            }
        }
        if (scanPreviewBtn != null) {
            scanPreviewBtn.active = canEditConfig;
            scanPreviewBtn.setMessage(Component.literal(scanningPreview ? "Lendo..." : "§b🔍 Raio-X"));
        }
        for (EditBox box : new EditBox[]{apiKeyBox, modFilterBox, customContextBox, modelBox}) {
            if (box != null) {
                box.setEditable(canEditConfig);
                box.active = canEditConfig;
                if (!canEditConfig) {
                    box.setFocused(false);
                }
            }
        }
        if (modelBox != null) {
            modelBox.setHint(Component.literal("§8Auto (" + workingConfig.getProvider().defaultModel() + ")"));
        }
        if (claudeEffortBtn != null) {
            boolean isClaude = workingConfig.getProvider() == ProviderType.CLAUDE;
            claudeEffortBtn.active = canEditConfig && isClaude;
            claudeEffortBtn.setMessage(claudeEffortLabel());
        }
        if (rateBox != null) {
            boolean editable = canEditConfig && !workingConfig.isAutoRateLimit();
            rateBox.setEditable(editable);
            rateBox.active = editable;
            if (!editable) {
                rateBox.setFocused(false);
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
            int pendingFailures = running ? 0 : FtbTranslatorMod.service().latestSnapshot().stringsFailed();
            pauseResumeBtn.active = running ? !cancelRequested : pendingFailures > 0 && canEditConfig;
            if (!running && pendingFailures > 0) {
                pauseResumeBtn.setMessage(Component.literal(canEditConfig ? "§e↻ Tentar falhas" : "↻ Tentar falhas"));
            } else if (!running) {
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
                    canEditConfig ? "§b↻ Aplicar" : "↻ Aplicar"));
        }
        if (exportZipBtn != null) {
            exportZipBtn.active = canEditConfig;
            exportZipBtn.setMessage(Component.literal(canEditConfig ? "§eExportar" : "Exportar"));
        }
        if (importZipBtn != null) {
            importZipBtn.active = canEditConfig;
            importZipBtn.setMessage(Component.literal(canEditConfig ? "§dImportar" : "Importar"));
        }
        if (resetBtn != null) {
            resetBtn.active = canEditConfig;
            resetBtn.setMessage(Component.literal(
                    canEditConfig ? "§c🗑 Resetar Cache e Traduções" : "🗑 Resetar Cache e Traduções"));
        }
        if (editorBtn != null) {
            editorBtn.active = canEditConfig;
            editorBtn.setMessage(Component.literal(
                    canEditConfig ? "§b✏ Editor de Traduções" : "✏ Editor de Traduções"));
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
            filledW = 6;
        }
        if (filledW > 0) {
            int barColor = snap.paused() ? 0xFFD99B26 : (snap.running() ? 0xFF2EA043 : 0xFF388BFD);
            g.fill(barX, BAR_Y, barX + Math.min(barW, filledW), BAR_Y + barH, barColor);
        }
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
        String etaSuffix = snap.formattedEta().isEmpty() ? "" : " | ETA " + snap.formattedEta();
        String barText = snap.phase() + " — " + pct + "% | Lote "
                + snap.batchIndex() + "/" + snap.batchTotal()
                + " | Arq: " + snap.tasksWritten() + "/" + snap.tasksTotal()
                + etaSuffix;
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
