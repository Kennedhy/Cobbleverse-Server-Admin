package br.com.cobbleverse.generations.integration;

import com.cobblemon.mod.common.api.reactive.ObservableSubscription;
import com.cobblemon.mod.common.api.storage.PokemonStore;
import java.util.UUID;

/** Constant-space change fence for a loaded store during one explicit preview. */
final class CacheWatch {
    static final class StoreKey {
        private final PokemonStore<?> store;
        StoreKey(PokemonStore<?> store) { this.store = store; }
        @Override public boolean equals(Object other) { return other instanceof StoreKey key && key.store == store; }
        @Override public int hashCode() { return System.identityHashCode(store); }
    }
    record StoreTarget(PokemonStore<?> store, boolean available) {
        StoreKey key() { return new StoreKey(store); }
    }

    static final class ChangeEpoch {
        private long changes;

        synchronized void changed(CacheWatch watch) {
            if (watch.changed) return;
            watch.changed = true;
            changes++;
        }

        synchronized long value() { return changes; }
    }

    private volatile boolean changed;
    private final UUID player;
    private final PokemonStore<?> store;
    private final boolean available;
    private final ObservableSubscription<?> subscription;

    CacheWatch(UUID player, PokemonStore<?> store, boolean available, ChangeEpoch epoch) {
        this.player = player;
        this.store = store;
        this.available = available;
        subscription = store.getAnyChangeObservable().subscribe(
                (java.util.function.Consumer<kotlin.Unit>) event -> epoch.changed(this));
    }

    UUID player() { return player; }
    PokemonStore<?> store() { return store; }
    boolean available() { return available; }
    StoreKey key() { return new StoreKey(store); }
    boolean dirty() { return changed; }

    void close() { subscription.unsubscribe(); }
}
