package br.com.cobbleverse.generations;

import br.com.cobbleverse.generations.config.GenerationConfig;
import br.com.cobbleverse.generations.rules.ExperienceRules;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ExperienceRulesTest {
    private static final String PLAYER="6a1938d1-a578-47df-8c3d-03f65c74b645";
    @Test void individualOverridesGroupAndGlobalStillApplies() {
        var c=new GenerationConfig(); c.globalXpMultiplier=2;
        c.groupXpMultipliers.put("vip",1.5); c.playerXpGroups.put(PLAYER,"vip");
        assertEquals(300,ExperienceRules.award(c,PLAYER,100,10000));
        c.playerXpMultipliers.put(PLAYER,0.5);
        assertEquals(100,ExperienceRules.award(c,PLAYER,100,10000));
        assertEquals(200,ExperienceRules.award(c,"unlisted",100,10000));
    }
    @Test void awardNeverExceedsRemainingOrOverflows() {
        var c=new GenerationConfig(); c.globalXpMultiplier=100; c.playerXpMultipliers.put(PLAYER,100.0);
        assertEquals(50,ExperienceRules.award(c,PLAYER,Integer.MAX_VALUE,50));
        assertEquals(0,ExperienceRules.award(c,PLAYER,100,-10));
        assertEquals(0,ExperienceRules.award(c,PLAYER,-100,100));
    }
    @Test void rejectsInvalidGroupsAndMultipliers() {
        var c=new GenerationConfig(); c.playerXpGroups.put(PLAYER,"missing");
        assertThrows(IllegalArgumentException.class,c::validated);
        c.playerXpGroups.clear(); c.globalXpMultiplier=Double.NaN;
        assertThrows(IllegalArgumentException.class,c::validated);
    }
    @Test void disablingManagerDisablesWildLevelControl() {
        var c=new GenerationConfig(); c.wildLevelBehavior="clamp";
        assertFalse(ExperienceRules.controlsWildLevels(c));
        c.manageExperience=true;
        assertTrue(ExperienceRules.controlsWildLevels(c));
        c.wildLevelBehavior="block"; assertTrue(ExperienceRules.controlsWildLevels(c));
        c.wildLevelBehavior="unchanged"; assertFalse(ExperienceRules.controlsWildLevels(c));
        assertFalse(ExperienceRules.controlsWildLevels(null));
    }

}
