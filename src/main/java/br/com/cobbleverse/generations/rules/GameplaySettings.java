package br.com.cobbleverse.generations.rules;

import br.com.cobbleverse.generations.config.GenerationConfig;

import java.util.Map;

/** Immutable XP, wild-level and capture settings consumed directly by runtime hooks. */
public record GameplaySettings(boolean manageExperience, double globalXpMultiplier,
                               Map<String, Double> playerXpMultipliers,
                               Map<String, Double> groupXpMultipliers,
                               Map<String, String> playerXpGroups,
                               String levelCapMode, int globalLevelCap,
                               String wildLevelBehavior, double captureMultiplier) {
    public GameplaySettings {
        playerXpMultipliers = Map.copyOf(playerXpMultipliers);
        groupXpMultipliers = Map.copyOf(groupXpMultipliers);
        playerXpGroups = Map.copyOf(playerXpGroups);
    }

    public static GameplaySettings from(GenerationConfig config) {
        return new GameplaySettings(config.manageExperience, config.globalXpMultiplier,
                config.playerXpMultipliers, config.groupXpMultipliers, config.playerXpGroups,
                config.levelCapMode, config.globalLevelCap, config.wildLevelBehavior,
                config.captureMultiplier);
    }

    public boolean matches(GenerationConfig config) {
        return manageExperience == config.manageExperience
                && Double.compare(globalXpMultiplier, config.globalXpMultiplier) == 0
                && playerXpMultipliers.equals(config.playerXpMultipliers)
                && groupXpMultipliers.equals(config.groupXpMultipliers)
                && playerXpGroups.equals(config.playerXpGroups)
                && levelCapMode.equals(config.levelCapMode)
                && globalLevelCap == config.globalLevelCap
                && wildLevelBehavior.equals(config.wildLevelBehavior)
                && Double.compare(captureMultiplier, config.captureMultiplier) == 0;
    }

    public Map<String, Object> view() {
        return Map.of("manageExperience", manageExperience, "globalXpMultiplier", globalXpMultiplier,
                "playerXpMultipliers", playerXpMultipliers, "groupXpMultipliers", groupXpMultipliers,
                "playerXpGroups", playerXpGroups, "levelCapMode", levelCapMode,
                "globalLevelCap", globalLevelCap, "wildLevelBehavior", wildLevelBehavior,
                "captureMultiplier", captureMultiplier);
    }
}
