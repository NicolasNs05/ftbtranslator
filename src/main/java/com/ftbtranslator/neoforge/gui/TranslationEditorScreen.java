package com.ftbtranslator.neoforge.gui;

import com.ftbtranslator.core.TranslatorConfigData;
import com.ftbtranslator.core.editor.TranslationOverrideManager;
import com.ftbtranslator.core.editor.TranslationOverrideManager.EditableEntry;
import com.ftbtranslator.neoforge.FtbTranslatorMod;
import com.ftbtranslator.neoforge.ResourcePackApplier;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;

/**
 * In-game translation search and override editor allowing players to find any translated string across
 * FTB Quests, KubeJS, and Mods, edit its Portuguese text directly, and persist the override both to the
 * generated file and to the SHA-256 translation cache.
 */
public final class TranslationEditorScreen extends Screen {

    private static final int PANEL_WIDTH = 430;
    private static final int PAGE_SIZE = 5;

    private final TranslatorDashboardScreen parent;
    private final TranslatorConfigData config;

    private volatile List<EditableEntry> allEntries = List.of();
    private List<EditableEntry> filteredEntries = new ArrayList<>();
    private boolean loading = true;
    private int currentPage = 0;
    private EditableEntry selectedEntry;

    private EditBox searchBox;
    private final Button[] rowButtons = new Button[PAGE_SIZE];
    private Button prevPageBtn;
    private Button nextPageBtn;
    private EditBox editValueBox;
    private Button saveOverrideBtn;
    private Button applyInGameBtn;

    private String statusMessage = "Carregando textos traduzidos do disco...";
    private int statusColor = 0xFFFFFF55;

    public TranslationEditorScreen(TranslatorDashboardScreen parent, TranslatorConfigData config) {
        super(Component.literal("Editor Rápido de Traduções (In-Game)"));
        this.parent = parent;
        this.config = config;
        loadEntriesAsync();
    }

    private void loadEntriesAsync() {
        loading = true;
        CompletableFuture.supplyAsync(() -> {
            try {
                return TranslationOverrideManager.loadAllEntries(
                        FtbTranslatorMod.service().paths(),
                        config.getTargetLanguage());
            } catch (IOException e) {
                throw new RuntimeException(e.getMessage(), e);
            }
        }).whenComplete((loaded, err) -> Minecraft.getInstance().execute(() -> {
            loading = false;
            if (err != null) {
                Throwable cause = err.getCause() != null ? err.getCause() : err;
                statusMessage = "Erro ao carregar traduções: " + cause.getMessage();
                statusColor = 0xFFFF5555;
                allEntries = List.of();
            } else {
                allEntries = new ArrayList<>(loaded);
                if (allEntries.isEmpty()) {
                    statusMessage = "Nenhuma tradução gerada encontrada ainda. Execute uma tradução primeiro!";
                    statusColor = 0xFFFFAA00;
                } else {
                    statusMessage = allEntries.size() + " traduções carregadas. Digite acima para filtrar e clique em um item.";
                    statusColor = 0xFF55FF55;
                }
            }
            rebuildFilteredList();
        }));
    }

    private int panelLeft() {
        return this.width / 2 - PANEL_WIDTH / 2;
    }

