package com.ftbtranslator.neoforge;

import com.ftbtranslator.core.LogLevel;
import com.ftbtranslator.core.ProgressSnapshot;
import com.ftbtranslator.core.TranslationCallback;
import com.ftbtranslator.core.TranslationReport;
import com.ftbtranslator.core.TranslationScope;
import com.ftbtranslator.core.TranslationService;
import com.ftbtranslator.core.TranslatorConfigData;
import com.ftbtranslator.core.pack.ResourcePackGenerator;
import com.ftbtranslator.neoforge.gui.TranslationHudOverlay;
import com.ftbtranslator.neoforge.gui.TranslatorDashboardScreen;
import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.logging.LogUtils;
import net.minecraft.ChatFormatting;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.CustomizeGuiOverlayEvent;
import net.neoforged.neoforge.client.event.RegisterClientCommandsEvent;
import net.neoforged.neoforge.client.event.RegisterGuiLayersEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.client.event.ScreenEvent;
import net.neoforged.neoforge.client.gui.IConfigScreenFactory;
import net.neoforged.neoforge.client.gui.VanillaGuiLayers;
import net.neoforged.neoforge.common.NeoForge;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;

import java.nio.file.Files;
import java.util.Set;

/**
 * NeoForge 1.21.1 entry point for the Client-Side Universal Modpack & Quests Auto-Translator.
 */
@Mod(value = FtbTranslatorMod.MOD_ID, dist = Dist.CLIENT)
public final class FtbTranslatorMod {

    public static final String MOD_ID = "ftbtranslator";
    public static final String KEY_CATEGORY = "FTB Auto-Translator";
    private static final Logger LOGGER = LogUtils.getLogger();

    private static TranslationService service;
    private static KeyMapping openDashboardKey;
    private static boolean joinWorldHintShownThisSession = false;

    private static final TranslationCallback UI_CALLBACK = new TranslationCallback() {
        @Override
        public void onProgress(ProgressSnapshot snapshot) {
            // TranslatorDashboardScreen polls service().latestSnapshot() every frame.
        }

        @Override
        public void onLog(LogLevel level, String message) {
            switch (level) {
                case INFO -> LOGGER.info("[FTBTranslator] {}", message);
                case WARN -> LOGGER.warn("[FTBTranslator] {}", message);
                case ERROR -> LOGGER.error("[FTBTranslator] {}", message);
            }
        }

        @Override
        public void onPartialCheckpoint(boolean questsUpdated, boolean resourcePackUpdated) {
            Minecraft mc = Minecraft.getInstance();
            mc.execute(() -> {
                if (questsUpdated && mc.player != null && mc.player.connection != null) {
                    mc.player.connection.sendCommand("ftbquests reload");
                }
            });
        }

        @Override
        public void onComplete(TranslationReport report) {
            Minecraft mc = Minecraft.getInstance();
            mc.execute(() -> {
                if (mc.screen instanceof TranslatorDashboardScreen dashboard) {
                    dashboard.setBanner(report.message(), report.cancelled() ? 0xFFFFAA00 : 0xFF55FF55);
                }
                ResourcePackApplier.sendChatInfo(mc, report.message());
                if (report.producedOutput()) {
                    ResourcePackApplier.applyOnMainThread(
                            service().paths(),
                            report.questsChanged());
                }
            });
        }

        @Override
        public void onError(String message, Throwable cause) {
            if (cause != null) {
                LOGGER.error("[FTBTranslator] {}", message, cause);
            } else {
                LOGGER.error("[FTBTranslator] {}", message);
            }
            Minecraft mc = Minecraft.getInstance();
            mc.execute(() -> {
                if (mc.screen instanceof TranslatorDashboardScreen dashboard) {
                    dashboard.setBanner(message, 0xFFFF5555);
                }
                ResourcePackApplier.sendChatError(mc, message);
            });
        }
    };

    public FtbTranslatorMod(IEventBus modEventBus, ModContainer modContainer) {
        service = new TranslationService(FMLPaths.GAMEDIR.get());

        // 1. Register client config (config/ftbtranslator-client.toml)
        modContainer.registerConfig(ModConfig.Type.CLIENT, ClientConfig.SPEC);

        // 2. Register native "Config" button in NeoForge's Mod List screen
        modContainer.registerExtensionPoint(
                IConfigScreenFactory.class,
                (container, parentScreen) -> new TranslatorDashboardScreen(parentScreen));

        // 3. Register F9 keybinding on the Mod event bus
        modEventBus.addListener(this::onRegisterKeyMappings);

        // 3b. Boss-bar style translation progress on the HUD (top centre, under any real boss bars)
        modEventBus.addListener(this::onRegisterGuiLayers);
        NeoForge.EVENT_BUS.addListener(
                CustomizeGuiOverlayEvent.BossEventProgress.class, TranslationHudOverlay::onBossEventProgress);

        // 4. Register client commands (/ftbtranslate), keybind tick handler, Pause/Title screen shortcut button,
        //    and friendly first-world-join hint on the NeoForge event bus
        NeoForge.EVENT_BUS.addListener(this::onRegisterClientCommands);
        NeoForge.EVENT_BUS.addListener(this::onClientTick);
        NeoForge.EVENT_BUS.addListener(this::onScreenInitPost);
        NeoForge.EVENT_BUS.addListener(this::onClientPlayerLoggedIn);
    }

