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
/** Disposable integration fixture for online players and applied XP group policies. */
public final class ConnectedXpGroupsVerification implements ModInitializer {
 public void onInitialize(){ServerTickEvents.END_SERVER_TICK.register(new ServerTickEvents.EndTick(){boolean done;
  public void onEndTick(net.minecraft.server.MinecraftServer server){
   if(done)return;var admin=server.getPlayerManager().getPlayer("NetAdmin");var guest=server.getPlayerManager().getPlayer("NetGuest");if(admin==null||guest==null)return;
   var a=PlayerState.get(admin);var b=PlayerState.get(guest);if(a==null||b==null||a.isLoading()||b.isLoading())return;
   var out=new LinkedHashMap<String,Object>();boolean success=true;
   try{
    var cfg=GenerationControl.rules().config();out.put("activeRevision",cfg.revision);out.put("global",cfg.globalXpMultiplier);
    for(var player:List.of(admin,guest)){
     var party=Cobblemon.INSTANCE.getStorage().getParty(player);var mon=PokemonSpecies.getByName("bulbasaur").create(5);boolean added=false;
     try{
      int cap=LevelUtils.levelCap(player);if(cap<7)throw new IllegalStateException("RCT cap too low: "+cap);
      if(!party.add(mon))throw new IllegalStateException("No free party slot");added=true;int before=mon.getExperience();
      mon.addExperience(new CommandExperienceSource(server.getCommandSource()),10);int gain=mon.getExperience()-before;
      int expected=player==admin?30:10;boolean ok=gain==expected;success &= ok;
      out.put(player.getGameProfile().getName(),Map.of("connected",player.networkHandler!=null,"cap",cap,"baseXp",10,"awardedXp",gain,"expectedXp",expected,"success",ok));
     }finally{if(added)party.remove(mon);}
    }
    out.put("success",success&&cfg.globalXpMultiplier==2.0);
   }catch(Throwable e){out.put("fatal",e.toString());e.printStackTrace();}
   done=true;try{ConfigStore.write(Path.of("connected-xp-groups-result.json").toAbsolutePath(),out);}catch(Exception e){e.printStackTrace();}server.stop(false);
  }
 });}
}
