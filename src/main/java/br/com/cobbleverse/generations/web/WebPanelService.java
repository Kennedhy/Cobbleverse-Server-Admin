package br.com.cobbleverse.generations.web;

import br.com.cobbleverse.generations.GenerationControl;
import br.com.cobbleverse.generations.config.ConfigStore;
import br.com.cobbleverse.generations.config.GenerationConfig;
import br.com.cobbleverse.generations.data.PolicyResources;
import br.com.cobbleverse.generations.integration.ValidationReport;
import br.com.cobbleverse.generations.integration.ValidationService;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.WorldSavePath;

import java.time.Instant;
import java.util.*;

/** Web requests use this service only on the Minecraft server thread. */
final class WebPanelService {
    record ActionRequest(String action, long expectedRevision, GenerationConfig candidate, String fingerprint) {}
    record PlayerEntry(String name, String uuid, boolean online) {}

    static final class Job {
        String id;
        String action;
        String status = "running";
        String message = "Validação em andamento.";
        long revision;
        long startedAt = System.currentTimeMillis();
        String fingerprint;
        GenerationConfig candidate;
        ValidationReport report;
    }

    private record Ticket(String fingerprint, long revision, long expiresAt) {}

    private final MinecraftServer server;
    private final Map<String, Job> jobs = new java.util.concurrent.ConcurrentHashMap<>();
    private final Map<String, Ticket> tickets = new java.util.concurrent.ConcurrentHashMap<>();

    WebPanelService(MinecraftServer server) { this.server = server; }

    void closeSession(String sessionId) {
        jobs.remove(sessionId);
        tickets.remove(sessionId);
    }

