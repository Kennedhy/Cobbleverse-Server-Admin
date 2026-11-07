package br.com.cobbleverse.generations.verification;

import br.com.cobbleverse.generations.GenerationControl;
import br.com.cobbleverse.generations.config.*;
import com.cobblemon.mod.common.api.pokemon.PokemonProperties;
import com.cobblemon.mod.common.api.pokeball.PokeBalls;
import com.cobblemon.mod.common.entity.pokeball.EmptyPokeBallEntity;
import com.cobblemon.mod.common.pokeball.catching.calculators.CobblemonCaptureCalculator;
import java.nio.file.*;
import java.util.*;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.entity.decoration.ArmorStandEntity;

/** Only activated through altered metadata in the disposable server fixture. */
public final class CaptureVerificationEntrypoint implements ModInitializer {
    public void onInitialize() {
        ServerTickEvents.END_SERVER_TICK.register(new ServerTickEvents.EndTick() {
            boolean done;
            public void onEndTick(net.minecraft.server.MinecraftServer server) {
                if(done) return; done=true;
                Map<String,Object> out=new LinkedHashMap<>();
                try {
                    var world=server.getOverworld();
                    var thrower=new ArmorStandEntity(world,0,80,0);
                    var ball=new EmptyPokeBallEntity(world);
                    var target=PokemonProperties.Companion.parse("bulbasaur level=5").createEntity(world);
                    var policy=new GenerationConfig(); policy.captureMultiplier=0;
                    Path base=Path.of("capture-policy-fixture").toAbsolutePath();
                    ConfigStore.write(base.resolve("generation-control/active.json"),policy);
                    GenerationControl.openWorld(base);
                    boolean zero=true;
                    for(int i=0;i<100;i++) zero &= !CobblemonCaptureCalculator.INSTANCE.processCapture(thrower,ball,target).isSuccessfulCapture();
                    out.put("zeroMultiplierBlocksNormalBall",zero);
                    ball.setPokeBall(PokeBalls.getMasterBall());
                    out.put("masterBallGuaranteed",CobblemonCaptureCalculator.INSTANCE.processCapture(thrower,ball,target).isSuccessfulCapture());
                    final int samples=6000;
                    double[][] rates=new double[2][2];
                    for(int m=0;m<2;m++) {
                        var setting=new GenerationConfig(); setting.captureMultiplier=m+1;
                        Path variant=Path.of("capture-variant-"+(m+1)).toAbsolutePath();
                        ConfigStore.write(variant.resolve("generation-control/active.json"),setting);
                        GenerationControl.openWorld(variant);
                        for(int kind=0;kind<2;kind++) {
                            ball.setPokeBall(kind==0 ? PokeBalls.getPokeBall() : PokeBalls.getUltraBall());
                            int success=0;
                            for(int i=0;i<samples;i++) if(CobblemonCaptureCalculator.INSTANCE.processCapture(thrower,ball,target).isSuccessfulCapture()) success++;
                            rates[m][kind]=(double)success/samples;
                        }
                    }
                    out.put("pokeBall1x",rates[0][0]); out.put("ultraBall1x",rates[0][1]);
                    out.put("pokeBall2x",rates[1][0]); out.put("ultraBall2x",rates[1][1]);
                    out.put("ultraEffectPreserved",rates[0][1]>rates[0][0] && rates[1][1]>rates[1][0]);
                    out.put("approximatelyDoublesBoth",Math.abs(rates[1][0]-2*rates[0][0])<0.045 && Math.abs(rates[1][1]-2*rates[0][1])<0.045);
                } catch(Throwable e) { out.put("fatal",e.toString()); e.printStackTrace(); }
                try { ConfigStore.write(Path.of("capture-test-result.json").toAbsolutePath(),out); } catch(Exception e) { e.printStackTrace(); }
                server.stop(false);
            }
        });
    }
}
