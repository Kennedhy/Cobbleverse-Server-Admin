package br.com.cobbleverse.generations.integration;

import com.cobblemon.mod.common.api.storage.PokemonStore;
import com.cobblemon.mod.common.api.reactive.ObservableSubscription;
import com.cobblemon.mod.common.pokemon.Pokemon;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;

/** Tracks only the fields that can affect the generation preview. */
final class CacheWatch {
    private record Signature(UUID id,String species,int number,String form,Set<String> aspects) {}
    private final PokemonStore<?> store;
    private final Map<Signature,Integer> expected=new HashMap<>();
    private final AtomicLong changes=new AtomicLong();
    private final ObservableSubscription<?> subscription;
    private long checked,readingVersion; private Iterator<Pokemon> iterator; private Map<Signature,Integer> actual;
    CacheWatch(PokemonStore<?> store) {
        this.store=store;
        subscription=store.getAnyChangeObservable().subscribe((java.util.function.Consumer<kotlin.Unit>)event -> changes.incrementAndGet());
    }
    void observed(Pokemon pokemon) { expected.merge(signature(pokemon),1,Integer::sum); }
    boolean dirty() { return changes.get()!=checked; }
    boolean step() {
        if(iterator==null) {
            readingVersion=changes.get(); actual=new HashMap<>(); iterator=store.iterator();
        }
        int budget=12; long end=System.nanoTime()+3_000_000L;
        while(iterator.hasNext() && budget-->0 && System.nanoTime()<end) actual.merge(signature(iterator.next()),1,Integer::sum);
        if(iterator.hasNext()) return false;
        iterator=null;
        if(readingVersion!=changes.get()) return false;
        if(!expected.equals(actual)) throw new IllegalStateException("Espécies, formas ou composição do armazenamento mudaram durante a análise; valide novamente.");
        checked=readingVersion; return true;
    }
    void close() { subscription.unsubscribe(); }
    private static Signature signature(Pokemon pokemon) {
        var species=pokemon.getSpecies();
        return new Signature(pokemon.getUuid(),species.getResourceIdentifier().toString(),species.getNationalPokedexNumber(),pokemon.getForm().getName(),Set.copyOf(pokemon.getAspects()));
    }
}