    @Override
    protected void init() {
        int left = panelLeft();

        String prevQuery = searchBox != null ? searchBox.getValue() : "";
        searchBox = new EditBox(this.font, left + 56, 24, 240, 18, Component.literal("Buscar"));
        searchBox.setMaxLength(128);
        searchBox.setValue(prevQuery);
        searchBox.setHint(Component.literal("§8Buscar em inglês, português ou mod..."));
        searchBox.setResponder(q -> {
            currentPage = 0;
            rebuildFilteredList();
        });
        this.addRenderableWidget(searchBox);

        prevPageBtn = this.addRenderableWidget(Button.builder(
                        Component.literal("◀"),
                        b -> {
                            if (currentPage > 0) {
                                currentPage--;
                                refreshRowsAndButtons();
                            }
                        })
                .bounds(left + 302, 23, 24, 20)
                .build());

        nextPageBtn = this.addRenderableWidget(Button.builder(
                        Component.literal("▶"),
                        b -> {
                            if ((currentPage + 1) * PAGE_SIZE < filteredEntries.size()) {
                                currentPage++;
                                refreshRowsAndButtons();
                            }
                        })
                .bounds(left + 406, 23, 24, 20)
                .build());

        int listStartY = 48;
        for (int i = 0; i < PAGE_SIZE; i++) {
            final int rowOffset = i;
            rowButtons[i] = this.addRenderableWidget(Button.builder(
                            Component.literal(""),
                            b -> selectRow(rowOffset))
                    .bounds(left, listStartY + i * 21, PANEL_WIDTH, 20)
                    .build());
        }

        int editY = 172;
        String prevEdit = editValueBox != null ? editValueBox.getValue()
                : (selectedEntry != null ? selectedEntry.translatedText() : "");
        editValueBox = new EditBox(this.font, left, editY, PANEL_WIDTH, 20, Component.literal("Tradução PT-BR"));
        editValueBox.setMaxLength(1024);
        editValueBox.setValue(prevEdit);
        editValueBox.setHint(Component.literal("§8Selecione uma linha acima para editar a tradução..."));
        this.addRenderableWidget(editValueBox);

        int footerY = 212;
        saveOverrideBtn = this.addRenderableWidget(Button.builder(
                        Component.literal("§a💾 Salvar Correção"),
                        b -> saveSelectedOverride())
                .bounds(left, footerY, 138, 20)
                .build());
        saveOverrideBtn.setTooltip(Tooltip.create(Component.literal(
                "Salva a tradução editada no arquivo correspondente e também no cache SHA-256 "
                        + "para que futuras traduções respeitem sua correção.")));

        applyInGameBtn = this.addRenderableWidget(Button.builder(
                        Component.literal("§b↻ Aplicar no Jogo"),
                        b -> {
                            ResourcePackApplier.applyOnMainThread(FtbTranslatorMod.service().paths(), true);
                            statusMessage = "Recursos e missões recarregados no jogo!";
                            statusColor = 0xFF55FFFF;
                        })
                .bounds(left + 144, footerY, 138, 20)
                .build());
        applyInGameBtn.setTooltip(Tooltip.create(Component.literal(
                "Recarrega o pacote de recursos e o FTB Quests imediatamente para ver as correções.")));

        this.addRenderableWidget(Button.builder(
                        Component.literal("← Voltar ao Painel"),
                        b -> onClose())
                .bounds(left + PANEL_WIDTH - 138, footerY, 138, 20)
                .build());

        rebuildFilteredList();
    }

    private void rebuildFilteredList() {
        String query = searchBox != null ? searchBox.getValue().trim().toLowerCase(Locale.ROOT) : "";
        filteredEntries = new ArrayList<>();
        for (EditableEntry e : allEntries) {
            if (e.matchesQuery(query)) {
                filteredEntries.add(e);
            }
        }
        int maxPage = Math.max(0, (filteredEntries.size() - 1) / PAGE_SIZE);
        if (currentPage > maxPage) {
            currentPage = maxPage;
        }
        if (selectedEntry == null && !filteredEntries.isEmpty()) {
            selectedEntry = filteredEntries.get(0);
            if (editValueBox != null) {
                editValueBox.setValue(selectedEntry.translatedText());
            }
        }
        refreshRowsAndButtons();
    }

    private void selectRow(int rowOffset) {
        int idx = currentPage * PAGE_SIZE + rowOffset;
        if (idx >= 0 && idx < filteredEntries.size()) {
            selectedEntry = filteredEntries.get(idx);
            if (editValueBox != null) {
                editValueBox.setValue(selectedEntry.translatedText());
            }
            refreshRowsAndButtons();
        }
    }

