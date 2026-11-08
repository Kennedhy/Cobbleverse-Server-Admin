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

    @Test void revisionHistoryExplainsChangesAndCanStageAnOlderSnapshot() throws Exception {
        ConfigStore store = new ConfigStore(directory);
        store.open();
        GenerationConfig second = store.active();
        second.selectedGeneration = 2;
        store.stage(second, store.editRevision(), "moderator");
        GenerationConfig third = store.pending();
        third.mode = "cumulative";
        third.selectedGeneration = 3;
        third.generations.clear();
        third.generations.addAll(Set.of(1, 2, 3));
        store.stage(third, store.editRevision(), "owner");

        var recent = store.recentChanges();
        assertEquals(List.of(3L, 2L), recent.stream().map(ConfigStore.HistoryEntry::revision).toList());
        assertEquals("owner", recent.getFirst().author());
        var detail = store.historyDetail(2);
        assertTrue(detail.changes().stream().anyMatch(change -> change.field().equals("selectedGeneration")
                && change.before().equals("1") && change.after().equals("2")));
        assertFalse(detail.changes().stream().anyMatch(change -> change.field().equals("revision")));

        store.stageRevision(2, store.editRevision(), "owner");
        assertEquals(2, store.pending().selectedGeneration);
        assertTrue(store.pending().revision > 3);
    }

    @Test void asynchronousStageKeepsThePreviousSnapshotUntilTheGameThreadPublishesIt() throws Exception {
        ConfigStore store = new ConfigStore(directory);
        store.open();
        GenerationConfig candidate = store.active();
        candidate.selectedGeneration = 4;
        var io = java.util.concurrent.Executors.newSingleThreadExecutor();
        var serverQueue = new java.util.concurrent.ArrayBlockingQueue<Runnable>(1);
        try {
            var save = store.stageAsync(candidate, store.editRevision(), "web-panel", io, serverQueue::add);
            Runnable publish = serverQueue.poll(5, java.util.concurrent.TimeUnit.SECONDS);
            assertNotNull(publish, "I/O worker should enqueue a short game-thread publication");
            assertNull(store.pending(), "Disk completion must not publish before the server task runs");
            assertEquals(1, store.editRevision());
            publish.run();
            assertEquals(4, save.get(1, java.util.concurrent.TimeUnit.SECONDS).selectedGeneration);
            assertEquals(2, store.pending().revision);
        } finally {
            io.shutdownNow();
        }
    }

    @Test void namedPresetsAreBoundedWorldScopedAndValidatedOnRead() throws Exception {
        ConfigStore store = new ConfigStore(directory);
        store.open();
        GenerationConfig candidate = store.active();
        candidate.mode = "cumulative";
        candidate.selectedGeneration = 3;
        var saved = store.savePreset("Temporada Johto", candidate);
        assertEquals("temporada-johto", saved.id());
        assertEquals("Temporada Johto", saved.name());
        assertEquals(Set.of(1, 2, 3), store.preset(saved.id()).generations);
        assertEquals(List.of(saved.id()), store.listPresets().stream().map(ConfigStore.PresetInfo::id).toList());

        candidate.selectedGeneration = 2;
        store.savePreset("Temporada Johto", candidate);
        assertEquals(Set.of(1, 2), store.preset(saved.id()).generations);
        store.deletePreset(saved.id());
        assertTrue(store.listPresets().isEmpty());
        assertThrows(IllegalStateException.class, () -> store.preset(saved.id()));
    }

    @Test void asyncRestoreReadsHistoryOffThreadAndStagesANewRevision() throws Exception {
        ConfigStore store = new ConfigStore(directory);
        store.open();
        GenerationConfig second = store.active();
        second.selectedGeneration = 2;
        store.stage(second, store.editRevision(), "admin");
        store.activatePending("startup");

        var previous = store.stagePreviousAsync(store.editRevision(), "admin", Runnable::run, Runnable::run).join();
        assertEquals(Set.of(1), previous.generations);
        assertEquals(3, previous.revision);
        store.discardPending("admin");

        var historic = store.stageRevisionAsync(2, store.editRevision(), "admin", Runnable::run, Runnable::run).join();
        assertEquals(Set.of(2), historic.generations);
        assertTrue(historic.revision > 3);
    }

    @Test void asyncDiscardPublishesOnlyAfterPersistenceAndRecoversAnInterruptedIntent() throws Exception {
        ConfigStore store = new ConfigStore(directory);
        store.open();
        GenerationConfig candidate = store.active();
        candidate.selectedGeneration = 2;
        store.stage(candidate, store.editRevision(), "admin");
        long revision = store.editRevision();
        var result = store.discardPendingAsync(revision, "admin", Runnable::run, Runnable::run);
        assertEquals(revision + 1, result.join());
        assertNull(store.pending());
        assertFalse(Files.exists(directory.resolve("discard-transaction.json")));

        store.stage(candidate, store.editRevision(), "admin");
        long pendingRevision = store.pending().revision;
        ConfigStore.write(directory.resolve("discard-transaction.json"), Map.of(
                "pendingRevision", pendingRevision, "nextEditRevision", store.editRevision() + 1,
                "activeRevision", store.active().revision, "actor", "admin"));
        ConfigStore recovered = new ConfigStore(directory);
        recovered.open();
        assertNull(recovered.pending());
        assertEquals(pendingRevision + 1, recovered.editRevision());
        assertFalse(Files.exists(directory.resolve("discard-transaction.json")));
    }

    @Test void startupRebuildsHistoryAfterACommittedStageWasInterrupted() throws Exception {
        ConfigStore initial = new ConfigStore(directory);
        initial.open();
        GenerationConfig before = initial.active();
        GenerationConfig after = before.copy();
        after.selectedGeneration = 2;
        after.validated();
        after.revision = 2;
        String time = java.time.Instant.now().toString();
        ConfigStore.write(directory.resolve("history/revision-2.json"), after);
        ConfigStore.write(directory.resolve("pending.json"), after);
        ConfigStore.write(directory.resolve("stage-transaction.json"), Map.of(
                "next", after, "expectedRevision", 1, "previousActiveRevision", 1,
                "previousEditRevision", 1, "before", before, "actor", "web-panel", "time", time));

        ConfigStore recovered = new ConfigStore(directory);
        recovered.open();
        assertEquals(2, recovered.pending().revision);
        assertEquals("web-panel", recovered.historyDetail(2).author());
        assertFalse(Files.exists(directory.resolve("stage-transaction.json")));
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
