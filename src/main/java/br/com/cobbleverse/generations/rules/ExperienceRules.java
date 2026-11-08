package br.com.cobbleverse.generations.rules;

import br.com.cobbleverse.generations.config.GenerationConfig;

/** Deterministic arithmetic; configuration is validated once when loaded. */
public final class ExperienceRules {
    private ExperienceRules() {}
    public record Multiplier(double global, double player, double total, String source, String group) {}
    /** The master switch disables both XP handling and wild-level adjustments. */
    public static boolean controlsWildLevels(GenerationConfig c) {
        return c != null && c.manageExperience && !c.wildLevelBehavior.equals("unchanged");
    }
    public static boolean controlsWildLevels(GameplaySettings settings) {
        return settings != null && settings.manageExperience() && !settings.wildLevelBehavior().equals("unchanged");
    }
    public static int award(GenerationConfig c, String playerId, int base, int remaining) {
        return award(GameplaySettings.from(c), playerId, base, remaining);
    }
    public static int award(GameplaySettings settings, String playerId, int base, int remaining) {
        Multiplier value = multiplier(settings, playerId);
        long scaled = Math.round(Math.max(0, base) * value.global() * value.player());
        return (int)Math.min(Math.max(0,remaining), Math.min(Integer.MAX_VALUE, scaled));
    }

    public static Multiplier multiplier(GenerationConfig c, String playerId) {
        return multiplier(GameplaySettings.from(c), playerId);
    }

    public static Multiplier multiplier(GameplaySettings settings, String playerId) {
        String group = settings.playerXpGroups().get(playerId);
        Double individual = settings.playerXpMultipliers().get(playerId);
        if (individual != null) return new Multiplier(settings.globalXpMultiplier(), individual,
                settings.globalXpMultiplier() * individual, "individual", group);
        double groupValue = group == null ? 1.0 : settings.groupXpMultipliers().getOrDefault(group, 1.0);
        return new Multiplier(settings.globalXpMultiplier(), groupValue, settings.globalXpMultiplier() * groupValue,
                group == null ? "global" : "group", group);
    }
}
