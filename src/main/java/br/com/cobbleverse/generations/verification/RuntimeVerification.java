package br.com.cobbleverse.generations.verification;

import br.com.cobbleverse.generations.GenerationControl;
import br.com.cobbleverse.generations.config.ConfigStore;
import br.com.cobbleverse.generations.integration.CobblemonIntegration;
import com.cobblemon.mod.common.api.events.CobblemonEvents;
import com.cobblemon.mod.common.api.events.pokemon.PokemonSentEvent;
import com.cobblemon.mod.common.api.events.pokemon.evolution.EvolutionAcceptedEvent;
import com.cobblemon.mod.common.api.pokemon.PokemonProperties;
import com.cobblemon.mod.common.api.pokemon.PokemonSpecies;
import com.gitlab.srcmc.rctmod.api.RCTMod;
import com.google.gson.*;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.Vec3d;
import java.time.Instant;
import java.util.*;

/** Explicit, developer-only integration checks against a running dedicated server.
 * Enable with -Dgeneration_control.integrationTests=true in an isolated test world. */
public final class RuntimeVerification {
    private RuntimeVerification() {}

    public static Map<String,Object> run(MinecraftServer server) throws Exception {
        List<String> passed = new ArrayList<>();
        List<String> failed = new ArrayList<>();
        Map<String,Object> detail = new LinkedHashMap<>();
        var rules = GenerationControl.rules();
        boolean completed=false;
        try {
        var world = server.getOverworld();
        // The fixture inserts entities directly; ensure its chunk is available even when
        // startup has finished but the spawn area has not yet reached entity-ticking state.
        world.getChunk(0,0);
        var settings=rules.config();
        if(settings.levelCapMode.equals("global") && settings.globalLevelCap<100 && rules.speciesAllowed("cobblemon:bulbasaur",1)) {
            var entity=PokemonProperties.Companion.parse("bulbasaur level=100").createEntity(world);
            var mon=entity.getPokemon();
            var spawner=(com.cobblemon.mod.common.api.spawning.spawner.Spawner)java.lang.reflect.Proxy.newProxyInstance(
                RuntimeVerification.class.getClassLoader(),new Class<?>[]{com.cobblemon.mod.common.api.spawning.spawner.Spawner.class},(p,m,args) -> null);
            var cause=new com.cobblemon.mod.common.api.spawning.SpawnCause(spawner,null);
            var position=new com.cobblemon.mod.common.api.spawning.position.BasicSpawnablePosition(cause,world,new net.minecraft.util.math.BlockPos(0,200,0),15,15,true,List.of());
            var event=new com.cobblemon.mod.common.api.events.entity.SpawnEvent<>(entity,position);
            CobblemonEvents.POKEMON_ENTITY_SPAWN.emit(event);
            if(settings.wildLevelBehavior.equals("clamp"))
                check("spawn: real Cobblemon event clamps wild level",!event.isCanceled() && mon.getLevel()==settings.globalLevelCap,passed,failed);
            else if(settings.wildLevelBehavior.equals("block"))
                check("spawn: real Cobblemon event cancels over-cap encounter",event.isCanceled(),passed,failed);
            else check("spawn: unchanged mode preserves original level",!event.isCanceled() && mon.getLevel()==100,passed,failed);
        }
        for (String raw : List.of("bulbasaur", "pichu", "treecko", "raichu alolan", "eevee")) {
            var properties = PokemonProperties.Companion.parse(raw);
            var pokemon = properties.create();
            if (raw.contains("alolan")) check("fixture: Alolan Raichu form resolved", pokemon.getForm().getName().equalsIgnoreCase("Alola"),passed,failed);
            boolean expected = rules.allowed(pokemon.getSpecies().getResourceIdentifier().toString(),pokemon.getSpecies().getNationalPokedexNumber(),pokemon.getForm().getName(),pokemon.getAspects());
            var sent = new PokemonSentEvent.Pre(pokemon,world,Vec3d.ZERO);
            CobblemonEvents.POKEMON_SENT_PRE.emit(sent);
            check("send-out: "+raw, sent.isCanceled() == (!expected && rules.restrictExisting()), passed, failed);
        }
        detail.put("worldInsertionCheck", "Requires separate multi-tick WorldInsertionVerification or WorldReloadVerification fixture.");
        var eevee = PokemonSpecies.getByName("eevee").create(20);
        for (var evolution : eevee.getForm().getEvolutions()) {
            if (!"espeon".equals(evolution.getResult().getSpecies())) continue;
            var accepted = new EvolutionAcceptedEvent(eevee,evolution);
            CobblemonEvents.EVOLUTION_ACCEPTED.emit(accepted);
            check("evolution: eevee -> espeon", accepted.isCanceled() != CobblemonIntegration.allowedResult(evolution.getResult(),eevee), passed,failed);
        }
        int spawnFiles = 0;
        int spawnEntries = 0;
        for (var resource : server.getResourceManager().findResources("spawn_pool_world",id -> id.getPath().endsWith(".json")).entrySet()) {
            try (var reader = resource.getValue().getReader()) {
                var root = JsonParser.parseReader(reader).getAsJsonObject();
                if (!root.has("spawns")) continue;
                spawnFiles++;
                for (var element : root.getAsJsonArray("spawns")) {
                    var spawn = element.getAsJsonObject();
                    if (!spawn.has("pokemon")) continue;
                    String raw = spawn.get("pokemon").getAsString();
                    spawnEntries++;
                    if (!CobblemonIntegration.allowed(PokemonProperties.Companion.parse(raw))) failed.add("Spawn proibido carregado: "+resource.getKey()+" -> "+raw);
                }
            }
        }
        detail.put("spawnFilesInspected",spawnFiles);
        detail.put("spawnEntriesInspected",spawnEntries);
        int[] teams = {0};
        RCTMod.getInstance().getTrainerManager().getAllData().forEach(entry -> {
            teams[0]++;
            if (!rules.filterTeams()) return;
            for (var model : entry.getValue().getTrainerTeam().getTeam()) {
                var species = CobblemonIntegration.species(model.getSpecies());
                if (species == null || !rules.speciesAllowed(species.getResourceIdentifier().toString(),species.getNationalPokedexNumber())) failed.add("Espécie proibida no treinador "+entry.getKey()+": "+model.getSpecies());
                var properties=PokemonProperties.Companion.parse(model.getSpecies()+" "+String.join(" ",model.getAspects()));
                Set<String> aspects=new HashSet<>(properties.getAspects()); aspects.addAll(model.getAspects()); properties.setAspects(aspects);
                if(!CobblemonIntegration.allowed(properties)) failed.add("Forma/aspecto proibido no treinador "+entry.getKey()+": "+model.getSpecies()+" "+model.getAspects());
                if(species!=null) {
                    var form=species.getForm(aspects);
                    var legal=form.getMoves().getAllLegalMoves().stream().map(com.cobblemon.mod.common.api.moves.MoveTemplate::getName).collect(java.util.stream.Collectors.toSet());
                    for(String move:model.getMoveset()) if(!legal.contains(move)) failed.add("Golpe ilegal: "+entry.getKey()+" "+model.getSpecies()+" -> "+move);
                    Set<String> abilities=new HashSet<>(); for(var potential:form.getAbilities()) abilities.add(potential.getTemplate().getName());
                    if(model.getAbility()!=null && !model.getAbility().isBlank() && !model.getAbility().equals("random") && !abilities.contains(model.getAbility())) failed.add("Habilidade ilegal: "+entry.getKey()+" "+model.getSpecies()+" -> "+model.getAbility());
                }
            }
        });
        detail.put("trainerTeamsInspected",teams[0]);
        var structures = server.getRegistryManager().get(RegistryKeys.STRUCTURE);
        for (String gym : List.of("brock","misty","ltsurge","erika","koga","sabrina","blaine","giovanni","kanto_league")) {
            var structure = structures.get(Identifier.of("cobbleverse",gym));
            if (structure != null && rules.filterGyms()) check("worldgen: "+gym, (structure.getValidBiomes().size() > 0) == rules.regionAllowed("kanto"),passed,failed);
        }
        if (net.fabricmc.loader.api.FabricLoader.getInstance().isModLoaded("cobbreeding")) {
            var babies = br.com.cobbleverse.generations.integration.BreedingGuard.allowedBabies();
            check("breeding: allowed random babies available", !babies.isEmpty(),passed,failed);
            boolean randomAllowed = true;
            for (int i=0; i<100 && !babies.isEmpty(); i++) {
                var baby = br.com.cobbleverse.generations.integration.BreedingGuard.randomBaby();
                if (!rules.allowed(baby.getSpecies().getResourceIdentifier().toString(),baby.getSpecies().getNationalPokedexNumber(),baby.getName(),Set.of())) randomAllowed = false;
            }
            check("breeding: 100 random outcomes obey policy",randomAllowed,passed,failed);
            var blocked = PokemonSpecies.getImplemented().stream().filter(species -> !rules.speciesAllowed(species.getResourceIdentifier().toString(),species.getNationalPokedexNumber())).findFirst();
            if (blocked.isPresent()) {
                var properties = new PokemonProperties();
                properties.setSpecies(blocked.get().getResourceIdentifier().getPath());
                Class<?> utilities = Class.forName("ludichat.cobbreeding.EggUtilities");
                var egg = (net.minecraft.item.ItemStack)utilities.getMethod("getEggFromPokemonProperties",PokemonProperties.class,Integer.class).invoke(null,properties,0);
                Class<?> eggClass = Class.forName("ludichat.cobbreeding.PokemonEgg");
                Object companion = eggClass.getField("Companion").get(null);
                @SuppressWarnings("unchecked") var timer = (net.minecraft.component.ComponentType<Integer>)companion.getClass().getMethod("getTIMER").invoke(companion);
                @SuppressWarnings("unchecked") var second = (net.minecraft.component.ComponentType<Integer>)companion.getClass().getMethod("getSECOND").invoke(companion);
                egg.set(timer,0);
                egg.set(second,20);
                var before = egg.copy();
                var testPlayer = new net.minecraft.server.network.ServerPlayerEntity(server,world,
                    new com.mojang.authlib.GameProfile(java.util.UUID.randomUUID(),"GenEggTest"),net.minecraft.network.packet.c2s.common.SyncedClientOptions.createDefault());
                egg.getItem().inventoryTick(egg,world,testPlayer,0,false);
                check("breeding: blocked existing egg preserved before hatch",egg.getCount() == before.getCount()
                    && net.minecraft.item.ItemStack.areItemsAndComponentsEqual(egg,before),passed,failed);
            }
        }
        if (net.fabricmc.loader.api.FabricLoader.getInstance().isModLoaded("mega_showdown") && !rules.allowMegas()) {
            var response=new com.cobblemon.mod.common.battles.MoveActionResponse("tackle",null,"mega");
            var actor=new com.cobblemon.mod.common.battles.actor.PlayerBattleActor(UUID.randomUUID(),List.of());
            new com.cobblemon.mod.common.api.battles.model.PokemonBattle(com.cobblemon.mod.common.battles.BattleFormat.Companion.getGEN_9_SINGLES(),
                new com.cobblemon.mod.common.battles.BattleSide(actor),new com.cobblemon.mod.common.battles.BattleSide());
            var active=new com.cobblemon.mod.common.battles.ActiveBattlePokemon(actor,null);
            response.isValid(active,null,false);
            check("battle: mega choice sanitized by real validation method",response.getGimmickID()==null,passed,failed);
            var charizard=PokemonProperties.Companion.parse("charizard level=50").create();
            com.github.yajatkaul.mega_showdown.gimmick.MegaGimmick.megaEvolve(charizard);
            check("mega: direct transformation leaves no active flag",!charizard.getPersistentData().getBoolean("is_mega"),passed,failed);
            check("mega: UI availability denied",!com.github.yajatkaul.mega_showdown.gimmick.MegaGimmick.canMega(charizard),passed,failed);
            com.github.yajatkaul.mega_showdown.utils.AspectUtils.applyAspects(charizard,List.of("mega_evolution=megax"));
            check("mega: aspect transformation blocked",!charizard.getForm().getName().toLowerCase(java.util.Locale.ROOT).contains("mega"),passed,failed);
        }
        int fossilsChecked=0;
        for(var fossil:com.cobblemon.mod.common.api.fossil.Fossils.all()) {
            if(CobblemonIntegration.allowed(fossil.getResult())) continue;
            var ingredients=new ArrayList<net.minecraft.item.ItemStack>();
            for(var ingredient:fossil.getFossils()) {
                net.minecraft.registry.Registries.ITEM.stream().map(net.minecraft.item.ItemStack::new)
                    .filter(ingredient::test).findFirst().ifPresent(ingredients::add);
            }
            if(ingredients.isEmpty()) continue;
            var actual=com.cobblemon.mod.common.api.fossil.Fossils.getFossilByItemStacks(ingredients);
            if(actual==null || CobblemonIntegration.allowed(actual.getResult())) continue;
            var machine=new com.cobblemon.mod.common.block.multiblock.FossilMultiblockStructure(
                new net.minecraft.util.math.BlockPos(0,200,0),new net.minecraft.util.math.BlockPos(1,200,0),new net.minecraft.util.math.BlockPos(2,200,0),0,0f);
            machine.setTankConnectorDirection(net.minecraft.util.math.Direction.NORTH);
            machine.setFossilInventory(new ArrayList<>(ingredients.subList(0,ingredients.size()-1)));
            var last=ingredients.getLast();
            var before=last.copy();
            boolean inserted=machine.insertFossil(last,world);
            check("fossil: forbidden recipe preserved "+fossil.getIdentifier(),!inserted && last.getCount()==before.getCount()
                && machine.getFossilInventory().size()==ingredients.size()-1,passed,failed);
            fossilsChecked++;
            machine.setFossilInventory(new ArrayList<>(ingredients));
            var fossilField=machine.getClass().getDeclaredField("resultingFossil"); fossilField.setAccessible(true); fossilField.set(machine,actual);
            var timeField=machine.getClass().getDeclaredField("timeRemaining"); timeField.setAccessible(true); timeField.setInt(machine,1);
            var organicField=machine.getClass().getDeclaredField("organicMaterialInside"); organicField.setAccessible(true); organicField.setInt(machine,128);
            var frozen=machine.writeToNbt(server.getRegistryManager()).copy();
            machine.tick(world);
            check("fossil: existing forbidden machine pauses without changing NBT "+fossil.getIdentifier(),frozen.equals(machine.writeToNbt(server.getRegistryManager())),passed,failed);
        }
        var candidateReports=new LinkedHashMap<String,Object>();
        for(int generation=1;generation<=9;generation++) {
            var candidate=rules.config(); candidate.mode="single"; candidate.selectedGeneration=generation;
            var report=new br.com.cobbleverse.generations.integration.ValidationReport();
            br.com.cobbleverse.generations.data.PolicyResources.previewTeams(new br.com.cobbleverse.generations.rules.GenerationRules(candidate),report);
            candidateReports.put("single-"+generation,Map.of("teams",report.trainerTeams,"adapted",report.adaptedTeams,"errors",report.errors));
            check("preview: generation "+generation+" has legal team substitutions",report.errors.isEmpty() && report.trainerTeams>0,passed,failed);
        }
        detail.put("candidateTeamPreviews",candidateReports);
        detail.put("ruleCheckBenchmark", "Skipped: the synchronous microbenchmark is not representative of server performance.");
        detail.put("forbiddenFossilRecipesInspected",fossilsChecked);
        if (net.fabricmc.loader.api.FabricLoader.getInstance().isModLoaded("cobblemonraiddens")) {
            var raidIds=com.necro.raid.dens.common.registry.RaidRegistry.RAID_LIST;
            int legal=0;
            int forbidden=0;
            Integer legalIndex=null;
            Integer forbiddenIndex=null;
            for(int i=0;i<raidIds.size();i++) {
                var boss=com.necro.raid.dens.common.registry.RaidRegistry.getRaidBoss(raidIds.get(i));
                boolean allowed=br.com.cobbleverse.generations.integration.RaidGuard.allowed(boss);
                if (allowed) { legal++; if(legalIndex==null && boss.getWeight()>0) legalIndex=i; }
                else { forbidden++; if(forbiddenIndex==null) forbiddenIndex=i; if(boss.getWeight()!=0.0) failed.add("Raid proibida com peso positivo: "+raidIds.get(i)); }
            }
            detail.put("raidBossesInspected",raidIds.size());
            detail.put("allowedRaidBosses",legal);
            detail.put("forbiddenRaidBosses",forbidden);
            check("raids: policy retains at least one legal encounter",legal>0,passed,failed);
            var random=net.minecraft.util.math.random.Random.create(123);
            if(forbiddenIndex!=null) check("raids: explicit forbidden ID excluded from weighted roll",com.necro.raid.dens.common.registry.RaidRegistry.roll(random,new float[]{1},new int[]{forbiddenIndex})==null,passed,failed);
            if(legalIndex!=null) {
                check("raids: legal boss can be selected",com.necro.raid.dens.common.registry.RaidRegistry.roll(random,new float[]{1},new int[]{legalIndex}).equals(raidIds.get(legalIndex)),passed,failed);
                check("raids: zero total weight produces no encounter",com.necro.raid.dens.common.registry.RaidRegistry.roll(random,new float[]{0},new int[]{legalIndex})==null,passed,failed);
            }
        }
        PlayerRuntimeChecks.run(server,passed,failed,detail);
        completed=true;
        } catch(Exception error) {
            failed.add("Verificação interrompida: "+error.getClass().getSimpleName()+": "+error.getMessage());
            GenerationControl.LOGGER.error("Falha durante os testes nativos; relatório parcial preservado.",error);
        }
        detail.put("completed",completed);
        detail.put("timestamp",Instant.now().toString());
        detail.put("revision",rules.config().revision);
        detail.put("generations",rules.config().generations);
        detail.put("passed",passed);
        detail.put("failed",failed);
        ConfigStore.write(GenerationControl.store().directory().resolve("runtime-verification-"+rules.config().revision+".json"),detail);
        GenerationControl.LOGGER.info("Runtime verification: {} checks passed, {} failures; completed={}",passed.size(),failed.size(),completed);
        return detail;
    }

    private static void check(String name, boolean condition, List<String> passed,List<String> failed) { (condition ? passed : failed).add(name); }
}
