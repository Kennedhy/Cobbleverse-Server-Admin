package br.com.cobbleverse.generations;

import br.com.cobbleverse.generations.integration.ReadOnlyStores;
import br.com.cobbleverse.generations.integration.StorePaths;
import br.com.cobbleverse.generations.integration.ValidationReport;
import java.nio.file.*;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class ReadOnlyStoresTest {
    @TempDir Path root;
    @Test void storePathsMatchBothSupportedLayoutsWithoutCreatingFolders() {
        UUID id=UUID.fromString("12345678-1234-1234-1234-123456789abc");
        assertEquals(root.resolve("playerpartystore/12/"+id+".nbt"),StorePaths.path(root.toString(),true,true,"nbt","PlayerPartyStore",id));
        assertEquals(root.resolve(id+"-pcstore.json"),StorePaths.path(root.toString(),false,false,"json","PCStore",id));
        assertFalse(Files.exists(root.resolve("playerpartystore")));
    }
    @Test void malformedAndMissingPrimaryFilesAreNeverRepairedOrDeleted() throws Exception {
        Path file=root.resolve("pokemon.nbt"), old=root.resolve("pokemon.nbt.old");
        Files.writeString(file,"damaged"); Files.writeString(old,"backup");
        assertThrows(IllegalStateException.class,() -> ReadOnlyStores.read(file,unused -> null));
        assertEquals("damaged",Files.readString(file)); assertEquals("backup",Files.readString(old));
        Files.delete(file);
        assertThrows(IllegalStateException.class,() -> ReadOnlyStores.read(file,unused -> "unexpected"));
        assertFalse(Files.exists(file)); assertEquals("backup",Files.readString(old));
        Files.delete(old);
        assertNull(ReadOnlyStores.read(file,unused -> "unexpected"));
        assertFalse(Files.exists(file));
    }
    @Test void incompleteImpactPreviewDoesNotReportPartialPlayerTotals() {
        var report=new ValidationReport();
        report.inspectedPlayers=10; report.affectedPlayers=3; report.playersWithoutUsablePokemon=1; report.affectedPokemon=5;
        report.affectedPlayerNames.add("player"); report.allowedSpecies=151;
        report.clearPlayerImpact();
        assertEquals(0,report.inspectedPlayers); assertEquals(0,report.affectedPlayers);
        assertEquals(0,report.playersWithoutUsablePokemon); assertEquals(0,report.affectedPokemon);
        assertTrue(report.affectedPlayerNames.isEmpty()); assertEquals(151,report.allowedSpecies);
    }
}
