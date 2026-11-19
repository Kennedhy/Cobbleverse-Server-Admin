package br.com.cobbleverse.generations.data;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.function.Predicate;

/** Reads and filters the Pokémon properties used by Raid Dens boss resources. */
public final class RaidResourcePolicy {
    private RaidResourcePolicy() {}

    public static boolean filterBoss(JsonObject boss, Predicate<String> allowed) {
        if (!boss.has("pokemon") || !boss.get("pokemon").isJsonObject()) return false;
        String properties = pokemonProperties(boss.getAsJsonObject("pokemon"));
        if (allowed.test(properties)) return false;
        if (boss.has("weight") && !boss.get("weight").isJsonNull()
                && Double.compare(boss.get("weight").getAsDouble(), 0.0) == 0) {
            return false;
        }
        boss.addProperty("weight", 0);
        return true;
    }

    public static String pokemonProperties(JsonObject pokemon) {
        StringBuilder properties = new StringBuilder(pokemon.get("species").getAsString());
        if (pokemon.has("form") && !pokemon.get("form").isJsonNull()) {
            properties.append(" form=").append(pokemon.get("form").getAsString());
        }
        appendValues(pokemon.get("aspects"), properties);
        appendCustomProperties(pokemon.get("custom_properties"), properties);
        return properties.toString();
    }

    private static void appendCustomProperties(JsonElement customProperties, StringBuilder properties) {
        if (customProperties == null || !customProperties.isJsonArray()) return;
        for (JsonElement element : customProperties.getAsJsonArray()) {
            if (!element.isJsonObject()) continue;
            JsonObject custom = element.getAsJsonObject();
            if (!custom.has("name") || !custom.has("value")) continue;
            String name = custom.get("name").getAsString();
            String value = custom.get("value").getAsString();
            properties.append(' ').append(name).append('=').append(value);
        }
    }

    private static void appendValues(JsonElement values, StringBuilder properties) {
        if (values == null || !values.isJsonArray()) return;
        for (JsonElement value : values.getAsJsonArray()) {
            properties.append(' ').append(value.getAsString());
        }
    }
}
