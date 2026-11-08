package br.com.cobbleverse.generations.verification;
import br.com.cobbleverse.generations.GenerationControl;
import br.com.cobbleverse.generations.config.ConfigStore;
import br.com.cobbleverse.generations.rules.GameplaySettings;
import br.com.cobbleverse.generations.rules.GenerationRules;
import com.cobblemon.mod.common.api.events.CobblemonEvents;
import com.cobblemon.mod.common.api.events.entity.SpawnEvent;
import com.cobblemon.mod.common.api.pokemon.PokemonProperties;
import com.cobblemon.mod.common.api.spawning.*;
import com.cobblemon.mod.common.api.spawning.spawner.Spawner;
import com.cobblemon.mod.common.api.spawning.position.BasicSpawnablePosition;
import com.gitlab.srcmc.rctmod.api.data.sync.PlayerState;
import com.gitlab.srcmc.rctmod.api.utils.LevelUtils;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.util.math.BlockPos;
import java.nio.file.Path;
import java.util.*;
/** Disposable fixture; real connections, temporary test-only RCT progress. */
public final class ConnectedWildLevelsVerification implements ModInitializer {
 public void onInitialize(){ServerTickEvents.END_SERVER_TICK.register(new ServerTickEvents.EndTick(){
  boolean done;int ticks;java.lang.reflect.Field field,settingsField;GenerationRules original;GameplaySettings originalSettings;
  @SuppressWarnings("unchecked") public void onEndTick(net.minecraft.server.MinecraftServer server){
   if(done)return;ticks++;var low=server.getPlayerManager().getPlayer("NetAdmin");var high=server.getPlayerManager().getPlayer("NetGuest");
   if(low==null||high==null)return;var ls=PlayerState.get(low);var hs=PlayerState.get(high);if(ls==null||hs==null||ls.isLoading()||hs.isLoading())return;
   var out=new LinkedHashMap<String,Object>();Map<UUID,Object> states=null;Map<UUID,Object> cache=null;Object oldLow=null,oldHigh=null,oldCacheLow=null,oldCacheHigh=null;
   try{
    var world=server.getOverworld();original=GenerationControl.rules();originalSettings=GenerationControl.gameplaySettings();field=GenerationControl.class.getDeclaredField("rules");field.setAccessible(true);settingsField=GenerationControl.class.getDeclaredField("gameplaySettings");settingsField.setAccessible(true);
    var statesField=PlayerState.class.getDeclaredField("remoteStates");statesField.setAccessible(true);states=(Map<UUID,Object>)statesField.get(null);oldLow=states.get(low.getUuid());oldHigh=states.get(high.getUuid());
    var constructor=PlayerState.class.getDeclaredConstructor(net.minecraft.entity.player.PlayerEntity.class);constructor.setAccessible(true);
    var a=(PlayerState)constructor.newInstance(low);var b=(PlayerState)constructor.newInstance(high);a.setCurrentSeries("kanto");b.setCurrentSeries("kanto");
    for(String id:List.of("kanto_brock","kanto_misty","kanto_ltsurge","kanto_erika"))b.addProgressDefeat(id);
    states.put(low.getUuid(),a);states.put(high.getUuid(),b);
    // The reduced fixture does not load trainer graphs. Seed only the RCT cap cache for this selection test.
    var cacheField=LevelUtils.class.getDeclaredField("PLAYER_LEVEL_CAP_CACHE");cacheField.setAccessible(true);cache=(Map<UUID,Object>)cacheField.get(null);oldCacheLow=cache.get(low.getUuid());oldCacheHigh=cache.get(high.getUuid());
    var keyType=Class.forName("com.gitlab.srcmc.rctmod.api.utils.LevelUtils$PlayerSeriesState");var keyCtor=keyType.getDeclaredConstructor(String.class,Set.class);keyCtor.setAccessible(true);
    cache.put(low.getUuid(),Map.entry(keyCtor.newInstance(a.getCurrentSeries(),Set.copyOf(a.getDefeatedTrainerIds())),20));cache.put(high.getUuid(),Map.entry(keyCtor.newInstance(b.getCurrentSeries(),Set.copyOf(b.getDefeatedTrainerIds())),35));
    out.put("rctCapCacheSeededForFixture",true);int lowCap=LevelUtils.levelCap(low),highCap=LevelUtils.levelCap(high);
    low.refreshPositionAndAngles(1,200,1,0,0);high.refreshPositionAndAngles(4,200,1,0,0);
    var cfg=original.config();cfg.manageExperience=true;cfg.levelCapMode="rct";cfg.wildLevelBehavior="clamp";activate(cfg);
    int both=spawn(world);high.refreshPositionAndAngles(300,200,0,0,0);int near=spawn(world);
    cfg.wildLevelBehavior="block";activate(cfg);int block=spawn(world);
    cfg.wildLevelBehavior="unchanged";activate(cfg);int unchanged=spawn(world);
    cfg.levelCapMode="global";cfg.globalLevelCap=25;cfg.wildLevelBehavior="clamp";activate(cfg);int global=spawn(world);
    out.put("playersConnected",low.networkHandler!=null&&high.networkHandler!=null);out.put("lowCap",lowCap);out.put("highCap",highCap);out.put("bothNearby",both);out.put("highFarAway",near);out.put("blocked",block==-1);out.put("unchanged",unchanged);out.put("globalClamped",global);
    out.put("success",lowCap<highCap&&highCap<100&&both==highCap&&near==lowCap&&block==-1&&unchanged==100&&global==25);
   }catch(Throwable e){out.put("fatal",e.toString());e.printStackTrace();}finally{
    try {if(field!=null&&original!=null)field.set(null,original);if(settingsField!=null&&originalSettings!=null)settingsField.set(null,originalSettings);if(cache!=null){if(oldCacheLow==null)cache.remove(low.getUuid());else cache.put(low.getUuid(),oldCacheLow);if(oldCacheHigh==null)cache.remove(high.getUuid());else cache.put(high.getUuid(),oldCacheHigh);}if(states!=null){if(oldLow==null)states.remove(low.getUuid());else states.put(low.getUuid(),oldLow);if(oldHigh==null)states.remove(high.getUuid());else states.put(high.getUuid(),oldHigh);}}catch(Exception e){e.printStackTrace();}
   }
   done=true;try{ConfigStore.write(Path.of("connected-wild-result.json").toAbsolutePath(),out);}catch(Exception e){e.printStackTrace();}server.stop(false);
  }
  int spawn(net.minecraft.server.world.ServerWorld world){
   var e=PokemonProperties.Companion.parse("bulbasaur level=100").createEntity(world);e.refreshPositionAndAngles(0,200,0,0,0);
   var spawner=(Spawner)java.lang.reflect.Proxy.newProxyInstance(getClass().getClassLoader(),new Class<?>[]{Spawner.class},(p,m,a)->null);
   var pos=new BasicSpawnablePosition(new SpawnCause(spawner,null),world,new BlockPos(0,200,0),15,15,true,List.of());var event=new SpawnEvent<>(e,pos);CobblemonEvents.POKEMON_ENTITY_SPAWN.emit(event);return event.isCanceled()?-1:e.getPokemon().getLevel();
  }
  void activate(br.com.cobbleverse.generations.config.GenerationConfig config)throws IllegalAccessException {field.set(null,new GenerationRules(config));settingsField.set(null,GameplaySettings.from(config));}
 });}
}
