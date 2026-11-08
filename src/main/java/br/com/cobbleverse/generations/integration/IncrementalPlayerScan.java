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
    private record FileTask(Path path,boolean nbt,boolean pc) {}
    private record Entry(Object value,boolean available,CacheWatch watch) {}
    private record LiveSource(Iterator<Pokemon> iterator,boolean available,CacheWatch watch) {}
    private final UUID id; private final MinecraftServer server; private final GenerationRules rules;
    private final ValidationReport report; private final Set<UUID> seen=new HashSet<>(),availableSeen=new HashSet<>(),reserveSeen=new HashSet<>();
    private final List<LiveSource> live=new ArrayList<>(); private final CompletableFuture<List<Entry>> offline;
    private Iterator<Entry> rawIterator; private int liveIndex,count,restricted,available,availableRestricted,reserve;
    private final java.util.function.Function<PokemonStore<?>,CacheWatch> watch;
    IncrementalPlayerScan(MinecraftServer server,GenerationRules rules,ValidationReport report,List<FileBackedPokemonStoreFactory<?>> factories,UUID id,java.util.function.Function<PokemonStore<?>,CacheWatch> watch,OfflineFileLedger ledger,java.util.function.Consumer<java.util.function.BooleanSupplier> cacheGuard) {
        this.id=id; this.server=server; this.rules=rules; this.report=report; this.watch=watch;
        List<FileTask> tasks=new ArrayList<>();
        for(var factory:factories) {
            var access=(StoreFactoryAccessor)(Object)factory;
            var adapter=access.generationControl$adapter();
            var info=(StorePathAccessor)(Object)adapter;
            for(Class<?> type:List.of(PlayerPartyStore.class,PCStore.class)) {
                Object cache=access.generationControl$caches().get(type);
                PokemonStore<?> store=cache==null ? null : ((StoreCacheAccessor)cache).generationControl$cacheMap().get(id);
                if(store!=null) {
                    cacheGuard.accept(() -> {
                        Object current=access.generationControl$caches().get(type);
                        return current!=null && ((StoreCacheAccessor)current).generationControl$cacheMap().get(id)==store;
                    });
                    CacheWatch mainWatch=watch.apply(store);
                    live.add(new LiveSource(store.iterator(),true,mainWatch));
                    if(store instanceof PCStore pc) {
                        CacheWatch backupWatch=watch.apply(pc.getBackupStore());
                        live.add(new LiveSource(pc.getBackupStore().iterator(),false,backupWatch));
                    }
                } else {
                    cacheGuard.accept(() -> {
                        Object current=access.generationControl$caches().get(type);
                        return current==null || ((StoreCacheAccessor)current).generationControl$cacheMap().get(id)==null;
                    });
                    tasks.add(new FileTask(StorePaths.path(info.generationControl$root(),info.generationControl$nested(),info.generationControl$perClass(),info.generationControl$extension(),type.getSimpleName(),id),adapter instanceof NBTStoreAdapter,type==PCStore.class));
                }
            }
        }
        offline=CompletableFuture.supplyAsync(()-> {
            List<Entry> found=new ArrayList<>();
            try { for(FileTask task:tasks) read(task,found,ledger); }
            catch(Exception e) { throw new CompletionException(e); }
            return found;
        });
    }
    boolean step() throws Exception {
        int budget=12; long end=System.nanoTime()+TimeUnit.MILLISECONDS.toNanos(3);
        while(budget-->0 && System.nanoTime()<end) {
            Entry entry;
            if(liveIndex<live.size()) {
                LiveSource source=live.get(liveIndex);
                if(source.iterator().hasNext()) entry=new Entry(source.iterator().next(),source.available(),source.watch());
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
            if(entry.watch()!=null) entry.watch().observed(pokemon);
            var species=pokemon.getSpecies();
            boolean blocked=rules.restrictExisting() && !rules.allowed(species.getResourceIdentifier().toString(),species.getNationalPokedexNumber(),pokemon.getForm().getName(),pokemon.getAspects());
            if(seen.add(pokemon.getUuid())) { count++; if(blocked) restricted++; }
            if(entry.available() && availableSeen.add(pokemon.getUuid())) { available++; if(blocked) availableRestricted++; }
            if(!entry.available() && reserveSeen.add(pokemon.getUuid())) reserve++;
        }
        return false;
    }
    private void finish() {
        report.inspectedPlayers++;
        if(restricted>0) {
            report.affectedPlayers++; report.affectedPokemon+=restricted;
            var player=server.getPlayerManager().getPlayer(id);
            if(report.affectedPlayerNames.size()<30) report.affectedPlayerNames.add(player==null ? id.toString() : player.getGameProfile().getName());
        }
        if(count>0 && available==availableRestricted) report.playersWithoutUsablePokemon++;
        if(reserve>0) { report.playersWithReserve++; report.reservePokemon+=reserve; }
    }
    void cancel() { offline.cancel(false); }
    private static void read(FileTask task,List<Entry> out,OfflineFileLedger ledger) throws Exception {
        Path p=task.path();
        ledger.read(p,file -> {
            BasicFileAttributes before=Files.readAttributes(p,BasicFileAttributes.class);
            if(before.size()>64L*1024*1024) throw new IllegalStateException("Arquivo excede 64 MiB: "+p);
            if(task.nbt()) {
                NbtCompound root=NbtIo.readCompressed(p,NbtSizeTracker.of(128L*1024*1024));
                if(root==null) throw new IllegalStateException("NBT vazio: "+p);
                collectNbt(root,task.pc(),out);
            } else try(var reader=Files.newBufferedReader(p)) {
                JsonElement root=JsonParser.parseReader(reader);
                if(!root.isJsonObject()) throw new IllegalStateException("JSON inválido: "+p);
                collectJson(root.getAsJsonObject(),task.pc(),out);
            }
            BasicFileAttributes after=Files.readAttributes(p,BasicFileAttributes.class);
            if(before.size()!=after.size() || !before.lastModifiedTime().equals(after.lastModifiedTime()) || !Objects.equals(before.fileKey(),after.fileKey()))
                throw new IllegalStateException("Arquivo alterado durante a leitura: "+p);
            return Boolean.TRUE;
        });
    }
    private static void collectNbt(NbtCompound root,boolean pc,List<Entry> out) {
        if(pc) {
            if(!root.contains("BoxCount",NbtElement.NUMBER_TYPE)) throw new IllegalStateException("PC sem número de caixas.");
            int boxes=root.getInt("BoxCount"); if(boxes<0 || boxes>4096) throw new IllegalStateException("Número inválido de caixas.");
            checkIndexedKeys(root.getKeys(),"Box",boxes);
            if(root.contains("BackupStore")) {
                if(!root.contains("BackupStore",NbtElement.COMPOUND_TYPE)) throw new IllegalStateException("Reserva inválida.");
                backupNbt(root.getCompound("BackupStore"),out);
            }
            for(int i=0;i<boxes;i++) { String key="Box"+i; if(!root.contains(key,NbtElement.COMPOUND_TYPE)) throw new IllegalStateException("Caixa inválida: "+key); slotsNbt(root.getCompound(key),30,out); }
        } else { int slots=root.contains("SlotCount",NbtElement.NUMBER_TYPE) ? root.getInt("SlotCount") : 6; if(slots<1 || slots>6) slots=6; slotsNbt(root,slots,out); }
    }
    private static void slotsNbt(NbtCompound root,int count,List<Entry> out) {
        checkIndexedKeys(root.getKeys(),"Slot",count);
        for(int i=0;i<count;i++) { String key="Slot"+i; if(root.contains(key)) { if(!root.contains(key,NbtElement.COMPOUND_TYPE) || root.getCompound(key).isEmpty()) throw new IllegalStateException("Slot inválido: "+key); out.add(new Entry(root.getCompound(key),true,null)); } }
    }
    private static void collectJson(JsonObject root,boolean pc,List<Entry> out) {
        if(pc) {
            int boxes=root.get("BoxCount").getAsInt(); if(boxes<0 || boxes>4096) throw new IllegalStateException("Número inválido de caixas.");
            checkIndexedKeys(root.keySet(),"Box",boxes);
            if(root.has("BackupStore")) {
                if(!root.get("BackupStore").isJsonObject()) throw new IllegalStateException("Reserva inválida.");
                backupJson(root.getAsJsonObject("BackupStore"),out);
            }
            for(int i=0;i<boxes;i++) { String key="Box"+i; if(!root.has(key) || !root.get(key).isJsonObject()) throw new IllegalStateException("Caixa inválida: "+key); slotsJson(root.getAsJsonObject(key),30,out); }
        } else { int slots=root.get("SlotCount").getAsInt(); if(slots<1 || slots>6) throw new IllegalStateException("Número inválido de slots."); slotsJson(root,slots,out); }
    }
    private static void slotsJson(JsonObject root,int count,List<Entry> out) {
        checkIndexedKeys(root.keySet(),"Slot",count);
        for(int i=0;i<count;i++) { String key="Slot"+i; if(root.has(key)) { if(!root.get(key).isJsonObject() || root.getAsJsonObject(key).entrySet().isEmpty()) throw new IllegalStateException("Slot inválido: "+key); out.add(new Entry(root.getAsJsonObject(key),true,null)); } }
    }
    private static void backupNbt(NbtCompound backup,List<Entry> out) {
        int size=backup.getKeys().size(); checkIndexedKeys(backup.getKeys(),"Slot",size);
        for(int i=0;i<size;i++) { String key="Slot"+i; if(!backup.contains(key,NbtElement.COMPOUND_TYPE) || backup.getCompound(key).isEmpty()) throw new IllegalStateException("Reserva inválida: "+key); out.add(new Entry(backup.getCompound(key),false,null)); }
    }
    private static void backupJson(JsonObject backup,List<Entry> out) {
        int size=backup.size(); checkIndexedKeys(backup.keySet(),"Slot",size);
        for(int i=0;i<size;i++) { String key="Slot"+i; if(!backup.has(key) || !backup.get(key).isJsonObject() || backup.getAsJsonObject(key).entrySet().isEmpty()) throw new IllegalStateException("Reserva inválida: "+key); out.add(new Entry(backup.getAsJsonObject(key),false,null)); }
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
