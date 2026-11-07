package br.com.cobbleverse.generations.verification;

import br.com.cobbleverse.generations.GenerationControl;
import br.com.cobbleverse.generations.config.*;
import br.com.cobbleverse.generations.integration.*;
import br.com.cobbleverse.generations.mixin.*;
import com.cobblemon.mod.common.Cobblemon;
import com.cobblemon.mod.common.api.storage.factory.FileBackedPokemonStoreFactory;
import com.cobblemon.mod.common.api.storage.party.PlayerPartyStore;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.util.WorldSavePath;

/** Disposable server fixture for asynchronous scan failure paths. */
public final class BatchFailureVerificationEntrypoint implements ModInitializer {
    public void onInitialize() {
        ValidationService.register();
        ServerTickEvents.END_SERVER_TICK.register(new ServerTickEvents.EndTick() {
            boolean started; int ticks; final Map<String,Object> out=new LinkedHashMap<>();
            CountDownLatch release=new CountDownLatch(1); Future<?> occupied;
            public void onEndTick(net.minecraft.server.MinecraftServer server) {
                if(++ticks>250) { finish(server,"timeout"); return; }
                if(started) return;
                started=true;
                try {
                    var factories=((StoreManagerAccessor)(Object)Cobblemon.INSTANCE.getStorage()).generationControl$factories();
                    FileBackedPokemonStoreFactory<?> factory=(FileBackedPokemonStoreFactory<?>)factories.iterator().next();
                    var access=(StoreFactoryAccessor)(Object)factory;
                    var paths=(StorePathAccessor)(Object)access.generationControl$adapter();
                    UUID first=new UUID(0,1), second=new UUID(0,2);
                    Path playerdata=server.getSavePath(WorldSavePath.PLAYERDATA); Files.createDirectories(playerdata);
                    Files.write(playerdata.resolve(first+".dat"),new byte[]{1}); Files.write(playerdata.resolve(second+".dat"),new byte[]{1});
                    Path damaged=StorePaths.path(paths.generationControl$root(),paths.generationControl$nested(),paths.generationControl$perClass(),paths.generationControl$extension(),PlayerPartyStore.class.getSimpleName(),second);
                    Files.createDirectories(damaged.getParent()); byte[] bad={1,2,3,4,5}; Files.write(damaged,bad);
                    Path backup=Path.of(damaged+".old"); byte[] originalBackup={9,8,7}; Files.write(backup,originalBackup);
                    var active=new GenerationConfig(); Path world=Path.of("failure-policy-fixture").toAbsolutePath();
                    ConfigStore.write(world.resolve("generation-control/active.json"),active); GenerationControl.openWorld(world);
                    var candidate=active.copy(); candidate.restrictExistingPokemon=false;
                    boolean accepted=ValidationService.start(server,candidate,report -> {
                        try {
                            out.put("corruptRejected",!report.errors.isEmpty());
                            out.put("partialCountsCleared",report.inspectedPlayers==0 && report.affectedPlayers==0 && report.affectedPokemon==0);
                            out.put("corruptFilePreserved",Arrays.equals(bad,Files.readAllBytes(damaged)));
                            out.put("backupPreserved",Arrays.equals(originalBackup,Files.readAllBytes(backup)));
                            Files.delete(damaged); Files.delete(backup);
                            CountDownLatch blocked=new CountDownLatch(1);
                            occupied=access.generationControl$executor().submit(()-> { blocked.countDown(); try { release.await(); } catch(InterruptedException e) { Thread.currentThread().interrupt(); } });
                            if(!blocked.await(1,TimeUnit.SECONDS)) throw new IllegalStateException("failed to occupy writer");
                            long before=System.nanoTime();
                            boolean secondStarted=ValidationService.start(server,candidate,busy -> {
                                out.put("busyWriterRejected",busy.errors.stream().anyMatch(s->s.contains("Armazenamento ocupado")));
                                out.put("busyPartialCountsCleared",busy.inspectedPlayers==0);
                                out.put("busyElapsedMs",TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-before));
                                release.countDown();
                                try {
                                    var root=new net.minecraft.nbt.NbtCompound(); root.putInt("SlotCount",6);
                                    var invalid=new net.minecraft.nbt.NbtCompound(); invalid.putString("invalid", "fixture"); root.put("Slot0",invalid);
                                    net.minecraft.nbt.NbtIo.writeCompressed(root,damaged);
                                    byte[] beforeInvalid=Files.readAllBytes(damaged);
                                    boolean slotStarted=ValidationService.start(server,candidate,invalidReport -> {
                                        try {
                                            out.put("invalidPokemonRejected",!invalidReport.errors.isEmpty());
                                            out.put("invalidPokemonCountsCleared",invalidReport.inspectedPlayers==0);
                                            out.put("invalidPokemonFilePreserved",Arrays.equals(beforeInvalid,Files.readAllBytes(damaged)));
                                            finish(server,null);
                                        } catch(Exception e) { finish(server,e.toString()); }
                                    });
                                    out.put("invalidPokemonJobAccepted",slotStarted);
                                    if(!slotStarted) finish(server,"invalid slot job rejected");
                                } catch(Exception e) { finish(server,e.toString()); }
                            });
                            out.put("busyJobAccepted",secondStarted);
                        } catch(Throwable e) { finish(server,e.toString()); }
                    });
                    out.put("corruptJobAccepted",accepted);
                    out.put("concurrentJobRejected",!ValidationService.start(server,candidate,report -> out.put("unexpectedCallback",true)));
                } catch(Throwable e) { finish(server,e.toString()); }
            }
            void finish(net.minecraft.server.MinecraftServer server,String failure) {
                release.countDown(); if(failure!=null) out.put("fatal",failure);
                try { ConfigStore.write(Path.of("batch-failure-result.json").toAbsolutePath(),out); }
                catch(Exception e) { e.printStackTrace(); }
                server.stop(false);
            }
        });
    }
}
