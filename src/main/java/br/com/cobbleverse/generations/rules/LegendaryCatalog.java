package br.com.cobbleverse.generations.rules;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** The server's editable legendary policy catalog. A limit of -1 means unlimited. */
public final class LegendaryCatalog {
    public record Entry(String key, String species, String form, String name, int generation, int defaultLimit) {}

    private static final String[] GENERATION_NAMES = {
            "", "Kanto", "Johto", "Hoenn", "Sinnoh", "Unova", "Kalos", "Alola", "Galar e Hisui", "Paldea"
    };
    private static final List<Entry> ENTRIES = List.of(
            entry(1, "Articuno", "articuno", -1),
            entry(1, "Zapdos", "zapdos", -1),
            entry(1, "Moltres", "moltres", -1),
            entry(1, "Mewtwo", "mewtwo", 2),
            entry(1, "Mew", "mew", -1),

            entry(2, "Raikou", "raikou", -1),
            entry(2, "Entei", "entei", -1),
            entry(2, "Suicune", "suicune", -1),
            entry(2, "Lugia", "lugia", 2),
            entry(2, "Ho-Oh", "hooh", 1),
            entry(2, "Celebi", "celebi", -1),

            entry(3, "Regirock", "regirock", -1),
            entry(3, "Regice", "regice", -1),
            entry(3, "Registeel", "registeel", -1),
            entry(3, "Latias", "latias", -1),
            entry(3, "Latios", "latios", -1),
            entry(3, "Kyogre", "kyogre", 2),
            entry(3, "Groudon", "groudon", 2),
            entry(3, "Rayquaza", "rayquaza", 2),
            entry(3, "Jirachi", "jirachi", 1),
            entry(3, "Deoxys", "deoxys", -1),

            entry(4, "Uxie", "uxie", -1),
            entry(4, "Mesprit", "mesprit", -1),
            entry(4, "Azelf", "azelf", -1),
            entry(4, "Dialga", "dialga", 2),
            entry(4, "Palkia", "palkia", 2),
            entry(4, "Heatran", "heatran", -1),
            entry(4, "Regigigas", "regigigas", -1),
            entry(4, "Giratina", "giratina", 1),
            entry(4, "Cresselia", "cresselia", -1),
            entry(4, "Phione", "phione", -1),
            entry(4, "Manaphy", "manaphy", 1),
            entry(4, "Darkrai", "darkrai", -1),
            entry(4, "Shaymin", "shaymin", -1),
            entry(4, "Arceus", "arceus", 1),

            entry(5, "Victini", "victini", 1),
            entry(5, "Cobalion", "cobalion", -1),
            entry(5, "Terrakion", "terrakion", -1),
            entry(5, "Virizion", "virizion", -1),
            entry(5, "Tornadus", "tornadus", -1),
            entry(5, "Thundurus", "thundurus", -1),
            entry(5, "Reshiram", "reshiram", 1),
            entry(5, "Zekrom", "zekrom", 1),
            entry(5, "Landorus", "landorus", -1),
            entry(5, "Kyurem", "kyurem", 1),
            entry(5, "Keldeo", "keldeo", 1),
            entry(5, "Meloetta", "meloetta", 1),
            entry(5, "Genesect", "genesect", -1),

            entry(6, "Xerneas", "xerneas", 2),
            entry(6, "Yveltal", "yveltal", 2),
            entry(6, "Zygarde", "zygarde", 2),
            entry(6, "Diancie", "diancie", 1),
            entry(6, "Hoopa", "hoopa", 1),
            entry(6, "Volcanion", "volcanion", 1),

            entry(7, "Type: Null", "typenull", 4),
            entry(7, "Silvally", "silvally", 1),
            entry(7, "Tapu Koko", "tapukoko", 1),
            entry(7, "Tapu Lele", "tapulele", 1),
            entry(7, "Tapu Bulu", "tapubulu", 1),
            entry(7, "Tapu Fini", "tapufini", 1),
            entry(7, "Cosmog", "cosmog", 3),
            entry(7, "Cosmoem", "cosmoem", 1),
            entry(7, "Solgaleo", "solgaleo", 1),
            entry(7, "Lunala", "lunala", 1),
            entry(7, "Necrozma", "necrozma", 1),
            entry(7, "Magearna", "magearna", 1),
            entry(7, "Marshadow", "marshadow", 1),
            entry(7, "Zeraora", "zeraora", 2),
            entry(7, "Meltan", "meltan", -1),
            entry(7, "Melmetal", "melmetal", -1),

            entry(8, "Articuno de Galar", "articuno", "galar", 1),
            entry(8, "Zapdos de Galar", "zapdos", "galar", 1),
            entry(8, "Moltres de Galar", "moltres", "galar", 1),
            entry(8, "Zacian", "zacian", 1),
            entry(8, "Zamazenta", "zamazenta", 1),
            entry(8, "Eternatus", "eternatus", 1),
            entry(8, "Kubfu", "kubfu", -1),
            entry(8, "Urshifu", "urshifu", -1),
            entry(8, "Regieleki", "regieleki", 1),
            entry(8, "Regidrago", "regidrago", 1),
            entry(8, "Glastrier", "glastrier", 1),
            entry(8, "Spectrier", "spectrier", 1),
            entry(8, "Calyrex", "calyrex", 1),
            entry(8, "Enamorus", "enamorus", 1),
            entry(8, "Zarude", "zarude", -1),

            entry(9, "Wo-Chien", "wochien", 1),
            entry(9, "Chien-Pao", "chienpao", 1),
            entry(9, "Ting-Lu", "tinglu", 1),
            entry(9, "Chi-Yu", "chiyu", 1),
            entry(9, "Koraidon", "koraidon", 2),
            entry(9, "Miraidon", "miraidon", 2),
            entry(9, "Okidogi", "okidogi", 1),
            entry(9, "Munkidori", "munkidori", 1),
            entry(9, "Fezandipiti", "fezandipiti", 1),
            entry(9, "Ogerpon", "ogerpon", 1),
            entry(9, "Terapagos", "terapagos", 1),
            entry(9, "Pecharunt", "pecharunt", 1)
    );
    private static final Map<String, Entry> BY_KEY = indexEntries();

