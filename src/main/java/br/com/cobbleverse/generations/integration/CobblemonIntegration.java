package br.com.cobbleverse.generations.integration;

import br.com.cobbleverse.generations.GenerationControl;
import br.com.cobbleverse.generations.config.GenerationConfig;
import br.com.cobbleverse.generations.rules.GenerationRules;
import com.cobblemon.mod.common.api.events.CobblemonEvents;
import com.cobblemon.mod.common.api.pokemon.PokemonProperties;
import com.cobblemon.mod.common.api.pokemon.PokemonSpecies;
import com.cobblemon.mod.common.entity.pokemon.PokemonEntity;
import com.cobblemon.mod.common.pokemon.Pokemon;
import com.cobblemon.mod.common.pokemon.Species;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import java.util.*;

public final class CobblemonIntegration {
    private CobblemonIntegration() {}

    public static void register() {
        net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents.ENTITY_LOAD.register((entity, world) -> {
            if (entity instanceof PokemonEntity pokemon && pokemon.getPokemon().isWild() && pokemon.getPokemon().getOwnerUUID() == null
                    && pokemon.getTethering() == null && pokemon.getBattleId() == null && !allowed(pokemon.getPokemon())) pokemon.discard();
        });
        CobblemonEvents.POKEMON_ENTITY_SPAWN.subscribe(event -> {
            if (!allowed(event.getEntity().getPokemon())) event.cancel();
        });
        CobblemonEvents.POKEMON_SENT_PRE.subscribe(event -> {
            if (restrictExisting() && !allowed(event.getPokemon())) { event.cancel(); tellOwner(event.getPokemon()); }
        });
        CobblemonEvents.STARTER_CHOSEN.subscribe(event -> {
            if (!allowed(event.getPokemon())) { event.cancel(); event.getPlayer().sendMessage(blockMessage()); }
        });
        CobblemonEvents.EVOLUTION_ACCEPTED.subscribe(event -> {
            if (!allowedResult(event.getEvolution().getResult(), event.getPokemon())) { event.cancel(); tellOwner(event.getPokemon()); }
        });
        CobblemonEvents.TRADE_EVENT_PRE.subscribe(event -> {
            if (restrictExisting() && (!allowed(event.getTradeParticipant1Pokemon()) || !allowed(event.getTradeParticipant2Pokemon()))) event.cancel();
        });
        CobblemonEvents.COLLECT_EGG.subscribe(event -> {
            if (!allowed(event.getEgg())) { event.cancel(); event.getPlayer().sendMessage(blockMessage()); }
        });
        CobblemonEvents.HATCH_EGG_PRE.subscribe(event -> {
            if (!allowed(event.getEgg())) { event.cancel(); event.getPlayer().sendMessage(blockMessage()); }
        });
        CobblemonEvents.BATTLE_STARTED_PRE.subscribe(event -> {
            if (!GenerationControl.ready()) return;
            for (var actor : event.getBattle().getActors()) {
                for (var entry : actor.getPokemonList()) {
                    var pokemon=entry.getOriginalPokemon();
                    boolean exemptExistingPlayer = !restrictExisting() && pokemon.getOwnerPlayer()!=null;
                    boolean exemptTrainer = !GenerationControl.rules().filterTeams() && !pokemon.isWild() && pokemon.getOwnerPlayer()==null;
                    {
                        String item=net.minecraft.registry.Registries.ITEM.getId(pokemon.heldItem().getItem()).getPath().replace("_","");
                        String speciesName=pokemon.getSpecies().getResourceIdentifier().getPath();
                        PokemonProperties primal=new PokemonProperties(); primal.setForm("Primal");
                        if (((item.equals("blueorb") && speciesName.equals("kyogre")) || (item.equals("redorb") && speciesName.equals("groudon")))
                            && !allowedResult(primal,pokemon)) {
                            event.setReason(Text.literal("Remova o orbe de reversão primal para batalhar neste servidor."));
                            event.cancel();
                            return;
                        }
                    }
                    if (!exemptExistingPlayer && !exemptTrainer && !allowed(pokemon)) {
                        event.setReason(blockMessage());
                        event.cancel();
                        return;
                    }
                }
            }
        });
    }

