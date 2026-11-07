package br.com.cobbleverse.generations.verification;

import br.com.cobbleverse.generations.GenerationControl;
import br.com.cobbleverse.generations.config.*;
import br.com.cobbleverse.generations.integration.ValidationService;
import br.com.cobbleverse.generations.mixin.*;
import com.cobblemon.mod.common.Cobblemon;
import com.cobblemon.mod.common.api.pokemon.PokemonSpecies;
import com.cobblemon.mod.common.api.storage.adapter.flatfile.FileStoreAdapter;
import com.cobblemon.mod.common.api.storage.factory.FileBackedPokemonStoreFactory;
import com.cobblemon.mod.common.api.storage.pc.PCStore;
import java.nio.file.*;
import java.util.*;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.util.WorldSavePath;

/** Performance experiment: a disposable offline player with a large PC. */
public final class FullPcVerificationEntrypoint implements ModInitializer {
    private long tickStart, longest; private int measuredTicks; private boolean measure;
    private PCStore liveTarget; private String mutation="none"; private boolean recheckMutated;
    private static boolean isRechecking() {
        try {
            var jobField=ValidationService.class.getDeclaredField("job"); jobField.setAccessible(true); Object job=jobField.get(null);
            if(job==null) return false;
            var checkingField=job.getClass().getDeclaredField("checking"); checkingField.setAccessible(true); Object checking=checkingField.get(job);
            if(checking==null) return false;
            var iteratorField=checking.getClass().getDeclaredField("iterator"); iteratorField.setAccessible(true);
            return iteratorField.get(checking)!=null;
        } catch(ReflectiveOperationException e) { throw new IllegalStateException(e); }
    }
    @SuppressWarnings({"rawtypes","unchecked"})
    public void onInitialize() {
        ServerTickEvents.START_SERVER_TICK.register(server -> tickStart=System.nanoTime());
        ValidationService.register();
        ServerTickEvents.END_SERVER_TICK.register(new ServerTickEvents.EndTick() {
            boolean started; int elapsed;
            public void onEndTick(net.minecraft.server.MinecraftServer server) {
                if(measure) { longest=Math.max(longest,System.nanoTime()-tickStart); measuredTicks++; }
                if(++elapsed>500) { finish(server,Map.of("fatal","timeout")); return; }
                if(started) {
                    boolean duringCheck=mutation.equals("recheck") && !recheckMutated && elapsed>8 && isRechecking();
                    if(!mutation.equals("none") && (elapsed==8 || duringCheck) && liveTarget!=null) {
                        var pokemon=liveTarget.iterator().next();
                        if(duringCheck) recheckMutated=true;
                        if(mutation.equals("species") || duringCheck) pokemon.setSpecies(PokemonSpecies.getByName(pokemon.getSpecies().getName().equalsIgnoreCase("bulbasaur") ? "pichu" : "bulbasaur"));
                        else if(mutation.equals("remove")) liveTarget.remove(pokemon);
                        else pokemon.setLevel(6);
                        liveTarget.onPokemonChanged(pokemon);
                    }
                    return;
                } started=true;
                try {
                    var factories=((StoreManagerAccessor)(Object)Cobblemon.INSTANCE.getStorage()).generationControl$factories();
                    FileBackedPokemonStoreFactory<?> factory=(FileBackedPokemonStoreFactory<?>)factories.iterator().next();
                    var access=(StoreFactoryAccessor)(Object)factory;
                    var adapter=(FileStoreAdapter)access.generationControl$adapter();
                    UUID id=UUID.randomUUID();
                    Path players=server.getSavePath(WorldSavePath.PLAYERDATA); Files.createDirectories(players); Files.write(players.resolve(id+".dat"),new byte[]{1});
                    var pc=new PCStore(id); pc.initialize(); pc.resize(30,false,pokemon -> kotlin.Unit.INSTANCE);
                    int added=0; for(int i=0;i<900;i++) { if(!pc.add(PokemonSpecies.getByName("pichu").create(5))) break; added++; }
                    final int total=added; if(total<900) throw new IllegalStateException("PC fixture expected 900 entries, got "+total);
                    boolean withReserve=Boolean.getBoolean("generation_control.testReserve");
                    if(withReserve) { pc.getBackupStore().add(PokemonSpecies.getByName("pichu").create(5)); pc.getBackupStore().add(PokemonSpecies.getByName("bulbasaur").create(5)); }
                    adapter.save(PCStore.class,id,adapter.serialize(pc,server.getRegistryManager()));
                    boolean liveCache=Boolean.getBoolean("generation_control.testLiveCache");
                    mutation=System.getProperty("generation_control.testMutation","none");
                    if(liveCache) {
                        var live=factory.getPC(id,server.getRegistryManager()); liveTarget=live;
                        if(withReserve && !live.getBackupStore().iterator().hasNext()) { live.getBackupStore().add(PokemonSpecies.getByName("pichu").create(5)); live.getBackupStore().add(PokemonSpecies.getByName("bulbasaur").create(5)); }
                        var first=live.iterator().next();
                        if(!live.remove(first) || !live.add(PokemonSpecies.getByName("bulbasaur").create(5))) throw new IllegalStateException("Could not modify live cache fixture");
                    }
                    Path policy=Path.of("full-pc-policy-fixture").toAbsolutePath();
                    var active=new GenerationConfig(); active.restrictExistingPokemon=false;
                    ConfigStore.write(policy.resolve("generation-control/active.json"),active); GenerationControl.openWorld(policy);
                    var candidate=active.copy(); candidate.restrictExistingPokemon=true;
                    boolean accepted=ValidationService.start(server,candidate,report -> {
                        long thisTick=System.nanoTime()-tickStart;
                        Map<String,Object> result=new LinkedHashMap<>();
                        result.put("pokemonInserted",total); result.put("errors",report.errors);
                        result.put("liveCache",liveCache);
                        result.put("countMatches",report.errors.isEmpty() && report.inspectedPlayers==1 && report.affectedPokemon==total-(liveCache ? 1 : 0)+(withReserve ? 1 : 0));
                        result.put("reserveCountMatches",report.reservePokemon==(withReserve ? 2 : 0));
                        boolean relevant=mutation.equals("species") || mutation.equals("remove") || mutation.equals("recheck");
                        result.put("usableCountMatches",relevant || report.playersWithoutUsablePokemon==(liveCache ? 0 : 1));
                        result.put("relevantChangeRejected",!relevant || report.errors.stream().anyMatch(error->error.contains("mudaram durante a análise")));
                        result.put("irrelevantChangeAllowed",!mutation.equals("xp") || (report.errors.isEmpty() && report.inspectedPlayers==1));
                        result.put("partialCountsCleared",!relevant || report.inspectedPlayers==0);
                        result.put("changedDuringRecheck",!mutation.equals("recheck") || recheckMutated);
                        result.put("pokemonCounted",report.affectedPokemon); result.put("measuredTicks",measuredTicks);
                        result.put("maxServerTickMs",Math.max(longest,thisTick)/1_000_000.0);
                        finish(server,result);
                    });
                    if(!accepted) { finish(server,Map.of("fatal","job rejected")); return; }
                    measure=true;
                } catch(Throwable e) { e.printStackTrace(); finish(server,Map.of("fatal",e.toString())); }
            }
            void finish(net.minecraft.server.MinecraftServer server,Map<String,Object> out) {
                try { ConfigStore.write(Path.of("full-pc-result.json").toAbsolutePath(),out); }
                catch(Exception e) { e.printStackTrace(); }
                server.stop(false);
            }
        });
    }
}