    private LegendaryCatalog() {}

    private static Entry entry(int generation, String name, String species, int limit) {
        return new Entry("cobblemon:" + species, "cobblemon:" + species, null, name, generation, limit);
    }

    private static Entry entry(int generation, String name, String species, String form, int limit) {
        String speciesId = "cobblemon:" + species;
        return new Entry(speciesId + "/" + form, speciesId, form, name, generation, limit);
    }

    private static Map<String, Entry> indexEntries() {
        Map<String, Entry> result = new LinkedHashMap<>();
        for (Entry entry : ENTRIES) result.put(entry.key(), entry);
        return Map.copyOf(result);
    }

    public static List<Entry> entries() { return ENTRIES; }
    public static Entry entry(String key) { return BY_KEY.get(normalizeKey(key)); }
    public static String generationName(int generation) { return GENERATION_NAMES[generation]; }

    public static Map<String, Integer> defaultLimits() {
        Map<String, Integer> defaults = new LinkedHashMap<>();
        for (Entry entry : ENTRIES) defaults.put(entry.key(), entry.defaultLimit());
        return defaults;
    }

    public static boolean hasFiniteLimit(Map<String, Integer> limits) {
        if (limits == null) return false;
        return limits.values().stream().anyMatch(limit -> limit != null && limit >= 0);
    }

    public static String keyForPokemon(String species, String form) {
        String speciesId = normalizeSpecies(species);
        if (speciesId == null || !BY_KEY.containsKey(speciesId)) return null;
        if (form != null && hasRegionalLimit(speciesId)) {
            String canonical = FormNames.canonical(form);
            if (canonical.endsWith("galar")) canonical = "galar";
            String formKey = speciesId + "/" + canonical;
            if (BY_KEY.containsKey(formKey)) return formKey;
        }
        return speciesId;
    }

    private static boolean hasRegionalLimit(String speciesId) {
        return switch (speciesId) {
            case "cobblemon:articuno", "cobblemon:zapdos", "cobblemon:moltres" -> true;
            default -> false;
        };
    }

    public static String normalizeKey(String raw) {
        if (raw == null) return null;
        String key = raw.trim().toLowerCase(Locale.ROOT);
        if (key.contains("/")) {
            int slash = key.indexOf('/');
            String species = normalizeSpecies(key.substring(0, slash));
            if (species == null) return null;
            String form = FormNames.canonical(key.substring(slash + 1));
            if (form.endsWith("galar")) form = "galar";
            return species + "/" + form;
        }
        return normalizeSpecies(key);
    }

    private static String normalizeSpecies(String raw) {
        if (raw == null || raw.isBlank()) return null;
        String species = raw.trim().toLowerCase(Locale.ROOT);
        return species.contains(":") ? species : "cobblemon:" + species;
    }
}