    private void saveSelectedOverride() {
        if (selectedEntry == null || editValueBox == null) {
            return;
        }
        String newText = editValueBox.getValue().trim();
        if (newText.isEmpty()) {
            statusMessage = "A tradução não pode ficar vazia.";
            statusColor = 0xFFFF5555;
            return;
        }
        try {
            EditableEntry updated = TranslationOverrideManager.saveOverride(
                    FtbTranslatorMod.service().paths(),
                    config,
                    selectedEntry,
                    newText);
            List<EditableEntry> mutable = new ArrayList<>(allEntries);
            int idxInAll = mutable.indexOf(selectedEntry);
            if (idxInAll >= 0) {
                mutable.set(idxInAll, updated);
            }
            allEntries = mutable;
            selectedEntry = updated;
            rebuildFilteredList();
            statusMessage = "✔ Salvo em disco e no cache! Clique em '↻ Aplicar no Jogo' para atualizar.";
            statusColor = 0xFF55FF55;
        } catch (IOException e) {
            statusMessage = "Erro ao salvar: " + e.getMessage();
            statusColor = 0xFFFF5555;
        }
    }

    private void refreshRowsAndButtons() {
        int startIdx = currentPage * PAGE_SIZE;
        for (int i = 0; i < PAGE_SIZE; i++) {
            Button btn = rowButtons[i];
            if (btn == null) {
                continue;
            }
            int itemIdx = startIdx + i;
            if (itemIdx < filteredEntries.size()) {
                EditableEntry entry = filteredEntries.get(itemIdx);
                boolean isSelected = entry.equals(selectedEntry);
                String prefix = isSelected ? "§e▶ " : "§7";
                String origShort = shorten(entry.originalText(), 24);
                String ptShort = shorten(entry.translatedText(), 32);
                String label = prefix + "§b[" + shorten(entry.sourceLabel(), 14) + "] §f"
                        + origShort + " §7→ §a" + ptShort;
                btn.setMessage(Component.literal(label));
                btn.active = true;
                btn.visible = true;
            } else {
                btn.setMessage(Component.literal(""));
                btn.active = false;
                btn.visible = false;
            }
        }
        if (prevPageBtn != null) {
            prevPageBtn.active = currentPage > 0;
        }
        if (nextPageBtn != null) {
            nextPageBtn.active = (currentPage + 1) * PAGE_SIZE < filteredEntries.size();
        }
        boolean hasSelection = selectedEntry != null && !loading;
        if (editValueBox != null) {
            editValueBox.setEditable(hasSelection);
            editValueBox.active = hasSelection;
        }
        if (saveOverrideBtn != null) {
            saveOverrideBtn.active = hasSelection;
        }
        if (applyInGameBtn != null) {
            applyInGameBtn.active = !loading && !allEntries.isEmpty();
        }
    }

    private static String shorten(String s, int max) {
        if (s == null) {
            return "";
        }
        String clean = s.replace('\n', ' ').strip();
        return clean.length() > max ? clean.substring(0, max - 2) + ".." : clean;
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.render(g, mouseX, mouseY, partialTick);

        int centerX = this.width / 2;
        int left = panelLeft();

        g.drawCenteredString(this.font,
                "§b§lEditor Rápido de Traduções §7(Pesquisa e Correção In-Game)",
                centerX, 8, 0xFFFFFFFF);
        g.drawString(this.font, "§fBuscar:", left, 29, 0xFFE0E0E0);

        int totalPages = Math.max(1, (filteredEntries.size() + PAGE_SIZE - 1) / PAGE_SIZE);
        String pageStr = "§e" + (currentPage + 1) + "/" + totalPages
                + " §8(" + filteredEntries.size() + ")";
        g.drawCenteredString(this.font, pageStr, left + 366, 29, 0xFFFFFFFF);

        if (selectedEntry != null) {
            String origLine = "§7Original (" + selectedEntry.sourceLabel() + "): §f"
                    + shorten(selectedEntry.originalText(), 64);
            g.drawString(this.font, origLine, left, 159, 0xFFFFFFFF);
        } else {
            g.drawString(this.font, "§8Nenhum texto selecionado.", left, 159, 0xFFA0A0A0);
        }

        g.drawString(this.font, shorten(statusMessage, 82), left, 196, statusColor);
    }

    @Override
    public void onClose() {
        if (this.minecraft != null) {
            this.minecraft.setScreen(parent);
        }
    }
}
