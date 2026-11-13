package br.com.cobbleverse.generations.integration;

import java.util.*;
import java.util.regex.Pattern;

/** Separate, live server policy for trainer battle difficulty. */
public final class TrainerDifficultyConfig {
    public static final int SCHEMA = 1;
    private static final Pattern GROUP_ID = Pattern.compile("[a-z0-9_-]{1,40}");
    private static final Pattern TRAINER_ID = Pattern.compile("[a-z0-9_.:/-]{1,128}");

    public int schemaVersion = SCHEMA;
    public long revision = 1;
    public boolean enabled;
    public Profile defaultProfile = new Profile();
    public List<TrainerGroup> trainerGroups = new ArrayList<>();
    public List<PlayerGroup> playerGroups = new ArrayList<>();
    public List<ProfileRule> profiles = new ArrayList<>();

    public TrainerDifficultyConfig validated() {
        if (schemaVersion != SCHEMA) throw new IllegalArgumentException("Versão da configuração de dificuldade não suportada: " + schemaVersion);
        if (revision < 0) throw new IllegalArgumentException("Revisão de dificuldade inválida.");
        if (defaultProfile == null) defaultProfile = new Profile();
        defaultProfile.validate();
        if (trainerGroups == null || trainerGroups.size() > 64) throw new IllegalArgumentException("Crie no máximo 64 grupos de treinadores.");
        if (playerGroups == null || playerGroups.size() > 64) throw new IllegalArgumentException("Crie no máximo 64 grupos de jogadores.");
        if (profiles == null || profiles.size() > 256) throw new IllegalArgumentException("Crie no máximo 256 regras de dificuldade.");

        Set<String> trainerGroupIds = new HashSet<>();
        Set<String> assignedTypes = new HashSet<>();
        Set<String> assignedTrainerIds = new HashSet<>();
        for (TrainerGroup group : trainerGroups) {
            group.validate();
            if (!trainerGroupIds.add(group.id)) throw new IllegalArgumentException("Grupo de treinador duplicado: " + group.id);
            for (String type : group.trainerTypes) {
                if (!assignedTypes.add(type)) throw new IllegalArgumentException("A categoria RCT já pertence a outro grupo: " + type);
            }
            for (String trainer : group.trainerIds) {
                if (!assignedTrainerIds.add(trainer)) throw new IllegalArgumentException("O treinador já pertence a outro grupo: " + trainer);
            }
        }

        Set<String> playerGroupIds = new HashSet<>();
        Set<String> assignedPlayers = new HashSet<>();
        int totalPlayerAssignments = 0;
        for (PlayerGroup group : playerGroups) {
            group.validate();
            if (!playerGroupIds.add(group.id)) throw new IllegalArgumentException("Grupo de jogador duplicado: " + group.id);
            totalPlayerAssignments += group.playerUuids.size();
            for (String player : group.playerUuids) {
                if (!assignedPlayers.add(player)) throw new IllegalArgumentException("O jogador já pertence a outro grupo: " + player);
            }
        }
        if (totalPlayerAssignments > 2048) throw new IllegalArgumentException("O limite total é 2048 jogadores em grupos.");

        Set<String> ruleKeys = new HashSet<>();
        for (ProfileRule profile : profiles) {
            profile.validate(trainerGroupIds, playerGroupIds);
            String key = profile.trainerGroup + "\u0000" + profile.playerGroup;
            if (!ruleKeys.add(key)) throw new IllegalArgumentException("Existe mais de um perfil para este par de grupos.");
        }
        return this;
    }

    private static String groupId(String value) {
        String id = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
        if (!GROUP_ID.matcher(id).matches()) throw new IllegalArgumentException("ID de grupo inválido: " + id);
        return id;
    }

    private static String displayName(String value) {
        String name = value == null ? "" : value.trim();
        if (name.isBlank() || name.length() > 48) throw new IllegalArgumentException("O nome do grupo deve ter de 1 a 48 caracteres.");
        return name;
    }

    private static String trainerId(String value) {
        String id = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
        if (!TRAINER_ID.matcher(id).matches()) throw new IllegalArgumentException("ID de treinador inválido: " + id);
        return id;
    }

