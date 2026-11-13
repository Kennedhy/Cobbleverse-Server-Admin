package br.com.cobbleverse.generations.web;

import br.com.cobbleverse.generations.GenerationControl;
import br.com.cobbleverse.generations.config.ConfigStore;
import br.com.cobbleverse.generations.integration.RctIntegration;
import com.gitlab.srcmc.rctmod.api.RCTMod;
import com.gitlab.srcmc.rctmod.api.data.save.TrainerPlayerData;
import com.gitlab.srcmc.rctmod.api.data.sync.PlayerState;
import com.gitlab.srcmc.rctmod.api.service.SeriesManager;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/** On-demand administrative access to a connected player's RCT progression. */
public final class PlayerProgressAdmin {
    private static final int MAX_TRAINERS_PER_EDIT = 1024;
    private static final long MAX_LEDGER_BYTES = 262_144;
    private static final Set<UUID> EDITING = ConcurrentHashMap.newKeySet();

    public record EditRequest(String playerUuid, String expectedProgressRevision, String series,
                              List<String> defeatedTrainerIds) {}

    /** Immutable view captured on the server thread; contains no filesystem access. */
    public record Snapshot(String player, String playerName, String series, int levelCap,
                           Set<String> defeated, Map<String, Integer> completedSeries,
                           String progressRevision, List<Map<String, Object>> seriesOptions,
                           Map<String, Set<String>> trainerIdsBySeries) {}

    /** Serializable edit plan prepared on the server thread, persisted off-thread, then committed on-server. */
    public record EditPlan(String player, String playerName, String actor, String changedAt,
                           String expectedProgressRevision, String oldSeries, int oldLevelCap,
                           Set<String> oldDefeated, Map<String, Integer> oldCompletedSeries,
                           String targetSeries, Set<String> targetDefeated, boolean changed) {}

    public record PersistedEditPlan(EditPlan edit, boolean hadLedger,
                                    Map<String, Set<String>> ledgerBefore,
                                    Map<String, Set<String>> ledgerAfter,
                                    String archiveRelativePath) {}

    private record ProgressSnapshot(String player, String series, int levelCap,
                                    Set<String> defeated, Map<String, Integer> completed) {}

    private record ProgressArchive(String player, String playerName, String actor, String changedAt,
                                   String series, int levelCap, Set<String> defeated,
                                   Map<String, Integer> completed, String targetSeries,
                                   Set<String> targetDefeated) {}

    private PlayerProgressAdmin() {}

    /** Must run on the Minecraft server thread. It reads only live RCT state. */
    public static Snapshot snapshot(MinecraftServer server, String playerUuid) {
        UUID id = parseUuid(playerUuid);
        ServerPlayerEntity player = server.getPlayerManager().getPlayer(id);
        if (player == null) throw new WebPanelService.Problem(409,
                "O progresso RCT só pode ser consultado enquanto o jogador estiver conectado.");

        var manager = RCTMod.getInstance().getTrainerManager();
        if (manager.isLoading()) throw new WebPanelService.Problem(503, "Os dados de treinadores ainda estão carregando.");
        TrainerPlayerData data = manager.getData(player);
        PlayerState state = PlayerState.get(player);
        if (data == null || state == null || state.isLoading())
            throw new WebPanelService.Problem(503, "O progresso RCT do jogador ainda está carregando.");

        SeriesManager seriesManager = RCTMod.getInstance().getSeriesManager();
        TreeSet<String> allSeries = new TreeSet<>(seriesManager.getSeriesIds());
        allSeries.add(SeriesManager.FREEROAM_SERIES_ID);
        allSeries.add(SeriesManager.EMPTY_SERIES_ID);
        List<String> availableSeries = allSeries.stream()
                .filter(series -> series.equals(RctIntegration.permittedSeries(series))
                        && seriesManager.getGraph(series) != seriesManager.UNKNOWN_SERIES)
                .toList();
        List<Map<String, Object>> options = availableSeries.stream()
                .map(series -> Map.<String, Object>of("id", series,
                        "trainerCount", seriesManager.getGraph(series).size()))
                .toList();
        Map<String, Set<String>> trainersBySeries = new TreeMap<>();
        for (String series : availableSeries) {
            SeriesManager.SeriesGraph graph = seriesManager.getGraph(series);
            if (graph.size() <= MAX_TRAINERS_PER_EDIT) trainersBySeries.put(series, trainerIds(graph));
        }

        String currentSeries = normalizedSeries(data.getCurrentSeries());
        return new Snapshot(id.toString(), player.getGameProfile().getName(), currentSeries, state.getLevelCap(),
                sorted(data.getDefeatedTrainerIds()), new TreeMap<>(data.getCompletedSeries()),
                fingerprint(id, data, state), options, trainersBySeries);
    }