    private static boolean restrictExisting() {
        return GenerationControl.ready() && GenerationControl.rules().restrictExisting();
    }

    public static boolean allowed(Pokemon pokemon) {
        if (!GenerationControl.ready()) return true;
        var species = pokemon.getSpecies();
        return species.getImplemented() && GenerationControl.rules().allowed(species.getResourceIdentifier().toString(), species.getNationalPokedexNumber(), pokemon.getForm().getName(), pokemon.getAspects());
    }

    public static boolean allowed(PokemonProperties properties) { return allowedResult(properties, null); }

    public static boolean allowedResult(PokemonProperties properties, Pokemon base) {
        if (!GenerationControl.ready()) return true;
        Species species = properties.getSpecies() == null ? (base == null ? null : base.getSpecies()) : species(properties.getSpecies());
        if (species == null || !species.getImplemented()) return false;
        PokemonProperties copy = properties.copy();
        Set<String> explicitAspects=new HashSet<>(properties.getAspects());
        copy.updateAspects();
        explicitAspects.addAll(copy.getAspects());
        String form = copy.getForm();
        if (form == null) form = species.getForm(explicitAspects).getName();
        else {
            String expected=br.com.cobbleverse.generations.rules.FormNames.canonical(form);
            var resolved=formsOf(species).stream().filter(f -> br.com.cobbleverse.generations.rules.FormNames.canonical(f.getName()).equals(expected)).findFirst();
            if(resolved.isEmpty()) return false;
            form=resolved.get().getName();
        }
        return GenerationControl.rules().allowed(species.getResourceIdentifier().toString(), species.getNationalPokedexNumber(), form, explicitAspects);
    }

    public static Species species(String key) {
        Identifier id = Identifier.tryParse(GenerationRules.speciesKey(key));
        return id == null ? null : PokemonSpecies.getByIdentifier(id);
    }

    public static net.minecraft.text.MutableText blockMessage() { return Text.literal("Este Pokémon ou forma está bloqueado pelas gerações do servidor."); }
    public static void tellOwner(Pokemon pokemon) {
        if (pokemon.getOwnerPlayer() != null) pokemon.getOwnerPlayer().sendMessage(blockMessage());
    }

    private static java.util.List<com.cobblemon.mod.common.pokemon.FormData> formsOf(Species species) {
        return java.util.stream.Stream.concat(java.util.stream.Stream.of(species.getStandardForm()),species.getForms().stream()).toList();
    }

    public static ValidationReport validateRegistries(GenerationConfig candidate) {
        var report=new ValidationReport();
        GenerationRules rules;
        try { rules=new GenerationRules(candidate); } catch(RuntimeException e) { report.errors.add(e.getMessage()); return report; }
        inspectRegistryRules(rules,report);
        return report;
    }

    private static void inspectRegistryRules(GenerationRules rules,ValidationReport report) {
        if(rules.config().captureMultiplier!=1.0 && !(com.cobblemon.mod.common.Cobblemon.INSTANCE.getConfig().getCaptureCalculator()
                instanceof com.cobblemon.mod.common.pokeball.catching.calculators.CobblemonCaptureCalculator))
            report.errors.add("O multiplicador de captura requer o calculador padrão do Cobblemon.");
        for (Species species : PokemonSpecies.getSpecies())
            if (species.getImplemented() && formsOf(species).stream().anyMatch(form -> rules.allowed(species.getResourceIdentifier().toString(),species.getNationalPokedexNumber(),form.getName(),new HashSet<>(form.getAspects())))) report.allowedSpecies++;
        if (report.allowedSpecies == 0) report.errors.add("Nenhuma espécie ou forma implementada disponível com esta seleção.");
        for (String exception : rules.config().allowSpecies) {
            var entry=species(exception);
            if (entry==null || !entry.getImplemented()) report.errors.add("Espécie desconhecida ou não implementada: " + exception);
        }
        for (String exception : rules.config().denySpecies) if (species(exception) == null) report.warnings.add("Bloqueio de espécie não carregada: " + exception);
        Set<String> forms=new TreeSet<>(rules.config().allowForms); forms.addAll(rules.config().denyForms);
        for(String exception:forms) {
            int split=exception.indexOf('/'); var entry=species(exception.substring(0,split)); String form=exception.substring(split+1);
            if(entry==null || formsOf(entry).stream().noneMatch(f -> br.com.cobbleverse.generations.rules.FormNames.canonical(f.getName()).equals(form))) report.errors.add("Forma desconhecida: "+exception);
        }
    }

