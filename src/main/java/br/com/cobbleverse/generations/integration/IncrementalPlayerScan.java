package br.com.cobbleverse.generations.integration;

import br.com.cobbleverse.generations.mixin.*;
import br.com.cobbleverse.generations.rules.GenerationRules;
import com.cobblemon.mod.common.api.storage.PokemonStore;
import com.cobblemon.mod.common.api.storage.party.PlayerPartyStore;
import com.cobblemon.mod.common.api.storage.pc.PCStore;
import com.cobblemon.mod.common.api.storage.factory.FileBackedPokemonStoreFactory;
import com.cobblemon.mod.common.api.storage.adapter.flatfile.NBTStoreAdapter;
import com.cobblemon.mod.common.pokemon.Pokemon;
import com.google.gson.*;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
import java.util.concurrent.*;
import net.minecraft.nbt.*;
import net.minecraft.server.MinecraftServer;

/** Reads files off-thread and decodes a small number of Pokémon per server tick. */
final class IncrementalPlayerScan {
    private static final int MAX_STORE_SNAPSHOT_ENTRIES_PER_PLAYER = 10_000;
    private record FileTask(Path path,boolean nbt,boolean pc) {}
    private record Entry(Object value,boolean available,Object source) {}
    private record LiveSource(Iterator<Pokemon> iterator,boolean available,Object source) {}
    @FunctionalInterface interface WatchFactory { CacheWatch apply(UUID player,PokemonStore<?> store,boolean available); }
    private final UUID id; private final MinecraftServer server; private final GenerationRules rules;
    private final ValidationReport report; private final Set<UUID> seen=new HashSet<>(),availableSeen=new HashSet<>(),reserveSeen=new HashSet<>();
    private final List<LiveSource> live=new ArrayList<>(); private final CompletableFuture<List<Entry>> offline;
    private Iterator<Entry> rawIterator; private int liveIndex,count,restricted,available,availableRestricted,reserve;
    private boolean hasLiveStore, targeted, snapshotOverflow;
    private final java.util.function.Consumer<Pokemon> pokemonObserver;
    private Map<Object,LinkedHashMap<UUID,Byte>> storeImpacts;
    private int storeImpactEntries;
    private List<ValidationReport.StoreImpact> previousStoreImpacts;
    private final WatchFactory watch;

    IncrementalPlayerScan(MinecraftServer server, List<FileBackedPokemonStoreFactory<?>> factories, UUID id,
                          OfflineFileLedger ledger, ValidationIo io, java.util.function.Consumer<Pokemon> pokemonObserver) {
        this(server, null, null, factories, id, (player, store, available) -> null, ledger,
                (player, stillCurrent) -> {}, io, false, pokemonObserver);
    }

    IncrementalPlayerScan(MinecraftServer server,GenerationRules rules,ValidationReport report,
                          List<FileBackedPokemonStoreFactory<?>> factories,UUID id,WatchFactory watch,
                          OfflineFileLedger ledger,java.util.function.BiConsumer<UUID,java.util.function.BooleanSupplier> cacheGuard,
                          ValidationIo io,boolean cacheStoreImpacts) {
        this(server,rules,report,factories,id,watch,ledger,cacheGuard,io,cacheStoreImpacts,null);
    }

    private IncrementalPlayerScan(MinecraftServer server,GenerationRules rules,ValidationReport report,
                          List<FileBackedPokemonStoreFactory<?>> factories,UUID id,WatchFactory watch,
                          OfflineFileLedger ledger,java.util.function.BiConsumer<UUID,java.util.function.BooleanSupplier> cacheGuard,
                          ValidationIo io,boolean cacheStoreImpacts,java.util.function.Consumer<Pokemon> pokemonObserver) {
        this.id=id; this.server=server; this.rules=rules; this.report=report; this.watch=watch;
        this.pokemonObserver=pokemonObserver;
        storeImpacts=cacheStoreImpacts ? new LinkedHashMap<>() : null;
        List<FileTask> tasks=new ArrayList<>();
        for(var factory:factories) {
            var access=(StoreFactoryAccessor)(Object)factory;
            var adapter=access.generationControl$adapter();
            var info=(StorePathAccessor)(Object)adapter;
            for(Class<?> type:List.of(PlayerPartyStore.class,PCStore.class)) {
                Object cache=access.generationControl$caches().get(type);
                PokemonStore<?> store=cache==null ? null : ((StoreCacheAccessor)cache).generationControl$cacheMap().get(id);
                if(store!=null) {
                    hasLiveStore=true;
                    cacheGuard.accept(id,() -> {
                        Object current=access.generationControl$caches().get(type);
                        return current!=null && ((StoreCacheAccessor)current).generationControl$cacheMap().get(id)==store;
                    });
                    watch.apply(id,store,true);
                    var source=new CacheWatch.StoreKey(store);
                    live.add(new LiveSource(store.iterator(),true,source));
                    if(store instanceof PCStore pc) {
                        PokemonStore<?> backup=pc.getBackupStore();
                        watch.apply(id,backup,false);
                        var backupSource=new CacheWatch.StoreKey(backup);
                        live.add(new LiveSource(backup.iterator(),false,backupSource));
                    }
                } else {
                    cacheGuard.accept(id,() -> {
                        Object current=access.generationControl$caches().get(type);
                        return current==null || ((StoreCacheAccessor)current).generationControl$cacheMap().get(id)==null;
                    });
                    Path path=StorePaths.path(info.generationControl$root(),info.generationControl$nested(),info.generationControl$perClass(),info.generationControl$extension(),type.getSimpleName(),id);
                    tasks.add(new FileTask(path,adapter instanceof NBTStoreAdapter,type==PCStore.class));
                }
            }
        }
        for(LiveSource source:live) registerImpactSource(source.source());
        for(FileTask task:tasks) registerImpactSource(task.path());
        offline=io.submit(()-> {
            List<Entry> found=new ArrayList<>();
            try { for(FileTask task:tasks) { ValidationIo.checkCancelled(); read(task,found,ledger); } }
            catch(Exception e) { throw new CompletionException(e); }
            return found;
        });
    }

