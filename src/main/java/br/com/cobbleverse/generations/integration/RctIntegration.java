package br.com.cobbleverse.generations.integration;

import br.com.cobbleverse.generations.GenerationControl;
import br.com.cobbleverse.generations.config.ConfigStore;
import com.gitlab.srcmc.rctmod.api.RCTMod;
import com.gitlab.srcmc.rctmod.world.entities.TrainerMob;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.util.WorldSavePath;
import java.nio.file.Files;
import java.util.*;

public final class RctIntegration {
    public static final class ProgressLedger {
        public Map<String,Set<String>> defeatsBySeries=new TreeMap<>();
    }
    private RctIntegration() {}

    public static boolean trainerAllowed(String trainerId) {
        if (!GenerationControl.ready() || !GenerationControl.rules().filterGyms()) return true;
        var data = RCTMod.getInstance().getTrainerManager().getData(trainerId);
        if (data == null) return false;
        List<String> series = data.getSeries().toList();
        return series.isEmpty() || series.stream().anyMatch(GenerationControl.rules()::regionAllowed);
    }

    public static String permittedSeries(String series) {
        if (!GenerationControl.ready() || !GenerationControl.rules().filterGyms()) return series;
        if ("freeroam".equals(series) || GenerationControl.rules().regionAllowed(series)) return series;
        return GenerationControl.rules().config().initialRegion;
    }

    public static void register() {
        ServerEntityEvents.ENTITY_LOAD.register((entity, world) -> {
            if (entity instanceof TrainerMob trainer && !trainer.isInBattle() && !trainerAllowed(trainer.getTrainerId())) trainer.discard();
        });
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
            boolean newPlayer = !Files.exists(server.getSavePath(WorldSavePath.PLAYERDATA).resolve(handler.player.getUuidAsString()+".dat"));
            server.execute(() ->
            RCTMod.getInstance().getTrainerManager().runAfterLoaded(() -> {
                if (!GenerationControl.ready() || !GenerationControl.rules().filterGyms()) return;
                var player = handler.player;
                var data = RCTMod.getInstance().getTrainerManager().getData(player);
                String desired = newPlayer ? GenerationControl.rules().config().initialRegion : permittedSeries(data.getCurrentSeries());
                if (!Objects.equals(desired, data.getCurrentSeries())) {
                    try {
                        migrate(player,data,desired);
                        player.sendMessage(net.minecraft.text.Text.literal("Circuito ativo ajustado para "+desired+". O progresso anterior foi preservado no histórico do servidor."));
                    } catch (Exception e) { throw new IllegalStateException("Falha ao preservar progresso de " + player.getUuid(),e); }
                }
            })
            );
        });
    }

    public static void migrate(net.minecraft.server.network.ServerPlayerEntity player,
            com.gitlab.srcmc.rctmod.api.data.save.TrainerPlayerData data,String desired) throws java.io.IOException {
        var directory=GenerationControl.store().directory().resolve("migrations");
        var path=directory.resolve("progress-"+player.getUuid()+".json");
        ProgressLedger ledger=Files.exists(path) ? ConfigStore.GSON.fromJson(Files.readString(path),ProgressLedger.class) : new ProgressLedger();
        if(ledger==null || ledger.defeatsBySeries==null) throw new java.io.IOException("Histórico individual inválido.");
        String previous=Objects.toString(data.getCurrentSeries(),"freeroam");
        ledger.defeatsBySeries.put(previous,Set.copyOf(data.getDefeatedTrainerIds()));
        ConfigStore.write(directory.resolve(player.getUuid()+"-"+GenerationControl.rules().config().revision+".json"),
            Map.of("previousSeries",previous,"nextSeries",desired,"defeatedTrainers",Set.copyOf(data.getDefeatedTrainerIds()),"completedSeries",Map.copyOf(data.getCompletedSeries())));
        ConfigStore.write(path,ledger); // durable archive before the upstream setter clears the active set
        if("freeroam".equals(desired)) { data.setCurrentSeries(desired,true); return; }
        var graph=RCTMod.getInstance().getSeriesManager().getGraph(desired);
        Set<String> graphIds=graph.stream().map(com.gitlab.srcmc.rctmod.api.service.SeriesManager.TrainerNode::id)
            .collect(java.util.stream.Collectors.toSet());
        boolean completed= data.getCompletedSeries().getOrDefault(desired,0)>0;
        Set<String> target=targetDefeats(ledger,desired,completed,graphIds);
        data.setCurrentSeries(desired,false);
        var access=(br.com.cobbleverse.generations.mixin.TrainerProgressAccessor)data;
        access.generationControl$completed(true); // suppress duplicate completion rewards while restoring defeats
        target.stream().filter(id -> graph.get(id)!=null).forEach(data::addProgressDefeat);
        access.generationControl$completed(graph.size()>0 && graph.getRemaining(data.getDefeatedTrainerIds()).size()==0);
        data.markDirty();
    }

    static Set<String> targetDefeats(ProgressLedger ledger,String series,boolean completed,Collection<String> trainerIds) {
        Set<String> result=ledger==null || ledger.defeatsBySeries==null
            ? new HashSet<>() : new HashSet<>(ledger.defeatsBySeries.getOrDefault(series,Set.of()));
        if(completed) result.addAll(trainerIds);
        return result;
    }
}
