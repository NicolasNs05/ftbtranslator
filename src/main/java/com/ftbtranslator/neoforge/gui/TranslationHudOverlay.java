package com.ftbtranslator.neoforge.gui;

import com.ftbtranslator.core.ProgressSnapshot;
import com.ftbtranslator.neoforge.ClientConfig;
import com.ftbtranslator.neoforge.FtbTranslatorMod;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.client.event.CustomizeGuiOverlayEvent;

/**
 * Boss-bar style translation progress shown at the top centre of the HUD (like the Wither's bar), drawn with
 * the vanilla boss bar sprites:
 * <ul>
 *   <li>purple while translating, yellow while paused;</li>
 *   <li>green for a few seconds after the run completes, red when it was cancelled or failed;</li>
 *   <li>below the bar: tokens used so far and texts translated by the AI.</li>
 * </ul>
 * It sits under any real boss bars on screen, hides with F1, while the dashboard is open (which has its own
 * bar) and when disabled in the dashboard / {@code showHudProgressBar}.
 */
public final class TranslationHudOverlay {

    public static final ResourceLocation LAYER_ID =
            ResourceLocation.fromNamespaceAndPath(FtbTranslatorMod.MOD_ID, "translation_progress");

    private static final int BAR_WIDTH = 182;
    private static final int BAR_HEIGHT = 5;
    private static final int FIRST_BAR_Y = 12;
    private static final long FINISHED_DISPLAY_MS = 10_000L;

    private enum Color {
        PURPLE, YELLOW, GREEN, RED;

        final ResourceLocation background;
        final ResourceLocation progress;

        Color() {
            String name = name().toLowerCase(java.util.Locale.ROOT);
            this.background = ResourceLocation.withDefaultNamespace("boss_bar/" + name + "_background");
            this.progress = ResourceLocation.withDefaultNamespace("boss_bar/" + name + "_progress");
        }
    }

    /** Bottom of the real boss bars drawn this frame (-1 when none), so this bar goes below them. */
    private static int bossBarsBottomY = -1;
    private static boolean wasRunning;
    private static long finishedAtMillis;

    private TranslationHudOverlay() {
    }

    /** Called for every vanilla / modded boss bar rendered this frame (before this layer). */
    public static void onBossEventProgress(CustomizeGuiOverlayEvent.BossEventProgress event) {
        bossBarsBottomY = Math.max(bossBarsBottomY, event.getY() + event.getIncrement());
    }

    public static void render(GuiGraphics g, DeltaTracker deltaTracker) {
        int bossBottom = bossBarsBottomY;
        bossBarsBottomY = -1;

        Minecraft mc = Minecraft.getInstance();
        ProgressSnapshot snap = FtbTranslatorMod.service().latestSnapshot();
        long now = System.currentTimeMillis();
        if (snap.running()) {
            wasRunning = true;
        } else if (wasRunning) {
            wasRunning = false;
            finishedAtMillis = now;
        }
        boolean showFinished = !snap.running() && finishedAtMillis > 0L && now - finishedAtMillis < FINISHED_DISPLAY_MS;
        if (!snap.running() && !showFinished) {
            return;
        }
        if (mc.options.hideGui || !ClientConfig.showHudBar() || mc.screen instanceof TranslatorDashboardScreen) {
            return;
        }

        String title;
        Color color;
        double fraction = snap.fraction();
        int pct = (int) Math.round(fraction * 100.0);
        if (snap.running()) {
            if (snap.paused()) {
                title = "Tradução pausada: " + pct + "%";
                color = Color.YELLOW;
            } else {
                title = "Traduzindo modpack: " + pct + "%"
                        + (snap.batchTotal() > 0 ? " · Lote " + snap.batchIndex() + "/" + snap.batchTotal() : "");
                color = Color.PURPLE;
            }
        } else if ("Concluído".equals(snap.phase())) {
            title = "Tradução concluída!";
            color = Color.GREEN;
            fraction = 1.0;
        } else {
            title = "Erro".equals(snap.phase()) ? "Tradução interrompida por erro" : "Tradução cancelada";
            color = Color.RED;
        }

        Font font = mc.font;
        int x = g.guiWidth() / 2 - BAR_WIDTH / 2;
        int y = Math.max(FIRST_BAR_Y, bossBottom);

        g.drawString(font, title, g.guiWidth() / 2 - font.width(title) / 2, y - 9, 0xFFFFFFFF);
        g.blitSprite(color.background, x, y, BAR_WIDTH, BAR_HEIGHT);
        int filled = (int) Math.round(Math.max(0.0, Math.min(1.0, fraction)) * BAR_WIDTH);
        if (filled > 0) {
            g.blitSprite(color.progress, BAR_WIDTH, BAR_HEIGHT, 0, 0, x, y, filled, BAR_HEIGHT);
        }

        String detail = "§7Tokens: §f" + ProgressSnapshot.formatCount(snap.totalTokens())
                + " §8| §7IA: §f" + ProgressSnapshot.formatCount(snap.stringsTranslatedByAi()) + " §7textos";
        g.drawString(font, detail, g.guiWidth() / 2 - font.width(detail) / 2, y + BAR_HEIGHT + 2, 0xFFFFFFFF);
    }
}
