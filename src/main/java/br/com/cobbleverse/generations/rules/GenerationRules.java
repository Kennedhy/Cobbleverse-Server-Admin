package br.com.cobbleverse.generations.rules;

import br.com.cobbleverse.generations.config.GenerationConfig;
import java.util.*;

/** Pure rule engine, shared by validation, resource generation and runtime checks. */
public final class GenerationRules {
    private static final int[] LAST_DEX = {151,251,386,493,649,721,809,905,1025};
    private final GenerationConfig config;

    public GenerationRules(GenerationConfig config) { this.config = config.copy().validated(); }
    public GenerationConfig config() { return config.copy(); }

    /** Compare effective, normalized species restrictions, not editable draft fields. */
    public boolean sameSpeciesPolicy(GenerationRules other) {
        GenerationConfig a=config, b=other.config;
        return a.generations.equals(b.generations) && a.regionalForms==b.regionalForms && a.megaEvolutions==b.megaEvolutions
            && a.allowSpecies.equals(b.allowSpecies) && a.denySpecies.equals(b.denySpecies)
            && a.allowForms.equals(b.allowForms) && a.denyForms.equals(b.denyForms);
    }

    public static int generation(int nationalDex) {
        if (nationalDex <= 0) return 0;
        for (int i = 0; i < LAST_DEX.length; i++) if (nationalDex <= LAST_DEX[i]) return i + 1;
        return 0;
    }

    public static String speciesKey(String species) {
        String key = species.trim().toLowerCase(Locale.ROOT);
        return key.contains(":") ? key : "cobblemon:" + key;
    }

    private boolean speciesAllowedKey(String key, int dex) {
        if (config.denySpecies.contains(key)) return false;
        if (config.allowSpecies.contains(key)) return true;
        return key.startsWith("cobblemon:") && config.generations.contains(generation(dex));
    }

    public boolean speciesAllowed(String species, int dex) {
        return speciesAllowedKey(speciesKey(species), dex);
    }

    public boolean allowed(String species, int dex, String form, Iterable<String> aspects) {
        String speciesId = speciesKey(species);
        if (!speciesAllowedKey(speciesId, dex)) return false;
        String normalizedForm = FormNames.canonical(form);
        if (!config.denyForms.isEmpty() || !config.allowForms.isEmpty()) {
            String formKey = speciesId + "/" + normalizedForm;
            if (config.denyForms.contains(formKey)) return false;
            if (config.allowForms.contains(formKey)) return true;
        }
        if (!config.megaEvolutions && FormNames.activeMega(normalizedForm)) return false;
        if (!config.regionalForms && isRegional(normalizedForm)) return false;
        if (aspects != null) for (String aspect : aspects) {
            if (!config.megaEvolutions && FormNames.activeMega(aspect)) return false;
            if (!config.regionalForms && isRegional(aspect.toLowerCase(Locale.ROOT))) return false;
        }
        return true;
    }

    private static boolean isRegional(String token) {
        return token.contains("alola") || token.contains("galar") || token.contains("hisui") || token.contains("hisuian") || token.contains("paldea") || token.contains("sevii");
    }

    public boolean regionAllowed(String region) { return config.regions.contains(region); }
    public boolean hasGyms() { return !config.regions.isEmpty(); }
    public boolean restrictExisting() { return config.restrictExistingPokemon; }
    public boolean filterGyms() { return config.filterGyms; }
    public boolean filterTeams() { return config.filterTrainerTeams; }
    public boolean allowMegas() { return config.megaEvolutions; }
    public boolean onlyGeneration(int generation) { return config.generations.equals(Set.of(generation)); }
}