    /** Formats a server-thread snapshot with a ledger loaded by the HTTP worker thread. */
    public static Map<String, Object> view(Snapshot snapshot, Map<String, Set<String>> ledger) {
        Map<String, Set<String>> saved = copyLedger(ledger);
        saved.put(snapshot.series(), new TreeSet<>(snapshot.defeated()));
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("player", snapshot.player());
        result.put("playerName", snapshot.playerName());
        result.put("series", snapshot.series());
        result.put("levelCap", snapshot.levelCap());
        result.put("defeated", snapshot.defeated());
        result.put("completedSeries", snapshot.completedSeries());
        result.put("progressRevision", snapshot.progressRevision());
        result.put("seriesOptions", snapshot.seriesOptions());
        result.put("trainerIdsBySeries", snapshot.trainerIdsBySeries());
        result.put("savedProgressBySeries", saved);
        return result;
    }

    /** Read on the HTTP worker. Only a UUID-derived path and at most 256 KiB are accepted. */
    public static Map<String, Set<String>> readLedger(String playerUuid) {
        UUID id = parseUuid(playerUuid);
        var directory = GenerationControl.store().directory();
        var ledgerPath = ledgerPath(directory, id);
        if (!Files.isRegularFile(ledgerPath)) return new TreeMap<>();
        try {
            if (Files.size(ledgerPath) > MAX_LEDGER_BYTES) throw new IOException("Histórico de progresso excede o limite.");
            RctIntegration.ProgressLedger loaded = ConfigStore.GSON.fromJson(Files.readString(ledgerPath),
                    RctIntegration.ProgressLedger.class);
            if (loaded == null || loaded.defeatsBySeries == null) throw new IOException("Histórico de progresso inválido.");
            TreeMap<String, Set<String>> copy = new TreeMap<>();
            loaded.defeatsBySeries.forEach((series, ids) -> {
                if (series != null && series.length() <= 64 && ids != null && ids.size() <= MAX_TRAINERS_PER_EDIT)
                    copy.put(series, new TreeSet<>(ids));
            });
            return copy;
        } catch (IOException | RuntimeException invalid) {
            throw new WebPanelService.Problem(409, "O histórico arquivado desta progressão está inválido; nenhuma alteração foi aplicada.");
        }
    }