    public static synchronized TranslationService service() {
        if (service == null) {
            service = new TranslationService(FMLPaths.GAMEDIR.get());
        }
        return service;
    }

    public static TranslationCallback uiCallback() {
        return UI_CALLBACK;
    }

    public static void startTranslationFromGui(TranslatorConfigData config) {
        service().startAsync(config, UI_CALLBACK);
    }

    public static void startTranslationWithScopes(TranslatorConfigData config, Set<TranslationScope> scopes) {
        service().startAsync(config, scopes, UI_CALLBACK);
    }

    private void onRegisterKeyMappings(RegisterKeyMappingsEvent event) {
        openDashboardKey = new KeyMapping(
                "key.ftbtranslator.open_dashboard",
                InputConstants.Type.KEYSYM,
                GLFW.GLFW_KEY_F9,
                KEY_CATEGORY);
        event.register(openDashboardKey);
    }

    private void onRegisterGuiLayers(RegisterGuiLayersEvent event) {
        event.registerAbove(VanillaGuiLayers.BOSS_OVERLAY, TranslationHudOverlay.LAYER_ID, TranslationHudOverlay::render);
    }

    private void onRegisterClientCommands(RegisterClientCommandsEvent event) {
        TranslatorCommands.register(event.getDispatcher());
    }

    private void onClientTick(ClientTickEvent.Post event) {
        if (openDashboardKey == null) {
            return;
        }
        while (openDashboardKey.consumeClick()) {
            Minecraft mc = Minecraft.getInstance();
            if (!(mc.screen instanceof TranslatorDashboardScreen)) {
                mc.setScreen(new TranslatorDashboardScreen(mc.screen));
            }
        }
    }

    /**
     * Adds a discrete "🌐 Tradutor PT-BR" button in the top-right corner of the Pause Screen (ESC) and
     * Title Screen so players can easily discover and open the dashboard without needing to memorize F9.
     */
    private void onScreenInitPost(ScreenEvent.Init.Post event) {
        Screen screen = event.getScreen();
        if (screen instanceof PauseScreen || screen instanceof TitleScreen) {
            int btnWidth = 108;
            int x = Math.max(6, screen.width - btnWidth - 6);
            int y = 6;
            Button shortcutBtn = Button.builder(
                            Component.literal("§b🌐 Tradutor PT-BR"),
                            b -> Minecraft.getInstance().setScreen(new TranslatorDashboardScreen(screen)))
                    .bounds(x, y, btnWidth, 20)
                    .build();
            shortcutBtn.setTooltip(Tooltip.create(Component.literal(
                    "Abre o painel do Universal Modpack & Quests Auto-Translator (atalho no jogo: F9).")));
            event.addListener(shortcutBtn);
        }
    }

    /**
     * When the player joins a world and no translations have been generated yet, displays a one-time
     * clickable hint in chat so new users immediately know how to open the translator.
     */
    private void onClientPlayerLoggedIn(ClientPlayerNetworkEvent.LoggingIn event) {
        if (joinWorldHintShownThisSession || service().isRunning()) {
            return;
        }
        boolean hasPack = ResourcePackGenerator.hasGeneratedResourcePack(service().paths());
        boolean hasQuestsSnbt = Files.isRegularFile(
                service().paths().questsDir().resolve("lang").resolve("pt_br.snbt"));
        if (hasPack || hasQuestsSnbt) {
            return;
        }
        joinWorldHintShownThisSession = true;
        Minecraft mc = Minecraft.getInstance();
        mc.execute(() -> {
            if (mc.player == null) {
                return;
            }
            MutableComponent openBtn = Component.literal("[Clique aqui ou aperte F9 para abrir o Tradutor PT-BR]")
                    .withStyle(style -> style
                            .withColor(ChatFormatting.AQUA)
                            .withUnderlined(true)
                            .withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/ftbtranslate gui"))
                            .withHoverEvent(new HoverEvent(
                                    HoverEvent.Action.SHOW_TEXT,
                                    Component.literal("Abre o painel do FTB Auto-Translator"))));
            mc.player.displayClientMessage(
                    Component.literal("§a[FTB Auto-Translator] §fQuer traduzir o modpack e as missões para Português? ")
                            .append(openBtn),
                    false);
        });
    }
}
