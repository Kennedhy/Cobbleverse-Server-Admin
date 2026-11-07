package br.com.cobbleverse.generations.integration;

import br.com.cobbleverse.generations.GenerationControl;
import br.com.cobbleverse.generations.config.GenerationConfig;
import br.com.cobbleverse.generations.mixin.StoreFactoryAccessor;
import br.com.cobbleverse.generations.rules.GenerationRules;
import com.cobblemon.mod.common.api.storage.factory.FileBackedPokemonStoreFactory;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.WorldSavePath;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;

/** One explicit preview at a time, with filesystem enumeration off-thread and bounded work per tick. */
public final class ValidationService {
    private static Job job;
    private static long connections;
    private ValidationService() {}
    public static void register() {
        ServerTickEvents.END_SERVER_TICK.register(ValidationService::tick);
        ServerPlayConnectionEvents.JOIN.register((handler,sender,server)->connections++);
        ServerPlayConnectionEvents.DISCONNECT.register((handler,server)->connections++);
    }
    public static void clear() { if(job!=null) job.cancel(); job=null; connections=0; }
    public static boolean start(MinecraftServer server,GenerationConfig candidate,Consumer<ValidationReport> finished) {
        if(job!=null) return false;
        var frozen=candidate.copy().validated();
        ValidationReport report=CobblemonIntegration.validateBase(frozen);
        boolean scanPlayers=CobblemonIntegration.needsPlayerImpact(frozen), scanTeams=CobblemonIntegration.needsTeamPreview(frozen);
        if(!report.errors.isEmpty() || (!scanPlayers && !scanTeams)) { finished.accept(report); return true; }
        try { job=new Job(server,new GenerationRules(frozen),report,finished,scanPlayers,scanTeams); }
        catch(Exception e) { fail(report,e); finished.accept(report); }
        return true;
    }
    private static void tick(MinecraftServer server) {
        Job current=job;
        if(current==null || current.server!=server) return;
        try {
            if(current.epoch!=connections) throw new IllegalStateException("Jogador conectou ou desconectou durante a análise; valide novamente.");
            if(!current.step()) return;
            if(current.scanPlayers && current.report.errors.isEmpty()) current.report.warnings.add("Prévia concluída em lotes: "+current.report.inspectedPlayers+" jogadores. O estado pode mudar durante a análise.");
        } catch(Exception e) {
            current.cancel(); fail(current.report,e);
        }
        job=null;
        current.cleanup();
        current.finished.accept(current.report);
    }

