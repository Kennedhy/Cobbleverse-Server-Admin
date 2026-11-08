package br.com.cobbleverse.generations.web;

import br.com.cobbleverse.generations.GenerationControl;
import br.com.cobbleverse.generations.config.ConfigStore;
import br.com.cobbleverse.generations.config.GenerationConfig;
import br.com.cobbleverse.generations.data.PolicyResources;
import br.com.cobbleverse.generations.integration.ValidationReport;
import br.com.cobbleverse.generations.integration.ValidationService;
import br.com.cobbleverse.generations.integration.CobblemonIntegration;
import br.com.cobbleverse.generations.integration.ExperienceManager;
import br.com.cobbleverse.generations.rules.ExperienceRules;
import br.com.cobbleverse.generations.rules.GenerationRules;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.WorldSavePath;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

/** Web requests use this service only on the Minecraft server thread. */
final class WebPanelService {
    record ActionRequest(String action, long expectedRevision, long targetRevision, GenerationConfig candidate, String fingerprint) {}
    record SimulationRequest(GenerationConfig candidate, String playerUuid, String species, String form, int baseXp, int remainingXp) {}
    record PresetRequest(String name, GenerationConfig candidate) {}
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
        Map<String, Object> resultState;
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

    Map<String, Object> snapshot(String sessionId) { return snapshot(sessionId, "admin"); }

    Map<String, Object> snapshot(String sessionId, String accessRole) {
        return snapshot(sessionId, accessRole, "web-panel");
    }

