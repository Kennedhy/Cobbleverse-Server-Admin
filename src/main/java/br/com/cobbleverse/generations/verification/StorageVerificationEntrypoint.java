package br.com.cobbleverse.generations.verification;

import br.com.cobbleverse.generations.config.ConfigStore;
import br.com.cobbleverse.generations.config.GenerationConfig;
import br.com.cobbleverse.generations.integration.PlayerImpact;
import br.com.cobbleverse.generations.integration.StorePaths;
import br.com.cobbleverse.generations.integration.ValidationReport;
import br.com.cobbleverse.generations.mixin.*;
import br.com.cobbleverse.generations.rules.GenerationRules;
import com.cobblemon.mod.common.Cobblemon;
import com.cobblemon.mod.common.api.pokemon.PokemonSpecies;
import com.cobblemon.mod.common.api.storage.adapter.flatfile.FileStoreAdapter;
import com.cobblemon.mod.common.api.storage.factory.FileBackedPokemonStoreFactory;
import com.cobblemon.mod.common.api.storage.party.PlayerPartyStore;
import java.nio.file.*;
import java.util.*;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.util.WorldSavePath;

/** Used only by a separate, disposable Fabric test fixture with modified metadata. */
public final class StorageVerificationEntrypoint implements ModInitializer {
    @Override public void onInitialize() {
        ServerTickEvents.END_SERVER_TICK.register(new ServerTickEvents.EndTick() {
            boolean done;
            @Override public void onEndTick(net.minecraft.server.MinecraftServer server) {
                if(done) return; done=true;
                Map<String,Object> result=new LinkedHashMap<>();
                try { run(server,result); }
                catch(Throwable failure) { result.put("fatal",failure.toString()); failure.printStackTrace(); }
                try { ConfigStore.write(Path.of("storage-test-result.json").toAbsolutePath(),result); }
                catch(Exception failure) { failure.printStackTrace(); }
                server.stop(false);
            }
        });
    }

    @SuppressWarnings({"rawtypes","unchecked"})
    private static void run(net.minecraft.server.MinecraftServer server,Map<String,Object> out) throws Exception {
        var factories=((StoreManagerAccessor)(Object)Cobblemon.INSTANCE.getStorage()).generationControl$factories();
        FileBackedPokemonStoreFactory<?> factory=null;
        for(var candidate:factories) if(candidate instanceof FileBackedPokemonStoreFactory<?> f) { factory=f; break; }
        if(factory==null) throw new IllegalStateException("No file-backed store factory");
        var access=(StoreFactoryAccessor)(Object)factory;
        var adapter=(FileStoreAdapter)access.generationControl$adapter();
        var paths=(StorePathAccessor)(Object)adapter;
        UUID id=UUID.randomUUID();
        Path data=server.getSavePath(WorldSavePath.PLAYERDATA); Files.createDirectories(data); Files.write(data.resolve(id+".dat"),new byte[]{1});
        var party=new PlayerPartyStore(id); party.initialize();
        if(!party.add(PokemonSpecies.getByName("pichu").create(5))) throw new IllegalStateException("Fixture party full");
        adapter.save(PlayerPartyStore.class,id,adapter.serialize(party,server.getRegistryManager()));
        Path path=StorePaths.path(paths.generationControl$root(),paths.generationControl$nested(),paths.generationControl$perClass(),paths.generationControl$extension(),PlayerPartyStore.class.getSimpleName(),id);
        var rules=new GenerationRules(new GenerationConfig());
        var normal=new ValidationReport(); PlayerImpact.inspect(server,rules,normal);
        boolean cached=access.generationControl$caches().values().stream().anyMatch(cache -> ((StoreCacheAccessor)cache).generationControl$cacheMap().containsKey(id));
        out.put("validStoreCounted",normal.errors.isEmpty() && normal.inspectedPlayers==1 && normal.affectedPokemon==1);
        out.put("offlineStoreNotCached",!cached);
        var live=factory.getPlayerParty(id,server.getRegistryManager());
        if(live==null || !live.add(PokemonSpecies.getByName("pichu").create(5))) throw new IllegalStateException("Cached fixture unavailable");
        var liveReport=new ValidationReport(); PlayerImpact.inspect(server,rules,liveReport);
        out.put("cachedStateUsed",liveReport.errors.isEmpty() && liveReport.affectedPokemon==2);
        var liveCache=(StoreCacheAccessor)access.generationControl$caches().get(PlayerPartyStore.class);
        out.put("existingCachePreserved",liveCache.generationControl$cacheMap().get(id)==live);
        // Remove only this throwaway fixture entry before testing a damaged offline file.
        liveCache.generationControl$cacheMap().remove(id);
        Path pcPath=StorePaths.path(paths.generationControl$root(),paths.generationControl$nested(),paths.generationControl$perClass(),paths.generationControl$extension(),com.cobblemon.mod.common.api.storage.pc.PCStore.class.getSimpleName(),id);
        out.put("missingPcNotCreated",Files.notExists(pcPath));
        Path old=Path.of(path+".old"); Files.copy(path,old,StandardCopyOption.REPLACE_EXISTING); Files.writeString(path,"damaged-contents");
        byte[] primaryBefore=Files.readAllBytes(path), backupBefore=Files.readAllBytes(old);
        var corrupt=new ValidationReport(); PlayerImpact.inspect(server,rules,corrupt);
        out.put("corruptionRejected",!corrupt.errors.isEmpty());
        out.put("primaryUnchanged",Arrays.equals(primaryBefore,Files.readAllBytes(path)));
        out.put("backupUnchanged",Arrays.equals(backupBefore,Files.readAllBytes(old)));
        out.put("noLiveCacheAfterCorruptRead",access.generationControl$caches().values().stream().noneMatch(cache -> ((StoreCacheAccessor)cache).generationControl$cacheMap().containsKey(id)));
        var started=new java.util.concurrent.CountDownLatch(1);
        var release=new java.util.concurrent.CountDownLatch(1);
        var pending=access.generationControl$executor().submit(() -> { started.countDown(); try { release.await(); } catch(InterruptedException e) { Thread.currentThread().interrupt(); } });
        try {
            if(!started.await(1,java.util.concurrent.TimeUnit.SECONDS)) throw new IllegalStateException("Writer test did not start");
            var busy=new ValidationReport(); long before=System.nanoTime(); PlayerImpact.inspect(server,rules,busy);
            long durationMs=java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-before);
            out.put("busyWriterRejected",busy.errors.stream().anyMatch(s -> s.contains("armazenamento está ocupado")));
            out.put("busyWriterWaitMs",durationMs);
        } finally { release.countDown(); pending.get(1,java.util.concurrent.TimeUnit.SECONDS); }
        out.put("normalErrors",normal.errors); out.put("corruptErrors",corrupt.errors);
    }
}
