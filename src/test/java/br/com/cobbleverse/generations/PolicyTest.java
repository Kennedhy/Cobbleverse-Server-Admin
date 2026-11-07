package br.com.cobbleverse.generations;

import br.com.cobbleverse.generations.config.*;
import br.com.cobbleverse.generations.rules.GenerationRules;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class PolicyTest {
    @TempDir Path directory;

    @Test void nationalDexBoundariesIncludeHisuiInGenerationEight() {
        int[] starts = {1,152,252,387,494,650,722,810,906};
        int[] ends = {151,251,386,493,649,721,809,905,1025};
        for (int i = 0; i < starts.length; i++) {
            assertEquals(i+1, GenerationRules.generation(starts[i]));
            assertEquals(i+1, GenerationRules.generation(ends[i]));
        }
        assertEquals(0, GenerationRules.generation(0));
        assertEquals(0, GenerationRules.generation(1026));
    }

    @Test void onlyGenerationTwoDiffersFromCumulative() {
        GenerationConfig config = new GenerationConfig();
        config.selectedGeneration = 2;
        GenerationRules single = new GenerationRules(config);
        assertFalse(single.speciesAllowed("pikachu", 25));
        assertTrue(single.speciesAllowed("pichu", 172));
        assertEquals(Set.of("johto"), single.config().regions);
        config.mode = "cumulative";
        GenerationRules cumulative = new GenerationRules(config);
        assertTrue(cumulative.speciesAllowed("pikachu", 25));
        assertEquals(Set.of("kanto", "johto"), cumulative.config().regions);
    }

    @Test void impactComparisonUsesEffectiveGenerationsEvenForUnnormalizedCommandDraft() {
        var active=new GenerationConfig();
        var draft=active.copy();
        draft.selectedGeneration=2;
        assertEquals(Set.of(1),draft.generations,"command draft has not been normalized yet");
        assertFalse(new GenerationRules(active).sameSpeciesPolicy(new GenerationRules(draft)));
        draft.selectedGeneration=1;
        draft.globalXpMultiplier=3;
        assertTrue(new GenerationRules(active).sameSpeciesPolicy(new GenerationRules(draft)));
        draft.mode="cumulative"; draft.selectedGeneration=2;
        assertFalse(new GenerationRules(active).sameSpeciesPolicy(new GenerationRules(draft)));
    }

    @Test void validationFingerprintTracksTheExactDraft() {
        var draft=new GenerationConfig().validated();
        var original=ConfigStore.fingerprint(draft);
        assertEquals(original,ConfigStore.fingerprint(draft.copy()));
        draft.globalXpMultiplier=2;
        assertNotEquals(original,ConfigStore.fingerprint(draft));
    }

    @Test void formsAndExplicitExceptionsDoNotBypassSpeciesDenial() {
        GenerationConfig config = new GenerationConfig();
        GenerationRules rules = new GenerationRules(config);
        assertTrue(rules.allowed("raichu", 26, "Normal", Set.of()));
        assertFalse(rules.allowed("raichu", 26, "Alola", Set.of()));
        assertFalse(rules.allowed("charizard", 6, "Mega X", Set.of("mega-x")));
        config.allowForms.add("raichu/alola");
        assertTrue(new GenerationRules(config).allowed("raichu", 26, "Alola", Set.of()));
        config.denySpecies.add("raichu");
        assertFalse(new GenerationRules(config).allowed("raichu", 26, "Alola", Set.of()));
        assertFalse(rules.speciesAllowed("anothermod:bulbasaur", 1));
    }

    @Test void stagingIsIsolatedConcurrentSafeAndSurvivesRestart() throws Exception {
        ConfigStore store = new ConfigStore(directory);
        store.open();
        GenerationConfig next = store.active();
        next.selectedGeneration = 2;
        store.stage(next, 1, "admin");
        assertEquals(Set.of(1), store.active().generations);
        assertEquals(Set.of(2), store.pending().generations);
        assertThrows(IllegalStateException.class, () -> store.stage(next, 1, "outro"));
        ConfigStore restarted = new ConfigStore(directory);
        restarted.open();
        restarted.activatePending("startup");
        assertEquals(Set.of(2), restarted.active().generations);
        assertNull(restarted.pending());
        restarted.stagePrevious(2, "admin");
        assertEquals(Set.of(1), restarted.pending().generations);
        assertEquals(Set.of(2), restarted.active().generations);
    }

    @Test void malformedPolicyCannotBecomeActive() throws Exception {
        ConfigStore store = new ConfigStore(directory);
        store.open();
        GenerationConfig bad = store.active();
        bad.mode = "custom";
        bad.generations.clear();
        assertThrows(IllegalArgumentException.class, () -> store.stage(bad, 1, "admin"));
        assertNull(store.pending());
        Files.writeString(directory.resolve("pending.json"), "{invalid");
        ConfigStore recovered=new ConfigStore(directory); recovered.open();
        assertNull(recovered.pending()); assertEquals(Set.of(1),recovered.active().generations);
        assertNotNull(recovered.startupWarning());
        try(var rejected=Files.list(directory.resolve("rejected"))) { assertEquals(1,rejected.count()); }
        Files.writeString(directory.resolve("active.json"),"{invalid");
        assertThrows(java.io.IOException.class,()->new ConfigStore(directory).open());
    }

    @Test void discardingPendingDoesNotReuseAnOldEditRevision() throws Exception {
        ConfigStore store = new ConfigStore(directory);
        store.open();
        GenerationConfig candidate = store.active();
        store.stage(candidate, store.editRevision(), "first");
        long staleRevision = store.editRevision();
        store.discardPending("first");
        assertThrows(IllegalStateException.class, () -> store.stage(candidate, staleRevision, "second"));
        long token = store.editRevision();
        assertTrue(store.stage(candidate, token, "first").revision > token);
        ConfigStore reopened = new ConfigStore(directory);
        reopened.open();
        assertEquals(store.editRevision(), reopened.editRevision());
    }

    @Test void revertingMegaIsPermittedAndFormAliasesResolveConsistently() {
        GenerationConfig config=new GenerationConfig();
        GenerationRules rules=new GenerationRules(config);
        assertTrue(rules.allowed("charizard",6,"Normal",Set.of("mega_evolution=none")));
        assertFalse(rules.allowed("charizard",6,"Mega-X",Set.of()));
        config.allowForms.add("charizard/mega-x");
        assertTrue(new GenerationRules(config).allowed("charizard",6,"Mega X",Set.of()));
        config.allowForms.add("raichu/alolan");
        assertTrue(new GenerationRules(config).allowed("raichu",26,"Alola",Set.of()));
    }

    @Test void switchingFromAnEmptyCustomDraftCanSelectSingleOrCumulative() {
        GenerationConfig draft=new GenerationConfig();
        draft.mode="custom"; draft.generations.clear();
        assertThrows(IllegalArgumentException.class,() -> draft.copy().validated());
        draft.mode="single"; draft.selectedGeneration=2;
        assertEquals(Set.of(2),draft.copy().validated().generations);
        draft.mode="cumulative";
        assertEquals(Set.of(1,2),draft.copy().validated().generations);
    }

    @Test void aStandaloneCircuitDoesNotRequireAnExcludedGeneration() {
        var johto=com.google.gson.JsonParser.parseString("{\"requiredSeries\":[[\"kanto\"]]}").getAsJsonObject();
        var config=new GenerationConfig(); config.selectedGeneration=2;
        assertTrue(br.com.cobbleverse.generations.data.SeriesPolicy.apply("johto",johto,new GenerationRules(config)));
        assertTrue(johto.getAsJsonArray("requiredSeries").isEmpty());
        config.mode="cumulative";
        var cumulative=com.google.gson.JsonParser.parseString("{\"requiredSeries\":[[\"kanto\"]]}").getAsJsonObject();
        assertFalse(br.com.cobbleverse.generations.data.SeriesPolicy.apply("johto",cumulative,new GenerationRules(config)));
        assertEquals("kanto",cumulative.getAsJsonArray("requiredSeries").get(0).getAsJsonArray().get(0).getAsString());
    }

    @Test void visualFormFlagsUseTheEffectiveLearnsetAndLegacyMovesAreExcluded() {
        var catalog=new br.com.cobbleverse.generations.data.SpeciesCatalog();
        catalog.add("cobblemon",com.google.gson.JsonParser.parseString("{\"name\":\"Pikachu\",\"nationalPokedexNumber\":25,\"abilities\":[\"static\"],\"moves\":[\"1:thundershock\",\"tm:thunderbolt\",\"legacy:bouncybubble\"],\"forms\":[{\"name\":\"Surfing\",\"aspects\":[\"surfing-board\"],\"moves\":[\"1:surf\"],\"abilities\":[\"lightningrod\"]}]}").getAsJsonObject());
        assertFalse(catalog.resolve("pikachu surfing").entry().moves().contains("bouncybubble"));
        assertEquals("normal",catalog.resolve("pikachu surfing").form());
        assertEquals(Set.of("surf"),catalog.resolve("pikachu surfing-board").entry().moves());
        assertEquals("lightningrod",catalog.resolve("pikachu surfing-board").entry().ability());
        assertNull(catalog.resolve("pikachu form=missing"));
    }

    @Test void oversizedExceptionsCannotCreateAnUndeliverablePanelState() throws Exception {
        var store=new ConfigStore(directory); store.open();
        var candidate=store.active(); candidate.allowSpecies.add("a".repeat(40000));
        assertThrows(IllegalArgumentException.class,()->store.stage(candidate,store.editRevision(),"admin"));
        assertNull(store.pending()); assertEquals(1,store.active().revision);
    }
}
