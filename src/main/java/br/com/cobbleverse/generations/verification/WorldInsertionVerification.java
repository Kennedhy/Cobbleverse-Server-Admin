package br.com.cobbleverse.generations.verification;
import br.com.cobbleverse.generations.config.ConfigStore;
import com.cobblemon.mod.common.api.pokemon.PokemonProperties;
import com.cobblemon.mod.common.entity.pokemon.PokemonEntity;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import java.nio.file.Path;
import java.util.*;
/** Runs only through test metadata in an isolated world. */
public final class WorldInsertionVerification implements ModInitializer {
 public void onInitialize() { ServerTickEvents.END_SERVER_TICK.register(new ServerTickEvents.EndTick() {
  int ticks; final Map<String,PokemonEntity> entities=new LinkedHashMap<>(); final Map<String,Object> out=new LinkedHashMap<>();
  public void onEndTick(net.minecraft.server.MinecraftServer server) {
   try {
    var world=server.getOverworld();
    if(++ticks==2) {
     world.getChunk(0,0);
     for(String raw:List.of("bulbasaur","pichu","treecko","raichu alolan")) {
      var e=PokemonProperties.Companion.parse(raw).createEntity(world);e.refreshPositionAndAngles(0.5,200,0.5,0,0);e.setNoGravity(true);e.setAiDisabled(true);e.setInvulnerable(true);
      var result=new LinkedHashMap<String,Object>();result.put("wild",e.getPokemon().isWild());result.put("spawnReturned",world.spawnEntity(e));result.put("immediateRemoved",e.isRemoved());out.put(raw,result);entities.put(raw,e);
     }
    }
    if(ticks==12) {
     boolean success=true;
     for(var entry:entities.entrySet()) {
      @SuppressWarnings("unchecked") var item=(Map<String,Object>)out.get(entry.getKey());var e=entry.getValue();
      boolean inWorld=world.getEntity(e.getUuid())!=null;item.put("afterTicksRemoved",e.isRemoved());item.put("afterTicksInWorld",inWorld);
      boolean expected=entry.getKey().equals("bulbasaur");if(inWorld!=expected)success=false;e.discard();
     }
     out.put("success",success);ConfigStore.write(Path.of("world-insertion-result.json").toAbsolutePath(),out);server.stop(false);
    }
   } catch(Throwable e) { try {ConfigStore.write(Path.of("world-insertion-result.json").toAbsolutePath(),Map.of("fatal",e.toString()));} catch(Exception ignored) {} server.stop(false); }
  }
 }); }
}