    IncrementalPlayerScan(MinecraftServer server,GenerationRules rules,ValidationReport report,UUID id,
                          WatchFactory watch,ValidationIo io,List<ValidationReport.StoreImpact> previousStoreImpacts,
                          Collection<CacheWatch.StoreTarget> targets) {
        this.id=id; this.server=server; this.rules=rules; this.report=report; this.watch=watch;
        this.pokemonObserver=null;
        this.targeted=true;
        this.previousStoreImpacts=List.copyOf(previousStoreImpacts);
        this.storeImpacts=new LinkedHashMap<>();
        for(CacheWatch.StoreTarget target:targets) {
            CacheWatch.StoreKey key=target.key();
            registerImpactSource(key);
            watch.apply(id,target.store(),target.available());
            live.add(new LiveSource(target.store().iterator(),target.available(),key));
        }
        this.hasLiveStore=true;
        this.offline=CompletableFuture.completedFuture(List.of());
    }

    boolean step(long deadline) throws Exception {
        int budget=12;
        while(budget-->0 && System.nanoTime()<deadline) {
            Entry entry;
            if(liveIndex<live.size()) {
                LiveSource source=live.get(liveIndex);
                if(source.iterator().hasNext()) entry=new Entry(source.iterator().next(),source.available(),source.source());
                else { liveIndex++; continue; }
            }
            else {
                if(rawIterator==null) { if(!offline.isDone()) return false; rawIterator=offline.get().iterator(); }
                if(!rawIterator.hasNext()) { finish(); return true; }
                entry=rawIterator.next();
            }
            Object raw=entry.value(); Pokemon pokemon;
            try { pokemon=raw instanceof Pokemon mon ? mon : raw instanceof NbtCompound nbt ? Pokemon.Companion.loadFromNBT(server.getRegistryManager(),nbt) : Pokemon.Companion.loadFromJSON(server.getRegistryManager(),(JsonObject)raw); }
            catch(Exception e) { throw new IllegalStateException("Pokémon ilegível no armazenamento de "+id+"; dados preservados.",e); }
            if(pokemon==null) throw new IllegalStateException("Pokémon inválido no armazenamento de "+id);
            if(pokemonObserver!=null) {
                pokemonObserver.accept(pokemon);
                continue;
            }
            var species=pokemon.getSpecies();
            boolean blocked=rules.restrictExisting() && !rules.allowed(species.getResourceIdentifier().toString(),species.getNationalPokedexNumber(),pokemon.getForm().getName(),pokemon.getAspects());
            recordStoreImpact(entry.source(),pokemon.getUuid(),blocked,entry.available());
            if(targeted && snapshotOverflow) return true;
            if(!targeted) {
                if(seen.add(pokemon.getUuid())) { count++; if(blocked) restricted++; }
                if(entry.available() && availableSeen.add(pokemon.getUuid())) { available++; if(blocked) availableRestricted++; }
                if(!entry.available() && reserveSeen.add(pokemon.getUuid())) reserve++;
            }
        }
        return false;
    }
    private void finish() {
        if(pokemonObserver!=null) return;
        var player=server.getPlayerManager().getPlayer(id);
        String name=player==null ? id.toString() : player.getGameProfile().getName();
        if(targeted) report.replaceStoreImpacts(id,replaceTargetedStoreImpacts(),name);
        else report.recordPlayerImpact(id,count,restricted,available,availableRestricted,reserve,name,hasLiveStore,snapshotView());
    }

