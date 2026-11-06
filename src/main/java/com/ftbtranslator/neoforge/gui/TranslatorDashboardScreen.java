package com.ftbtranslator.neoforge.gui;

import com.ftbtranslator.core.ProgressSnapshot;
import com.ftbtranslator.core.TranslationScope;
import com.ftbtranslator.core.TranslatorConfigData;
import com.ftbtranslator.core.bundle.TranslationBundleManager;
import com.ftbtranslator.neoforge.ClientConfig;
import com.ftbtranslator.neoforge.FtbTranslatorMod;
import com.ftbtranslator.neoforge.ResourcePackApplier;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.util.FormattedCharSequence;

import java.io.IOException;

/**
 * Main in-game dashboard ({@code F9} or NeoForge Mods -> Config) covering provider & API key management,
 * scope checkboxes, thematic context presets, live progress bar and one-click bundle export/import.
 * <p>
 * All configuration controls and conflicting actions are strictly locked while a translation job (or API
 * connection test) is running.
 */
public final class TranslatorDashboardScreen extends Screen {

    private final Screen parent;
    private final TranslatorConfigData workingConfig;

    private Button providerBtn;
    private Button guideBtn;
    private EditBox apiKeyBox;
    private Button toggleMaskBtn;
    private Button pasteKeyBtn;
    private Button testConnBtn;

    private Button ftbQuestsBtn;
    private Button kubeJsBtn;
    private Button modsBtn;
    private Button patchouliBtn;

    private Button contextBtn;
    private EditBox modFilterBox;

    private Button startBtn;
    private Button pauseResumeBtn;
    private Button cancelBtn;
    private Button applyReloadBtn;
    private Button exportZipBtn;
    private Button importZipBtn;

    private boolean maskApiKey = true;
    private boolean testingConnection = false;
    private boolean cancelRequested = false;
    private String bannerMessage = "Pronto. Configure sua API Key ou importe um pacote .zip.";
    private int bannerColor = 0xFFE0E0E0;

    public TranslatorDashboardScreen(Screen parent) {
        super(Component.literal("Universal Modpack & Quests Auto-Translator (PT-BR)"));
        this.parent = parent;
        this.workingConfig = ClientConfig.toCoreData();
    }

