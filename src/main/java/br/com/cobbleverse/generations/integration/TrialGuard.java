package br.com.cobbleverse.generations.integration;

import br.com.cobbleverse.generations.GenerationControl;
import com.cobblemon.mod.common.api.pokemon.*;
import com.cobblemon.mod.common.pokemon.Species;
import java.util.*;

/** Keeps trial waves playable by replacing forbidden opponents before creation. */
public final class TrialGuard {
    private TrialGuard() {}
    public static String legalEncounter(String raw) {
        if (!GenerationControl.ready()) return raw;
        var properties=PokemonProperties.Companion.parse(raw);
        if (CobblemonIntegration.allowed(properties)) return raw;
        Species source=properties.getSpecies()==null ? null : CobblemonIntegration.species(properties.getSpecies());
        Species replacement=PokemonSpecies.getImplemented().stream()
            .filter(s -> GenerationControl.rules().allowed(s.getResourceIdentifier().toString(),s.getNationalPokedexNumber(),s.getStandardForm().getName(),Set.of()))
            .min(Comparator.comparingInt((Species s) -> distance(source,s)).thenComparing(s -> s.getResourceIdentifier().toString()))
            .orElseThrow(() -> new IllegalStateException("Nenhum oponente permitido para o desafio."));
        return replacement.getResourceIdentifier()+" level="+(properties.getLevel()==null ? 30 : properties.getLevel())+" uncatchable=yes";
    }
    private static int distance(Species source,Species candidate) {
        int target=source==null ? 400 : source.getBaseStats().values().stream().mapToInt(Integer::intValue).sum();
        boolean sharesType=source==null || candidate.getPrimaryType().equals(source.getPrimaryType()) || candidate.getPrimaryType().equals(source.getSecondaryType())
            || (candidate.getSecondaryType()!=null && (candidate.getSecondaryType().equals(source.getPrimaryType()) || candidate.getSecondaryType().equals(source.getSecondaryType())));
        return (sharesType ? 0 : 5000)+Math.abs(target-candidate.getBaseStats().values().stream().mapToInt(Integer::intValue).sum());
    }
}
