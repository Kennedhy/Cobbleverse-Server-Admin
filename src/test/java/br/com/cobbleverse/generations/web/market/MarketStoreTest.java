package br.com.cobbleverse.generations.web.market;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class MarketStoreTest {
    @TempDir Path directory;

    @Test
    void sortedListingsRefreshAfterUpdatesAndRollback() throws Exception {
        MarketStore store = new MarketStore(directory);
        store.update(state -> {
            MarketListing first = new MarketListing();
            first.id = "first";
            first.createdAt = 1;
            MarketListing second = new MarketListing();
            second.id = "second";
            second.createdAt = 2;
            state.listings.put(first.id, first);
            state.listings.put(second.id, second);
        });
        var before = store.listings();
        assertEquals("second", before.getFirst().id);
        assertSame(before, store.listings());
        assertThrows(IllegalStateException.class, () -> store.update(state -> {
            state.listings.get("first").createdAt = 3;
            state.listings.get("second").status = "sold";
            throw new IllegalStateException("Rollback");
        }));
        assertEquals("second", store.listings().getFirst().id);
        assertNull(store.listings().getFirst().status);
        assertNotSame(before.getFirst(), store.listings().getFirst());
        store.update(state -> state.listings.get("first").createdAt = 4);
        assertEquals("first", store.listings().getFirst().id);
        assertEquals("first", new MarketStore(directory).listings().getFirst().id);
    }

    @Test
    void invalidSettingsAreRejectedWithoutReplacingSavedSettings() throws Exception {
        MarketStore store = new MarketStore(directory);
        MarketSettings valid = store.settings();
        valid.maxListingsPerPlayer = 12;
        store.updateSettings(valid);

        MarketSettings invalid = store.settings();
        invalid.maxListingsPerPlayer = 51;
        assertThrows(IllegalArgumentException.class, () -> store.updateSettings(invalid));

        assertEquals(12, store.settings().maxListingsPerPlayer);
        assertEquals(12, new MarketStore(directory).settings().maxListingsPerPlayer);
    }

    @Test
    void failedMutationRollsBackMemoryAndDiskState() throws Exception {
        MarketStore store = new MarketStore(directory);
        String before = Files.readString(directory.resolve("market.json"));

        assertThrows(IllegalStateException.class, () -> store.update(state -> {
            for (int index = 0; index <= MarketStore.MAX_LISTINGS; index++) {
                MarketListing listing = new MarketListing();
                listing.id = "listing-" + index;
                state.listings.put(listing.id, listing);
            }
        }));

        assertTrue(store.listings().isEmpty());
        assertEquals(before, Files.readString(directory.resolve("market.json")));
        assertTrue(new MarketStore(directory).listings().isEmpty());
    }

    @Test
    void transactionCompactionNeverRemovesPendingOperations() throws Exception {
        MarketStore store = new MarketStore(directory);
        store.update(state -> {
            for (int index = 0; index < MarketStore.MAX_TRANSACTIONS; index++) {
                MarketTransaction transaction = new MarketTransaction();
                transaction.id = "transaction-" + index;
                transaction.status = index == 0 ? "buyer_debit_pending" : "complete";
                state.transactions.add(transaction);
            }
        });

        store.ensureFreeTransactions(2);

        assertEquals(MarketStore.MAX_TRANSACTIONS - 2, store.transactions().size());
        assertTrue(store.transactions().stream().anyMatch(tx -> "transaction-0".equals(tx.id)
                && "buyer_debit_pending".equals(tx.status)));
        assertTrue(store.transactions().stream().noneMatch(tx -> "transaction-1".equals(tx.id)));
        assertEquals(store.transactions().size(), new MarketStore(directory).transactions().size());
    }

    @Test
    void transactionCompactionFailsWhenOnlyPendingOperationsRemain() throws Exception {
        MarketStore store = new MarketStore(directory);
        store.update(state -> {
            for (int index = 0; index < MarketStore.MAX_TRANSACTIONS; index++) {
                MarketTransaction transaction = new MarketTransaction();
                transaction.id = "pending-" + index;
                transaction.status = "claim_pending";
                state.transactions.add(transaction);
            }
        });

        assertThrows(IllegalStateException.class, () -> store.ensureFreeTransactions(1));
        assertEquals(MarketStore.MAX_TRANSACTIONS, store.transactions().size());
        assertTrue(store.transactions().stream().allMatch(tx -> "claim_pending".equals(tx.status)));
        assertEquals(MarketStore.MAX_TRANSACTIONS, new MarketStore(directory).transactions().size());
    }
}