    public static ValidationReport validate(GenerationConfig candidate) { return validate(candidate,true,true); }
    public static ValidationReport validateBase(GenerationConfig candidate) { return validate(candidate,false,false); }
    public static boolean needsPlayerImpact(GenerationConfig candidate) {
        if(!GenerationControl.ready()) return true;
        var active=GenerationControl.rules().config();
        var rules=new GenerationRules(candidate);
        return !new GenerationRules(active).sameSpeciesPolicy(rules) || active.restrictExistingPokemon!=rules.config().restrictExistingPokemon;
    }
    public static boolean needsTeamPreview(GenerationConfig candidate) {
        if(!GenerationControl.ready()) return true;
        var active=GenerationControl.rules().config(); var rules=new GenerationRules(candidate); var effective=rules.config();
        return !new GenerationRules(active).sameSpeciesPolicy(rules) || active.filterTrainerTeams!=effective.filterTrainerTeams
            || active.filterGyms!=effective.filterGyms || !active.regions.equals(effective.regions) || !active.initialRegion.equals(effective.initialRegion);
    }
    private static ValidationReport validate(GenerationConfig candidate,boolean scanPlayers,boolean scanTeams) {
        ValidationReport report = new ValidationReport();
        GenerationRules rules;
        try { rules = new GenerationRules(candidate); }
        catch (RuntimeException e) { report.errors.add(e.getMessage()); return report; }
        inspectRegistryRules(rules,report);
        report.warnings.add("Alterações serão aplicadas após reinício completo do servidor.");
        report.warnings.add("Ginásios em áreas já geradas permanecem no terreno.");
        if(GenerationControl.ready() && GenerationControl.store().startupWarning()!=null) report.warnings.add("Última rejeição de inicialização: "+GenerationControl.store().startupWarning());
        Compatibility.inspect(report);
        var server=GenerationControl.server();
        if(server!=null && rules.filterGyms()) for(String region:rules.config().regions) {
            if(!region.equals("kanto") && server.getDataPackManager().getIds().stream().noneMatch(name -> name.toLowerCase(Locale.ROOT).contains(region)))
                report.errors.add("Datapack de "+region+" não encontrado no servidor.");
        }
        GenerationConfig active=GenerationControl.ready() ? GenerationControl.rules().config() : null;
        boolean sameSpeciesPolicy=active!=null && new GenerationRules(active).sameSpeciesPolicy(rules);
        GenerationConfig effective=rules.config();
        if(!needsTeamPreview(candidate))
            report.warnings.add("Equipes já validadas para as regras de espécies ativas; a configuração de XP não exige nova adaptação.");
        else if(scanTeams) br.com.cobbleverse.generations.data.PolicyResources.previewTeams(rules,report);
        if(sameSpeciesPolicy && active.restrictExistingPokemon==effective.restrictExistingPokemon)
            report.warnings.add("Sem nova varredura de jogadores: as restrições aos Pokémon capturados não foram alteradas.");
        else if(scanPlayers) PlayerImpact.inspect(GenerationControl.server(),rules,report);
        if(!rules.filterGyms()) report.warnings.add("Filtro de ginásios desligado: outros circuitos continuam disponíveis.");
        if(!rules.restrictExisting()) report.warnings.add("Pokémon capturados fora da seleção continuam utilizáveis. Novos encontros continuam filtrados.");
        if(rules.regionAllowed("hoenn") || rules.regionAllowed("sinnoh")) report.warnings.add("Hoenn/Sinnoh habilitam Terralith. O terreno novo pode diferir das áreas já geradas; não há regeneração de chunks.");
        report.warnings.add("Dynamax, Gigantamax, terastalização e golpes Z seguem as regras do pack. A opção de megas controla megavoluções e formas primais.");
        return report;
    }

}
