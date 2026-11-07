package br.com.cobbleverse.generations.data;

import br.com.cobbleverse.generations.rules.GenerationRules;
import java.util.*;

/** Reviewed single-generation roster. Levels, team size and progression remain those of the pack.
 * Deliberate repeats of a type preserve each leader's specialty; no legendary substitutes. */
public final class KantoTeams {
    private static final Map<String,List<String>> ROSTERS=Map.ofEntries(
        Map.entry("kanto_brock",List.of("geodude","omanyte","onix","rhyhorn")),
        Map.entry("kanto_misty",List.of("horsea","starmie","psyduck","goldeen")),
        Map.entry("kanto_ltsurge",List.of("raichu","magneton","electabuzz","electrode")),
        Map.entry("kanto_erika",List.of("tangela","exeggutor","victreebel","vileplume")),
        Map.entry("kanto_koga",List.of("golbat","weezing","venomoth","muk","arbok")),
        Map.entry("kanto_sabrina",List.of("slowbro","mrmime","alakazam","hypno")),
        Map.entry("kanto_blaine",List.of("ninetales","arcanine","magmar","rapidash","flareon","charizard")),
        Map.entry("kanto_giovanni",List.of("rhydon","dugtrio","persian","nidoking","nidoqueen","kangaskhan")),
        Map.entry("kanto_league_lorelei",List.of("dewgong","cloyster","jynx","slowbro","lapras","seadra")),
        Map.entry("kanto_league_bruno",List.of("poliwrath","machamp","primeape","hitmonchan","hitmonlee","onix")),
        Map.entry("kanto_league_agatha",List.of("haunter","arbok","golbat","weezing","muk","gengar")),
        Map.entry("kanto_league_lance",List.of("aerodactyl","gyarados","dragonair","seadra","dragonite","charizard")),
        Map.entry("kanto_champion_blue",List.of("pidgeot","alakazam","rhydon","exeggutor","gyarados","charizard"))
    );
    private KantoTeams() {}
    public static SpeciesCatalog.Entry preferred(String id,int slot,SpeciesCatalog catalog,GenerationRules rules) {
        if(!rules.onlyGeneration(1)) return null;
        String name=id.substring(id.lastIndexOf('/')+1).replace(".json","");
        var roster=ROSTERS.get(name);
        if(roster==null || slot>=roster.size()) return null;
        var entry=catalog.get(roster.get(slot));
        return entry!=null && rules.allowed(entry.id(),entry.dex(),"normal",Set.of()) ? entry : null;
    }
}
