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
                  "Species":"cobblemon:pikachu","FormId":"cosplay","Level":22,"Shiny":true
                }}}
                """).getAsJsonObject();
        assertTrue(PcInspection.jsonBox(store, 0).slots().isEmpty());
        var box = PcInspection.jsonBox(store, 1);
        assertEquals("Eventos", box.name());
        assertEquals(2, box.boxCount());
        assertEquals(List.of(new PcInspection.Slot(11, "cobblemon:pikachu", "cosplay", 22, true)), box.slots());
    }

    @Test void readsNbtAndDistinguishesAMissingStore() throws Exception {
        var pokemon = new NbtCompound();
        pokemon.putString("Species", "cobblemon:bulbasaur");
        pokemon.putInt("Level", 7);
        var rawBox = new NbtCompound();
        rawBox.put("Slot29", pokemon);
        var store = new NbtCompound();
        store.putInt("BoxCount", 1);
        store.put("Box0", rawBox);
        assertEquals(List.of(new PcInspection.Slot(29, "cobblemon:bulbasaur", "", 7, false)),
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
}
