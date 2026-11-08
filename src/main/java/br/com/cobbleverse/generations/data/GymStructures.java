package br.com.cobbleverse.generations.data;

import java.util.Map;
import java.util.Set;

/** Gym and league structure IDs from the four COBBLEVERSE regional datapacks. */
public final class GymStructures {
    private static final Map<String, Set<String>> STRUCTURES = Map.of(
        "kanto", Set.of("blaine", "brock", "erika", "giovanni", "koga", "kanto_league", "ltsurge", "misty", "sabrina"),
        "johto", Set.of("alfredo", "angelo", "chiara", "furio", "jasmine", "johto_league", "raffaello", "sandra", "valerio"),
        "hoenn", Set.of("adriano", "alice", "fiammetta", "hoenn_league", "norman", "petra", "rudi", "tell_pat", "walter"),
        "sinnoh", Set.of("bianca", "corrado", "fannie", "ferruccio", "gardenia", "sinnoh_league", "marzia", "omar", "pedro")
    );
    private GymStructures() {}

    public static Set<String> regions() { return STRUCTURES.keySet(); }
    public static int count(String region) { return STRUCTURES.getOrDefault(region, Set.of()).size(); }

    public static String region(String path) {
        String prefix="worldgen/structure/";
        if (!path.startsWith(prefix) || !path.endsWith(".json")) return null;
        String name=path.substring(prefix.length(),path.length()-5);
        for (var region:STRUCTURES.entrySet()) if(region.getValue().contains(name)) return region.getKey();
        return null;
    }
}
