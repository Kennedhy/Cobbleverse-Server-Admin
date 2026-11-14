package br.com.cobbleverse.generations.verification;

import br.com.cobbleverse.generations.GenerationControl;
import br.com.cobbleverse.generations.config.ConfigStore;
import br.com.cobbleverse.generations.integration.*;
import com.cobblemon.mod.common.Cobblemon;
import com.cobblemon.mod.common.api.pokemon.*;
import com.cobblemon.mod.common.api.storage.PokemonStore;
import com.google.gson.*;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.network.packet.c2s.common.SyncedClientOptions;
import net.minecraft.nbt.NbtCompound;
import java.nio.file.*;
import java.util.*;

/** Developer-only, disposable-world fixtures. Does not inspect real launcher accounts. */
public final class PlayerRuntimeChecks {
    private PlayerRuntimeChecks() {}
    public static void run(MinecraftServer server,List<String> passed,List<String> failed,Map<String,Object> detail) throws Exception {
        var directory=GenerationControl.store().directory();
        var activeConfig=GenerationControl.rules().config();
        if(activeConfig.levelCapMode.equals("rct") && activeConfig.wildLevelBehavior.equals("clamp")
                && GenerationControl.rules().regionAllowed("kanto") && GenerationControl.rules().speciesAllowed("cobblemon:bulbasaur",1)) {
            var world=server.getOverworld();
            var low=new ServerPlayerEntity(server,world,new com.mojang.authlib.GameProfile(UUID.randomUUID(),"LowCapProbe"),SyncedClientOptions.createDefault());
            var high=new ServerPlayerEntity(server,world,new com.mojang.authlib.GameProfile(UUID.randomUUID(),"HighCapProbe"),SyncedClientOptions.createDefault());
            var type=com.gitlab.srcmc.rctmod.api.data.sync.PlayerState.class;
            var constructor=type.getDeclaredConstructor(net.minecraft.entity.player.PlayerEntity.class); constructor.setAccessible(true);
            var lowState=(com.gitlab.srcmc.rctmod.api.data.sync.PlayerState)constructor.newInstance(low);
            var highState=(com.gitlab.srcmc.rctmod.api.data.sync.PlayerState)constructor.newInstance(high);
            lowState.setCurrentSeries("kanto"); highState.setCurrentSeries("kanto");
            for(String id:List.of("kanto_brock","kanto_misty","kanto_ltsurge","kanto_erika")) highState.addProgressDefeat(id);
            var statesField=type.getDeclaredField("remoteStates"); statesField.setAccessible(true);
            @SuppressWarnings("unchecked") var states=(Map<UUID,Object>)statesField.get(null);
            try {
                states.put(low.getUuid(),lowState); states.put(high.getUuid(),highState);
                low.refreshPositionAndAngles(0,200,0,0,0); high.refreshPositionAndAngles(4,200,0,0,0);
                world.getPlayers().add(low); world.getPlayers().add(high);
                int lowCap=com.gitlab.srcmc.rctmod.api.utils.LevelUtils.levelCap(low);
                int highCap=com.gitlab.srcmc.rctmod.api.utils.LevelUtils.levelCap(high);
                check("wild cap: fixture has two distinct RCT progress limits",lowCap<highCap && highCap<100,passed,failed);
                var entity=PokemonProperties.Companion.parse("bulbasaur level=100").createEntity(world);
                entity.refreshPositionAndAngles(0,200,0,0,0);
                var spawner=(com.cobblemon.mod.common.api.spawning.spawner.Spawner)java.lang.reflect.Proxy.newProxyInstance(
                    PlayerRuntimeChecks.class.getClassLoader(),new Class<?>[]{com.cobblemon.mod.common.api.spawning.spawner.Spawner.class},(p,m,args) -> null);
                var cause=new com.cobblemon.mod.common.api.spawning.SpawnCause(spawner,null);
                var position=new com.cobblemon.mod.common.api.spawning.position.BasicSpawnablePosition(cause,world,new net.minecraft.util.math.BlockPos(0,200,0),15,15,true,List.of());
                var event=new com.cobblemon.mod.common.api.events.entity.SpawnEvent<>(entity,position);
                com.cobblemon.mod.common.api.events.CobblemonEvents.POKEMON_ENTITY_SPAWN.emit(event);
                check("wild cap: highest nearby player limit wins",!event.isCanceled() && entity.getPokemon().getLevel()==Math.max(lowCap,highCap),passed,failed);
                high.refreshPositionAndAngles(300,200,0,0,0);
                var another=PokemonProperties.Companion.parse("bulbasaur level=100").createEntity(world);
                another.refreshPositionAndAngles(0,200,0,0,0);
                var second=new com.cobblemon.mod.common.api.events.entity.SpawnEvent<>(another,position);
                com.cobblemon.mod.common.api.events.CobblemonEvents.POKEMON_ENTITY_SPAWN.emit(second);
                check("wild cap: player beyond 128 blocks is ignored",!second.isCanceled() && another.getPokemon().getLevel()==lowCap,passed,failed);
                states.remove(low.getUuid()); states.remove(high.getUuid());
                var loading=PokemonProperties.Companion.parse("bulbasaur level=100").createEntity(world);
                loading.refreshPositionAndAngles(0,200,0,0,0);
                var loadingEvent=new com.cobblemon.mod.common.api.events.entity.SpawnEvent<>(loading,position);
                com.cobblemon.mod.common.api.events.CobblemonEvents.POKEMON_ENTITY_SPAWN.emit(loadingEvent);
                check("wild cap: missing progress preserves spawn without crashing",!loadingEvent.isCanceled() && loading.getPokemon().getLevel()==100,passed,failed);
            } finally {
                world.getPlayers().remove(low); world.getPlayers().remove(high);
                states.remove(low.getUuid()); states.remove(high.getUuid());
            }
        }
        if(activeConfig.manageExperience && activeConfig.levelCapMode.equals("global")) {
            var probe=new ServerPlayerEntity(server,server.getOverworld(),new com.mojang.authlib.GameProfile(UUID.randomUUID(),"CapProbe"),SyncedClientOptions.createDefault());
            check("XP: RCT trainer cap follows global configuration",com.gitlab.srcmc.rctmod.api.utils.LevelUtils.levelCap(probe)==activeConfig.globalLevelCap,passed,failed);
            if(activeConfig.globalLevelCap>=10) {
                var manager=server.getPlayerManager();
                java.lang.reflect.Field playerMapField=null;
                for(var field:net.minecraft.server.PlayerManager.class.getDeclaredFields()) {
                    if(field.getGenericType() instanceof java.lang.reflect.ParameterizedType type && type.getRawType()==Map.class
                        && Arrays.equals(type.getActualTypeArguments(),new java.lang.reflect.Type[]{UUID.class,ServerPlayerEntity.class})) { playerMapField=field; break; }
                }
                if(playerMapField==null) failed.add("XP: mapa de jogadores de teste não localizado");
                else {
                    playerMapField.setAccessible(true);
                    @SuppressWarnings("unchecked") var online=(Map<UUID,ServerPlayerEntity>)playerMapField.get(manager);
                    var party=Cobblemon.INSTANCE.getStorage().getParty(probe);
                    var mon=PokemonSpecies.getByName("bulbasaur").create(5);
                    if(!party.add(mon)) throw new IllegalStateException("Não foi possível preparar Pokémon de teste de XP.");
                    try {
                        online.put(probe.getUuid(),probe);
                        var source=new com.cobblemon.mod.common.api.pokemon.experience.CommandExperienceSource(server.getCommandSource());
                        var event=new com.cobblemon.mod.common.api.events.pokemon.ExperienceGainedEvent.Pre(mon,source,10);
                        com.cobblemon.mod.common.api.events.CobblemonEvents.EXPERIENCE_GAINED_EVENT_PRE.emit(event);
                        int expected=br.com.cobbleverse.generations.rules.ExperienceRules.award(activeConfig,probe.getUuidAsString(),10,mon.getExperienceToLevel(activeConfig.globalLevelCap));
                        check("XP: real pre-gain event applies configured multiplier",!event.isCanceled() && event.getExperience()==expected,passed,failed);
                        var limitEvent=new com.cobblemon.mod.common.api.events.pokemon.ExperienceGainedEvent.Pre(mon,source,Integer.MAX_VALUE);
                        com.cobblemon.mod.common.api.events.CobblemonEvents.EXPERIENCE_GAINED_EVENT_PRE.emit(limitEvent);
                        check("XP: real pre-gain event clamps to remaining cap XP",limitEvent.getExperience()==mon.getExperienceToLevel(activeConfig.globalLevelCap),passed,failed);
                    } finally { online.remove(probe.getUuid()); party.remove(mon); }
                }
            }
            var connected=server.getPlayerManager().getPlayer("GenAdmin");
            if(connected!=null && activeConfig.globalLevelCap>=10) {
                var mon=PokemonSpecies.getByName("bulbasaur").create(5);
                var storage=Cobblemon.INSTANCE.getStorage();
                PokemonStore<?> store=storage.getParty(connected);
                if(!store.add(mon)) { store=storage.getPC(connected); if(!store.add(mon)) throw new IllegalStateException("Nenhum espaço para fixture de XP."); }
                try {
                    check("XP: fixture resolves its connected owner",mon.getOwnerPlayer()==connected,passed,failed);
                    int before=mon.getExperience();
                    int award=br.com.cobbleverse.generations.rules.ExperienceRules.award(activeConfig,connected.getUuidAsString(),10,mon.getExperienceToLevel(activeConfig.globalLevelCap));
                    var source=new com.cobblemon.mod.common.api.pokemon.experience.CommandExperienceSource(server.getCommandSource());
                    mon.addExperience(source,10);
                    check("XP: real Cobblemon event applies configured multiplier",mon.getExperience()-before==award,passed,failed);
                    mon.addExperience(source,Integer.MAX_VALUE);
                    check("XP: real Cobblemon event respects global cap",mon.getLevel()==activeConfig.globalLevelCap,passed,failed);
                } finally { store.remove(mon); }
            }
        }
        Path prior=directory.resolve("verification-player-fixtures.json");
        if(Files.exists(prior)) {
            var old=JsonParser.parseString(Files.readString(prior)).getAsJsonObject();
            UUID owner=UUID.fromString(old.get("owner").getAsString());
            Set<UUID> saved=new HashSet<>();
            for(var party:Cobblemon.INSTANCE.getStorage().getParties(owner,server.getRegistryManager())) for(var pokemon:party) saved.add(pokemon.getUuid());
            for(var pc:Cobblemon.INSTANCE.getStorage().getPCs(owner,server.getRegistryManager())) for(var pokemon:pc) saved.add(pokemon.getUuid());
            for(var id:old.getAsJsonArray("preservedPokemon")) check("persistence: captured fixture survived restart "+id.getAsString(),saved.contains(UUID.fromString(id.getAsString())),passed,failed);
            check("persistence: rescue UUID survived restart",saved.contains(UUID.fromString(old.get("rescueId").getAsString())),passed,failed);
        }
        var blocked=PokemonSpecies.getImplemented().stream().filter(s -> !GenerationControl.rules().speciesAllowed(s.getResourceIdentifier().toString(),s.getNationalPokedexNumber())).findFirst();
        if(blocked.isPresent() && GenerationControl.rules().restrictExisting() && GenerationControl.rules().config().rescueStarter && !RescueService.choices().isEmpty()) {
            var player=new ServerPlayerEntity(server,server.getOverworld(),new com.mojang.authlib.GameProfile(UUID.randomUUID(),"GenRescueTest"),SyncedClientOptions.createDefault());
            var storage=Cobblemon.INSTANCE.getStorage();
            var party=storage.getParty(player);
            var pc=storage.getPC(player);
            check("rescue: empty account is ineligible",!RescueService.eligible(player),passed,failed);
            Map<UUID,JsonObject> snapshots=new LinkedHashMap<>();
            for(int slot=0;slot<7;slot++) {
                var pokemon=blocked.get().create(20);
                pokemon.getPersistentData().putString("generation_control_test_sentinel","do-not-replace-"+slot);
                if(slot<6) party.add(pokemon); else pc.add(pokemon);
                snapshots.put(pokemon.getUuid(),pokemon.saveToJSON(server.getRegistryManager(),new JsonObject()));
            }
            check("rescue: all blocked accounts are eligible",RescueService.eligible(player),passed,failed);
            String result=RescueService.claim(player,RescueService.choices().getFirst());
            var receiptFile=directory.resolve("rescues").resolve(player.getUuid()+".json");
            var receipt=ConfigStore.GSON.fromJson(Files.readString(receiptFile),RescueService.Receipt.class);
            check("rescue: full party sends starter to PC",result.contains("PC") && pc.get(receipt.pokemonId)!=null && party.get(receipt.pokemonId)==null,passed,failed);
            for(var entry:snapshots.entrySet()) {
                var pokemon=party.get(entry.getKey()); if(pokemon==null) pokemon=pc.get(entry.getKey());
                check("rescue: captured data preserved "+entry.getKey(),pokemon!=null && entry.getValue().equals(pokemon.saveToJSON(server.getRegistryManager(),new JsonObject())),passed,failed);
            }
            boolean denied=false;
            try { RescueService.claim(player,RescueService.choices().getFirst()); } catch(IllegalStateException expected) { denied=true; }
            RescueService.recover(player);
            int[] count={0}; for(var pokemon:party) if(pokemon.getUuid().equals(receipt.pokemonId)) count[0]++;
            for(var pokemon:pc) if(pokemon.getUuid().equals(receipt.pokemonId)) count[0]++;
            check("rescue: repeated request and recovery cannot duplicate",denied && count[0]==1 && !RescueService.eligible(player),passed,failed);
            check("rescue: starter stays locked while asynchronous save is pending",RescueService.pending(pc.get(receipt.pokemonId)) && "prepared".equals(receipt.state),passed,failed);
            ConfigStore.write(prior,Map.of("owner",player.getUuid(),"preservedPokemon",snapshots.keySet(),"rescueId",receipt.pokemonId));
            detail.put("rescueFixtureOwner",player.getUuid().toString());

            // Simulate a crash after the receipt was reserved but before the Pokemon was saved.
            var recoveryPlayer=new ServerPlayerEntity(server,server.getOverworld(),new com.mojang.authlib.GameProfile(UUID.randomUUID(),"GenRecoveryTest"),SyncedClientOptions.createDefault());
            var recoveryParty=storage.getParty(recoveryPlayer);
            recoveryParty.add(blocked.get().create(20));
            var reserved=PokemonProperties.Companion.parse(RescueService.choices().getFirst()+" level=5").create(recoveryPlayer);
            reserved.setTradeable(false); reserved.getPersistentData().putBoolean("generation_control_rescue_pending",true);
            var reservation=new RescueService.Receipt(); reservation.pokemonId=reserved.getUuid(); reservation.species=reserved.getSpecies().getResourceIdentifier().toString();
            reservation.snapshot=reserved.saveToJSON(server.getRegistryManager(),new JsonObject());
            var reservationPath=directory.resolve("rescues").resolve(recoveryPlayer.getUuid()+".json");
            ConfigStore.write(reservationPath,reservation);
            RescueService.recover(recoveryPlayer); RescueService.recover(recoveryPlayer);
            int sameId=0; for(var pokemon:recoveryParty) if(pokemon.getUuid().equals(reserved.getUuid())) sameId++;
            check("rescue: interrupted delivery reserves the same UUID once",sameId==1 && RescueService.pending(recoveryParty.get(reserved.getUuid())),passed,failed);
        }
        // The RCT fixture temporarily updates the sync state of this explicitly named dev client,
        // but never replaces its live TrainerPlayerData. Restore its sync state and archive in finally.
        var admin=server.getPlayerManager().getPlayer("GenAdmin");
        if(GenerationControl.rules().regionAllowed("kanto") && !GenerationControl.rules().regionAllowed("johto")) {
            var manager=com.gitlab.srcmc.rctmod.api.RCTMod.getInstance().getTrainerManager();
            boolean synthetic=admin==null;
            Map<UUID,Object> syntheticStates=null;
            if(synthetic) {
                admin=new ServerPlayerEntity(server,server.getOverworld(),new com.mojang.authlib.GameProfile(UUID.randomUUID(),"GenProgressTest"),SyncedClientOptions.createDefault());
                var type=com.gitlab.srcmc.rctmod.api.data.sync.PlayerState.class;
                var constructor=type.getDeclaredConstructor(net.minecraft.entity.player.PlayerEntity.class); constructor.setAccessible(true);
                Object state=constructor.newInstance(admin);
                var statesField=type.getDeclaredField("remoteStates"); statesField.setAccessible(true);
                @SuppressWarnings("unchecked") var states=(Map<UUID,Object>)statesField.get(null);
                syntheticStates=states; syntheticStates.put(admin.getUuid(),state);
            }
            detail.put("rctMigrationFixture",synthetic ? "synthetic server player; no renderer/network client" : "connected GenAdmin sync state; live progress not replaced");
            var live=synthetic ? null : manager.getData(admin);
            Path ledger=directory.resolve("migrations/progress-"+admin.getUuid()+".json");
            byte[] before=Files.exists(ledger) ? Files.readAllBytes(ledger) : null;
            try {
                var tag=new NbtCompound(); tag.putString("currentSeries","johto"); tag.putString("previousSeries","kanto"); tag.putBoolean("currentSeriesCompleted",true);
                var defeats=new NbtCompound(); defeats.putByte("johto_valerio",(byte)1); tag.put("progressDefeats",defeats);
                var completions=new NbtCompound(); completions.putInt("johto",2); tag.put("completedSeries",completions);
                var fixture=new com.gitlab.srcmc.rctmod.api.data.save.TrainerPlayerData.Builder(admin).of(tag,server.getRegistryManager());
                check("RCT: deserialize against old circuit before migration",fixture.getCurrentSeries().equals("johto") && fixture.getDefeatedTrainerIds().contains("johto_valerio"),passed,failed);
                RctIntegration.migrate(admin,fixture,"kanto");
                var history=ConfigStore.GSON.fromJson(Files.readString(ledger),RctIntegration.ProgressLedger.class);
                check("RCT: old victories durably archived",history.defeatsBySeries.getOrDefault("johto",Set.of()).contains("johto_valerio"),passed,failed);
                check("RCT: new circuit completion recalculated without reward duplication",fixture.getCurrentSeries().equals("kanto") && !fixture.isSeriesCompleted() && fixture.getCompletedSeries().getOrDefault("johto",0)==2,passed,failed);
                fixture.addProgressDefeat("kanto_brock");
                var progress=Set.copyOf(fixture.getDefeatedTrainerIds());
                fixture.setCurrentSeries("johto",false);
                check("RCT: forbidden circuit request cannot reset current victories",fixture.getCurrentSeries().equals("kanto") && progress.contains("kanto_brock") && progress.equals(fixture.getDefeatedTrainerIds()),passed,failed);
            } finally {
                if(before==null) Files.deleteIfExists(ledger); else Files.write(ledger,before);
                if(synthetic) syntheticStates.remove(admin.getUuid());
                else { com.gitlab.srcmc.rctmod.api.data.sync.PlayerState.get(admin).removeProgressDefeats(); live.sync(); }
            }
        }
    }
    private static void check(String name,boolean condition,List<String> passed,List<String> failed) { (condition ? passed : failed).add(name); }
}
