package br.com.cobbleverse.generations.verification;

import br.com.cobbleverse.generations.GenerationControl;
import br.com.cobbleverse.generations.config.*;
import br.com.cobbleverse.generations.integration.ValidationService;
import java.nio.file.*;
import java.util.*;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.util.WorldSavePath;

/** Activated only with disposable test metadata. */
public final class BatchVerificationEntrypoint implements ModInitializer {
    public void onInitialize() {
        ValidationService.register();
        ServerTickEvents.END_SERVER_TICK.register(new ServerTickEvents.EndTick() {
            boolean started,mutated; long ticks; long maxNanos; Path changePath; boolean changeTest=Boolean.getBoolean("generation_control.testOfflineChange"), cacheTest=Boolean.getBoolean("generation_control.testCacheChange"), replacementTest=Boolean.getBoolean("generation_control.testCacheReplacement");
            public void onEndTick(net.minecraft.server.MinecraftServer server) {
                ticks++;
                if(started) {
                    if(ticks>4000) { finish(server,Map.of("fatal","timeout")); return; }
                    if((changeTest || cacheTest || replacementTest) && !mutated) try {
                        var jf=ValidationService.class.getDeclaredField("job"); jf.setAccessible(true); Object job=jf.get(null);
                        if(job!=null) { var rf=job.getClass().getDeclaredField("report"); rf.setAccessible(true);
                            var report=(br.com.cobbleverse.generations.integration.ValidationReport)rf.get(job);
                            if(report.inspectedPlayers>=1) {
                                if(changeTest) { Files.createDirectories(changePath.getParent()); Files.writeString(changePath,"created after inspection"); }
                                else {
                                    var factories=((br.com.cobbleverse.generations.mixin.StoreManagerAccessor)(Object)com.cobblemon.mod.common.Cobblemon.INSTANCE.getStorage()).generationControl$factories();
                                    var factory=(com.cobblemon.mod.common.api.storage.factory.FileBackedPokemonStoreFactory<?>)factories.iterator().next();
                                    if(replacementTest) {
                                        var access=(br.com.cobbleverse.generations.mixin.StoreFactoryAccessor)(Object)factory;
                                        Object cache=access.generationControl$caches().get(com.cobblemon.mod.common.api.storage.party.PlayerPartyStore.class);
                                        ((br.com.cobbleverse.generations.mixin.StoreCacheAccessor)cache).generationControl$cacheMap().remove(new UUID(0,1));
                                    }
                                    var party=factory.getPlayerParty(new UUID(0,1),server.getRegistryManager());
                                    if(!replacementTest) party.add(com.cobblemon.mod.common.api.pokemon.PokemonSpecies.getByName("pichu").create(5));
                                }
                                mutated=true;
                            }
                        }
                    } catch(Exception e) { finish(server,Map.of("fatal",e.toString())); }
                    return;
                }
                started=true;
                try {
                    Path dir=server.getSavePath(WorldSavePath.PLAYERDATA); Files.createDirectories(dir);
                    int playerCount=(changeTest || cacheTest || replacementTest) ? 3 : 501;
                    for(int i=0;i<playerCount;i++) Files.write(dir.resolve(new UUID(0,i+1)+".dat"),new byte[]{1});
                    if(changeTest) {
                        var factories=((br.com.cobbleverse.generations.mixin.StoreManagerAccessor)(Object)com.cobblemon.mod.common.Cobblemon.INSTANCE.getStorage()).generationControl$factories();
                        var factory=(com.cobblemon.mod.common.api.storage.factory.FileBackedPokemonStoreFactory<?>)factories.iterator().next();
                        var access=(br.com.cobbleverse.generations.mixin.StoreFactoryAccessor)(Object)factory;
                        var paths=(br.com.cobbleverse.generations.mixin.StorePathAccessor)(Object)access.generationControl$adapter();
                        changePath=br.com.cobbleverse.generations.integration.StorePaths.path(paths.generationControl$root(),paths.generationControl$nested(),paths.generationControl$perClass(),paths.generationControl$extension(),com.cobblemon.mod.common.api.storage.party.PlayerPartyStore.class.getSimpleName(),new UUID(0,1));
                    }
                    if(replacementTest) {
                        var factories=((br.com.cobbleverse.generations.mixin.StoreManagerAccessor)(Object)com.cobblemon.mod.common.Cobblemon.INSTANCE.getStorage()).generationControl$factories();
                        var factory=(com.cobblemon.mod.common.api.storage.factory.FileBackedPokemonStoreFactory<?>)factories.iterator().next();
                        factory.getPlayerParty(new UUID(0,1),server.getRegistryManager());
                    }
                    Path base=Path.of("batch-policy-fixture").toAbsolutePath();
                    var active=new GenerationConfig(); ConfigStore.write(base.resolve("generation-control/active.json"),active);
                    GenerationControl.openWorld(base);
                    var candidate=active.copy(); candidate.restrictExistingPokemon=false;
                    boolean accepted=ValidationService.start(server,candidate,report -> {
                        Map<String,Object> out=new LinkedHashMap<>();
                        out.put("noErrors",report.errors.isEmpty()); out.put("errors",report.errors);
                        out.put("players",report.inspectedPlayers); out.put("scanned501",!changeTest && report.inspectedPlayers==501);
                        out.put("offlineChangeIntroduced",changeTest && mutated);
                        out.put("offlineChangeRejected",!changeTest || (!report.errors.isEmpty() && report.inspectedPlayers==0));
                        out.put("cacheChangeIntroduced",(cacheTest || replacementTest) && mutated);
                        out.put("cacheChangeRejected",!(cacheTest || replacementTest) || (report.inspectedPlayers==0 && report.errors.stream().anyMatch(e->e.contains("cache de Pokémon"))));
                        out.put("ticks",ticks); out.put("spreadAcrossTicks",!changeTest && ticks>=501);
                        finish(server,out);
                    });
                    if(!accepted) finish(server,Map.of("fatal","start rejected"));
                } catch(Throwable e) { e.printStackTrace(); finish(server,Map.of("fatal",e.toString())); }
            }
            void finish(net.minecraft.server.MinecraftServer server,Map<String,Object> result) {
                try { ConfigStore.write(Path.of(changeTest ? "batch-offline-change-result.json" : cacheTest ? "batch-cache-change-result.json" : replacementTest ? "batch-cache-replacement-result.json" : "batch-test-result.json").toAbsolutePath(),result); }
                catch(Exception e) { e.printStackTrace(); }
                server.stop(false);
            }
        });
    }
}