    Map<String, Object> snapshot(String sessionId) {
        var store = GenerationControl.store();
        var active = store.active();
        var pending = store.pending();
        LinkedHashMap<String, Object> state = new LinkedHashMap<>();
        var worldRoot = server.getSavePath(WorldSavePath.ROOT).toAbsolutePath().normalize();
        var worldName = worldRoot.getFileName();
        state.put("world", worldName == null ? worldRoot.toString() : worldName.toString());
        state.put("active", active);
        state.put("pending", pending);
        state.put("editRevision", store.editRevision());
        state.put("startupWarning", store.startupWarning());
        state.put("hasPrevious", java.nio.file.Files.isRegularFile(store.directory().resolve("previous.json")));
        state.put("averageTickMs", server.getAverageTickTime());
        Runtime runtime = Runtime.getRuntime();
        state.put("heapUsedMb", (runtime.totalMemory() - runtime.freeMemory()) / (1024 * 1024));
        state.put("heapMaxMb", runtime.maxMemory() / (1024 * 1024));
        state.put("transformedFiles", PolicyResources.transformedFiles);
        state.put("transformedTeams", PolicyResources.transformedTeams);
        List<PlayerEntry> online = new ArrayList<>();
        Map<String, PlayerEntry> known = new TreeMap<>();
        for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
            PlayerEntry entry = new PlayerEntry(player.getGameProfile().getName(), player.getUuidAsString(), true);
            online.add(entry);
            known.put(entry.uuid(), entry);
        }
        for (String id : active.playerXpMultipliers.keySet()) known.putIfAbsent(id, new PlayerEntry(id, id, false));
        for (String id : active.playerXpGroups.keySet()) known.putIfAbsent(id, new PlayerEntry(id, id, false));
        if (pending != null) {
            for (String id : pending.playerXpMultipliers.keySet()) known.putIfAbsent(id, new PlayerEntry(id, id, false));
            for (String id : pending.playerXpGroups.keySet()) known.putIfAbsent(id, new PlayerEntry(id, id, false));
        }
        state.put("onlinePlayers", online);
        state.put("knownPlayers", known.values());
        Job job = jobs.get(sessionId);
        state.put("job", job == null ? null : jobView(job));
        Ticket ticket = tickets.get(sessionId);
        if (ticket != null && ticket.expiresAt() <= System.currentTimeMillis()) {
            tickets.remove(sessionId);
            ticket = null;
        }
        state.put("validatedFingerprint", ticket == null ? null : ticket.fingerprint());
        state.put("validatedRevision", ticket == null ? null : ticket.revision());
        state.put("generatedAt", Instant.now().toString());
        return state;
    }

    Map<String, Object> job(String sessionId) {
        LinkedHashMap<String, Object> response = new LinkedHashMap<>();
        Job job = jobs.get(sessionId);
        response.put("job", job == null ? null : jobView(job));
        response.put("editRevision", GenerationControl.store().editRevision());
        return response;
    }

    Object action(String sessionId, ActionRequest request) {
        if (request == null || request.action() == null) throw new Problem(400, "Pedido incompleto.");
        var store = GenerationControl.store();
        if (request.expectedRevision() != store.editRevision())
            throw new Problem(409, "A configuração mudou em outra sessão. Atualize o painel antes de continuar.");
        return switch (request.action()) {
            case "validate" -> validate(sessionId, request);
            case "save" -> save(sessionId, request);
            case "restore" -> restore(sessionId, request);
            case "discard" -> discard(sessionId, request);
            default -> throw new Problem(400, "Operação desconhecida.");
        };
    }

    Object validate(String sessionId, ActionRequest request) {
        if (request.candidate() == null) throw new Problem(400, "Configuração ausente.");
        Job existing = jobs.get(sessionId);
        if (existing != null && existing.status.equals("running"))
            throw new Problem(409, "Esta sessão já tem uma validação em andamento. Aguarde o resultado.");
        GenerationConfig candidate;
        try { candidate = request.candidate().copy().validated(); }
        catch (RuntimeException invalid) { throw new Problem(400, message(invalid)); }
        String fingerprint = ConfigStore.fingerprint(candidate);
        Job job = new Job();
        job.id = UUID.randomUUID().toString();
        job.action = "validate";
        job.revision = request.expectedRevision();
        job.fingerprint = fingerprint;
        job.candidate = candidate.copy();
        jobs.put(sessionId, job);
        tickets.remove(sessionId);
        boolean started = ValidationService.start(server, candidate, report -> {
            if (jobs.get(sessionId) != job) return;
            job.report = report;
            if (GenerationControl.store().editRevision() != job.revision) {
                job.status = "invalid";
                job.message = "A configuração mudou durante a análise. Atualize e valide novamente.";
                return;
            }
            if (report.errors.isEmpty()) {
                job.status = "validated";
                job.message = "Configuração validada. Você pode salvar esta revisão pendente.";
                tickets.put(sessionId, new Ticket(fingerprint, request.expectedRevision(), System.currentTimeMillis() + 600_000));
            } else {
                job.status = "invalid";
                job.message = "Ajuste os problemas listados antes de salvar.";
            }
        });
        if (!started) {
            jobs.remove(sessionId, job);
            throw new Problem(409, "Já existe uma validação em andamento. Aguarde o resultado.");
        }
        return Map.of("job", jobView(job));
    }

    private Object save(String sessionId, ActionRequest request) {
        if (request.candidate() == null) throw new Problem(400, "Configuração ausente.");
        GenerationConfig candidate;
        try { candidate = request.candidate().copy().validated(); }
        catch (RuntimeException invalid) { throw new Problem(400, message(invalid)); }
        Ticket ticket = tickets.get(sessionId);
        String fingerprint = ConfigStore.fingerprint(candidate);
        if (ticket == null || ticket.expiresAt() <= System.currentTimeMillis()) {
            tickets.remove(sessionId);
            throw new Problem(409, "Valide novamente este rascunho antes de salvar.");
        }
        if (ticket.revision() != request.expectedRevision() || !ticket.fingerprint().equals(request.fingerprint())
                || !ticket.fingerprint().equals(fingerprint))
            throw new Problem(409, "O rascunho mudou depois da validação. Valide novamente antes de salvar.");
        try {
            GenerationConfig staged = GenerationControl.store().stage(candidate, request.expectedRevision(), "web-panel");
            tickets.remove(sessionId);
            Job job = jobs.get(sessionId);
            if (job != null && job.fingerprint.equals(fingerprint)) {
                job.status = "saved";
                job.message = "Alterações salvas como revisão pendente. Reinicie o servidor para aplicá-las.";
            }
            LinkedHashMap<String, Object> result = new LinkedHashMap<>();
            result.put("message", "Alterações salvas como revisão pendente. Reinicie o servidor para aplicá-las.");
            result.put("pending", staged);
            result.put("state", snapshot(sessionId));
            return result;
        } catch (Exception failure) { throw new Problem(400, message(failure)); }
    }

    private Object restore(String sessionId, ActionRequest request) {
        try {
            GenerationConfig staged = GenerationControl.store().stagePrevious(request.expectedRevision(), "web-panel");
            tickets.remove(sessionId);
            LinkedHashMap<String, Object> result = new LinkedHashMap<>();
            result.put("message", "A revisão anterior foi preparada. Reinicie o servidor para aplicá-la.");
            result.put("pending", staged);
            result.put("state", snapshot(sessionId));
            return result;
        } catch (Exception failure) { throw new Problem(400, message(failure)); }
    }

    private Object discard(String sessionId, ActionRequest request) {
        try {
            GenerationControl.store().discardPending("web-panel");
            tickets.remove(sessionId);
            LinkedHashMap<String, Object> result = new LinkedHashMap<>();
            result.put("message", "A configuração pendente foi descartada.");
            result.put("state", snapshot(sessionId));
            return result;
        } catch (Exception failure) { throw new Problem(400, message(failure)); }
    }

    Object jobView(Job job) {
        LinkedHashMap<String, Object> view = new LinkedHashMap<>();
        view.put("id", job.id);
        view.put("action", job.action);
        view.put("status", job.status);
        view.put("message", job.message);
        view.put("revision", job.revision);
        view.put("startedAt", job.startedAt);
        view.put("fingerprint", job.fingerprint);
        view.put("candidate", job.candidate);
        view.put("report", job.report);
        return view;
    }

    static final class Problem extends RuntimeException {
        final int status;
        Problem(int status, String message) { super(message); this.status = status; }
    }

    private static String message(Throwable failure) {
        String value = failure.getMessage();
        if (value == null || value.isBlank()) value = failure.getClass().getSimpleName();
        return value.length() > 512 ? value.substring(0, 512) + "…" : value;
    }
}
