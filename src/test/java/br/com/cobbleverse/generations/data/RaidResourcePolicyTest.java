package br.com.cobbleverse.generations.data;

import br.com.cobbleverse.generations.config.GenerationConfig;
import br.com.cobbleverse.generations.rules.GenerationRules;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class RaidResourcePolicyTest {
    @Test
    void blocksForbiddenGenerationAndRegionalFormAndPreservesAllowedBosses() {
        SpeciesCatalog catalog = new SpeciesCatalog();
        catalog.add("cobblemon", species("Charizard", 6));
        catalog.add("cobblemon", species("Treecko", 252));
        JsonObject geodude = species("Geodude", 74);
        geodude.add("forms", JsonParser.parseString("""
                [{"name":"Alola","aspects":["alolan"]}]
                """).getAsJsonArray());
        catalog.add("cobblemon", geodude);

        GenerationConfig config = new GenerationConfig();
        config.mode = "single";
        config.selectedGeneration = 1;
        config.generations = java.util.Set.of(1);
        config.regionalForms = false;
        GenerationRules rules = new GenerationRules(config);

        JsonObject forbiddenGeneration = boss("""
                {"species":"treecko"}
                """);
        JsonObject forbiddenRegionalForm = boss("""
                {"species":"geodude","custom_properties":[{"name":"alolan","value":true}]}
                """);
        JsonObject allowed = boss("""
                {"species":"charizard"}
                """);

        assertTrue(RaidResourcePolicy.filterBoss(forbiddenGeneration,
                properties -> catalog.allowed(rules, properties)));
        assertEquals(0, forbiddenGeneration.get("weight").getAsInt());
        assertEquals("treecko", RaidResourcePolicy.pokemonProperties(
                forbiddenGeneration.getAsJsonObject("pokemon")));

        assertTrue(RaidResourcePolicy.filterBoss(forbiddenRegionalForm,
                properties -> catalog.allowed(rules, properties)));
        assertEquals(0, forbiddenRegionalForm.get("weight").getAsInt());
        assertEquals("geodude alolan=true", RaidResourcePolicy.pokemonProperties(
                forbiddenRegionalForm.getAsJsonObject("pokemon")));

        assertFalse(RaidResourcePolicy.filterBoss(allowed, properties -> catalog.allowed(rules, properties)));
        assertEquals(20, allowed.get("weight").getAsInt());
        assertFalse(RaidResourcePolicy.filterBoss(forbiddenRegionalForm,
                properties -> catalog.allowed(rules, properties)), "Filtering an already disabled boss is a no-op");
    }

    private static JsonObject boss(String pokemon) {
        return JsonParser.parseString("""
                {"pokemon":%s,"weight":20.0,"raid_tier":"TIER_FIVE"}
                """.formatted(pokemon)).getAsJsonObject();
    }

    private static JsonObject species(String name, int dex) {
        return JsonParser.parseString("""
                {"name":"%s","nationalPokedexNumber":%d,"abilities":["static"],"moves":["1:tackle"]}
                """.formatted(name, dex)).getAsJsonObject();
    }
}
