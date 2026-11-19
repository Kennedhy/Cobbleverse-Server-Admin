package br.com.cobbleverse.generations.data;

import br.com.cobbleverse.generations.config.GenerationConfig;
import br.com.cobbleverse.generations.rules.GenerationRules;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SpawnResourcePolicyTest {
    @Test
    void filtersNestedAlphaHerdMembersWithoutDiscardingAllowedAlphaLeaders() {
        SpeciesCatalog catalog = catalog();
        GenerationRules rules = new GenerationRules(new GenerationConfig());
        JsonObject pool = JsonParser.parseString("""
                {
                  "spawns": [
                    {"type":"pokemon","pokemon":"charmander"},
                    {"type":"pokemon","pokemon":"treecko"},
                    {"type":"pokemon-herd","herdablePokemon":[
                      {"pokemon":"charizard held_item=cobblemon:fire_gem alpha=true","isLeader":true,"isFollower":false},
                      {"pokemon":"charmander","isLeader":false,"isFollower":true},
                      {"pokemon":"treecko","isLeader":false,"isFollower":true}
                    ]},
                    {"type":"pokemon-herd","herdablePokemon":[
                      {"pokemon":"treecko","isLeader":true,"isFollower":false},
                      {"pokemon":"charmander","isLeader":false,"isFollower":true}
                    ]}
                  ]
                }
                """).getAsJsonObject();

        assertTrue(SpawnResourcePolicy.filterWorldSpawnPool(pool, properties -> catalog.allowed(rules, properties)));
        List<String> remaining = SpawnResourcePolicy.worldSpawnProperties(pool);
        assertEquals(List.of(
                "charmander",
                "charizard held_item=cobblemon:fire_gem alpha=true",
                "charmander"
        ), remaining);
        assertTrue(remaining.get(1).contains("alpha=true"), "Alpha aspect must remain permitted for an allowed species");
        assertFalse(remaining.stream().anyMatch(properties -> properties.startsWith("treecko")));
        assertFalse(SpawnResourcePolicy.filterWorldSpawnPool(pool, properties -> catalog.allowed(rules, properties)),
                "A second pass over an already filtered pool should be a no-op");
    }

    @Test
    void filtersHabitatPoolsBySpeciesRegionalFormAndModifiers() {
        SpeciesCatalog catalog = catalog();
        GenerationRules rules = new GenerationRules(new GenerationConfig());
        JsonObject pool = JsonParser.parseString("""
                {
                  "type":"cobblemon:natural",
                  "spawns":[
                    {"species":"Bulbasaur","weight":6.0},
                    {"species":"Treecko","weight":6.0},
                    {"species":"Rattata","modifiers":"alolan","weight":6.0},
                    {"species":"Rattata","weight":6.0},
                    {"species":"Pikachu","modifiers":"alpha=true","weight":6.0}
                  ]
                }
                """).getAsJsonObject();

        assertTrue(SpawnResourcePolicy.filterHabitatPool(pool, properties -> catalog.allowed(rules, properties)));
        List<String> remaining = SpawnResourcePolicy.habitatSpawnProperties(pool);
        assertEquals(List.of("Bulbasaur", "Rattata", "Pikachu alpha=true"), remaining);
        assertFalse(SpawnResourcePolicy.filterHabitatPool(pool, properties -> catalog.allowed(rules, properties)),
                "A second pass over an already filtered pool should be a no-op");
    }

    private static SpeciesCatalog catalog() {
        SpeciesCatalog catalog = new SpeciesCatalog();
        catalog.add("cobblemon", species("Charmander", 4));
        catalog.add("cobblemon", species("Charizard", 6));
        catalog.add("cobblemon", species("Treecko", 252));
        catalog.add("cobblemon", species("Bulbasaur", 1));
        catalog.add("cobblemon", species("Pikachu", 25));
        JsonObject rattata = species("Rattata", 19);
        rattata.add("forms", JsonParser.parseString("""
                [{"name":"Alola","aspects":["alolan"]}]
                """).getAsJsonArray());
        catalog.add("cobblemon", rattata);
        return catalog;
    }

    private static JsonObject species(String name, int dex) {
        return JsonParser.parseString("""
                {"name":"%s","nationalPokedexNumber":%d,"abilities":["static"],"moves":["1:tackle"]}
                """.formatted(name, dex)).getAsJsonObject();
    }
}
