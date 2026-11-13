package br.com.cobbleverse.generations.config;

import java.util.*;

/** Serializable policy. Instances are copied at each trust boundary. */
public final class GenerationConfig {
    public static final int SCHEMA = 1;
    public int schemaVersion = SCHEMA;
    public long revision = 1;
    public String mode = "single";
    public int selectedGeneration = 1;
    public Set<Integer> generations = new TreeSet<>(Set.of(1));
    public boolean automaticRegions = true;
    public Set<String> regions = new TreeSet<>(Set.of("kanto"));
    public String initialRegion = "kanto";
    public boolean regionalForms = false;
    public boolean megaEvolutions = false;
    public boolean restrictExistingPokemon = true;
    public boolean rescueStarter = true;
    public boolean filterGyms = true;
    public boolean filterTrainerTeams = true;
    public Set<String> allowSpecies = new TreeSet<>();
    public Set<String> denySpecies = new TreeSet<>();
    public Set<String> allowForms = new TreeSet<>();
    public Set<String> denyForms = new TreeSet<>();
    /** Optional experience manager. Disabled for existing worlds until explicitly enabled. */
    public boolean manageExperience = false;
    public double globalXpMultiplier = 1.0;
    public Map<String, Double> playerXpMultipliers = new TreeMap<>();
    public Map<String, Double> groupXpMultipliers = new TreeMap<>();
    public Map<String, String> playerXpGroups = new TreeMap<>();
    public String levelCapMode = "rct";
    public int globalLevelCap = 100;
    public String wildLevelBehavior = "unchanged";
    /** Enables optional level-cap enforcement in PvE battles; the behavior selects team or per-Pokemon blocking. */
    public boolean blockOverCapPokemonInBattles = false;
    /** "team" blocks the whole PvE battle; "pokemon" blocks only over-cap Pokémon from entering PvE. */
    public String overCapBattleBehavior = "team";
    public double captureMultiplier = 1.0;
    public Integer shinyRate = null;

    public static final List<String> REGIONS = List.of("kanto", "johto", "hoenn", "sinnoh");

    public GenerationConfig validated() {
        if (schemaVersion != SCHEMA) throw new IllegalArgumentException("Versão de configuração não suportada: " + schemaVersion);
        if (revision < 0) throw new IllegalArgumentException("Revisão inválida.");
        if (!Set.of("single", "cumulative", "custom").contains(mode)) throw new IllegalArgumentException("Modo inválido.");
        if (selectedGeneration < 1 || selectedGeneration > 9) throw new IllegalArgumentException("Escolha uma geração entre 1 e 9.");
        if (mode.equals("single")) generations = new TreeSet<>(Set.of(selectedGeneration));
        if (mode.equals("cumulative")) {
            generations = new TreeSet<>();
            for (int i = 1; i <= selectedGeneration; i++) generations.add(i);
        }
        if (generations == null || generations.isEmpty() || generations.size() > 9 || generations.stream().anyMatch(n -> n == null || n < 1 || n > 9))
            throw new IllegalArgumentException("Selecione ao menos uma geração válida.");
        generations = new TreeSet<>(generations);
        if (automaticRegions) {
            regions = new TreeSet<>();
            for (int g : generations) if (g <= REGIONS.size()) regions.add(REGIONS.get(g - 1));
        }
        if (regions == null || regions.stream().anyMatch(r -> !REGIONS.contains(r))) throw new IllegalArgumentException("Circuito desconhecido.");
        regions = new TreeSet<>(regions);
        if (regions.isEmpty()) initialRegion = "freeroam";
        else if (!regions.contains(initialRegion)) initialRegion = REGIONS.stream().filter(regions::contains).findFirst().orElseThrow();
        allowSpecies = normalize(allowSpecies, false);
        denySpecies = normalize(denySpecies, false);
        allowForms = normalize(allowForms, true);
        denyForms = normalize(denyForms, true);
        if (!Collections.disjoint(allowSpecies, denySpecies) || !Collections.disjoint(allowForms, denyForms))
            throw new IllegalArgumentException("Uma exceção não pode permitir e bloquear a mesma entrada.");
        if (!Set.of("rct", "global").contains(levelCapMode)) throw new IllegalArgumentException("Modo de limite de nível inválido.");
        if (globalLevelCap < 1 || globalLevelCap > 100) throw new IllegalArgumentException("O limite global deve ficar entre 1 e 100.");
        if (!Set.of("unchanged", "clamp", "block").contains(wildLevelBehavior)) throw new IllegalArgumentException("Regra de nível dos selvagens inválida.");
        if (!Set.of("team", "pokemon").contains(overCapBattleBehavior)) throw new IllegalArgumentException("Regra de batalha acima do cap inválida.");
        validateMultiplier(globalXpMultiplier);
        if (!Double.isFinite(captureMultiplier) || captureMultiplier < 0 || captureMultiplier > 100)
            throw new IllegalArgumentException("O multiplicador de captura deve ficar entre 0 e 100.");
        if (shinyRate != null && (shinyRate < 1 || shinyRate > 1_000_000))
            throw new IllegalArgumentException("A taxa shiny deve ficar entre 1 e 1000000.");
        playerXpMultipliers = normalizeMultipliers(playerXpMultipliers, true);
        groupXpMultipliers = normalizeMultipliers(groupXpMultipliers, false);
        if (playerXpGroups == null || playerXpGroups.size() > 512) throw new IllegalArgumentException("Lista de grupos de jogadores inválida.");
        Map<String, String> normalizedGroups = new TreeMap<>();
        for (var entry : playerXpGroups.entrySet()) {
            String player;
            try { player = UUID.fromString(entry.getKey()).toString(); }
            catch (RuntimeException e) { throw new IllegalArgumentException("Jogador deve ser identificado pelo UUID."); }
            String group = entry.getValue() == null ? "" : entry.getValue().trim().toLowerCase(Locale.ROOT);
            if (!groupXpMultipliers.containsKey(group)) throw new IllegalArgumentException("Grupo de XP não definido: " + group);
            normalizedGroups.put(player, group);
        }
        playerXpGroups = normalizedGroups;
        if(ConfigStore.GSON.toJson(this).length()>32768) throw new IllegalArgumentException("A configuração excede o limite do painel. Reduza as listas de exceções.");
        return this;
    }

