package com.ftbtranslator.neoforge;

import com.ftbtranslator.core.GamePaths;
import com.ftbtranslator.core.pack.ResourcePackGenerator;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.packs.repository.Pack;
import net.minecraft.server.packs.repository.PackRepository;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * Activates the generated {@code file/AutoTranslated_Pack_PTBR} resource pack, saves client options,
 * triggers {@link Minecraft#reloadResourcePacks()} on the main client thread, and reloads FTB Quests if
 * the player is in a world.
 */
public final class ResourcePackApplier {

    public static final String PACK_ID = "file/" + GamePaths.RESOURCE_PACK_NAME;

    private ResourcePackApplier() {
    }

    /**
     * Schedules activation of {@code file/AutoTranslated_Pack_PTBR} and resource reload on the main
     * client thread.
     */
    public static void applyOnMainThread(GamePaths paths, boolean reloadQuestsIfInWorld) {
        Minecraft mc = Minecraft.getInstance();
        mc.execute(() -> applyNow(mc, paths, reloadQuestsIfInWorld));
    }

    private static void applyNow(Minecraft mc, GamePaths paths, boolean reloadQuestsIfInWorld) {
        try {
            if (!ResourcePackGenerator.hasGeneratedResourcePack(paths)) {
                ResourcePackGenerator.ensurePackMcmeta(paths);
            }
        } catch (IOException ignored) {
        }

        // 1. Reload repository so the newly created folder pack is discovered
        PackRepository repo = mc.getResourcePackRepository();
        repo.reload();

        // 2. Activate "file/AutoTranslated_Pack_PTBR" in PackRepository and Minecraft.getInstance().options.resourcePacks
        Pack generatedPack = repo.getPack(PACK_ID);
        if (generatedPack != null) {
            List<String> selectedIds = new ArrayList<>(repo.getSelectedIds());
            if (!selectedIds.contains(PACK_ID)) {
                selectedIds.add(PACK_ID);
            }
            repo.setSelected(selectedIds);
        } else {
            repo.addPack(PACK_ID);
        }

        if (!mc.options.resourcePacks.contains(PACK_ID)) {
            mc.options.resourcePacks.add(PACK_ID);
        }
        // Switch client language to pt_br automatically if the player was still on en_us
        if (!"pt_br".equalsIgnoreCase(mc.options.languageCode)) {
            mc.options.languageCode = "pt_br";
            mc.getLanguageManager().setSelected("pt_br");
        }
        mc.options.save();

        // 3. Reload resource packs (mods, KubeJS and Patchouli JSONs) without needing F3+T
        Collection<String> active = repo.getSelectedIds();
        mc.reloadResourcePacks();
        sendChatInfo(mc, "Resource Pack '" + GamePaths.RESOURCE_PACK_NAME + "' ativado (" + active.size()
                + " pacotes ativos) e recursos recarregados!");

        // 4. If the player is in a world and FTB Quests files were generated, reload quests
        if (reloadQuestsIfInWorld && mc.player != null && mc.player.connection != null) {
            mc.player.connection.sendCommand("ftbquests reload");
            MutableComponent button = Component.literal("[Clique para /ftbquests reload]")
                    .withStyle(style -> style
                            .withColor(ChatFormatting.AQUA)
                            .withUnderlined(true)
                            .withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/ftbquests reload"))
                            .withHoverEvent(new HoverEvent(
                                    HoverEvent.Action.SHOW_TEXT,
                                    Component.literal("Executa /ftbquests reload para atualizar os capítulos"))));
            mc.player.displayClientMessage(
                    Component.literal("§a[FTB Auto-Translator] §fTraduções do FTB Quests aplicadas! ").append(button),
                    false);
        }
    }

    public static void sendChatInfo(Minecraft mc, String text) {
        if (mc.player != null) {
            mc.player.displayClientMessage(
                    Component.literal("§a[FTB Auto-Translator] §f" + text), false);
        }
    }

    public static void sendChatError(Minecraft mc, String text) {
        if (mc.player != null) {
            mc.player.displayClientMessage(
                    Component.literal("§c[FTB Auto-Translator] §f" + text), false);
        }
    }
}
