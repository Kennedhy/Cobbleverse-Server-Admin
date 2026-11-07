package br.com.cobbleverse.generations.verification;

import br.com.cobbleverse.generations.GenerationControl;
import br.com.cobbleverse.generations.config.*;
import br.com.cobbleverse.generations.integration.ExperienceManager;
import com.cobblemon.mod.common.Cobblemon;
import com.cobblemon.mod.common.api.events.CobblemonEvents;
import com.cobblemon.mod.common.api.events.pokemon.ExperienceGainedEvent;
import com.cobblemon.mod.common.api.events.entity.SpawnEvent;
import com.cobblemon.mod.common.api.pokemon.*;
import com.cobblemon.mod.common.api.spawning.*;
import com.cobblemon.mod.common.api.spawning.position.BasicSpawnablePosition;
import com.cobblemon.mod.common.api.spawning.spawner.Spawner;
import java.nio.file.*;
import java.util.*;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.network.packet.c2s.common.SyncedClientOptions;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.math.BlockPos;

/** Runs only with test metadata on a disposable Cobblemon + RCT server. */
public final class ExperienceVerificationEntrypoint implements ModInitializer {
    public void onInitialize() {
        ExperienceManager.register();
        ServerTickEvents.END_SERVER_TICK.register(new ServerTickEvents.EndTick() {
            boolean done;
            @SuppressWarnings("unchecked") public void onEndTick(net.minecraft.server.MinecraftServer server) {
                if(done) return; done=true; Map<String,Object> out=new LinkedHashMap<>();
                Map<UUID,ServerPlayerEntity> online=null; UUID playerId=null;
                try {
                    var world=server.getOverworld();
                    var player=new ServerPlayerEntity(server,world,new com.mojang.authlib.GameProfile(UUID.randomUUID(),"XpProbe"),SyncedClientOptions.createDefault()); playerId=player.getUuid();
                    for(var f:net.minecraft.server.PlayerManager.class.getDeclaredFields()) if(f.getGenericType() instanceof java.lang.reflect.ParameterizedType t && t.getRawType()==Map.class && Arrays.equals(t.getActualTypeArguments(),new java.lang.reflect.Type[]{UUID.class,ServerPlayerEntity.class})) { f.setAccessible(true); online=(Map<UUID,ServerPlayerEntity>)f.get(server.getPlayerManager()); break; }
                    if(online==null) throw new IllegalStateException("Mapa de jogadores não encontrado.");
                    var mon=PokemonSpecies.getByName("bulbasaur").create(5); var party=Cobblemon.INSTANCE.getStorage().getParty(player);
                    if(!party.add(mon)) throw new IllegalStateException("Sem espaço para Pokémon de teste.");
                    online.put(playerId,player);
                    var source=new com.cobblemon.mod.common.api.pokemon.experience.CommandExperienceSource(server.getCommandSource());
                    var config=new GenerationConfig(); config.manageExperience=true; config.levelCapMode="global"; config.globalLevelCap=100; config.globalXpMultiplier=2;
                    config.groupXpMultipliers.put("vip",1.5); config.playerXpGroups.put(player.getUuidAsString(),"vip");
                    activate(config,"xp-group");
                    var groupEvent=new ExperienceGainedEvent.Pre(mon,source,10); CobblemonEvents.EXPERIENCE_GAINED_EVENT_PRE.emit(groupEvent);
                    out.put("groupXp",groupEvent.getExperience()); out.put("groupTimesGlobal",groupEvent.getExperience()==30);
                    config.playerXpMultipliers.put(player.getUuidAsString(),0.5); activate(config,"xp-individual");
                    var playerEvent=new ExperienceGainedEvent.Pre(mon,source,10); CobblemonEvents.EXPERIENCE_GAINED_EVENT_PRE.emit(playerEvent);
                    out.put("individualXp",playerEvent.getExperience()); out.put("individualOverridesGroup",playerEvent.getExperience()==10);
                    config.playerXpMultipliers.clear(); config.playerXpGroups.clear(); activate(config,"xp-default");
                    var baseEvent=new ExperienceGainedEvent.Pre(mon,source,10); CobblemonEvents.EXPERIENCE_GAINED_EVENT_PRE.emit(baseEvent);
                    out.put("globalXp",baseEvent.getExperience()); out.put("globalApplied",baseEvent.getExperience()==20);
                    config.levelCapMode="rct"; activate(config,"xp-loading");
                    var waiting=new ExperienceGainedEvent.Pre(mon,source,10); CobblemonEvents.EXPERIENCE_GAINED_EVENT_PRE.emit(waiting);
                    out.put("missingProgressXp",waiting.getExperience()); out.put("missingProgressPreservesXp",waiting.getExperience()==0);
                    config.levelCapMode="global"; config.globalLevelCap=20; config.wildLevelBehavior="clamp"; config.manageExperience=false; activate(config,"spawn-disabled");
                    out.put("disabledSpawnUnchanged",spawn(server)==100);
                    config.manageExperience=true; activate(config,"spawn-enabled");
                    out.put("enabledSpawnClamped",spawn(server)==20);
                    config.wildLevelBehavior="block"; config.manageExperience=false; activate(config,"spawn-block-disabled");
                    out.put("disabledBlockingUnchanged",spawn(server)==100);
                    config.manageExperience=true; activate(config,"spawn-block-enabled");
                    out.put("enabledBlockingCancels",spawn(server)==-1);
                    online.remove(playerId);
                    party.remove(mon);
                } catch(Throwable e) { out.put("fatal",e.toString()); e.printStackTrace(); }
                finally { if(online!=null && playerId!=null) online.remove(playerId); }
                try { ConfigStore.write(Path.of("experience-test-result.json").toAbsolutePath(),out); } catch(Exception e) { e.printStackTrace(); }
                server.stop(false);
            }
        });
    }
    private static void activate(GenerationConfig config,String name) throws Exception {
        Path base=Path.of("experience-policy-"+name).toAbsolutePath(); ConfigStore.write(base.resolve("generation-control/active.json"),config);
        GenerationControl.openWorld(base);
    }
    private static int spawn(net.minecraft.server.MinecraftServer server) {
        var world=server.getOverworld(); var entity=PokemonProperties.Companion.parse("bulbasaur level=100").createEntity(world);
        var spawner=(Spawner)java.lang.reflect.Proxy.newProxyInstance(ExperienceVerificationEntrypoint.class.getClassLoader(),new Class<?>[]{Spawner.class},(p,m,a)->null);
        var cause=new SpawnCause(spawner,null);
        var position=new BasicSpawnablePosition(cause,world,new BlockPos(0,200,0),15,15,true,List.of());
        var event=new SpawnEvent<>(entity,position); CobblemonEvents.POKEMON_ENTITY_SPAWN.emit(event);
        return event.isCanceled() ? -1 : entity.getPokemon().getLevel();
    }
}
