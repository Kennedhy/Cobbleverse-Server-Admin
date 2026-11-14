package br.com.cobbleverse.generations.integration;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** Immutable lookup tables built when the trainer policy changes, never during a battle. */
final class TrainerDifficultyIndex {
    private final TrainerDifficultyConfig config;
    private final Map<String, String> trainerIds;
    private final Map<String, String> trainerTypes;
    private final Map<String, String> playerUuids;
    private final Map<ProfileKey, TrainerDifficultyConfig.Profile> exactProfiles;
    private final Map<String, TrainerDifficultyConfig.Profile> trainerProfiles;
    private final Map<String, TrainerDifficultyConfig.Profile> playerProfiles;

    private TrainerDifficultyIndex(TrainerDifficultyConfig config) {
        this.config = config;
        Map<String, String> trainers = new HashMap<>();
        Map<String, String> types = new HashMap<>();
        Map<String, String> players = new HashMap<>();
        Map<ProfileKey, TrainerDifficultyConfig.Profile> exact = new HashMap<>();
        Map<String, TrainerDifficultyConfig.Profile> byTrainer = new HashMap<>();
        Map<String, TrainerDifficultyConfig.Profile> byPlayer = new HashMap<>();

        for (var group : config.trainerGroups) {
            group.trainerIds.forEach(id -> trainers.put(id, group.id));
            group.trainerTypes.forEach(type -> types.put(type, group.id));
        }
        for (var group : config.playerGroups)
            group.playerUuids.forEach(uuid -> players.put(uuid, group.id));
        for (var rule : config.profiles) {
            if (!"*".equals(rule.trainerGroup) && !"*".equals(rule.playerGroup))
                exact.put(new ProfileKey(rule.trainerGroup, rule.playerGroup), rule.profile);
            else if (!"*".equals(rule.trainerGroup)) byTrainer.put(rule.trainerGroup, rule.profile);
            else if (!"*".equals(rule.playerGroup)) byPlayer.put(rule.playerGroup, rule.profile);
        }

        this.trainerIds = Map.copyOf(trainers);
        this.trainerTypes = Map.copyOf(types);
        this.playerUuids = Map.copyOf(players);
        this.exactProfiles = Map.copyOf(exact);
        this.trainerProfiles = Map.copyOf(byTrainer);
        this.playerProfiles = Map.copyOf(byPlayer);
    }

    static TrainerDifficultyIndex build(TrainerDifficultyConfig config) {
        return new TrainerDifficultyIndex(config.validated());
    }

    TrainerDifficultyConfig config() { return config; }

    String trainerGroup(String trainerId, String type) {
        String group = trainerId == null ? null : trainerIds.get(trainerId.toLowerCase(java.util.Locale.ROOT));
        return group != null ? group : trainerTypes.get(type == null ? "unknown" : type.toLowerCase(java.util.Locale.ROOT));
    }

    String playerGroup(UUID player) {
        return player == null ? null : playerUuids.get(player.toString());
    }

    TrainerDifficultyConfig.Profile resolveProfile(String trainerGroup, String playerGroup) {
        TrainerDifficultyConfig.Profile profile = exactProfiles.get(new ProfileKey(
                trainerGroup == null ? "*" : trainerGroup,
                playerGroup == null ? "*" : playerGroup));
        if (profile != null) return profile;
        if (trainerGroup != null && (profile = trainerProfiles.get(trainerGroup)) != null) return profile;
        if (playerGroup != null && (profile = playerProfiles.get(playerGroup)) != null) return profile;
        return config.defaultProfile;
    }

    private record ProfileKey(String trainerGroup, String playerGroup) {}
}