    /** Must run on the server thread; it validates and reserves one edit per UUID. */
    public static EditPlan prepareEdit(MinecraftServer server, EditRequest request, String actor) {
        if (request == null || request.expectedProgressRevision() == null || request.series() == null
                || request.defeatedTrainerIds() == null)
            throw new WebPanelService.Problem(400, "Informe a série, o progresso e a revisão carregada.");

        UUID id = parseUuid(request.playerUuid());
        ServerPlayerEntity player = server.getPlayerManager().getPlayer(id);
        if (player == null) throw new WebPanelService.Problem(409,
                "O jogador precisa estar conectado para editar o progresso RCT.");

        var manager = RCTMod.getInstance().getTrainerManager();
        if (manager.isLoading()) throw new WebPanelService.Problem(503, "Os dados de treinadores ainda estão carregando.");
        if (manager.getBattle(id).isPresent()) throw new WebPanelService.Problem(409,
                "O progresso não pode ser alterado durante uma batalha.");

        TrainerPlayerData data = manager.getData(player);
        PlayerState state = PlayerState.get(player);
        if (data == null || state == null || state.isLoading())
            throw new WebPanelService.Problem(503, "O progresso RCT do jogador ainda está carregando.");
        String currentRevision = fingerprint(id, data, state);
        if (!currentRevision.equals(request.expectedProgressRevision()))
            throw new WebPanelService.Problem(409,
                    "O progresso mudou desde que foi carregado. Atualize os dados antes de salvar.");

        String desiredSeries = request.series().trim().toLowerCase(Locale.ROOT);
        SeriesManager seriesManager = RCTMod.getInstance().getSeriesManager();
        boolean knownSeries = seriesManager.getSeriesIds().contains(desiredSeries)
                || SeriesManager.FREEROAM_SERIES_ID.equals(desiredSeries)
                || SeriesManager.EMPTY_SERIES_ID.equals(desiredSeries);
        if (desiredSeries.length() > 64 || !knownSeries
                || seriesManager.getGraph(desiredSeries) == seriesManager.UNKNOWN_SERIES
                || !desiredSeries.equals(RctIntegration.permittedSeries(desiredSeries)))
            throw new WebPanelService.Problem(400, "A série escolhida não existe ou está bloqueada pela configuração de gerações.");

        SeriesManager.SeriesGraph graph = seriesManager.getGraph(desiredSeries);
        List<String> requestedIds = request.defeatedTrainerIds();
        if (requestedIds.size() > Math.min(MAX_TRAINERS_PER_EDIT, graph.size()))
            throw new WebPanelService.Problem(400, "A quantidade de treinadores excede o limite desta série.");
        TreeSet<String> desiredDefeated = new TreeSet<>();
        Set<String> knownIds = trainerIds(graph);
        for (String trainerId : requestedIds) {
            if (trainerId == null || trainerId.length() > 160 || !knownIds.contains(trainerId))
                throw new WebPanelService.Problem(400, "A lista contém um treinador que não pertence a esta série.");
            if (!desiredDefeated.add(trainerId))
                throw new WebPanelService.Problem(400, "A lista contém treinadores duplicados.");
        }

        String oldSeries = normalizedSeries(data.getCurrentSeries());
        TreeSet<String> oldDefeated = new TreeSet<>(data.getDefeatedTrainerIds());
        boolean changed = !oldSeries.equals(desiredSeries) || !oldDefeated.equals(desiredDefeated);
        if (!EDITING.add(id)) throw new WebPanelService.Problem(409,
                "Já existe uma edição de progresso em andamento para este jogador.");
        return new EditPlan(id.toString(), player.getGameProfile().getName(), sanitizeActor(actor),
                Instant.now().toString(), currentRevision, oldSeries, state.getLevelCap(), oldDefeated,
                new TreeMap<>(data.getCompletedSeries()), desiredSeries, desiredDefeated, changed);
    }

    /** Must run on the HTTP worker. Reads/writes the small RCT restore ledger and archives the change. */
    public static PersistedEditPlan persist(EditPlan plan) {
        UUID id = UUID.fromString(plan.player());
        var directory = GenerationControl.store().directory();
        var path = ledgerPath(directory, id);
        boolean hadLedger = Files.isRegularFile(path);
        Map<String, Set<String>> before = readLedger(plan.player());
        if (!plan.changed()) return new PersistedEditPlan(plan, hadLedger, before, before, null);
        Map<String, Set<String>> after = copyLedger(before);
        after.put(plan.oldSeries(), new TreeSet<>(plan.oldDefeated()));
        after.put(plan.targetSeries(), new TreeSet<>(plan.targetDefeated()));
        String archiveRelativePath = null;
        try {
            RctIntegration.ProgressLedger nextLedger = new RctIntegration.ProgressLedger();
            nextLedger.defeatsBySeries.putAll(after);
            ConfigStore.write(path, nextLedger);
            ProgressArchive archive = new ProgressArchive(plan.player(), plan.playerName(), plan.actor(),
                    plan.changedAt(), plan.oldSeries(), plan.oldLevelCap(), plan.oldDefeated(),
                    plan.oldCompletedSeries(), plan.targetSeries(), plan.targetDefeated());
            archiveRelativePath = "player-progress/history/" + id + "/" + UUID.randomUUID() + ".json";
            ConfigStore.write(directory.resolve(archiveRelativePath), archive);
            return new PersistedEditPlan(plan, hadLedger, before, after, archiveRelativePath);
        } catch (IOException failure) {
            try {
                restoreLedger(path, hadLedger, before);
                cleanupArchive(directory, id, archiveRelativePath);
            }
            catch (IOException restoreFailure) {
                throw new WebPanelService.Problem(500,
                        "A alteração não foi aplicada e o histórico do circuito pode estar incompleto; verifique a configuração do servidor.");
            }
            throw new WebPanelService.Problem(500, "Não foi possível arquivar a progressão antes da alteração.");
        }
    }

