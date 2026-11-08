package br.com.cobbleverse.generations.verification;
import br.com.cobbleverse.generations.GenerationControl;
import br.com.cobbleverse.generations.config.ConfigStore;
import br.com.cobbleverse.generations.rules.GameplaySettings;
import br.com.cobbleverse.generations.rules.GenerationRules;
import com.cobblemon.mod.common.api.pokemon.PokemonProperties;
import com.cobblemon.mod.common.api.pokeball.PokeBalls;
import com.cobblemon.mod.common.entity.pokeball.EmptyPokeBallEntity;
import com.cobblemon.mod.common.entity.pokemon.PokemonEntity;
import com.cobblemon.mod.common.pokeball.catching.calculators.CobblemonCaptureCalculator;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import java.nio.file.Path;
import java.util.*;
/** Server-only, disposable, sample-based connected-player capture fixture. */
public final class ConnectedCaptureVerification implements ModInitializer {
 public void onInitialize(){ServerTickEvents.END_SERVER_TICK.register(new ServerTickEvents.EndTick(){
  final int sample=2000; int phase=-1,count,success; int[] counts=new int[4]; boolean done; GenerationRules original; GameplaySettings originalSettings; EmptyPokeBallEntity ball; PokemonEntity target; java.lang.reflect.Field field,settingsField; int zeroSuccess;
  public void onEndTick(net.minecraft.server.MinecraftServer server){
   if(done)return;var player=server.getPlayerManager().getPlayer("NetAdmin");if(player==null)return;
   try {
    if(phase==-1){original=GenerationControl.rules();originalSettings=GenerationControl.gameplaySettings();field=GenerationControl.class.getDeclaredField("rules");field.setAccessible(true);settingsField=GenerationControl.class.getDeclaredField("gameplaySettings");settingsField.setAccessible(true);var world=server.getOverworld();ball=new EmptyPokeBallEntity(world);target=PokemonProperties.Companion.parse("bulbasaur level=5").createEntity(world);phase=0;setPolicy(1);}
    if(phase<4){ball.setPokeBall((phase%2)==0?PokeBalls.getPokeBall():PokeBalls.getUltraBall());
     for(int n=0;n<100 && count<sample;n++,count++)if(CobblemonCaptureCalculator.INSTANCE.processCapture(player,ball,target).isSuccessfulCapture())success++;
     if(count==sample){counts[phase]=success;phase++;count=0;success=0;if(phase==2)setPolicy(2);}
     return;
    }
    setPolicy(0);ball.setPokeBall(PokeBalls.getPokeBall());for(int i=0;i<100;i++)if(CobblemonCaptureCalculator.INSTANCE.processCapture(player,ball,target).isSuccessfulCapture())zeroSuccess++;
    ball.setPokeBall(PokeBalls.getMasterBall());boolean master=CobblemonCaptureCalculator.INSTANCE.processCapture(player,ball,target).isSuccessfulCapture();
    double p1=(double)counts[0]/sample,u1=(double)counts[1]/sample,p2=(double)counts[2]/sample,u2=(double)counts[3]/sample;
    var out=new LinkedHashMap<String,Object>();out.put("connected",player.networkHandler!=null);out.put("samplesEach",sample);out.put("pokeball1x",p1);out.put("ultraball1x",u1);out.put("pokeball2x",p2);out.put("ultraball2x",u2);out.put("zeroSuccess",zeroSuccess);out.put("masterGuaranteed",master);
    out.put("success",zeroSuccess==0 && master && u1>p1 && u2>p2 && Math.abs(p2-2*p1)<0.09 && Math.abs(u2-2*u1)<0.09);finish(server,out);
   }catch(Throwable e){e.printStackTrace();finish(server,Map.of("fatal",e.toString(),"phase",phase));}
  }
  void setPolicy(double multiplier)throws Exception {var c=original.config();c.captureMultiplier=multiplier;field.set(null,new GenerationRules(c));settingsField.set(null,GameplaySettings.from(c));}
  void finish(net.minecraft.server.MinecraftServer server,Map<String,Object> out){done=true;try{if(field!=null&&original!=null)field.set(null,original);if(settingsField!=null&&originalSettings!=null)settingsField.set(null,originalSettings);ConfigStore.write(Path.of("connected-capture-result.json").toAbsolutePath(),out);}catch(Exception e){e.printStackTrace();}server.stop(false);}
 });}
}