    private void recordStoreImpact(Object source,UUID pokemon,boolean blocked,boolean isAvailable) {
        if(storeImpacts==null) return;
        LinkedHashMap<UUID,Byte> entries=storeImpacts.computeIfAbsent(source,key -> new LinkedHashMap<>());
        Byte oldValue=entries.get(pokemon);
        int old=oldValue==null ? 0 : oldValue;
        int flags=old;
        if((old&ValidationReport.SEEN)==0) flags|=ValidationReport.SEEN|(blocked ? ValidationReport.SEEN_RESTRICTED : 0);
        if(isAvailable && (old&ValidationReport.AVAILABLE)==0) flags|=ValidationReport.AVAILABLE|(blocked ? ValidationReport.AVAILABLE_RESTRICTED : 0);
        if(!isAvailable) flags|=ValidationReport.RESERVE;
        entries.put(pokemon,(byte)flags);
        if(oldValue==null && ++storeImpactEntries>MAX_STORE_SNAPSHOT_ENTRIES_PER_PLAYER) {
            storeImpacts.clear(); storeImpacts=null; snapshotOverflow=true;
        }
    }

    private void registerImpactSource(Object source) {
        if(storeImpacts!=null) storeImpacts.putIfAbsent(source,new LinkedHashMap<>());
    }

    private List<ValidationReport.StoreImpact> snapshotView() {
        if(storeImpacts==null) return null;
        List<ValidationReport.StoreImpact> result=new ArrayList<>(storeImpacts.size());
        storeImpacts.forEach((source,entries) -> result.add(new ValidationReport.StoreImpact(source,entries)));
        return result;
    }

    private List<ValidationReport.StoreImpact> replaceTargetedStoreImpacts() {
        if(storeImpacts==null) throw new IllegalStateException("Snapshot temporário de store incompleto.");
        Map<Object,ValidationReport.StoreImpact> changed=new HashMap<>();
        storeImpacts.forEach((source,entries) -> changed.put(source,new ValidationReport.StoreImpact(source,entries)));
        List<ValidationReport.StoreImpact> result=new ArrayList<>(previousStoreImpacts.size());
        for(ValidationReport.StoreImpact previous:previousStoreImpacts) {
            ValidationReport.StoreImpact replacement=changed.remove(previous.source());
            result.add(replacement==null ? previous : replacement);
        }
        if(!changed.isEmpty()) throw new IllegalStateException("Store alterado não estava no snapshot original do jogador.");
        if(!storeImpacts.keySet().stream().allMatch(key -> previousStoreImpacts.stream().anyMatch(item -> Objects.equals(item.source(),key))))
            throw new IllegalStateException("Não foi possível associar o store reavaliado ao snapshot original.");
        return result;
    }