    /** Must run on the server thread. Rechecks the live progress immediately before applying it. */
    public static Snapshot applyEdit(MinecraftServer server, PersistedEditPlan persisted) {
        EditPlan plan = persisted.edit();
        UUID id = UUID.fromString(plan.player());
        ServerPlayerEntity player = server.getPlayerManager().getPlayer(id);
        if (player == null) throw new WebPanelService.Problem(409, "O jogador desconectou antes de aplicar a progressão.");
        var manager = RCTMod.getInstance().getTrainerManager();
        if (manager.isLoading() || manager.getBattle(id).isPresent())
            throw new WebPanelService.Problem(409, "A progressão não pode ser alterada durante uma carga ou batalha.");
        TrainerPlayerData data = manager.getData(player);
        PlayerState state = PlayerState.get(player);
        if (data == null || state == null || state.isLoading())
            throw new WebPanelService.Problem(503, "O progresso RCT do jogador ainda está carregando.");
        if (!fingerprint(id, data, state).equals(plan.expectedProgressRevision()))
            throw new WebPanelService.Problem(409,
                    "O progresso mudou enquanto o histórico era salvo. A alteração foi cancelada; atualize o painel.");
        if (!plan.changed()) return snapshot(server, id.toString());

        try {
            setProgress(data, plan.targetSeries(), plan.targetDefeated());
        } catch (RuntimeException failure) {
            try { setProgress(data, plan.oldSeries(), plan.oldDefeated()); }
            catch (RuntimeException restoreFailure) {
                throw new WebPanelService.Problem(500,
                        "O RCT recusou a alteração e não foi possível restaurar o progresso automaticamente. Consulte o jogador antes de novas mudanças.");
            }
            throw new WebPanelService.Problem(409, "O RCT recusou a alteração; o progresso anterior foi restaurado.");
        }
        return snapshot(server, id.toString());
    }

    /** Call from the HTTP route's finally block, on any thread, after all disk/game phases finish. */
    public static void close(EditPlan plan) { EDITING.remove(UUID.fromString(plan.player())); }

    /** Call on the HTTP worker if the final game-thread phase rejects a persisted edit. */
    public static void rollbackLedger(PersistedEditPlan persisted) {
        if (persisted == null || !persisted.edit().changed()) return;
        UUID id = UUID.fromString(persisted.edit().player());
        var directory = GenerationControl.store().directory();
        try {
            restoreLedger(ledgerPath(directory, id),
                    persisted.hadLedger(), persisted.ledgerBefore());
            cleanupArchive(directory, id, persisted.archiveRelativePath());
        } catch (IOException failure) {
            throw new WebPanelService.Problem(500,
                    "A operação foi cancelada, mas não foi possível remover o registro pendente do histórico. Verifique a pasta de configuração do servidor.");
        }
    }

