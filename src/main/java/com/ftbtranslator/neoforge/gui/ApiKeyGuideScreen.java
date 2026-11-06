package com.ftbtranslator.neoforge.gui;

import net.minecraft.Util;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;

import java.net.URI;

/**
 * Step-by-step guide in Portuguese showing how to obtain a free API Key in under 1 minute (Google
 * Gemini Studio, Groq Cloud or 100% local Ollama), with one-click buttons that open the official pages in
 * the operating system's browser via {@code Util.getPlatform().openUri(...)}.
 */
public final class ApiKeyGuideScreen extends Screen {

    public static final String URL_GEMINI_KEYS = "https://aistudio.google.com/app/apikey";
    public static final String URL_GROQ_KEYS = "https://console.groq.com/keys";
    public static final String URL_OPENROUTER_KEYS = "https://openrouter.ai/keys";

    private final Screen parent;

    public ApiKeyGuideScreen(Screen parent) {
        super(Component.literal("Como pegar uma API Key Gratuita (em 1 minuto)"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        int centerX = this.width / 2;
        int btnY = this.height - 56;
        int btnW = 148;
        int gap = 6;
        int totalRowW = btnW * 3 + gap * 2;
        int startX = centerX - totalRowW / 2;

        // Clickable buttons opening the official provider key URLs in the OS default browser
        this.addRenderableWidget(Button.builder(
                        Component.literal("§aAbrir Google AI Studio"),
                        b -> Util.getPlatform().openUri(URI.create(URL_GEMINI_KEYS)))
                .bounds(startX, btnY, btnW, 20)
                .build());

        this.addRenderableWidget(Button.builder(
                        Component.literal("§bAbrir Groq Cloud Keys"),
                        b -> Util.getPlatform().openUri(URI.create(URL_GROQ_KEYS)))
                .bounds(startX + btnW + gap, btnY, btnW, 20)
                .build());

        this.addRenderableWidget(Button.builder(
                        Component.literal("§eAbrir OpenRouter Keys"),
                        b -> Util.getPlatform().openUri(URI.create(URL_OPENROUTER_KEYS)))
                .bounds(startX + (btnW + gap) * 2, btnY, btnW, 20)
                .build());

        this.addRenderableWidget(Button.builder(
                        CommonComponents.GUI_BACK,
                        b -> onClose())
                .bounds(centerX - 90, this.height - 30, 180, 20)
                .build());
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.render(g, mouseX, mouseY, partialTick);

        int centerX = this.width / 2;
        int left = Math.max(20, centerX - 220);
        int right = Math.min(this.width - 20, centerX + 220);

        g.fill(left - 8, 12, right + 8, this.height - 62, 0xCC10141D);
        g.renderOutline(left - 8, 12, (right - left) + 16, this.height - 74, 0xFF3A4A6B);

        g.drawCenteredString(this.font, "§6§lComo Obter uma API Key Gratuita (1 Minuto)", centerX, 18, 0xFFFFFF);

        int y = 34;
        int lineH = 11;

        // Option 1 - Google Gemini Studio
        g.drawString(this.font, "§a§lOpção 1 (Recomendada - Google Gemini Studio):", left, y, 0x55FF55);
        y += lineH;
        g.drawString(this.font, "§f1. Clique no botão §a'Abrir Google AI Studio' §fabaixo.", left + 8, y, 0xE0E0E0);
        y += lineH;
        g.drawString(this.font, "§f2. Faça login com sua conta Google normal.", left + 8, y, 0xE0E0E0);
        y += lineH;
        g.drawString(this.font, "§f3. Clique em §e'Get API Key' §f-> §e'Create API Key'§f.", left + 8, y, 0xE0E0E0);
        y += lineH;
        g.drawString(this.font, "§f4. Copie a chave gerada, volte ao jogo e clique em §a'Colar Key'§f.", left + 8, y, 0xE0E0E0);
        y += lineH + 4;

        // Option 2 - Groq Cloud
        g.drawString(this.font, "§b§lOpção 2 (Groq Cloud - Gratuito e Ultra Rápido):", left, y, 0x55FFFF);
        y += lineH;
        g.drawString(this.font, "§f1. Clique em §b'Abrir Groq Cloud Keys' §fe entre com Google ou GitHub.", left + 8, y, 0xE0E0E0);
        y += lineH;
        g.drawString(this.font, "§f2. Clique em §e'Create API Key'§f, dê um nome qualquer e copie a chave (§7gsk_...§f).", left + 8, y, 0xE0E0E0);
        y += lineH;
        g.drawString(this.font, "§f3. Na tela principal, mude o Provedor para §bGroq §fe clique em §a'Colar Key'§f.", left + 8, y, 0xE0E0E0);
        y += lineH + 4;

        // Option 3 - Ollama Local
        g.drawString(this.font, "§e§lOpção 3 (Ollama - 100% Local e Sem Internet/Key):", left, y, 0xFFFF55);
        y += lineH;
        g.drawString(this.font, "§f1. Instale o Ollama (§7https://ollama.com§f) e rode §7ollama run qwen2.5:7b§f no terminal.", left + 8, y, 0xE0E0E0);
        y += lineH;
        g.drawString(this.font, "§f2. Selecione o Provedor §eOllama Local §f(não precisa de API Key!).", left + 8, y, 0xE0E0E0);
    }

    @Override
    public void onClose() {
        if (this.minecraft != null) {
            this.minecraft.setScreen(parent);
        }
    }
}
