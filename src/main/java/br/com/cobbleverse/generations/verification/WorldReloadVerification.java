package br.com.cobbleverse.generations.verification;
import br.com.cobbleverse.generations.GenerationControl;
import br.com.cobbleverse.generations.config.ConfigStore;
import br.com.cobbleverse.generations.rules.GenerationRules;
import com.cobblemon.mod.common.api.pokemon.PokemonProperties;
import com.cobblemon.mod.common.entity.pokemon.PokemonEntity;
import com.google.gson.*;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents;
import java.nio.file.*;
import java.util.*;
/** Two-run fixture, exclusively for an isolated test world. */
public final class WorldReloadVerification implements ModInitializer {
 public void onInitialize() {
  Set<UUID> loads=new HashSet<>(); ServerEntityEvents.ENTITY_LOAD.register((entity,world)->loads.add(entity.getUuid()));
  ServerTickEvents.END_SERVER_TICK.register(new ServerTickEvents.EndTick() {
   final Path state=Path.of("world-reload-fixture.json"); final Path result=Path.of("world-reload-result.json");
   final Map<String,UUID> ids=new LinkedHashMap<>(); final Map<String,PokemonEntity> created=new LinkedHashMap<>(); boolean second=Files.exists(state); int ticks;
   public void onEndTick(net.minecraft.server.MinecraftServer server) {
    try {
     var world=server.getOverworld(); ticks++;
     if(ticks==2) {
      world.setChunkForced(0,0,true);world.getChunk(0,0);
      if(second) {
       JsonObject saved=JsonParser.parseString(Files.readString(state)).getAsJsonObject();
       for(var e:saved.entrySet())ids.put(e.getKey(),UUID.fromString(e.getValue().getAsString()));
      } else {
       var cfg=GenerationControl.rules().config(); cfg.mode="cumulative";cfg.selectedGeneration=9;cfg.regionalForms=true;cfg.megaEvolutions=true;
       var field=GenerationControl.class.getDeclaredField("rules");field.setAccessible(true);field.set(null,new GenerationRules(cfg));
       int x=2;
       for(String raw:List.of("bulbasaur","pichu","treecko","raichu alolan")) {
        var e=PokemonProperties.Companion.parse(raw).createEntity(world);e.refreshPositionAndAngles(x,200,2,0,0);x+=3;e.setNoGravity(true);e.setAiDisabled(true);e.setInvulnerable(true);e.setPersistent();
        if(!world.spawnEntity(e))throw new IllegalStateException("Falha ao inserir "+raw);ids.put(raw,e.getUuid());created.put(raw,e);
       }
      }
     }
     if(ticks==102) {
      var details=new LinkedHashMap<String,Object>(); boolean success=true;
      for(var entry:ids.entrySet()) {
       boolean present=world.getEntity(entry.getValue())!=null; boolean expected=!second || entry.getKey().equals("bulbasaur");
       if(present!=expected)success=false;
       details.put(entry.getKey(),Map.of("uuid",entry.getValue().toString(),"present",present,"expected",expected,"entityLoadObserved",loads.contains(entry.getValue())));
      }
      if(!second && success)ConfigStore.write(state.toAbsolutePath(),ids);
      ConfigStore.write(result.toAbsolutePath(),Map.of("phase",second?"reload":"seed","success",success,"details",details));
      server.stop(false);
     }
    } catch(Throwable e) {e.printStackTrace();try {ConfigStore.write(result.toAbsolutePath(),Map.of("fatal",e.toString(),"phase",second?"reload":"seed"));} catch(Exception ignored) {}server.stop(false);}
   }
  });
 }
}