    private static void cleanupArchive(java.nio.file.Path directory, UUID id, String relativePath) throws IOException {
        if (relativePath == null) return;
        java.nio.file.Path root = directory.resolve("player-progress/history").normalize();
        java.nio.file.Path expectedParent = root.resolve(id.toString()).normalize();
        java.nio.file.Path archive = directory.resolve(relativePath).normalize();
        if (!archive.startsWith(root) || !archive.getParent().equals(expectedParent)
                || !archive.getFileName().toString().matches("[0-9a-f-]{36}\\.json"))
            throw new IOException("Caminho de histórico inválido.");
        Files.deleteIfExists(archive);
        Files.deleteIfExists(archive.resolveSibling(archive.getFileName() + ".tmp"));
    }

    private static void setProgress(TrainerPlayerData data, String series, Set<String> defeats) {
        SeriesManager manager = RCTMod.getInstance().getSeriesManager();
        SeriesManager.SeriesGraph graph = manager.getGraph(series);
        data.setCurrentSeries(series, false);
        if (!series.equals(normalizedSeries(data.getCurrentSeries())))
            throw new IllegalStateException("O RCT recusou a mudança de circuito.");
        var accessor = (br.com.cobbleverse.generations.mixin.TrainerProgressAccessor) data;
        accessor.generationControl$completed(true); // Admin edits must never grant RCT completion rewards.
        defeats.stream().sorted().forEach(data::addProgressDefeat);
        accessor.generationControl$completed(graph.size() > 0
                && graph.getRemaining(data.getDefeatedTrainerIds()).size() == 0);
        data.markDirty();
        data.sync();
    }

    private static void restoreLedger(java.nio.file.Path path, boolean hadLedger,
                                     Map<String, Set<String>> values) throws IOException {
        if (!hadLedger) {
            Files.deleteIfExists(path);
            return;
        }
        RctIntegration.ProgressLedger ledger = new RctIntegration.ProgressLedger();
        ledger.defeatsBySeries.putAll(copyLedger(values));
        ConfigStore.write(path, ledger);
    }

    private static java.nio.file.Path ledgerPath(java.nio.file.Path directory, UUID id) {
        return directory.resolve("migrations/progress-" + id + ".json").normalize();
    }

    private static Map<String, Set<String>> copyLedger(Map<String, Set<String>> ledger) {
        TreeMap<String, Set<String>> copy = new TreeMap<>();
        if (ledger != null) ledger.forEach((series, ids) -> {
            if (series != null && ids != null) copy.put(series, new TreeSet<>(ids));
        });
        return copy;
    }

    private static String fingerprint(UUID id, TrainerPlayerData data, PlayerState state) {
        ProgressSnapshot snapshot = new ProgressSnapshot(id.toString(), normalizedSeries(data.getCurrentSeries()),
                state.getLevelCap(), sorted(data.getDefeatedTrainerIds()), new TreeMap<>(data.getCompletedSeries()));
        try {
            byte[] bytes = ConfigStore.GSON.toJson(snapshot).getBytes(StandardCharsets.UTF_8);
            byte[] digest = java.security.MessageDigest.getInstance("SHA-256").digest(bytes);
            return java.util.HexFormat.of().formatHex(digest);
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 não está disponível.", impossible);
        }
    }

    private static TreeSet<String> trainerIds(SeriesManager.SeriesGraph graph) {
        return graph.stream().map(SeriesManager.TrainerNode::id).collect(Collectors.toCollection(TreeSet::new));
    }

    private static TreeSet<String> sorted(Collection<String> values) { return new TreeSet<>(values); }

    private static String normalizedSeries(String series) {
        return series == null || series.isBlank() ? "empty" : series;
    }

    private static UUID parseUuid(String value) {
        try { return UUID.fromString(value); }
        catch (RuntimeException invalid) { throw new WebPanelService.Problem(400, "UUID de jogador inválido."); }
    }

    private static String sanitizeActor(String actor) {
        if (actor == null || actor.isBlank()) return "web-panel";
        String safe = actor.replaceAll("[\\r\\n\\t]", " ");
        return safe.length() > 64 ? safe.substring(0, 64) : safe;
    }
}
