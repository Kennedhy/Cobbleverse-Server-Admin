package br.com.cobbleverse.generations.verification;
import br.com.cobbleverse.generations.GenerationControl;
import br.com.cobbleverse.generations.config.ConfigStore;
import com.cobblemon.mod.common.Cobblemon;
import com.cobblemon.mod.common.api.pokemon.PokemonSpecies;
import com.cobblemon.mod.common.api.pokemon.experience.CommandExperienceSource;
import com.gitlab.srcmc.rctmod.api.data.sync.PlayerState;
import com.gitlab.srcmc.rctmod.api.utils.LevelUtils;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import java.nio.file.Path;
import java.util.*;
/** Test-only entrypoint against a disposable world and connected protocol client. */
public final class ConnectedXpVerification implements ModInitializer {
 public void onInitialize(){ServerTickEvents.END_SERVER_TICK.register(new ServerTickEvents.EndTick(){
  boolean done;int ticks;
  public void onEndTick(net.minecraft.server.MinecraftServer server){
   if(done)return; ticks++;
   var player=server.getPlayerManager().getPlayer("NetAdmin");
   if(player==null)return;
   var state=PlayerState.get(player); if(state==null||state.isLoading()){if(ticks<2200)return;finish(server,Map.of("fatal","RCT progress did not load"));return;}
   var out=new LinkedHashMap<String,Object>();var party=Cobblemon.INSTANCE.getStorage().getParty(player);
   var mon=PokemonSpecies.getByName("bulbasaur").create(1);boolean added=false;
   try {
    int cap=LevelUtils.levelCap(player);out.put("playerConnected",player.networkHandler!=null);out.put("rctLoaded",true);out.put("rctCap",cap);out.put("activeRevision",GenerationControl.rules().config().revision);out.put("globalMultiplier",GenerationControl.rules().config().globalXpMultiplier);
    if(cap<2)throw new IllegalStateException("Limite RCT insuficiente para conferir ganho de XP");
    if(!party.add(mon))throw new IllegalStateException("Equipe do jogador de teste sem espaço");added=true;
    int before=mon.getExperience();mon.addExperience(new CommandExperienceSource(server.getCommandSource()),1);int gained=mon.getExperience()-before;
    out.put("baseXp",1);out.put("awardedXp",gained);out.put("success",gained==3 && GenerationControl.rules().config().revision==103);
   } catch(Throwable e){out.put("fatal",e.toString());e.printStackTrace();} finally {if(added)party.remove(mon);}
   finish(server,out);
  }
  void finish(net.minecraft.server.MinecraftServer server,Map<String,Object> out){done=true;try{ConfigStore.write(Path.of("connected-xp-result.json").toAbsolutePath(),out);}catch(Exception e){e.printStackTrace();}server.stop(false);}
 });}
}
