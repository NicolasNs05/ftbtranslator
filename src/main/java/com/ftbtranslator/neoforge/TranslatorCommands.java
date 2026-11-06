package com.ftbtranslator.neoforge;

import com.ftbtranslator.core.ContextPreset;
import com.ftbtranslator.core.ProgressSnapshot;
import com.ftbtranslator.core.TranslationScope;
import com.ftbtranslator.core.TranslatorConfigData;
import com.ftbtranslator.core.bundle.TranslationBundleManager;
import com.ftbtranslator.neoforge.gui.TranslatorDashboardScreen;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.minecraft.client.Minecraft;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;

import java.io.IOException;
import java.util.Set;

/**
 * Registers the {@code /ftbtranslate} client command tree:
 * <ul>
 *   <li>{@code /ftbtranslate gui}</li>
 *   <li>{@code /ftbtranslate start [all|ftbquests|kubejs|mods|patchouli]}</li>
 *   <li>{@code /ftbtranslate status}</li>
 *   <li>{@code /ftbtranslate cancel}</li>
 *   <li>{@code /ftbtranslate apply}</li>
 *   <li>{@code /ftbtranslate export}</li>
 *   <li>{@code /ftbtranslate import}</li>
 *   <li>{@code /ftbtranslate apikey <key>}</li>
 *   <li>{@code /ftbtranslate context <preset>}</li>
 * </ul>
 */
public final class TranslatorCommands {

