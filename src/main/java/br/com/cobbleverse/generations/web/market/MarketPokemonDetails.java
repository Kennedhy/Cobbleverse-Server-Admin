package br.com.cobbleverse.generations.web.market;

import java.util.Map;

/** Small listing metadata kept after the full Pokémon payload leaves escrow. */
public record MarketPokemonDetails(String species, String form, String nickname, int level,
                                   boolean shiny, String nature, float scaleModifier,
                                   Map<String, Integer> ivs, Map<String, Integer> evs) {
    public MarketPokemonDetails {
        ivs = Map.copyOf(ivs);
        evs = Map.copyOf(evs);
    }

    public static MarketPokemonDetails from(MarketPokemonSnapshot snapshot) {
        return new MarketPokemonDetails(snapshot.species(), snapshot.form(), snapshot.nickname(),
                snapshot.level(), snapshot.shiny(), snapshot.nature(), snapshot.scaleModifier(),
                snapshot.ivs(), snapshot.evs());
    }
}