    public static final class TrainerGroup {
        public String id = "";
        public String name = "";
        public List<String> trainerTypes = new ArrayList<>();
        public List<String> trainerIds = new ArrayList<>();

        public void validate() {
            id = groupId(id);
            name = displayName(name);
            if (trainerTypes == null || trainerTypes.size() > 128) throw new IllegalArgumentException("Lista de categorias de treinador inválida.");
            if (trainerIds == null || trainerIds.size() > 256) throw new IllegalArgumentException("Lista de treinadores específicos inválida.");
            trainerTypes = normalize(trainerTypes, "categoria RCT");
            trainerIds = normalize(trainerIds, "treinador", true);
        }
    }

    public static final class PlayerGroup {
        public String id = "";
        public String name = "";
        public List<String> playerUuids = new ArrayList<>();

        public void validate() {
            id = groupId(id);
            name = displayName(name);
            if (playerUuids == null || playerUuids.size() > 2048) throw new IllegalArgumentException("Lista de jogadores inválida.");
            Set<String> result = new TreeSet<>();
            for (String raw : playerUuids) {
                try { result.add(UUID.fromString(raw.trim()).toString()); }
                catch (RuntimeException e) { throw new IllegalArgumentException("Grupo de jogadores deve usar UUIDs válidos."); }
            }
            playerUuids = new ArrayList<>(result);
        }
    }

    public static final class ProfileRule {
        /** '*' targets any RCT trainer group. */
        public String trainerGroup = "*";
        /** '*' targets any player group, including ungrouped players. */
        public String playerGroup = "*";
        public Profile profile = new Profile();

        public void validate(Set<String> trainerIds, Set<String> playerIds) {
            trainerGroup = trainerGroup == null ? "*" : trainerGroup.trim().toLowerCase(Locale.ROOT);
            playerGroup = playerGroup == null ? "*" : playerGroup.trim().toLowerCase(Locale.ROOT);
            if (!trainerGroup.equals("*") && !trainerIds.contains(trainerGroup)) throw new IllegalArgumentException("Grupo de treinador inexistente no perfil: " + trainerGroup);
            if (!playerGroup.equals("*") && !playerIds.contains(playerGroup)) throw new IllegalArgumentException("Grupo de jogador inexistente no perfil: " + playerGroup);
            if (trainerGroup.equals("*") && playerGroup.equals("*")) throw new IllegalArgumentException("Use o perfil padrão para a regra geral.");
            if (profile == null) profile = new Profile();
            profile.validate();
        }
    }

    public static final class Profile {
        /** Multiplies the configured NPC level, then applies levelOffset. */
        public double levelMultiplier = 1.0;
        public int levelOffset;
        /** Minimum IV value copied onto battle-only trainer Pokémon. */
        public int minimumIv;
        /** Minimum EV value per main stat, subject to Cobblemon's 510-point total. */
        public int evPerStat;

        public void validate() {
            if (!Double.isFinite(levelMultiplier) || levelMultiplier < 0.25 || levelMultiplier > 3.0)
                throw new IllegalArgumentException("Multiplicador de nível deve ficar entre 0,25 e 3,00.");
            if (levelOffset < -100 || levelOffset > 100) throw new IllegalArgumentException("Ajuste de nível deve ficar entre -100 e 100.");
            if (minimumIv < 0 || minimumIv > 31) throw new IllegalArgumentException("IV mínimo deve ficar entre 0 e 31.");
            if (evPerStat < 0 || evPerStat > 85) throw new IllegalArgumentException("EV por atributo deve ficar entre 0 e 85.");
        }
    }

    private static List<String> normalize(List<String> values, String label) { return normalize(values, label, false); }

    private static List<String> normalize(List<String> values, String label, boolean ids) {
        Set<String> result = new TreeSet<>();
        for (String raw : values) {
            String value = ids ? trainerId(raw) : (raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT));
            if (value.isBlank() || value.length() > 128 || (!ids && !TRAINER_ID.matcher(value).matches()))
                throw new IllegalArgumentException("Entrada inválida para " + label + ".");
            result.add(value);
        }
        return new ArrayList<>(result);
    }

    public TrainerDifficultyConfig copy() {
        return TrainerDifficultyManager.gson().fromJson(TrainerDifficultyManager.gson().toJson(this), TrainerDifficultyConfig.class);
    }
}
