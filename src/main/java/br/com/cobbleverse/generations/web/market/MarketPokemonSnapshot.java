package br.com.cobbleverse.generations.web.market;

import com.cobblemon.mod.common.pokemon.Pokemon;
import com.cobblemon.mod.common.pokemon.PokemonStats;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.registry.DynamicRegistryManager;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/** Durable Pokémon listing snapshot. Capture and restore must run on the server thread. */
public record MarketPokemonSnapshot(
        UUID pokemonUuid,
        String species,
        String form,
        String nickname,
        int level,
        boolean shiny,
        String nature,
        float scaleModifier,
        Map<String, Integer> ivs,
        Map<String, Integer> evs,
        String pokemonJson) {
    private static final int MAX_POKEMON_JSON_CHARS = 16_384;

    public MarketPokemonSnapshot {
        ivs = Map.copyOf(ivs);
        evs = Map.copyOf(evs);
    }

    public static MarketPokemonSnapshot capture(Pokemon pokemon, DynamicRegistryManager registries) {
        if (!pokemon.getTradeable()) throw new IllegalArgumentException("Este Pokémon está marcado como não negociável.");
        JsonObject serialized = pokemon.saveToJSON(registries, new JsonObject());
        String pokemonJson = serialized.toString();
        if (pokemonJson.length() > MAX_POKEMON_JSON_CHARS)
            throw new IllegalArgumentException("Os dados deste Pokémon são grandes demais para um anúncio.");
        return new MarketPokemonSnapshot(
                pokemon.getUuid(),
                pokemon.getSpecies().getResourceIdentifier().toString(),
                pokemon.getForm().formOnlyShowdownId(),
                pokemon.getNickname() == null ? "" : pokemon.getNickname().getString(),
                pokemon.getLevel(),
                pokemon.getShiny(),
                pokemon.getNature().getDisplayName(),
                pokemon.getScaleModifier(),
                statValues(pokemon.getIvs()),
                statValues(pokemon.getEvs()),
                pokemonJson);
    }

    public Pokemon restore(DynamicRegistryManager registries) {
        if (pokemonJson == null || pokemonJson.isBlank() || pokemonJson.length() > MAX_POKEMON_JSON_CHARS)
            throw new IllegalStateException("Os dados salvos deste Pokémon são inválidos.");
        JsonObject serialized = JsonParser.parseString(pokemonJson).getAsJsonObject();
        Pokemon pokemon = new Pokemon().loadFromJSON(registries, serialized);
        if (pokemon == null || !pokemon.getUuid().equals(pokemonUuid))
            throw new IllegalStateException("O Pokémon salvo no anúncio está inválido.");
        return pokemon;
    }

    private static Map<String, Integer> statValues(PokemonStats stats) {
        Map<String, Integer> values = new LinkedHashMap<>();
        for (var entry : stats) {
            String id = entry.getKey().getShowdownId();
            if (id != null && !id.isBlank()) values.put(id, entry.getValue());
        }
        return Map.copyOf(values);
    }
}
