package br.com.cobbleverse.generations.rules;

import br.com.cobbleverse.generations.config.GenerationConfig;

/** Deterministic arithmetic; configuration is validated once when loaded. */
public final class ExperienceRules {
    private ExperienceRules() {}
    /** The master switch disables both XP handling and wild-level adjustments. */
    public static boolean controlsWildLevels(GenerationConfig c) {
        return c != null && c.manageExperience && !c.wildLevelBehavior.equals("unchanged");
    }
    public static int award(GenerationConfig c, String playerId, int base, int remaining) {
        Double individual = c.playerXpMultipliers.get(playerId);
        String group = c.playerXpGroups.get(playerId);
        double extra = individual != null ? individual : group == null ? 1 : c.groupXpMultipliers.getOrDefault(group, 1.0);
        long scaled = Math.round(Math.max(0, base) * c.globalXpMultiplier * extra);
        return (int)Math.min(Math.max(0,remaining), Math.min(Integer.MAX_VALUE, scaled));
    }
}
