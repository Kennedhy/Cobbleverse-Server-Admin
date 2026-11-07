package br.com.cobbleverse.generations.verification;

import br.com.cobbleverse.generations.GenerationControl;
import br.com.cobbleverse.generations.config.*;
import com.cobblemon.mod.common.Cobblemon;
import com.cobblemon.mod.common.api.pokedex.*;
import com.cobblemon.mod.common.api.pokemon.*;
import com.cobblemon.mod.common.api.pokeball.PokeBalls;
import com.cobblemon.mod.common.entity.pokeball.EmptyPokeBallEntity;
import com.cobblemon.mod.common.pokeball.catching.calculators.CobblemonCaptureCalculator;
import java.nio.file.*;
import java.util.*;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.network.packet.c2s.common.SyncedClientOptions;
import net.minecraft.server.network.ServerPlayerEntity;

/** Server-side player fixture, only activated in a disposable server. */
public final class PlayerCaptureVerificationEntrypoint implements ModInitializer {
    public void onInitialize() {
        ServerTickEvents.END_SERVER_TICK.register(new ServerTickEvents.EndTick() {
            boolean done;
            public void onEndTick(net.minecraft.server.MinecraftServer server) {
                if(done) return; done=true; Map<String,Object> out=new LinkedHashMap<>();
                try {
                    var world=server.getOverworld();
                    var player=new ServerPlayerEntity(server,world,new com.mojang.authlib.GameProfile(UUID.randomUUID(),"CaptureProbe"),SyncedClientOptions.createDefault());
                    var dex=Cobblemon.INSTANCE.getPlayerDataManager().getPokedexData(player);
                    int added=0;
                    for(var species:PokemonSpecies.getImplemented()) {
                        if(species.getName().equalsIgnoreCase("bulbasaur")) continue;
                        dex.getOrCreateSpeciesRecord(species.getResourceIdentifier()).getOrCreateFormRecord(species.getStandardForm().getName()).setKnowledgeProgress(PokedexEntryProgress.CAUGHT);
                        if(++added>=601) break;
                    }
                    int caught=dex.getGlobalCalculatedValue(CaughtCount.INSTANCE);
                    out.put("caughtSpecies",caught); if(caught<601) throw new IllegalStateException("Fixture não obteve progresso suficiente na Pokédex.");
                    var ball=new EmptyPokeBallEntity(world); ball.setPokeBall(PokeBalls.getPokeBall());
                    var target=PokemonProperties.Companion.parse("bulbasaur level=5").createEntity(world);
                    var zeroPolicy=new GenerationConfig(); zeroPolicy.captureMultiplier=0;
                    Path zeroBase=Path.of("player-capture-policy-zero").toAbsolutePath();
                    ConfigStore.write(zeroBase.resolve("generation-control/active.json"),zeroPolicy); GenerationControl.openWorld(zeroBase);
                    boolean zero=true;
                    for(int i=0;i<100;i++) zero &= !CobblemonCaptureCalculator.INSTANCE.processCapture(player,ball,target).isSuccessfulCapture();
                    out.put("playerZeroBlocksCommonBall",zero);
                    ball.setPokeBall(PokeBalls.getMasterBall());
                    out.put("playerMasterBallGuaranteed",CobblemonCaptureCalculator.INSTANCE.processCapture(player,ball,target).isSuccessfulCapture());
                    ball.setPokeBall(PokeBalls.getPokeBall());
                    final int samples=6000; double[] success=new double[2],critical=new double[2];
                    for(int m=0;m<2;m++) {
                        var policy=new GenerationConfig(); policy.captureMultiplier=m+1;
                        Path base=Path.of("player-capture-policy-"+(m+1)).toAbsolutePath();
                        ConfigStore.write(base.resolve("generation-control/active.json"),policy); GenerationControl.openWorld(base);
                        int successes=0,criticals=0;
                        for(int i=0;i<samples;i++) {
                            var result=CobblemonCaptureCalculator.INSTANCE.processCapture(player,ball,target);
                            if(result.isSuccessfulCapture()) successes++;
                            if(result.isCriticalCapture()) criticals++;
                        }
                        success[m]=(double)successes/samples; critical[m]=(double)criticals/samples;
                    }
                    out.put("success1x",success[0]); out.put("success2x",success[1]);
                    out.put("criticalFrequency1x",critical[0]); out.put("criticalFrequency2x",critical[1]);
                    out.put("criticalBranchExercised",critical[0]>.005 && critical[1]>.005);
                    out.put("criticalFrequencyPreserved",Math.abs(critical[0]-critical[1])<.025);
                    out.put("overallChanceApproximatelyDoubled",Math.abs(success[1]-2*success[0])<.05);
                } catch(Throwable e) { out.put("fatal",e.toString()); e.printStackTrace(); }
                try { ConfigStore.write(Path.of("player-capture-result.json").toAbsolutePath(),out); } catch(Exception e) { e.printStackTrace(); }
                server.stop(false);
            }
        });
    }
}