    private static void validateMultiplier(double value) {
        if (!Double.isFinite(value) || value < 0 || value > 100) throw new IllegalArgumentException("Multiplicadores de XP devem ficar entre 0 e 100.");
    }

    private static Map<String, Double> normalizeMultipliers(Map<String, Double> entries, boolean players) {
        if (entries == null || entries.size() > 512) throw new IllegalArgumentException("Lista de multiplicadores inválida.");
        Map<String, Double> result = new TreeMap<>();
        for (var entry : entries.entrySet()) {
            if (entry.getKey() == null || entry.getValue() == null) throw new IllegalArgumentException("Multiplicador incompleto.");
            String key = entry.getKey().trim().toLowerCase(Locale.ROOT);
            if (players) {
                try { key = UUID.fromString(key).toString(); }
                catch (IllegalArgumentException e) { throw new IllegalArgumentException("Jogador deve ser identificado pelo UUID: " + key); }
            } else if (!key.matches("[a-z0-9_.-]{1,48}")) throw new IllegalArgumentException("Nome de grupo inválido: " + key);
            validateMultiplier(entry.getValue());
            if (result.put(key, entry.getValue()) != null) throw new IllegalArgumentException("Multiplicador duplicado: " + key);
        }
        return result;
    }

    private static Set<String> normalize(Set<String> entries, boolean forms) {
        if (entries == null || entries.size() > 2048) throw new IllegalArgumentException("Lista de exceções inválida.");
        Set<String> result = new TreeSet<>();
        for (String item : entries) {
            if (item == null) throw new IllegalArgumentException("Exceção vazia.");
            String key = item.trim().toLowerCase(Locale.ROOT);
            if (!key.matches(forms ? "[a-z0-9_:.-]+/[a-z0-9_-]+" : "[a-z0-9_:.-]+"))
                throw new IllegalArgumentException("Exceção inválida: " + item);
            if (!key.contains(":")) key = "cobblemon:" + key;
            if (forms) {
                int slash=key.indexOf('/');
                key=key.substring(0,slash+1)+br.com.cobbleverse.generations.rules.FormNames.canonical(key.substring(slash+1));
            }
            result.add(key);
        }
        return result;
    }

    public GenerationConfig copy() {
        return ConfigStore.GSON.fromJson(ConfigStore.GSON.toJson(this), GenerationConfig.class);
    }
}
