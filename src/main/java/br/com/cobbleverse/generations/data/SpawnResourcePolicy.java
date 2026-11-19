package br.com.cobbleverse.generations.data;

import com.google.gson.*;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

/** Applies species rules to Cobblemon world spawns and habitat-pool resources. */
public final class SpawnResourcePolicy {
    private SpawnResourcePolicy() {}

    /**
     * Filters both ordinary spawn details and the individual members of Cobblemon's
     * pokemon-herd details. A herd without any permitted leader cannot spawn safely.
     */
    public static boolean filterWorldSpawnPool(JsonObject root, Predicate<String> allowed) {
        JsonArray source = root.has("spawns") && root.get("spawns").isJsonArray()
                ? root.getAsJsonArray("spawns") : null;
        if (source == null) return false;

        JsonArray filtered = new JsonArray();
        boolean changed = false;
        for (JsonElement element : source) {
            JsonObject detail = element.getAsJsonObject();
            FilterResult result = filterWorldDetail(detail, allowed);
            if (result.keep()) filtered.add(detail);
            else changed = true;
            changed |= result.changed();
        }
        if (changed) root.add("spawns", filtered);
        return changed;
    }

    /** Filters each habitat spawn record using its species and optional PokemonProperties modifiers. */
    public static boolean filterHabitatPool(JsonObject root, Predicate<String> allowed) {
        JsonArray source = root.has("spawns") && root.get("spawns").isJsonArray()
                ? root.getAsJsonArray("spawns") : null;
        if (source == null) return false;

        JsonArray filtered = new JsonArray();
        boolean changed = false;
        for (JsonElement element : source) {
            JsonObject spawn = element.getAsJsonObject();
            if (allowed.test(habitatProperties(spawn))) filtered.add(spawn);
            else changed = true;
        }
        if (changed) root.add("spawns", filtered);
        return changed;
    }

    /** Returns all Pokémon property strings represented by a world-spawn resource. */
    public static List<String> worldSpawnProperties(JsonObject root) {
        List<String> properties = new ArrayList<>();
        if (!root.has("spawns") || !root.get("spawns").isJsonArray()) return properties;
        for (JsonElement element : root.getAsJsonArray("spawns")) {
            JsonObject detail = element.getAsJsonObject();
            addPokemonProperty(detail, properties);
            if (detail.has("herdablePokemon") && detail.get("herdablePokemon").isJsonArray()) {
                for (JsonElement member : detail.getAsJsonArray("herdablePokemon")) {
                    addPokemonProperty(member.getAsJsonObject(), properties);
                }
            }
        }
        return properties;
    }

    /** Returns all Pokémon property strings represented by a habitat-pool resource. */
    public static List<String> habitatSpawnProperties(JsonObject root) {
        List<String> properties = new ArrayList<>();
        if (!root.has("spawns") || !root.get("spawns").isJsonArray()) return properties;
        for (JsonElement element : root.getAsJsonArray("spawns")) {
            properties.add(habitatProperties(element.getAsJsonObject()));
        }
        return properties;
    }

    private static FilterResult filterWorldDetail(JsonObject detail, Predicate<String> allowed) {
        if (detail.has("pokemon") && !detail.get("pokemon").isJsonNull()
                && !allowed.test(detail.get("pokemon").getAsString())) {
            return new FilterResult(false, false);
        }
        if (!detail.has("herdablePokemon") || !detail.get("herdablePokemon").isJsonArray()) {
            return new FilterResult(true, false);
        }

        JsonArray source = detail.getAsJsonArray("herdablePokemon");
        JsonArray filtered = new JsonArray();
        int originalLeaders = 0;
        int permittedLeaders = 0;
        boolean changed = false;
        for (JsonElement element : source) {
            JsonObject member = element.getAsJsonObject();
            boolean leader = member.has("isLeader") && member.get("isLeader").getAsBoolean();
            if (leader) originalLeaders++;
            if (member.has("pokemon") && !member.get("pokemon").isJsonNull()
                    && !allowed.test(member.get("pokemon").getAsString())) {
                changed = true;
                continue;
            }
            filtered.add(member);
            if (leader) permittedLeaders++;
        }

        if (filtered.isEmpty() || (originalLeaders > 0 && permittedLeaders == 0)) {
            return new FilterResult(false, true);
        }
        if (changed) detail.add("herdablePokemon", filtered);
        return new FilterResult(true, changed);
    }

    private static void addPokemonProperty(JsonObject object, List<String> properties) {
        if (object.has("pokemon") && !object.get("pokemon").isJsonNull()) {
            properties.add(object.get("pokemon").getAsString());
        }
    }

    private static String habitatProperties(JsonObject spawn) {
        String species = spawn.get("species").getAsString();
        if (!spawn.has("modifiers") || spawn.get("modifiers").isJsonNull()
                || spawn.get("modifiers").getAsString().isBlank()) {
            return species;
        }
        return species + " " + spawn.get("modifiers").getAsString();
    }

    private record FilterResult(boolean keep, boolean changed) {}
}