    @Override
    protected void init() {
        int centerX = this.width / 2;
        int panelLeft = centerX - 210;
        int panelWidth = 420;

        // ------------------------------------------------------------------ Section 1: Provider & API Key
        int row1Y = 30;
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
                .bounds(panelLeft, row1Y, 205, 20)
                .build());

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
                .bounds(panelLeft + 210, row1Y, 210, 20)
                .build());

        int row2Y = 54;
        apiKeyBox = new EditBox(this.font, panelLeft + 54, row2Y, 178, 20, Component.literal("API Key"));
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
        this.addRenderableWidget(apiKeyBox);

        toggleMaskBtn = this.addRenderableWidget(Button.builder(
                        Component.literal(maskApiKey ? "👁" : "🔒"),
                        b -> {
                            maskApiKey = !maskApiKey;
                            b.setMessage(Component.literal(maskApiKey ? "👁" : "🔒"));
                            applyKeyMaskFormatter();
                        })
                .bounds(panelLeft + 234, row2Y, 22, 20)
                .build());

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
                .bounds(panelLeft + 258, row2Y, 68, 20)
                .build());

        testConnBtn = this.addRenderableWidget(Button.builder(
                        Component.literal("Testar Conexão"),
                        b -> runConnectionTest())
                .bounds(panelLeft + 328, row2Y, 92, 20)
                .build());

        // ------------------------------------------------------------------ Section 2: Scopes & Filters
        int row3Y = 92;
        int scopeW = 102;
        int scopeGap = 4;

        ftbQuestsBtn = this.addRenderableWidget(Button.builder(
                        scopeLabel(TranslationScope.FTBQUESTS),
                        b -> toggleScope(TranslationScope.FTBQUESTS, b))
                .bounds(panelLeft, row3Y, scopeW, 20)
                .build());

        kubeJsBtn = this.addRenderableWidget(Button.builder(
                        scopeLabel(TranslationScope.KUBEJS),
                        b -> toggleScope(TranslationScope.KUBEJS, b))
                .bounds(panelLeft + (scopeW + scopeGap), row3Y, scopeW, 20)
                .build());

        modsBtn = this.addRenderableWidget(Button.builder(
                        scopeLabel(TranslationScope.MODS),
                        b -> toggleScope(TranslationScope.MODS, b))
                .bounds(panelLeft + (scopeW + scopeGap) * 2, row3Y, scopeW, 20)
                .build());

        patchouliBtn = this.addRenderableWidget(Button.builder(
                        scopeLabel(TranslationScope.PATCHOULI),
                        b -> toggleScope(TranslationScope.PATCHOULI, b))
                .bounds(panelLeft + (scopeW + scopeGap) * 3, row3Y, scopeW, 20)
                .build());

        int row4Y = 116;
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
                .bounds(panelLeft, row4Y, 175, 20)
                .build());

        modFilterBox = new EditBox(this.font, panelLeft + 250, row4Y, 170, 20, Component.literal("Filtro Mods"));
        modFilterBox.setMaxLength(256);
        modFilterBox.setValue(workingConfig.getModFilter());
        modFilterBox.setHint(Component.literal("§8tensura, manas (vazio=todos)"));
        modFilterBox.setResponder(val -> {
            if (!isBusy()) {
                workingConfig.setModFilter(val);
                persistWorkingConfig();
            }
        });
        this.addRenderableWidget(modFilterBox);

        // ------------------------------------------------------------------ Section 3: Execution & Sharing
        int actionRow1Y = 186;
        // 4 execution buttons: Iniciar (108), Pausar/Retomar (96), Cancelar Agora (96), Aplicar e Recarregar (108)
        startBtn = this.addRenderableWidget(Button.builder(
                        Component.literal("▶ Iniciar Tradução"),
                        b -> triggerStartTranslation())
                .bounds(panelLeft, actionRow1Y, 108, 20)
                .build());

        pauseResumeBtn = this.addRenderableWidget(Button.builder(
                        Component.literal("⏸ Pausar"),
                        b -> triggerTogglePause())
                .bounds(panelLeft + 111, actionRow1Y, 96, 20)
                .build());

        cancelBtn = this.addRenderableWidget(Button.builder(
                        Component.literal("■ Cancelar Agora"),
                        b -> triggerCancel())
                .bounds(panelLeft + 210, actionRow1Y, 96, 20)
                .build());

        applyReloadBtn = this.addRenderableWidget(Button.builder(
                        Component.literal("↻ Aplicar e Recarregar"),
                        b -> {
                            if (isBusy()) {
                                return;
                            }
                            ResourcePackApplier.applyOnMainThread(FtbTranslatorMod.service().paths(), true);
                            setBanner("Resource Pack ativado e recursos recarregados!", 0xFF55FFFF);
                        })
                .bounds(panelLeft + 309, actionRow1Y, 111, 20)
                .build());

        int actionRow2Y = 210;
        exportZipBtn = this.addRenderableWidget(Button.builder(
                        Component.literal("Exportar"),
                        b -> triggerExportZip())
                .bounds(panelLeft, actionRow2Y, 165, 20)
                .build());

        importZipBtn = this.addRenderableWidget(Button.builder(
                        Component.literal("Importar"),
                        b -> triggerImportZip())
                .bounds(panelLeft + 169, actionRow2Y, 165, 20)
                .build());

        this.addRenderableWidget(Button.builder(
                        Component.literal("Fechar"),
                        b -> onClose())
                .bounds(panelLeft + 338, actionRow2Y, 82, 20)
                .build());

        refreshButtonStates();
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
        boolean on = workingConfig.isScopeEnabled(scope);
        String prefix = on ? "§a[x] §f" : "§7[ ] §8";
        return Component.literal(prefix + scope.displayName());
    }

    private void toggleScope(TranslationScope scope, Button btn) {
        if (isBusy()) {
            return;
        }
        boolean next = !workingConfig.isScopeEnabled(scope);
        workingConfig.setScopeEnabled(scope, next);
        btn.setMessage(scopeLabel(scope));
        persistWorkingConfig();
    }

    private void persistWorkingConfig() {
        ClientConfig.saveFromCoreData(workingConfig);
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

    /**
     * Strictly locks / unlocks buttons and text boxes depending on whether the translator is currently
     * working or idle.
     */
    private void refreshButtonStates() {
        boolean running = FtbTranslatorMod.service().isRunning();
        if (!running) {
            cancelRequested = false;
        }
        boolean paused = FtbTranslatorMod.service().isPaused();
        boolean canEditConfig = !running && !testingConnection;

        // Lock configuration controls while working
        if (providerBtn != null) {
            providerBtn.active = canEditConfig;
        }
        if (guideBtn != null) {
            guideBtn.active = canEditConfig;
        }
        if (apiKeyBox != null) {
            apiKeyBox.setEditable(canEditConfig);
            apiKeyBox.active = canEditConfig;
            if (!canEditConfig) {
                apiKeyBox.setFocused(false);
            }
        }
        if (toggleMaskBtn != null) {
            toggleMaskBtn.active = canEditConfig;
        }
        if (pasteKeyBtn != null) {
            pasteKeyBtn.active = canEditConfig;
        }
        if (testConnBtn != null) {
            testConnBtn.active = canEditConfig;
            testConnBtn.setMessage(Component.literal(testingConnection ? "Testando..." : "Testar Conexão"));
        }
        if (ftbQuestsBtn != null) {
            ftbQuestsBtn.active = canEditConfig;
        }
        if (kubeJsBtn != null) {
            kubeJsBtn.active = canEditConfig;
        }
        if (modsBtn != null) {
            modsBtn.active = canEditConfig;
        }
        if (patchouliBtn != null) {
            patchouliBtn.active = canEditConfig;
        }
        if (contextBtn != null) {
            contextBtn.active = canEditConfig;
        }
        if (modFilterBox != null) {
            modFilterBox.setEditable(canEditConfig);
            modFilterBox.active = canEditConfig;
            if (!canEditConfig) {
                modFilterBox.setFocused(false);
            }
        }

        // Execution & sharing buttons
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

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        refreshButtonStates();
        super.render(g, mouseX, mouseY, partialTick);

        int centerX = this.width / 2;
        int panelLeft = centerX - 210;
        int panelWidth = 420;

        // Title
        g.drawCenteredString(this.font, "§b§lUniversal Modpack & Quests Auto-Translator §7(PT-BR)", centerX, 8, 0xFFFFFF);

        // Section labels
        g.drawString(this.font, "§71. Provedor e Chave de API:", panelLeft, 20, 0xA0A0A0);
        g.drawString(this.font, "§fAPI Key:", panelLeft, 60, 0xE0E0E0);

        g.drawString(this.font, "§72. Frentes de Tradução, Contexto e Filtro:", panelLeft, 82, 0xA0A0A0);
        g.drawString(this.font, "§fFiltro Mods:", panelLeft + 182, 122, 0xE0E0E0);

        // Real-time progress bar
        ProgressSnapshot snap = FtbTranslatorMod.service().latestSnapshot();
        int barX = panelLeft;
        int barY = 142;
        int barW = panelWidth;
        int barH = 14;

        g.fill(barX, barY, barX + barW, barY + barH, 0xFF1A1F2C);
        int filledW = (int) Math.round(barW * snap.fraction());
        if (snap.running() && filledW < 6) {
            // Minimum visible bar indicator as soon as a run is active
            filledW = 6;
        }
        if (filledW > 0) {
            int barColor = snap.paused() ? 0xFFD99B26 : (snap.running() ? 0xFF2EA043 : 0xFF388BFD);
            g.fill(barX, barY, barX + Math.min(barW, filledW), barY + barH, barColor);
        }
        // Animated activity pulse when actively translating a batch
        if (snap.running() && !snap.paused()) {
            int pulseW = 36;
            int cycle = (int) ((System.currentTimeMillis() / 12L) % (barW + pulseW)) - pulseW;
            int pStart = Math.max(barX, barX + cycle);
            int pEnd = Math.min(barX + barW, barX + cycle + pulseW);
            if (pEnd > pStart) {
                g.fill(pStart, barY + 1, pEnd, barY + barH - 1, 0x4455FF88);
            }
        }
        g.renderOutline(barX, barY, barW, barH, 0xFF4A5A80);

        int pct = (int) Math.round(snap.fraction() * 100.0);
        String barText = snap.phase() + " — " + pct + "% | Lote "
                + snap.batchIndex() + "/" + snap.batchTotal()
                + " | Cache: " + snap.stringsFromCache()
                + " | Gravados: " + snap.tasksWritten() + "/" + snap.tasksTotal();
        g.drawCenteredString(this.font, barText, centerX, barY + 3, 0xFFFFFFFF);

        String itemLine = snap.running() && !snap.currentItem().isEmpty()
                ? "Status: " + snap.currentItem()
                : bannerMessage;
        if (itemLine.length() > 78) {
            itemLine = itemLine.substring(0, 75) + "...";
        }
        g.drawString(this.font, itemLine, panelLeft, 160, bannerColor);
        g.drawString(this.font, "§8Modelo ativo: §7" + workingConfig.effectiveModel()
                        + " §8| Rate-Limit: §7" + workingConfig.getRequestsPerMinute() + " req/min",
                panelLeft, 172, 0x909090);
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
