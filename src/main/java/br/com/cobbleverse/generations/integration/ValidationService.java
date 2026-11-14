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
    private static final int MAX_PLAYER_STORE_RECHECKS = 64;
    private static final int MAX_RECHECKS_PER_PLAYER = 8;
    private static Job job;
    private static long connections;
    private ValidationService() {}
    public static void register() {
        ServerTickEvents.END_SERVER_TICK.register(ValidationService::tick);
        ServerPlayConnectionEvents.JOIN.register((handler,sender,server)->connections++);
        ServerPlayConnectionEvents.DISCONNECT.register((handler,server)->connections++);
    }
    public static void clear() { if(job!=null) job.cancel(); job=null; connections=0; }
    public static boolean cancel(MinecraftServer server) {
        Job current=job;
        if(current==null || current.server!=server) return false;
        job=null;
        current.cancel();
        return true;
    }
    public static boolean start(MinecraftServer server,GenerationConfig candidate,Consumer<ValidationReport> finished) {
        if(job!=null) return false;
        var frozen=candidate.copy().validated();
        CobblemonIntegration.BaseValidation base=CobblemonIntegration.beginBaseValidation(frozen);
        ValidationReport report=base.report();
        if(!base.canContinue()) { finished.accept(report); return true; }
        boolean scanPlayers=CobblemonIntegration.needsPlayerImpact(frozen), scanTeams=CobblemonIntegration.needsTeamPreview(frozen);
        try { job=new Job(server,base,report,finished,scanPlayers,scanTeams); }
        catch(Exception e) { fail(report,e); finished.accept(report); }
        return true;
    }
    private static void tick(MinecraftServer server) {
        Job current=job;
        if(current==null || current.server!=server) return;
        try {
            if(current.scanPlayers && current.playerImpactPhase && current.epoch!=connections)
                throw new ImpactStale("Jogadores conectaram ou desconectaram durante a análise. A configuração foi validada, mas as contagens de impacto foram omitidas.");
            long budgetMs=server.getAverageTickTime()>45.0f ? 1 : 3;
            long deadline=System.nanoTime()+TimeUnit.MILLISECONDS.toNanos(budgetMs);
            if(!current.step(deadline)) return;
            if(current.scanPlayers && current.report.errors.isEmpty()) current.report.warnings.add("Prévia concluída em lotes: "+current.report.inspectedPlayers+" jogadores. O estado pode mudar durante a análise.");
        } catch(ImpactStale stale) {
            current.cancel();
            current.report.markPlayerImpactStale(stale.getMessage());
        } catch(Exception e) {
            current.cancel();
            if(current.playerImpactPhase)
                current.report.markPlayerImpactStale("A configuração foi validada, mas as contagens de jogadores foram omitidas: "+e.getMessage());
            else fail(current.report,e);
        }
        job=null;
        current.cleanup();
        current.finished.accept(current.report);
    }

    private static void fail(ValidationReport report,Exception e) {
        report.clearPlayerImpact(); report.trainerTeams=0; report.adaptedTeams=0;
        report.errors.add("Não foi possível concluir a prévia administrativa: "+e.getMessage());
        report.warnings.add("Contagens parciais descartadas; tente validar novamente.");
        GenerationControl.LOGGER.warn("Falha na prévia em lotes",e);
    }
    private static final class ImpactStale extends RuntimeException {
        ImpactStale(String message) { super(message); }
    }
    private static final class Job {
        private record CacheGuard(UUID player,java.util.function.BooleanSupplier check) {}
        final MinecraftServer server; final CobblemonIntegration.BaseValidation base; final GenerationRules rules; final ValidationReport report;
        final Consumer<ValidationReport> finished; final long epoch;
        final List<FileBackedPokemonStoreFactory<?>> factories;
        final CompletableFuture<Set<UUID>> enumeration;
        final List<CacheWatch> watches=new ArrayList<>(); final CacheWatch.ChangeEpoch cacheChanges=new CacheWatch.ChangeEpoch();
        final Deque<UUID> playerRechecks=new ArrayDeque<>(); final Set<UUID> queuedRechecks=new HashSet<>();
        final Map<UUID,LinkedHashMap<CacheWatch.StoreKey,CacheWatch.StoreTarget>> queuedStoreRechecks=new HashMap<>();
        final Set<UUID> fullPlayerRechecks=new HashSet<>();
        final Map<UUID,Integer> rechecksByPlayer=new HashMap<>(); int totalRechecks;
        final ValidationIo io=new ValidationIo();
        final long startedNanos=System.nanoTime();
        final OfflineFileLedger ledger=new OfflineFileLedger(); CompletableFuture<Void> fileCheck;
        final List<CacheGuard> cacheGuards=new ArrayList<>(); int cacheIndex; boolean cachesCheckedBeforeFiles;
        int watchIndex; long watchSweepVersion,stableChangeVersion=Long.MIN_VALUE; boolean watchSweepActive;
        boolean cleaned;
        Iterator<UUID> players; List<Future<?>> barriers; long barrierStart; IncrementalPlayerScan scan; boolean playerImpactPhase,scanIsRecheck;
        final br.com.cobbleverse.generations.data.PolicyResources.TeamPreview teams; final boolean scanPlayers; boolean teamsDone;
        Job(MinecraftServer server,CobblemonIntegration.BaseValidation base,ValidationReport report,Consumer<ValidationReport> finished,boolean scanPlayers,boolean scanTeams) {
            this.server=server;this.base=base;this.rules=base.rules();this.report=report;this.finished=finished;this.scanPlayers=scanPlayers;epoch=connections;
            teams=scanTeams ? br.com.cobbleverse.generations.data.PolicyResources.beginTeamPreview(rules,report) : null;
            if(!scanPlayers) { factories=List.of(); enumeration=CompletableFuture.completedFuture(Set.of()); return; }
            factories=PlayerImpact.providers();
            Set<UUID> online=new TreeSet<>(); server.getPlayerManager().getPlayerList().forEach(p->online.add(p.getUuid()));
            Path directory=server.getSavePath(WorldSavePath.PLAYERDATA);
            enumeration=io.submit(()-> {
                try {
                    if(!Files.notExists(directory)) try(var paths=Files.list(directory)) {
                        paths.filter(p->p.getFileName().toString().endsWith(".dat")).forEach(p->{
                            try { ValidationIo.checkCancelled(); String name=p.getFileName().toString(); online.add(UUID.fromString(name.substring(0,name.length()-4))); }
                            catch(IllegalArgumentException ignored) {}
                        });
                    }
                    return online;
                } catch(Exception e) { throw new CompletionException(e); }
            });
        }
        boolean step(long deadline) throws Exception {
            if(System.nanoTime()-startedNanos>TimeUnit.MINUTES.toNanos(10)) throw new IllegalStateException("A análise excedeu dez minutos; tente novamente em um período de menor atividade.");
            if(!base.step(deadline)) return false;
            if(!report.errors.isEmpty() || (!scanPlayers && teams==null)) return true;
            if(teams!=null) teams.ensureCurrent();
            if(!teamsDone) {
                if(teams!=null && !teams.step(8,deadline)) return false;
                teamsDone=true;
                if(!report.errors.isEmpty()) return true;
            }
            if(players==null) {
                if(!enumeration.isDone()) return false;
                playerImpactPhase=scanPlayers;
                players=enumeration.get().iterator();
            }
            if(scan!=null) {
                if(scan.step(deadline)) {
                    if(scan.targeted() && scan.snapshotOverflow()) {
                        UUID player=scan.playerId();
                        scan.cancel();
                        removePlayerWatches(player);
                        cacheGuards.removeIf(guard -> guard.player().equals(player));
                        scan=createPlayerScan(player);
                        scanIsRecheck=true;
                    } else { scan=null; scanIsRecheck=false; }
                }
                return false;
            }
            if(!playerRechecks.isEmpty()) {
                if(!waitForStoreWrites()) return false;
                UUID changedPlayer=playerRechecks.removeFirst();
                queuedRechecks.remove(changedPlayer);
                if(fullPlayerRechecks.remove(changedPlayer)) {
                    queuedStoreRechecks.remove(changedPlayer);
                    removePlayerWatches(changedPlayer);
                    cacheGuards.removeIf(guard -> guard.player().equals(changedPlayer));
                    scan=createPlayerScan(changedPlayer);
                } else {
                    var targets=queuedStoreRechecks.remove(changedPlayer);
                    if(targets==null || targets.isEmpty()) throw new ImpactStale("O store alterado não pôde ser identificado; as contagens de impacto foram omitidas.");
                    scan=createStoreScan(changedPlayer,targets.values());
                }
                scanIsRecheck=true;
                return false;
            }
            if(!players.hasNext()) {
                if(!checkChanges(deadline)) {
                    if(fileCheck!=null) { fileCheck.cancel(false); fileCheck=null; cachesCheckedBeforeFiles=false; cacheIndex=0; }
                    return false;
                }
                if(!cachesCheckedBeforeFiles) {
                    if(!checkCacheGuards(deadline)) return false;
                    cachesCheckedBeforeFiles=true; cacheIndex=0;
                }
                if(fileCheck==null) { fileCheck=ledger.verify(io); return false; }
                if(!fileCheck.isDone()) return false;
                fileCheck.get();
                if(!checkCacheGuards(deadline)) return false;
                return checkChanges(deadline);
            }
            if(!waitForStoreWrites()) return false;
            scan=createPlayerScan(players.next());
            return false;
        }
        boolean waitForStoreWrites() throws Exception {
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
            return true;
        }
        IncrementalPlayerScan createPlayerScan(UUID id) {
            return new IncrementalPlayerScan(server,rules,report,factories,id,this::watchStore,ledger,
                    (player,check) -> cacheGuards.add(new CacheGuard(player,check)),io,report.canCacheStoreImpacts());
        }
        IncrementalPlayerScan createStoreScan(UUID id,Collection<CacheWatch.StoreTarget> targets) {
            List<ValidationReport.StoreImpact> previous=report.storeImpacts(id);
            if(previous==null) throw new ImpactStale("O snapshot de stores do jogador não está disponível; as contagens de impacto foram omitidas.");
            return new IncrementalPlayerScan(server,rules,report,id,this::watchStore,io,previous,targets);
        }
        CacheWatch watchStore(UUID player,com.cobblemon.mod.common.api.storage.PokemonStore<?> store,boolean available) {
            CacheWatch watch=new CacheWatch(player,store,available,cacheChanges); watches.add(watch); return watch;
        }
        void removePlayerWatches(UUID player) {
            watches.removeIf(watch -> {
                if(!watch.player().equals(player)) return false;
                watch.close(); return true;
            });
        }
        boolean checkCacheGuards(long deadline) {
            int limit=64;
            while(cacheIndex<cacheGuards.size() && limit-->0 && System.nanoTime()<deadline) {
                if(!cacheGuards.get(cacheIndex++).check().getAsBoolean()) throw new IllegalStateException("O cache de Pokémon foi carregado, removido ou substituído durante a prévia; valide novamente.");
            }
            return cacheIndex==cacheGuards.size();
        }
        boolean checkChanges(long deadline) {
            long currentVersion=cacheChanges.value();
            if(stableChangeVersion==currentVersion) return true;
            // Watches are added during scans, then checked here until the report has a stable snapshot.
            if(!watchSweepActive) {
                watchSweepActive=true; watchIndex=0; watchSweepVersion=currentVersion;
            }
            int limit=64;
            while(watchIndex<watches.size() && limit-->0 && System.nanoTime()<deadline) {
                CacheWatch watch=watches.get(watchIndex++);
                if(watch.dirty()) {
                    queueStoreRecheck(watch);
                    return false;
                }
            }
            if(watchIndex<watches.size()) return false;
            currentVersion=cacheChanges.value();
            if(currentVersion!=watchSweepVersion) {
                watchIndex=0; watchSweepVersion=currentVersion; return false;
            }
            watchIndex=0; watchSweepActive=false; stableChangeVersion=currentVersion;
            return true;
        }
        void queueStoreRecheck(CacheWatch changed) {
            UUID player=changed.player();
            if(!report.canRescanPlayer(player))
                throw new ImpactStale("O cache de Pokémon mudou antes de registrar a prévia daquele jogador; as contagens de impacto foram omitidas.");
            if(!queuedRechecks.contains(player) && (totalRechecks>=MAX_PLAYER_STORE_RECHECKS || rechecksByPlayer.getOrDefault(player,0)>=MAX_RECHECKS_PER_PLAYER))
                throw new ImpactStale("Os Pokémon continuam mudando durante a prévia; as contagens de impacto foram omitidas.");
            if(queuedRechecks.add(player)) {
                totalRechecks++;
                rechecksByPlayer.merge(player,1,Integer::sum);
                playerRechecks.addLast(player);
            }
            if(report.canRecheckStore(player) && !fullPlayerRechecks.contains(player)) {
                queuedStoreRechecks.computeIfAbsent(player,key -> new LinkedHashMap<>())
                        .putIfAbsent(changed.key(),new CacheWatch.StoreTarget(changed.store(),changed.available()));
            } else {
                fullPlayerRechecks.add(player);
                queuedStoreRechecks.remove(player);
            }
            CacheWatch.StoreKey changedKey=changed.key();
            watches.removeIf(watch -> {
                if(!watch.player().equals(player) || !watch.key().equals(changedKey)) return false;
                watch.close(); return true;
            });
            cacheIndex=0; cachesCheckedBeforeFiles=false; watchIndex=0; watchSweepActive=false;
            stableChangeVersion=Long.MIN_VALUE;
            if(fileCheck!=null) { fileCheck.cancel(false); fileCheck=null; }
        }
        void cancel() { enumeration.cancel(false); if(barriers!=null) barriers.forEach(f->f.cancel(false)); if(scan!=null) scan.cancel(); if(fileCheck!=null) fileCheck.cancel(false); cleanup(); }
        void cleanup() {
            if(cleaned) return;
            cleaned=true;
            io.close(); watches.forEach(CacheWatch::close); watches.clear();
            report.releaseRescanCache();
        }
    }
}