    private static void fail(ValidationReport report,Exception e) {
        report.clearPlayerImpact(); report.trainerTeams=0; report.adaptedTeams=0;
        report.errors.add("Não foi possível concluir a prévia dos jogadores: "+e.getMessage());
        report.warnings.add("Contagens parciais descartadas; tente validar novamente.");
        GenerationControl.LOGGER.warn("Falha na prévia em lotes",e);
    }
    private static final class Job {
        final MinecraftServer server; final GenerationRules rules; final ValidationReport report;
        final Consumer<ValidationReport> finished; final long epoch;
        final List<FileBackedPokemonStoreFactory<?>> factories;
        final CompletableFuture<Set<UUID>> enumeration;
        final List<CacheWatch> watches=new ArrayList<>();
        final OfflineFileLedger ledger=new OfflineFileLedger(); CompletableFuture<Void> fileCheck;
        final List<java.util.function.BooleanSupplier> cacheGuards=new ArrayList<>(); int cacheIndex; boolean cachesCheckedBeforeFiles;
        int watchIndex; CacheWatch checking;
        Iterator<UUID> players; List<Future<?>> barriers; long barrierStart; IncrementalPlayerScan scan;
        final br.com.cobbleverse.generations.data.PolicyResources.TeamPreview teams; final boolean scanPlayers; boolean teamsDone;
        Job(MinecraftServer server,GenerationRules rules,ValidationReport report,Consumer<ValidationReport> finished,boolean scanPlayers,boolean scanTeams) {
            this.server=server;this.rules=rules;this.report=report;this.finished=finished;this.scanPlayers=scanPlayers;epoch=connections;
            teams=scanTeams ? br.com.cobbleverse.generations.data.PolicyResources.beginTeamPreview(rules,report) : null;
            if(!scanPlayers) { factories=List.of(); enumeration=CompletableFuture.completedFuture(Set.of()); return; }
            factories=PlayerImpact.providers();
            Set<UUID> online=new TreeSet<>(); server.getPlayerManager().getPlayerList().forEach(p->online.add(p.getUuid()));
            Path directory=server.getSavePath(WorldSavePath.PLAYERDATA);
            enumeration=CompletableFuture.supplyAsync(()-> {
                try {
                    if(!Files.notExists(directory)) try(var paths=Files.list(directory)) {
                        paths.filter(p->p.getFileName().toString().endsWith(".dat")).forEach(p->{
                            try { String name=p.getFileName().toString(); online.add(UUID.fromString(name.substring(0,name.length()-4))); }
                            catch(IllegalArgumentException ignored) {}
                        });
                    }
                    return online;
                } catch(Exception e) { throw new CompletionException(e); }
            });
        }
        boolean step() throws Exception {
            if(teams!=null) teams.ensureCurrent();
            if(!teamsDone) {
                if(teams!=null && !teams.step(8,System.nanoTime()+TimeUnit.MILLISECONDS.toNanos(3))) return false;
                teamsDone=true;
                if(!report.errors.isEmpty()) return true;
            }
            if(players==null) {
                if(!enumeration.isDone()) return false;
                players=enumeration.get().iterator();
            }
            if(scan!=null) { if(scan.step()) scan=null; return false; }
            if(!players.hasNext()) {
                if(!checkChanges()) {
                    if(fileCheck!=null) { fileCheck.cancel(false); fileCheck=null; cachesCheckedBeforeFiles=false; cacheIndex=0; }
                    return false;
                }
                if(!cachesCheckedBeforeFiles) {
                    if(!checkCacheGuards()) return false;
                    cachesCheckedBeforeFiles=true; cacheIndex=0;
                }
                if(fileCheck==null) { fileCheck=ledger.verify(); return false; }
                if(!fileCheck.isDone()) return false;
                fileCheck.get();
                if(!checkCacheGuards()) return false;
                return checkChanges();
            }
            if(barriers==null) {
                barriers=new ArrayList<>();
                for(var factory:factories) barriers.add(((StoreFactoryAccessor)(Object)factory).generationControl$executor().submit(()->{}));
                barrierStart=System.nanoTime(); return false;
            }
            if(barriers.stream().anyMatch(f->!f.isDone())) {
                if(System.nanoTime()-barrierStart>TimeUnit.SECONDS.toNanos(2)) throw new IllegalStateException("Armazenamento ocupado; tente novamente.");
                return false;
            }
            for(var barrier:barriers) barrier.get();
            barriers=null;
            scan=new IncrementalPlayerScan(server,rules,report,factories,players.next(),store -> {
                CacheWatch watch=new CacheWatch(store); watches.add(watch); return watch;
            },ledger,cacheGuards::add);
            return false;
        }
        boolean checkCacheGuards() {
            int limit=64; long deadline=System.nanoTime()+TimeUnit.MILLISECONDS.toNanos(3);
            while(cacheIndex<cacheGuards.size() && limit-->0 && System.nanoTime()<deadline) {
                if(!cacheGuards.get(cacheIndex++).getAsBoolean()) throw new IllegalStateException("O cache de Pokémon foi carregado, removido ou substituído durante a prévia; valide novamente.");
            }
            return cacheIndex==cacheGuards.size();
        }
        boolean checkChanges() {
            if(checking!=null) { if(checking.step()) checking=null; return false; }
            while(watchIndex<watches.size()) {
                CacheWatch next=watches.get(watchIndex++);
                if(next.dirty()) { checking=next; return false; }
            }
            // A store already checked may have changed during later checks.
            for(CacheWatch watch:watches) if(watch.dirty()) { watchIndex=0; return false; }
            return true;
        }
        void cancel() { enumeration.cancel(false); if(barriers!=null) barriers.forEach(f->f.cancel(false)); if(scan!=null) scan.cancel(); if(fileCheck!=null) fileCheck.cancel(false); cleanup(); }
        void cleanup() { watches.forEach(CacheWatch::close); watches.clear(); }
    }
}
