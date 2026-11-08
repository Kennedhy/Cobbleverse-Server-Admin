package br.com.cobbleverse.generations.integration;

import com.google.gson.JsonParser;
import net.minecraft.nbt.NbtCompound;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class PcInspectionTest {
    @TempDir Path directory;

    @Test void readsOnlyTheRequestedBoxAndPreservesPositions() {
        var store = JsonParser.parseString("""
                {"BoxCount":2,"Box0":{},"Box1":{"BoxName":"Eventos","Slot11":{
                  "Species":"cobblemon:pikachu","FormId":"cosplay","Level":22,"Shiny":true,
                  "Nature":"hardy","Ability":{"Name":"static"},
                  "MoveSet":{"Moves":[{"Move":"thunderbolt"},{"Move":"quick-attack"}]},
                  "IVs":{"hp":31,"atk":28},"EVs":{"hp":252,"spe":252}
                }}}
                """).getAsJsonObject();
        assertTrue(PcInspection.jsonBox(store, 0).slots().isEmpty());
        var box = PcInspection.jsonBox(store, 1);
        assertEquals("Eventos", box.name());
        assertEquals(2, box.boxCount());
        var pokemon = box.slots().getFirst();
        assertEquals(new PcInspection.Slot(11, "cobblemon:pikachu", "cosplay", 22, true,
                "hardy", "static", List.of("thunderbolt", "quick-attack"),
                java.util.Map.of("hp", 31, "atk", 28), java.util.Map.of("hp", 252, "spe", 252)), pokemon);
    }

    @Test void readsNbtAndDistinguishesAMissingStore() throws Exception {
        var pokemon = new NbtCompound();
        pokemon.putString("Species", "cobblemon:bulbasaur");
        pokemon.putInt("Level", 7);
        pokemon.putString("Nature", "bold");
        pokemon.put("IVs", new NbtCompound());
        var rawBox = new NbtCompound();
        rawBox.put("Slot29", pokemon);
        var store = new NbtCompound();
        store.putInt("BoxCount", 1);
        store.put("Box0", rawBox);
        assertEquals(List.of(new PcInspection.Slot(29, "cobblemon:bulbasaur", "", 7, false,
                        "bold", "", List.of(), java.util.Map.of(), java.util.Map.of())),
                PcInspection.nbtBox(store, 0).slots());
        assertEquals("missing", PcInspection.savedBox(
                List.of(new PcInspection.FileStore(directory.resolve("absent.nbt"), true)), 0).source());
    }

    @Test void findsOfflineNamesFromTheCacheAndOmitsPlayersFromOtherWorlds() throws Exception {
        var offline = UUID.fromString("00000000-0000-0000-0000-000000000001");
        var elsewhere = UUID.fromString("00000000-0000-0000-0000-000000000002");
        Files.createFile(directory.resolve(offline + ".dat"));
        var cache = Files.writeString(directory.resolve("usercache.json"), """
                [{"name":"Misty","uuid":"%s"},{"name":"MistyElsewhere","uuid":"%s"}]
                """.formatted(offline, elsewhere));
        var players = PcInspection.players(new PcInspection.Directory(List.of(), directory, cache), "misty");
        assertEquals(List.of(new PcInspection.Player("Misty", offline, false)), players);
    }

    @Test void playerDirectoryUsesCursorPagesWithoutDuplicatesAndIncludesUnsavedOnlinePlayers() throws Exception {
        var saved = new java.util.ArrayList<UUID>();
        for (long i = 1; i <= 83; i++) {
            UUID id = new UUID(0, i);
            saved.add(id);
            Files.createFile(directory.resolve(id + ".dat"));
        }
        UUID onlineOnly = new UUID(0, 100);
        var live = new PcInspection.Player("OnlineOnly", onlineOnly, true);
        var source = new PcInspection.Directory(List.of(live), directory, directory.resolve("absent-usercache.json"));

        var result = new java.util.ArrayList<PcInspection.Player>();
        PcInspection.PlayerPage page = PcInspection.players(source, "", null);
        result.addAll(page.players());
        assertEquals(40, page.players().size());
        while (page.nextCursor() != null) {
            page = PcInspection.players(source, "", page.nextCursor());
            result.addAll(page.players());
        }

        assertEquals(84, result.size());
        assertEquals(84, result.stream().map(PcInspection.Player::uuid).distinct().count());
        assertTrue(result.contains(live));
        assertEquals(saved.size(), result.stream().filter(player -> !player.online()).count());
    }
}
