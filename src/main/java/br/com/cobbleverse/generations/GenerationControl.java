package br.com.cobbleverse.generations;

import br.com.cobbleverse.generations.config.*;
import br.com.cobbleverse.generations.rules.GenerationRules;
import br.com.cobbleverse.generations.integration.CobblemonIntegration;
import br.com.cobbleverse.generations.integration.RctIntegration;
import br.com.cobbleverse.generations.web.WebPanelServer;
import net.fabricmc.api.DedicatedServerModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.command.CommandSource;
import net.minecraft.server.command.CommandManager;
import net.minecraft.text.Text;
import com.mojang.brigadier.arguments.StringArgumentType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.nio.file.Path;
import java.io.IOException;

public final class GenerationControl implements DedicatedServerModInitializer {
    public static final String ID = "generation_control";
    public static final Logger LOGGER = LoggerFactory.getLogger(ID);
    private static volatile ConfigStore store;
    private static volatile GenerationRules rules;
    private static volatile Path currentWorld;
    private static volatile net.minecraft.server.MinecraftServer server;

    @Override public void onInitializeServer() {
        br.com.cobbleverse.generations.integration.Compatibility.requireSupported();
        br.com.cobbleverse.generations.integration.ValidationService.register();
        CobblemonIntegration.register();
        br.com.cobbleverse.generations.integration.ExperienceManager.register();
        RctIntegration.register();
        br.com.cobbleverse.generations.integration.InvocationGuard.register();
        br.com.cobbleverse.generations.integration.RescueService.register();
        ServerLifecycleEvents.SERVER_STARTING.register(starting -> {
            server=starting;
            var report=CobblemonIntegration.validateRegistries(rules().config());
            if(!report.errors.isEmpty()) {
                var error=new IllegalStateException(String.join("; ",report.errors));
                rejectStartupPolicy(error);
                throw error;
            }
        });
        CommandRegistrationCallback.EVENT.register((dispatcher,access,environment) -> dispatcher.register(
            CommandManager.literal("geracoes").then(CommandManager.literal("resgate").executes(ctx -> {
                br.com.cobbleverse.generations.integration.RescueService.open(ctx.getSource().getPlayerOrThrow());
                return 1;
            }).then(CommandManager.argument("especie", StringArgumentType.word())
                .suggests((ctx,builder) -> CommandSource.suggestMatching(br.com.cobbleverse.generations.integration.RescueService.choices(),builder))
                .executes(ctx -> {
                    var player=ctx.getSource().getPlayerOrThrow();
                    String species=StringArgumentType.getString(ctx,"especie");
                    try {
                        player.sendMessage(Text.literal(br.com.cobbleverse.generations.integration.RescueService.claim(player,species)));
                        return 1;
                    } catch(Exception e) {
                        ctx.getSource().sendError(Text.literal(e.getMessage()==null ? "O resgate não pôde ser concluído." : e.getMessage()));
                        return 0;
                    }
                }))
            )
        ));
        ServerLifecycleEvents.SERVER_STARTED.register(server -> {
            try {
                if (store().pending() != null && store().pending().revision == rules().config().revision) store().activatePending("server-ready");
                java.nio.file.Files.deleteIfExists(store().directory().resolve("startup-attempt.json"));
                WebPanelServer.start(server);
            } catch (IOException e) { throw new IllegalStateException("Falha ao confirmar a revisão de gerações.", e); }
        });
        CommandRegistrationCallback.EVENT.register((dispatcher, access, environment) -> dispatcher.register(
            CommandManager.literal("geracoes")
                .then(CommandManager.literal("status").executes(ctx -> {
                    ctx.getSource().sendFeedback(() -> Text.literal(status()), false); return 1;
                }))
                .then(CommandManager.literal("web").requires(br.com.cobbleverse.generations.net.AdminPermissions::canEdit).executes(ctx -> {
                    ctx.getSource().sendFeedback(() -> Text.literal(WebPanelServer.connectionInfo()), false); return 1;
                }))
                .then(CommandManager.literal("validar").requires(br.com.cobbleverse.generations.net.AdminPermissions::canEdit).executes(ctx -> {
                    var candidate = store().pending() == null ? store().active() : store().pending();
                    var source=ctx.getSource(); long revision=store().editRevision();
                    boolean started=br.com.cobbleverse.generations.integration.ValidationService.start(source.getServer(),candidate,report -> {
                        if (!br.com.cobbleverse.generations.net.AdminPermissions.canEdit(source) || !validCommandSource(source)) return;
                        if(revision!=store().editRevision()) { source.sendError(Text.literal("Configuração alterada durante a prévia; valide novamente.")); return; }
                        source.sendFeedback(() -> Text.literal(report.toString()),false);
                    });
                    if(!started) { source.sendError(Text.literal("Já existe uma validação em andamento.")); return 0; }
                    source.sendFeedback(() -> Text.literal("Validação iniciada; aguarde o resultado."),false); return 1;
                }))
                .then(CommandManager.literal("perfil").requires(br.com.cobbleverse.generations.net.AdminPermissions::canEdit)
                    .then(CommandManager.argument("geracao", com.mojang.brigadier.arguments.IntegerArgumentType.integer(1, 9)).executes(ctx -> {
                        var candidate = store().active();
                        candidate.mode = "single";
                        candidate.selectedGeneration = com.mojang.brigadier.arguments.IntegerArgumentType.getInteger(ctx, "geracao");
                        var source=ctx.getSource(); long revision=store().editRevision();
                        boolean started=br.com.cobbleverse.generations.integration.ValidationService.start(source.getServer(),candidate,report -> {
                            if (!br.com.cobbleverse.generations.net.AdminPermissions.canEdit(source) || !validCommandSource(source)) return;
                            if(revision!=store().editRevision()) { source.sendError(Text.literal("Configuração alterada durante a prévia; valide novamente.")); return; }
                            if(!report.errors.isEmpty()) { source.sendError(Text.literal(String.join("; ",report.errors))); return; }
                            try { store().stage(candidate,revision,source.getName()); }
                            catch(Exception e) { source.sendError(Text.literal(e.getMessage())); return; }
                            source.sendFeedback(() -> Text.literal("Perfil salvo como pendente. Reinicie o servidor para aplicar."),true);
                        });
                        if(!started) { source.sendError(Text.literal("Já existe uma validação em andamento.")); return 0; }
                        source.sendFeedback(() -> Text.literal("Validação iniciada; aguarde o resultado."),false); return 1;
                    })))
                .then(CommandManager.literal("restaurar").requires(br.com.cobbleverse.generations.net.AdminPermissions::canEdit).executes(ctx -> {
                    try { store().stagePrevious(store().editRevision(), ctx.getSource().getName()); }
                    catch (Exception e) { ctx.getSource().sendError(Text.literal(e.getMessage())); return 0; }
                    ctx.getSource().sendFeedback(() -> Text.literal("Revisão anterior preparada para o próximo reinício."), true); return 1;
                }))
        ));
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> {
            WebPanelServer.stop();
            synchronized (GenerationControl.class) { store = null; rules = null; currentWorld = null; GenerationControl.server=null; }
            br.com.cobbleverse.generations.integration.ValidationService.clear();
            br.com.cobbleverse.generations.integration.ExperienceManager.clear();
            br.com.cobbleverse.generations.integration.CaptureManager.clear();
        });
        LOGGER.info("Generation Control inicializado. As regras são controladas pelo servidor.");
    }

    private static boolean validCommandSource(net.minecraft.server.command.ServerCommandSource source) {
        var player=source.getPlayer();
        return player==null || source.getServer().getPlayerManager().getPlayer(player.getUuid())==player;
    }

    /** Invoked at the end of world-session construction, before datapack/worldgen loading. */
    public static synchronized void openWorld(Path world) {
        Path resolved = world.toAbsolutePath().normalize();
        if (resolved.equals(currentWorld)) return;
        try {
            ConfigStore next = new ConfigStore(resolved.resolve("generation-control"));
            next.open();
            if(next.startupWarning()!=null) LOGGER.warn("Aviso de configuração: {}",next.startupWarning());
            Path attempt = next.directory().resolve("startup-attempt.json");
            if (next.pending() != null && java.nio.file.Files.exists(attempt)) {
                Long previousAttempt = ConfigStore.GSON.fromJson(java.nio.file.Files.readString(attempt), Long.class);
                if (previousAttempt != null && previousAttempt == next.pending().revision) {
                    LOGGER.warn("A revisão {} não concluiu a inicialização anterior. Restaurando regras ativas.", previousAttempt);
                    ConfigStore.write(next.directory().resolve("rejected-"+previousAttempt+".json"), next.pending());
                    next.discardPending("startup-incomplete");
                }
            }
            store = next;
            var boot = next.pending() == null ? next.active() : next.pending();
            rules = new GenerationRules(boot);
            if (next.pending() != null) ConfigStore.write(attempt, boot.revision);
            currentWorld = resolved;
            LOGGER.info("Mundo {}: preparando revisão {}, gerações {}", resolved.getFileName(), boot.revision, boot.generations);
        } catch (IOException | RuntimeException e) {
            throw new IllegalStateException("Não foi possível preparar as regras de gerações do mundo " + resolved, e);
        }
    }

    public static synchronized boolean rejectStartupPolicy(Exception failure) {
        if (store == null || store.pending() == null || store.pending().revision != rules.config().revision) return false;
        try {
            long revision = store.pending().revision;
            ConfigStore.write(store.directory().resolve("rejected-"+revision+".json"), store.pending());
            store.discardPending("startup-validation: "+failure.getMessage());
            store.recordStartupWarning("Revisão "+revision+" rejeitada durante a preparação: "+failure.getMessage()+". A ativa foi preservada. Reinicie para carregar novamente os datapacks correspondentes.");
            java.nio.file.Files.deleteIfExists(store.directory().resolve("startup-attempt.json"));
            rules = new GenerationRules(store.active());
            LOGGER.error("Revisão {} rejeitada; mantendo revisão {}.", revision, store.active().revision, failure);
            return true;
        } catch (IOException e) { throw new IllegalStateException("Falha ao restaurar configuração ativa.",e); }
    }

    public static boolean ready() { return rules != null; }
    public static net.minecraft.server.MinecraftServer server() { return server; }
    public static GenerationRules rules() { return rules; }
    public static ConfigStore store() {
        ConfigStore current = store;
        if (current == null) throw new IllegalStateException("Nenhum mundo de servidor carregado.");
        return current;
    }
    public static String status() {
        if (!ready()) return "Nenhum mundo carregado.";
        var active = store().active();
        return "Gerações: " + active.generations + " | Circuitos: " + active.regions + " | Revisão ativa: " + active.revision
            + (store().pending() != null ? " | Há mudanças aguardando reinício." : "");
    }
}
