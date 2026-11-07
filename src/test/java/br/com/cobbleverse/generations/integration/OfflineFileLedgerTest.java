package br.com.cobbleverse.generations.integration;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.concurrent.CompletionException;
import static org.junit.jupiter.api.Assertions.*;

class OfflineFileLedgerTest {
    @TempDir Path directory;
    @Test void detectsCreationOfPreviouslyAbsentStoreAndBackup() throws Exception {
        Path primary=directory.resolve("player.dat");
        var ledger=new OfflineFileLedger(); ledger.read(primary,file -> true);
        ledger.verify().join();
        Files.writeString(primary,"new store");
        assertThrows(CompletionException.class,()->ledger.verify().join());
        var second=new OfflineFileLedger(); second.read(primary,file -> true);
        Files.writeString(Path.of(primary+".old"),"new backup");
        assertThrows(CompletionException.class,()->second.verify().join());
    }
    @Test void detectsReplacementAfterRead() throws Exception {
        Path primary=directory.resolve("player.dat"); Files.writeString(primary,"one");
        var ledger=new OfflineFileLedger(); ledger.read(primary,file -> true);
        Files.writeString(primary,"different content");
        assertThrows(CompletionException.class,()->ledger.verify().join());
    }
}