    private TranslatorCommands() {
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("ftbtranslate")
                .executes(ctx -> openGui())
                .then(Commands.literal("gui")
                        .executes(ctx -> openGui()))
                .then(Commands.literal("start")
                        .executes(ctx -> startScope("all"))
                        .then(Commands.argument("scope", StringArgumentType.word())
                                .suggests((ctx, builder) -> {
                                    for (String s : new String[]{"all", "ftbquests", "kubejs", "mods", "patchouli"}) {
                                        builder.suggest(s);
                                    }
                                    return builder.buildFuture();
                                })
                                .executes(ctx -> startScope(StringArgumentType.getString(ctx, "scope")))))
                .then(Commands.literal("status")
                        .executes(ctx -> showStatus(ctx.getSource())))
                .then(Commands.literal("cancel")
                        .executes(ctx -> cancelRun(ctx.getSource())))
                .then(Commands.literal("apply")
                        .executes(ctx -> applyPack(ctx.getSource())))
                .then(Commands.literal("export")
                        .executes(ctx -> exportZip(ctx.getSource())))
                .then(Commands.literal("import")
                        .executes(ctx -> importZip(ctx.getSource())))
                .then(Commands.literal("apikey")
                        .then(Commands.argument("key", StringArgumentType.greedyString())
                                .executes(ctx -> setApiKey(ctx.getSource(),
                                        StringArgumentType.getString(ctx, "key")))))
                .then(Commands.literal("context")
                        .then(Commands.argument("preset", StringArgumentType.word())
                                .suggests((ctx, builder) -> {
                                    for (ContextPreset p : ContextPreset.values()) {
                                        builder.suggest(p.name().toLowerCase());
                                    }
                                    return builder.buildFuture();
                                })
                                .executes(ctx -> setContextPreset(ctx.getSource(),
                                        StringArgumentType.getString(ctx, "preset"))))));
    }

    private static int openGui() {
        Minecraft mc = Minecraft.getInstance();
        mc.execute(() -> mc.setScreen(new TranslatorDashboardScreen(mc.screen)));
        return 1;
    }

    private static int startScope(String rawScope) {
        Set<TranslationScope> scopes = TranslationScope.parseArgument(rawScope);
        Minecraft mc = Minecraft.getInstance();
        if (scopes == null) {
            ResourcePackApplier.sendChatError(mc,
                    "Escopo inválido: '" + rawScope + "'. Use all, ftbquests, kubejs, mods ou patchouli.");
            return 0;
        }
        TranslatorConfigData cfg = ClientConfig.toCoreData();
        String validation = cfg.validateForTranslation();
        if (validation != null) {
            ResourcePackApplier.sendChatError(mc, validation);
            return 0;
        }
        FtbTranslatorMod.startTranslationWithScopes(cfg, scopes);
        return 1;
    }

    private static int showStatus(CommandSourceStack source) {
        ProgressSnapshot s = FtbTranslatorMod.service().latestSnapshot();
        int pct = (int) Math.round(s.fraction() * 100.0);
        String msg = "§a[FTB Auto-Translator] §fFase: §b" + s.phase()
                + " §7(" + pct + "%) §f| Lote: §e" + s.batchIndex() + "/" + s.batchTotal()
                + " §f| Cache: §a" + s.stringsFromCacheOnly()
                + " §f| Glossário: §a" + s.stringsFromGlossary()
                + " §f| IA: §e" + s.stringsTranslatedByAi()
                + " §f| Arquivos: §b" + s.tasksWritten() + "/" + s.tasksTotal()
                + (s.currentItem().isEmpty() ? "" : " §7(" + s.currentItem() + ")");
        source.sendSuccess(() -> Component.literal(msg), false);
        return 1;
    }

    private static int cancelRun(CommandSourceStack source) {
        boolean cancelled = FtbTranslatorMod.service().cancel();
        if (cancelled) {
            source.sendSuccess(() -> Component.literal(
                    "§e[FTB Auto-Translator] §fCancelamento solicitado. O cache até o último lote foi salvo."), false);
        } else {
            source.sendSuccess(() -> Component.literal(
                    "§7[FTB Auto-Translator] Nenhuma tradução em execução no momento."), false);
        }
        return 1;
    }

    private static int applyPack(CommandSourceStack source) {
        if (FtbTranslatorMod.service().isRunning()) {
            source.sendFailure(Component.literal(
                    "§c[FTB Auto-Translator] Aguarde ou cancele a tradução atual antes de aplicar."));
            return 0;
        }
        ResourcePackApplier.applyOnMainThread(FtbTranslatorMod.service().paths(), true);
        source.sendSuccess(() -> Component.literal(
                "§a[FTB Auto-Translator] §fAtivando Resource Pack e recarregando recursos..."), false);
        return 1;
    }

    private static int exportZip(CommandSourceStack source) {
        TranslatorConfigData cfg = ClientConfig.toCoreData();
        try {
            TranslationBundleManager.ExportResult res = FtbTranslatorMod.service()
                    .exportBundle(cfg.getTargetLanguage());
            source.sendSuccess(() -> Component.literal(
                    "§a[FTB Auto-Translator] §fBundle exportado com sucesso (" + res.filesExported()
                            + " arquivos, " + (res.sizeBytes() / 1024) + " KB): §eexports/"
                            + res.zipPath().getFileName()), false);
            return 1;
        } catch (IOException e) {
            source.sendFailure(Component.literal("§c[FTB Auto-Translator] " + e.getMessage()));
            return 0;
        }
    }

    private static int importZip(CommandSourceStack source) {
        TranslatorConfigData cfg = ClientConfig.toCoreData();
        try {
            TranslationBundleManager.ImportResult res = FtbTranslatorMod.service()
                    .importBundle(cfg.getTargetLanguage());
            ResourcePackApplier.applyOnMainThread(FtbTranslatorMod.service().paths(), res.questsTouched());
            source.sendSuccess(() -> Component.literal(
                    "§a[FTB Auto-Translator] §fBundle importado (" + res.filesExtracted()
                            + " arquivo(s) extraído(s), " + res.cacheEntriesMerged()
                            + " entrada(s) mesclada(s) no cache) e aplicado!"), false);
            return 1;
        } catch (IOException e) {
            source.sendFailure(Component.literal("§c[FTB Auto-Translator] " + e.getMessage()));
            return 0;
        }
    }

    private static int setApiKey(CommandSourceStack source, String key) {
        if (FtbTranslatorMod.service().isRunning()) {
            source.sendFailure(Component.literal(
                    "§c[FTB Auto-Translator] Pause/cancele a tradução atual antes de alterar a API Key."));
            return 0;
        }
        TranslatorConfigData cfg = ClientConfig.toCoreData();
        cfg.setApiKey(key.trim());
        ClientConfig.saveFromCoreData(cfg);
        source.sendSuccess(() -> Component.literal(
                "§a[FTB Auto-Translator] §fAPI Key salva com sucesso para §b"
                        + cfg.getProvider().displayName() + "§f!"), false);
        return 1;
    }

    private static int setContextPreset(CommandSourceStack source, String rawPreset) {
        if (FtbTranslatorMod.service().isRunning()) {
            source.sendFailure(Component.literal(
                    "§c[FTB Auto-Translator] Pause/cancele a tradução atual antes de alterar o contexto."));
            return 0;
        }
        ContextPreset preset = ContextPreset.parse(rawPreset);
        if (preset == null) {
            source.sendFailure(Component.literal(
                    "§c[FTB Auto-Translator] Preset inválido: '" + rawPreset
                            + "'. Opções: tensura, default, tech, magic, custom."));
            return 0;
        }
        TranslatorConfigData cfg = ClientConfig.toCoreData();
        cfg.setContextPreset(preset);
        ClientConfig.markContextManuallySet();
        ClientConfig.saveFromCoreData(cfg);
        source.sendSuccess(() -> Component.literal(
                "§a[FTB Auto-Translator] §fContexto alterado para §e" + preset.displayName() + "§f."), false);
        return 1;
    }
}
