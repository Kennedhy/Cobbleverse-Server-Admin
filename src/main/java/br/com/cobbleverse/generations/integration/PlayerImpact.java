package br.com.cobbleverse.generations.integration;

import br.com.cobbleverse.generations.GenerationControl;
import br.com.cobbleverse.generations.rules.GenerationRules;
import com.cobblemon.mod.common.Cobblemon;
import com.cobblemon.mod.common.pokemon.Pokemon;
import com.cobblemon.mod.common.api.storage.PokemonStore;
import com.cobblemon.mod.common.api.storage.party.PlayerPartyStore;
import com.cobblemon.mod.common.api.storage.pc.PCStore;
import com.cobblemon.mod.common.api.storage.factory.FileBackedPokemonStoreFactory;
import com.cobblemon.mod.common.api.storage.adapter.flatfile.OneToOneFileStoreAdapter;
import br.com.cobbleverse.generations.mixin.StoreCacheAccessor;
import br.com.cobbleverse.generations.mixin.StoreFactoryAccessor;
import br.com.cobbleverse.generations.mixin.StoreManagerAccessor;
import br.com.cobbleverse.generations.mixin.StorePathAccessor;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.WorldSavePath;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;

/** Explicit admin preview only. Never runs on a tick or changes a captured Pokemon. */
public final class PlayerImpact {
    private PlayerImpact() {}
    public static void inspect(MinecraftServer server,GenerationRules rules,ValidationReport report) {
        if(server==null) { report.errors.add("Servidor ainda não disponível para calcular o impacto."); return; }
        Set<UUID> players=new TreeSet<>();
        server.getPlayerManager().getPlayerList().forEach(p -> players.add(p.getUuid()));
        Path playerData=server.getSavePath(WorldSavePath.PLAYERDATA);
        try {
            if(Files.isDirectory(playerData)) try(var paths=Files.list(playerData)) {
                paths.filter(p -> p.getFileName().toString().endsWith(".dat")).forEach(p -> {
                    try { players.add(UUID.fromString(p.getFileName().toString().replace(".dat",""))); }
                    catch(IllegalArgumentException ignored) { /* not a vanilla UUID data file */ }
                });
            }
            // Large servers need a batched offline scanner rather than loading thousands of PC stores in one request.
            if(players.size()>500) { report.errors.add("Prévia acima de 500 jogadores exige análise em lotes; ativação recusada para evitar um relatório parcial."); return; }
            List<FileBackedPokemonStoreFactory<?>> factories=providers();
            // A disconnect can queue a write before evicting its cache. Do not read an
            // older file while that write is pending; never wait indefinitely on a tick.
            for(var factory:factories) {
                var barrier=((StoreFactoryAccessor)(Object)factory).generationControl$executor().submit(() -> {});
                try { barrier.get(25,TimeUnit.MILLISECONDS); }
                catch(java.util.concurrent.TimeoutException busy) {
                    barrier.cancel(false);
                    throw new IllegalStateException("O armazenamento está ocupado salvando dados. Aguarde e valide novamente.");
                }
            }
            for(UUID id:players) {
                inspectPlayer(server,rules,report,factories,id);
            }
            report.warnings.add("Prévia: equipe e PCs dos "+report.inspectedPlayers+" jogadores conectados ou com playerdata neste mundo. Arquivos offline são apenas lidos; dados em cache usam o estado atual.");
        } catch(Exception e) {
            report.clearPlayerImpact();
            report.errors.add("Não foi possível concluir a prévia dos jogadores: "+e.getMessage());
            report.warnings.add("Contagens parciais de jogadores foram descartadas; nenhuma alteração pode ser salva até concluir a prévia.");
            GenerationControl.LOGGER.error("Falha na prévia de impacto.",e);
        }
    }
    static List<FileBackedPokemonStoreFactory<?>> providers() {
        List<FileBackedPokemonStoreFactory<?>> factories=new ArrayList<>();
        for(var factory:((StoreManagerAccessor)(Object)Cobblemon.INSTANCE.getStorage()).generationControl$factories()) {
            if(!(factory instanceof FileBackedPokemonStoreFactory<?> files))
                throw new IllegalStateException("Provedor de armazenamento não suportado para prévia segura: "+factory.getClass().getName());
            var adapter=((StoreFactoryAccessor)(Object)files).generationControl$adapter();
            if(adapter.getClass()!=com.cobblemon.mod.common.api.storage.adapter.flatfile.NBTStoreAdapter.class
                && adapter.getClass()!=com.cobblemon.mod.common.api.storage.adapter.flatfile.JSONStoreAdapter.class)
                throw new IllegalStateException("Adaptador de armazenamento não suportado para prévia segura: "+adapter.getClass().getName());
            factories.add(files);
        }
        if(factories.isEmpty()) throw new IllegalStateException("Nenhum provedor de armazenamento disponível para a prévia.");
        return List.copyOf(factories);
    }
    static void inspectPlayer(MinecraftServer server,GenerationRules rules,ValidationReport report,List<FileBackedPokemonStoreFactory<?>> factories,UUID id) throws Exception {
        Set<UUID> seen=new HashSet<>(); int[] counts={0,0};
        for(var factory:factories) {
            inspectStore(factory,PlayerPartyStore.class,id,server,rules,seen,counts);
            inspectStore(factory,PCStore.class,id,server,rules,seen,counts);
        }
        report.inspectedPlayers++;
        if(counts[1]>0) {
            report.affectedPlayers++; report.affectedPokemon+=counts[1];
            var player=server.getPlayerManager().getPlayer(id);
            if(report.affectedPlayerNames.size()<30) report.affectedPlayerNames.add(player==null ? id.toString() : player.getGameProfile().getName());
        }
        if(counts[0]>0 && counts[0]==counts[1]) report.playersWithoutUsablePokemon++;
    }
    private static <E extends com.cobblemon.mod.common.api.storage.StorePosition,T extends PokemonStore<E>> void inspectStore(FileBackedPokemonStoreFactory<?> factory,Class<T> type,UUID id,
            MinecraftServer server,GenerationRules rules,Set<UUID> seen,int[] counts) throws Exception {
        var access=(StoreFactoryAccessor)(Object)factory;
        Object cache=access.generationControl$caches().get(type);
        PokemonStore<?> store=cache==null ? null : ((StoreCacheAccessor)cache).generationControl$cacheMap().get(id);
        if(store==null) {
            var adapter=(OneToOneFileStoreAdapter<?>)access.generationControl$adapter();
            var pathInfo=(StorePathAccessor)(Object)adapter;
            Path path=StorePaths.path(pathInfo.generationControl$root(),pathInfo.generationControl$nested(),pathInfo.generationControl$perClass(),
                pathInfo.generationControl$extension(),type.getSimpleName(),id);
            // Calling adapter.load(Class, ...) invokes Cobblemon's recovery routine,
            // which may delete or replace damaged files. Load the specific file only.
            store=ReadOnlyStores.read(path,file -> adapter.load(file,type,id,server.getRegistryManager()));
            if(store==null) return;
            store.initialize();
        }
        for(Pokemon pokemon:store) count(pokemon,rules,seen,counts);
    }
    private static void count(Pokemon pokemon,GenerationRules rules,Set<UUID> seen,int[] counts) {
        if(!seen.add(pokemon.getUuid())) return;
        counts[0]++;
        var species=pokemon.getSpecies();
        if(rules.restrictExisting() && !rules.allowed(species.getResourceIdentifier().toString(),species.getNationalPokedexNumber(),pokemon.getForm().getName(),pokemon.getAspects())) counts[1]++;
    }
}
