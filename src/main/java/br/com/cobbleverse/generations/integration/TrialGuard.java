package br.com.cobbleverse.generations.integration;

import br.com.cobbleverse.generations.GenerationControl;
import br.com.cobbleverse.generations.rules.GenerationRules;
import com.cobblemon.mod.common.api.pokemon.*;
import com.cobblemon.mod.common.pokemon.Species;
import java.util.*;

/** Keeps trial waves playable by replacing forbidden opponents before creation. */
public final class TrialGuard {
    private static final int MAX_ENCOUNTER_CACHE = 256;
    private record Candidate(String id, Species species, int baseStatTotal) {}

    private static GenerationRules cachedRules;
    private static List<Candidate> candidates;
    private static final LinkedHashMap<String, String> encounters = new LinkedHashMap<>(64, 0.75f, true);

    private TrialGuard() {}

    public static synchronized void clear() {
        cachedRules = null;
        candidates = null;
        encounters.clear();
    }

    /**
     * BCA calls this from its spawner tick checks. Cache the common, unchanged
     * datapack strings so they do not need to be parsed on every check.
     */
    public static synchronized String legalEncounter(String raw) {
        if (raw == null || !GenerationControl.ready()) return raw;
        GenerationRules rules = GenerationControl.rules();
        if (rules != cachedRules) {
            cachedRules = rules;
            candidates = null;
            encounters.clear();
        }
        String cached = encounters.get(raw);
        if (cached != null) return cached;

        var properties=PokemonProperties.Companion.parse(raw);
        if (CobblemonIntegration.allowed(properties)) return remember(raw, raw);
        Species source=properties.getSpecies()==null ? null : CobblemonIntegration.species(properties.getSpecies());
        if (candidates == null) candidates = buildCandidates(rules);
        int target = source == null ? 400 : baseStatTotal(source);
        Candidate replacement = null;
        int bestDistance = Integer.MAX_VALUE;
        for (Candidate candidate : candidates) {
            int distance = distance(source, target, candidate);
            if (distance < bestDistance || (distance == bestDistance
                    && (replacement == null || candidate.id().compareTo(replacement.id()) < 0))) {
                replacement = candidate;
                bestDistance = distance;
            }
        }
        if (replacement == null) throw new IllegalStateException("Nenhum oponente permitido para o desafio.");
        String result = replacement.id()+" level="+(properties.getLevel()==null ? 30 : properties.getLevel())+" uncatchable=yes";
        return remember(raw, result);
    }

    private static List<Candidate> buildCandidates(GenerationRules rules) {
        List<Candidate> result = new ArrayList<>();
        for (Species species : PokemonSpecies.getImplemented()) {
            String id = species.getResourceIdentifier().toString();
            if (rules.allowed(id, species.getNationalPokedexNumber(), species.getStandardForm().getName(), Set.of()))
                result.add(new Candidate(id, species, baseStatTotal(species)));
        }
        result.sort(Comparator.comparing(Candidate::id));
        return List.copyOf(result);
    }

    private static int distance(Species source, int target, Candidate candidate) {
        Species species = candidate.species();
        boolean sharesType = source == null || species.getPrimaryType().equals(source.getPrimaryType())
                || species.getPrimaryType().equals(source.getSecondaryType())
                || (species.getSecondaryType() != null && (species.getSecondaryType().equals(source.getPrimaryType())
                || species.getSecondaryType().equals(source.getSecondaryType())));
        return (sharesType ? 0 : 5000) + Math.abs(target - candidate.baseStatTotal());
    }

    private static int baseStatTotal(Species species) {
        int total = 0;
        for (int value : species.getBaseStats().values()) total += value;
        return total;
    }

    private static String remember(String raw, String result) {
        if (encounters.size() >= MAX_ENCOUNTER_CACHE) {
            Iterator<String> oldest = encounters.keySet().iterator();
            if (oldest.hasNext()) encounters.remove(oldest.next());
        }
        encounters.put(raw, result);
        return result;
    }
}
