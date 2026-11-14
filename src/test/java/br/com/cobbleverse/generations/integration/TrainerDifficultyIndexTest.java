package br.com.cobbleverse.generations.integration;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

class TrainerDifficultyIndexTest {
    @Test
    void resolvesTrainerAndPlayerGroupsAndKeepsSpecificTrainerPriority() {
        UUID player = UUID.fromString("98ae5c76-19f9-4a34-8888-8d407c831741");
        TrainerDifficultyConfig config = new TrainerDifficultyConfig();
        TrainerDifficultyConfig.TrainerGroup named = trainerGroup("named", "rctmod:leader", "rctmod:brock");
        TrainerDifficultyConfig.TrainerGroup category = trainerGroup("category", "rctmod:ace", null);
        config.trainerGroups = List.of(named, category);
        TrainerDifficultyConfig.PlayerGroup players = new TrainerDifficultyConfig.PlayerGroup();
        players.id = "vip";
        players.name = "VIP";
        players.playerUuids = List.of(player.toString());
        config.playerGroups = List.of(players);

        TrainerDifficultyIndex index = TrainerDifficultyIndex.build(config);

        assertEquals("named", index.trainerGroup("RCTMOD:BROCK", "rctmod:ace"));
        assertEquals("category", index.trainerGroup("rctmod:other", "RCTMOD:ACE"));
        assertEquals("vip", index.playerGroup(player));
        assertNull(index.playerGroup(null));
    }

    @Test
    void resolvesExactPairThenTrainerThenPlayerThenDefault() {
        TrainerDifficultyConfig config = new TrainerDifficultyConfig();
        config.trainerGroups = List.of(trainerGroup("boss", "rctmod:leader", null));
        TrainerDifficultyConfig.PlayerGroup players = new TrainerDifficultyConfig.PlayerGroup();
        players.id = "vip";
        players.name = "VIP";
        config.playerGroups = List.of(players);
        TrainerDifficultyConfig.Profile trainerOnly = profile(2.0);
        TrainerDifficultyConfig.Profile playerOnly = profile(1.5);
        TrainerDifficultyConfig.Profile pair = profile(2.5);
        config.profiles = List.of(
                rule("boss", "*", trainerOnly),
                rule("*", "vip", playerOnly),
                rule("boss", "vip", pair));

        TrainerDifficultyIndex index = TrainerDifficultyIndex.build(config);

        assertSame(pair, index.resolveProfile("boss", "vip"));
        assertSame(trainerOnly, index.resolveProfile("boss", "other"));
        assertSame(playerOnly, index.resolveProfile("other", "vip"));
        assertSame(config.defaultProfile, index.resolveProfile(null, null));
    }

    private static TrainerDifficultyConfig.TrainerGroup trainerGroup(String id, String type, String trainerId) {
        TrainerDifficultyConfig.TrainerGroup group = new TrainerDifficultyConfig.TrainerGroup();
        group.id = id;
        group.name = id;
        group.trainerTypes = List.of(type);
        group.trainerIds = trainerId == null ? List.of() : List.of(trainerId);
        return group;
    }

    private static TrainerDifficultyConfig.ProfileRule rule(String trainerGroup, String playerGroup,
                                                              TrainerDifficultyConfig.Profile profile) {
        TrainerDifficultyConfig.ProfileRule rule = new TrainerDifficultyConfig.ProfileRule();
        rule.trainerGroup = trainerGroup;
        rule.playerGroup = playerGroup;
        rule.profile = profile;
        return rule;
    }

    private static TrainerDifficultyConfig.Profile profile(double multiplier) {
        TrainerDifficultyConfig.Profile profile = new TrainerDifficultyConfig.Profile();
        profile.levelMultiplier = multiplier;
        return profile;
    }
}