    boolean targeted() { return targeted; }
    boolean snapshotOverflow() { return snapshotOverflow; }
    UUID playerId() { return id; }
    void cancel() { offline.cancel(false); }
    private static void read(FileTask task,List<Entry> out,OfflineFileLedger ledger) throws Exception {
        ValidationIo.checkCancelled();
        Path p=task.path();
        ledger.read(p,file -> {
            BasicFileAttributes before=Files.readAttributes(p,BasicFileAttributes.class);
            if(before.size()>64L*1024*1024) throw new IllegalStateException("Arquivo excede 64 MiB: "+p);
            if(task.nbt()) {
                NbtCompound root=NbtIo.readCompressed(p,NbtSizeTracker.of(128L*1024*1024));
                if(root==null) throw new IllegalStateException("NBT vazio: "+p);
                collectNbt(root,task.pc(),p,out);
            } else try(var reader=Files.newBufferedReader(p)) {
                JsonElement root=JsonParser.parseReader(reader);
                if(!root.isJsonObject()) throw new IllegalStateException("JSON inválido: "+p);
                collectJson(root.getAsJsonObject(),task.pc(),p,out);
            }
            BasicFileAttributes after=Files.readAttributes(p,BasicFileAttributes.class);
            if(before.size()!=after.size() || !before.lastModifiedTime().equals(after.lastModifiedTime()) || !Objects.equals(before.fileKey(),after.fileKey()))
                throw new IllegalStateException("Arquivo alterado durante a leitura: "+p);
            return Boolean.TRUE;
        });
    }
    private static void collectNbt(NbtCompound root,boolean pc,Object source,List<Entry> out) {
        if(pc) {
            if(!root.contains("BoxCount",NbtElement.NUMBER_TYPE)) throw new IllegalStateException("PC sem número de caixas.");
            int boxes=root.getInt("BoxCount"); if(boxes<0 || boxes>4096) throw new IllegalStateException("Número inválido de caixas.");
            checkIndexedKeys(root.getKeys(),"Box",boxes);
            if(root.contains("BackupStore")) {
                if(!root.contains("BackupStore",NbtElement.COMPOUND_TYPE)) throw new IllegalStateException("Reserva inválida.");
                backupNbt(root.getCompound("BackupStore"),source,out);
            }
            for(int i=0;i<boxes;i++) { ValidationIo.checkCancelled(); String key="Box"+i; if(!root.contains(key,NbtElement.COMPOUND_TYPE)) throw new IllegalStateException("Caixa inválida: "+key); slotsNbt(root.getCompound(key),30,source,out); }
        } else { int slots=root.contains("SlotCount",NbtElement.NUMBER_TYPE) ? root.getInt("SlotCount") : 6; if(slots<1 || slots>6) slots=6; slotsNbt(root,slots,source,out); }
    }
    private static void slotsNbt(NbtCompound root,int count,Object source,List<Entry> out) {
        checkIndexedKeys(root.getKeys(),"Slot",count);
        for(int i=0;i<count;i++) { String key="Slot"+i; if(root.contains(key)) { if(!root.contains(key,NbtElement.COMPOUND_TYPE) || root.getCompound(key).isEmpty()) throw new IllegalStateException("Slot inválido: "+key); out.add(new Entry(root.getCompound(key),true,source)); } }
    }
    private static void collectJson(JsonObject root,boolean pc,Object source,List<Entry> out) {
        if(pc) {
            int boxes=root.get("BoxCount").getAsInt(); if(boxes<0 || boxes>4096) throw new IllegalStateException("Número inválido de caixas.");
            checkIndexedKeys(root.keySet(),"Box",boxes);
            if(root.has("BackupStore")) {
                if(!root.get("BackupStore").isJsonObject()) throw new IllegalStateException("Reserva inválida.");
                backupJson(root.getAsJsonObject("BackupStore"),source,out);
            }
            for(int i=0;i<boxes;i++) { ValidationIo.checkCancelled(); String key="Box"+i; if(!root.has(key) || !root.get(key).isJsonObject()) throw new IllegalStateException("Caixa inválida: "+key); slotsJson(root.getAsJsonObject(key),30,source,out); }
        } else { int slots=root.get("SlotCount").getAsInt(); if(slots<1 || slots>6) throw new IllegalStateException("Número inválido de slots."); slotsJson(root,slots,source,out); }
    }
    private static void slotsJson(JsonObject root,int count,Object source,List<Entry> out) {
        checkIndexedKeys(root.keySet(),"Slot",count);
        for(int i=0;i<count;i++) { String key="Slot"+i; if(root.has(key)) { if(!root.get(key).isJsonObject() || root.getAsJsonObject(key).entrySet().isEmpty()) throw new IllegalStateException("Slot inválido: "+key); out.add(new Entry(root.getAsJsonObject(key),true,source)); } }
    }
    private static void backupNbt(NbtCompound backup,Object source,List<Entry> out) {
        int size=backup.getKeys().size(); checkIndexedKeys(backup.getKeys(),"Slot",size);
        for(int i=0;i<size;i++) { ValidationIo.checkCancelled(); String key="Slot"+i; if(!backup.contains(key,NbtElement.COMPOUND_TYPE) || backup.getCompound(key).isEmpty()) throw new IllegalStateException("Reserva inválida: "+key); out.add(new Entry(backup.getCompound(key),false,source)); }
    }
    private static void backupJson(JsonObject backup,Object source,List<Entry> out) {
        int size=backup.size(); checkIndexedKeys(backup.keySet(),"Slot",size);
        for(int i=0;i<size;i++) { ValidationIo.checkCancelled(); String key="Slot"+i; if(!backup.has(key) || !backup.get(key).isJsonObject() || backup.getAsJsonObject(key).entrySet().isEmpty()) throw new IllegalStateException("Reserva inválida: "+key); out.add(new Entry(backup.getAsJsonObject(key),false,source)); }
    }
    private static void checkIndexedKeys(Set<String> keys,String prefix,int limit) {
        for(String key:keys) if(key.startsWith(prefix)) {
            String suffix=key.substring(prefix.length());
            if(suffix.isEmpty() || !suffix.chars().allMatch(Character::isDigit)) continue;
            try {
                int index=Integer.parseInt(suffix);
                if(index<0 || index>=limit || !suffix.equals(Integer.toString(index))) throw new IllegalStateException("Entrada fora do limite: "+key);
            } catch(NumberFormatException e) { throw new IllegalStateException("Índice inválido: "+key,e); }
        }
    }

}