    Map<String, Object> snapshot(String sessionId, String accessRole, String accessActor) {
        var store = GenerationControl.store();
        var active = store.active();
        var pending = store.pending();
        LinkedHashMap<String, Object> state = new LinkedHashMap<>();
        var worldRoot = server.getSavePath(WorldSavePath.ROOT).toAbsolutePath().normalize();
        var worldName = worldRoot.getFileName();
        state.put("world", worldName == null ? worldRoot.toString() : worldName.toString());
        state.put("accessRole", accessRole);
        state.put("accessActor", accessActor);
        state.put("active", active);
        state.put("pending", pending);
        var runtimeGameplay = GenerationControl.gameplaySettings();
        state.put("liveGameplaySettings", runtimeGameplay == null ? Map.of() : runtimeGameplay.view());
        state.put("liveGameplayRevision", GenerationControl.gameplaySettingsRevision());
        state.put("pendingGameplaySettingsDiffer", pending != null && runtimeGameplay != null && !runtimeGameplay.matches(pending));
        state.put("editRevision", store.editRevision());
        state.put("startupWarning", store.startupWarning());
        state.put("hasPrevious", java.nio.file.Files.isRegularFile(store.directory().resolve("previous.json")));
        state.put("averageTickMs", server.getAverageTickTime());
        state.put("estimatedTps", ServerMetrics.estimateTps(server.getAverageTickTime()));
        Runtime runtime = Runtime.getRuntime();
        state.put("heapUsedMb", (runtime.totalMemory() - runtime.freeMemory()) / (1024 * 1024));
        state.put("heapMaxMb", runtime.maxMemory() / (1024 * 1024));
        state.put("transformedFiles", PolicyResources.transformedFiles);
        state.put("transformedTeams", PolicyResources.transformedTeams);
        state.put("persistenceQueue", br.com.cobbleverse.generations.config.ConfigPersistenceExecutor.metrics());
        state.put("rejectedValidations", ValidationService.rejectedStarts());
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

    Object simulate(SimulationRequest request) {
        if (request == null || request.candidate() == null) throw new Problem(400, "Configuração ausente.");
        if (request.species() == null || request.species().length() > 96 || request.form() != null && request.form().length() > 64)
            throw new Problem(400, "Espécie ou forma inválida.");
        if (request.baseXp() < 0 || request.baseXp() > 1_000_000 || request.remainingXp() < 0 || request.remainingXp() > 10_000_000)
            throw new Problem(400, "Valores de XP fora do limite permitido.");
        java.util.UUID playerId;
        try { playerId = java.util.UUID.fromString(request.playerUuid()); }
        catch (RuntimeException invalid) { throw new Problem(400, "UUID de jogador inválido."); }
        GenerationConfig candidate;
        try { candidate = request.candidate().copy().validated(); }
        catch (RuntimeException invalid) { throw new Problem(400, message(invalid)); }
        String key = GenerationRules.speciesKey(request.species());
        var species = CobblemonIntegration.species(key);
        if (species == null || !species.getImplemented()) throw new Problem(400, "Espécie desconhecida ou não implementada.");
        String requestedForm = request.form() == null || request.form().isBlank()
                ? species.getStandardForm().getName() : request.form().trim();
        String form = CobblemonIntegration.resolveForm(species, requestedForm);
        if (form == null) throw new Problem(400, "Forma não encontrada para esta espécie.");
        List<String> aspects = CobblemonIntegration.aspectsForForm(species, form);
        GenerationRules rules = new GenerationRules(candidate);
        boolean allowed = rules.allowed(key, species.getNationalPokedexNumber(), form, aspects);
        ExperienceRules.Multiplier multiplier = ExperienceRules.multiplier(candidate, playerId.toString());
        Integer awarded = candidate.manageExperience
                ? ExperienceRules.award(candidate, playerId.toString(), request.baseXp(), request.remainingXp()) : null;
        Integer cap = null;
        String capSource = "XP e limites estão desativados.";
        if (candidate.manageExperience) {
            if (candidate.levelCapMode.equals("global")) {
                cap = candidate.globalLevelCap;
                capSource = "Limite global";
            } else {
                var player = server.getPlayerManager().getPlayer(playerId);
                if (player == null) capSource = "Jogador offline; o progresso RCT não pode ser consultado agora.";
                else {
                    int value = ExperienceManager.previewCap(candidate, player);
                    if (value > 0) { cap = value; capSource = "Progresso RCT do jogador"; }
                    else capSource = "O progresso RCT ainda está carregando.";
                }
            }
        }
        LinkedHashMap<String, Object> result = new LinkedHashMap<>();
        result.put("species", key);
        result.put("form", form);
        result.put("generation", GenerationRules.generation(species.getNationalPokedexNumber()));
        result.put("allowed", allowed);
        result.put("reason", rules.explain(key, species.getNationalPokedexNumber(), form, aspects));
        result.put("xpManaged", candidate.manageExperience);
        result.put("xpSource", multiplier.source());
        result.put("xpGroup", multiplier.group());
        result.put("globalMultiplier", multiplier.global());
        result.put("specificMultiplier", multiplier.player());
        result.put("totalMultiplier", multiplier.total());
        result.put("baseXp", request.baseXp());
        result.put("remainingXp", request.remainingXp());
        result.put("awardedXp", awarded);
        result.put("levelCap", cap);
        result.put("levelCapSource", capSource);
        result.put("wildLevelBehavior", candidate.wildLevelBehavior);
        return result;
    }

    Object action(String sessionId, ActionRequest request) {
        if (request == null || request.action() == null) throw new Problem(400, "Pedido incompleto.");
        var store = GenerationControl.store();
        if (request.expectedRevision() != store.editRevision())
            throw new Problem(409, "A configuração mudou em outra sessão. Atualize o painel antes de continuar.");
        return switch (request.action()) {
            case "validate" -> validate(sessionId, request);
            case "cancelValidation" -> cancelValidation(sessionId);
            default -> throw new Problem(400, "Operação desconhecida.");
        };
    }

    Map<String, Object> applyLiveGameplaySettings(String sessionId, String accessRole, String accessActor,
                                                   long expectedRevision) {
        var store = GenerationControl.store();
        if (expectedRevision != store.editRevision())
            throw new Problem(409, "A configuração mudou em outra sessão. Atualize o painel antes de aplicar ao vivo.");
        if (store.pending() == null)
            throw new Problem(409, "Salve uma revisão pendente antes de aplicar XP, limites ou captura ao vivo.");
        try { GenerationControl.applyPendingGameplaySettings(expectedRevision); }
        catch (IllegalStateException conflict) { throw new Problem(409, message(conflict)); }
        return snapshot(sessionId, accessRole, accessActor);
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

    Object beginSave(String sessionId, ActionRequest request, Executor ioExecutor, Executor gameExecutor) {
        return beginSave(sessionId, "web-panel", request, ioExecutor, gameExecutor);
    }

    Object beginSave(String sessionId, String author, ActionRequest request, Executor ioExecutor, Executor gameExecutor) {
        if (request == null || request.candidate() == null) throw new Problem(400, "Configuração ausente.");
        var store = GenerationControl.store();
        if (request.expectedRevision() != store.editRevision())
            throw new Problem(409, "A configuração mudou em outra sessão. Atualize o painel antes de continuar.");
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
        Job job = jobs.get(sessionId);
        if (job == null || !job.status.equals("validated") || !job.fingerprint.equals(fingerprint)
                || job.revision != request.expectedRevision())
            throw new Problem(409, "A validação não está mais disponível. Valide novamente antes de salvar.");

        job.action = "save";
        job.status = "running";
        job.startedAt = System.currentTimeMillis();
        job.message = "Salvando a revisão em segundo plano…";
        tickets.remove(sessionId);
        try {
            store.stageAsync(candidate, request.expectedRevision(), author, ioExecutor, gameExecutor)
                    .whenComplete((staged, failure) -> {
                        if (jobs.get(sessionId) != job) return;
                        if (failure != null) {
                            job.status = "invalid";
                            job.message = message(failure instanceof java.util.concurrent.CompletionException && failure.getCause() != null
                                    ? failure.getCause() : failure);
                            return;
                        }
                        job.status = "saved";
                        job.message = "Alterações salvas como revisão pendente. XP, limites e captura podem ser aplicados ao vivo; gerações, ginásios e mundo exigem reinício.";
                        job.resultState = snapshot(sessionId, "admin", actorName(author));
                    });
        } catch (RuntimeException failure) {
            job.action = "validate";
            job.status = "validated";
            job.message = message(failure);
            throw new Problem(409, message(failure));
        }
        return Map.of("job", jobView(job));
    }

    Object beginRecoveryAction(String sessionId, ActionRequest request, Executor ioExecutor, Executor gameExecutor) {
        return beginRecoveryAction(sessionId, "web-panel", request, ioExecutor, gameExecutor);
    }

    Object beginRecoveryAction(String sessionId, String author, ActionRequest request, Executor ioExecutor, Executor gameExecutor) {
        if (request == null || request.action() == null) throw new Problem(400, "Pedido incompleto.");
        var store = GenerationControl.store();
        if (request.expectedRevision() != store.editRevision())
            throw new Problem(409, "A configuração mudou em outra sessão. Atualize o painel antes de continuar.");
        Job existing = jobs.get(sessionId);
        if (existing != null && existing.status.equals("running"))
            throw new Problem(409, "Esta sessão já tem uma operação em andamento.");
        if (!Set.of("restore", "restoreRevision", "discard").contains(request.action()))
            throw new Problem(400, "Operação de recuperação desconhecida.");

        Job job = new Job();
        job.id = UUID.randomUUID().toString();
        job.action = request.action();
        job.revision = request.expectedRevision();
        job.status = "running";
        job.startedAt = System.currentTimeMillis();
        job.message = request.action().equals("discard") ? "Descartando a revisão pendente…" : "Preparando a revisão em segundo plano…";
        jobs.put(sessionId, job);
        tickets.remove(sessionId);

        CompletableFuture<?> operation;
        try {
            operation = switch (request.action()) {
                case "restore" -> store.stagePreviousAsync(request.expectedRevision(), author, ioExecutor, gameExecutor);
                case "restoreRevision" -> store.stageRevisionAsync(request.targetRevision(), request.expectedRevision(), author, ioExecutor, gameExecutor);
                case "discard" -> store.discardPendingAsync(request.expectedRevision(), author, ioExecutor, gameExecutor);
                default -> throw new IllegalStateException("Unreachable action.");
            };
        } catch (RuntimeException failure) {
            job.status = "invalid";
            job.message = message(failure);
            job.resultState = snapshot(sessionId, "admin", actorName(author));
            return Map.of("job", jobView(job));
        }
        operation.whenComplete((value, failure) -> {
            try {
                gameExecutor.execute(() -> {
                    if (jobs.get(sessionId) != job) return;
                    if (failure != null) {
                        Throwable cause = failure instanceof java.util.concurrent.CompletionException && failure.getCause() != null
                                ? failure.getCause() : failure;
                        job.status = "invalid";
                        job.message = message(cause);
                    } else {
                        if (job.action.equals("discard")) GenerationControl.resetGameplaySettingsToActive();
                        job.status = "completed";
                        job.message = switch (job.action) {
                            case "restore" -> "A revisão anterior foi preparada. Reinicie o servidor para aplicá-la.";
                            case "restoreRevision" -> "A revisão " + request.targetRevision() + " foi preparada. Reinicie o servidor para aplicá-la.";
                            default -> "A configuração pendente foi descartada.";
                        };
                    }
                    job.resultState = snapshot(sessionId, "admin", actorName(author));
                });
            } catch (RuntimeException rejected) {
                job.status = "invalid";
                job.message = message(rejected);
            }
        });
        return Map.of("job", jobView(job));
    }

    private static String actorName(String author) {
        return author.startsWith("web:") ? author.substring("web:".length()) : author;
    }

    private Object cancelValidation(String sessionId) {
        Job job = jobs.get(sessionId);
        if (job == null || !job.status.equals("running") || !job.action.equals("validate"))
            throw new Problem(409, "Esta sessão não tem uma validação em andamento para cancelar.");
        if (!ValidationService.cancel(server)) throw new Problem(409, "A validação já terminou ou não está disponível.");
        job.status = "cancelled";
        job.message = "Validação cancelada. Nenhuma configuração foi salva.";
        return Map.of("job", jobView(job));
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
        view.put("state", job.resultState);
        view.put("progress", job.status.equals("running") && job.action.equals("validate")
                ? ValidationService.progress(server) : null);
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
