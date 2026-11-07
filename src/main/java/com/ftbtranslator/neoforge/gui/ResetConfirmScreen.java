package com.ftbtranslator.neoforge.gui;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;

/**
 * Confirmation screen shown when the player clicks "Resetar Cache e Traduções", explaining in plain
 * Portuguese what the cache and generated translations are, what will be removed/restored, and what is
 * kept safe (such as their API Key and exported .zip bundles).
 */
public final class ResetConfirmScreen extends Screen {

    private final TranslatorDashboardScreen parent;

    public ResetConfirmScreen(TranslatorDashboardScreen parent) {
        super(Component.literal("Confirmar Reset do Cache e das Traduções"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        int centerX = this.width / 2;
        int btnY = this.height - 32;
        int btnW = 205;
        int gap = 10;

        this.addRenderableWidget(Button.builder(
                        Component.literal("§a← Cancelar e Voltar"),
                        b -> onClose())
                .bounds(centerX - btnW - gap / 2, btnY, btnW, 20)
                .build());

        this.addRenderableWidget(Button.builder(
                        Component.literal("§c🗑 Sim, Apagar Tudo e Restaurar"),
                        b -> {
                            parent.performConfirmedReset();
                            onClose();
                        })
                .bounds(centerX + gap / 2, btnY, btnW, 20)
                .build());
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.render(g, mouseX, mouseY, partialTick);

        int centerX = this.width / 2;
        int left = Math.max(20, centerX - 220);
        int right = Math.min(this.width - 20, centerX + 220);
        int textWidth = right - left;

        g.fill(left - 8, 12, right + 8, this.height - 40, 0xCC10141D);
        g.renderOutline(left - 8, 12, (right - left) + 16, this.height - 52, 0xFF6B3A3A);

        g.drawCenteredString(this.font,
                "§c§l⚠ Tem certeza que deseja resetar o cache e as traduções?",
                centerX, 18, 0xFFFFFF);

        int y = 34;
        y = drawParagraph(g,
                "§fEsta opção serve para §eremover todas as traduções geradas pelo mod §fe fazer o modpack "
                        + "voltar ao estado original (ou para testar uma nova tradução do zero).",
                left, y, textWidth);
        y += 4;

        g.drawString(this.font, "§c§lO que será apagado / restaurado:", left, y, 0xFFFF5555);
        y += 12;

        y = drawParagraph(g,
                "§e• Cache de traduções da IA: §7é a \"memória\" onde o mod guarda os textos já traduzidos "
                        + "para não gastar tempo nem tokens da IA de novo. Ao apagá-lo, a próxima tradução "
                        + "começará do zero.",
                left + 6, y, textWidth - 6);
        y += 2;

        y = drawParagraph(g,
                "§e• Pacote de Recursos (AutoTranslated_Pack_PTBR): §7remove as traduções geradas pelo mod "
                        + "para itens, blocos, menus e livros de guia (Patchouli).",
                left + 6, y, textWidth - 6);
        y += 2;

        y = drawParagraph(g,
                "§e• Missões (FTB Quests) e KubeJS: §7apaga os arquivos em português criados pelo mod e "
                        + "restaura automaticamente o backup original das missões do modpack.",
                left + 6, y, textWidth - 6);
        y += 5;

        g.drawString(this.font, "§a§lO que NÃO será apagado (continua salvo):", left, y, 0xFF55FF55);
        y += 12;

        y = drawParagraph(g,
                "§a✔ Sua API Key, provedor de IA e configurações §7continuam salvos normalmente.",
                left + 6, y, textWidth - 6);
        drawParagraph(g,
                "§a✔ Pacotes .zip exportados §7na pasta §fexports/ §7e as traduções oficiais que já vêm "
                        + "dentro dos próprios mods não são afetados.",
                left + 6, y, textWidth - 6);
    }

    private int drawParagraph(GuiGraphics g, String text, int x, int startY, int maxWidth) {
        int y = startY;
        for (FormattedCharSequence line : this.font.split(Component.literal(text), maxWidth)) {
            g.drawString(this.font, line, x, y, 0xFFE0E0E0);
            y += 10;
        }
        return y;
    }

    @Override
    public void onClose() {
        if (this.minecraft != null) {
            this.minecraft.setScreen(parent);
        }
    }
}
