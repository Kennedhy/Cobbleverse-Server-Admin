package br.com.cobbleverse.generations.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.text.Normalizer;
import java.time.Instant;
import java.util.Objects;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

/** One policy directory per world. A staged revision never changes the live policy. */
public final class ConfigStore {
    public static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    private static final int MAX_PRESETS = 20;
    private static final Object PRESET_IO_LOCK = new Object();
    public record HistoryEntry(long revision, String author, String time, long basedOnRevision, int changedFields) {}
    public record FieldChange(String field, String before, String after) {}
    public record HistoryDetail(long revision, String author, String time, long basedOnRevision, List<FieldChange> changes) {}
    public record PresetInfo(String id, String name, String updatedAt) {}
    private record SavedPreset(String name, String updatedAt, GenerationConfig config) {}
    public static String fingerprint(GenerationConfig config) {
        try {
            byte[] bytes=GSON.toJson(config).getBytes(StandardCharsets.UTF_8);
            return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
    private final Path directory;
    private GenerationConfig active;
    private GenerationConfig pending;
    private long editRevision;
    private String startupWarning;
    private boolean stageInProgress;
    private record StagePlan(GenerationConfig next, long expectedRevision, long previousActiveRevision,
                             long previousEditRevision, GenerationConfig before, String actor, String time) {}
    private record DiscardTransaction(long pendingRevision, long nextEditRevision, long activeRevision, String actor) {}

    public ConfigStore(Path directory) { this.directory = directory.toAbsolutePath().normalize(); }

    public synchronized void open() throws IOException {
        Files.createDirectories(directory.resolve("history"));
        Path activeFile = directory.resolve("active.json");
        active = Files.exists(activeFile) ? read(activeFile) : new GenerationConfig().validated();
        if (!Files.exists(activeFile)) write(activeFile, active);
        Path activeHistory=directory.resolve("history/revision-"+active.revision+".json");
        if(!Files.exists(activeHistory)) write(activeHistory,active);
        Path pendingFile = directory.resolve("pending.json");
        try {
            pending = Files.exists(pendingFile) ? read(pendingFile) : null;
            if (pending != null && pending.revision < active.revision) throw new IOException("A revisão pendente é anterior à ativa; use o comando de restauração.");
            if (pending != null && pending.revision == active.revision) {
                if (!GSON.toJson(active).equals(GSON.toJson(pending))) throw new IOException("Revisões iguais com conteúdo diferente.");
                Files.deleteIfExists(pendingFile); pending=null;
            }
        } catch(IOException invalid) {
            Path rejected=directory.resolve("rejected/pending-"+System.currentTimeMillis()+".json");
            Files.createDirectories(rejected.getParent());
            Files.move(pendingFile,rejected,StandardCopyOption.REPLACE_EXISTING);
            pending=null;
            recordStartupWarning("Configuração pendente inválida rejeitada; a revisão ativa foi preservada. Arquivo: "+rejected.getFileName()+". "+invalid.getMessage());
            audit("REJECT_PARSE","startup",active.revision,active.revision);
        }
        recoverStageTransaction();
        recoverDiscardTransaction();
        Path notice=directory.resolve("startup-warning.json");
        if(startupWarning==null && Files.isRegularFile(notice) && Files.size(notice)<32768) {
            try { startupWarning=GSON.fromJson(Files.readString(notice),String.class); }
            catch(RuntimeException ignored) { startupWarning="O aviso de inicialização anterior está ilegível; consulte o histórico de revisões."; }
        }
        Path editFile = directory.resolve("edit-revision.json");
        editRevision = Files.exists(editFile) ? GSON.fromJson(Files.readString(editFile), Long.class) : 0;
        editRevision = Math.max(editRevision, Math.max(active.revision, pending == null ? 0 : pending.revision));
    }

    public synchronized GenerationConfig active() { return Objects.requireNonNull(active, "Configuração não inicializada").copy(); }
    public synchronized GenerationConfig pending() { return pending == null ? null : pending.copy(); }
    public synchronized long editRevision() { return editRevision; }
    public Path directory() { return directory; }
    public synchronized String startupWarning() { return startupWarning; }
    public synchronized void recordStartupWarning(String message) throws IOException {
        if(message.length()>4096) message=message.substring(0,4096)+"...";
        startupWarning=message;
        write(directory.resolve("startup-warning.json"),message);
    }

    public synchronized GenerationConfig stage(GenerationConfig candidate, long expectedRevision, String actor) throws IOException {
        StagePlan plan = prepareStage(candidate, expectedRevision, actor);
        try {
            persistStage(plan);
            return commitStage(plan);
        } catch (IOException | RuntimeException failure) {
            stageInProgress = false;
            throw failure;
        }
    }

    /** Reserve the next edit revision, write files off-thread, then publish on the game thread. */
    public CompletableFuture<GenerationConfig> stageAsync(GenerationConfig candidate, long expectedRevision, String actor,
                                                           Executor ioExecutor, Executor gameExecutor) {
        final StagePlan plan;
        synchronized (this) {
            plan = prepareStage(candidate, expectedRevision, actor);
        }
        CompletableFuture<GenerationConfig> result = new CompletableFuture<>();
        try {
            ioExecutor.execute(() -> {
                try {
                    persistStage(plan);
                } catch (Throwable failure) {
                    completeOnGameThread(gameExecutor, result, () -> {
                        failStage(failure);
                        throw new java.util.concurrent.CompletionException(failure);
                    });
                    return;
                }
                completeOnGameThread(gameExecutor, result, () -> commitStage(plan));
            });
        } catch (RuntimeException rejected) {
            failStage(rejected);
            result.completeExceptionally(rejected);
        }
        return result;
    }

    public CompletableFuture<GenerationConfig> stagePreviousAsync(long expectedRevision, String actor,
                                                                   Executor ioExecutor, Executor gameExecutor) {
        return stageFromFileAsync(directory.resolve("previous.json"), expectedRevision, actor, ioExecutor, gameExecutor);
    }

    public CompletableFuture<GenerationConfig> stageRevisionAsync(long revision, long expectedRevision, String actor,
                                                                   Executor ioExecutor, Executor gameExecutor) {
        if (revision < 1) throw new IllegalArgumentException("Revisão inválida.");
        Path snapshot = directory.resolve("history/revision-" + revision + ".json").normalize();
        if (!snapshot.getParent().equals(directory.resolve("history"))) throw new IllegalArgumentException("Revisão inválida.");
        return stageFromFileAsync(snapshot, expectedRevision, actor, ioExecutor, gameExecutor);
    }

    private CompletableFuture<GenerationConfig> stageFromFileAsync(Path source, long expectedRevision, String actor,
                                                                     Executor ioExecutor, Executor gameExecutor) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                if (!Files.isRegularFile(source)) throw new IllegalStateException("A revisão escolhida não existe no histórico.");
                return read(source);
            } catch (IOException failure) { throw new java.util.concurrent.CompletionException(failure); }
        }, ioExecutor).thenCompose(candidate -> stageAsync(candidate, expectedRevision, actor, ioExecutor, gameExecutor));
    }

    /** Delete a pending revision off-thread; the durable discard journal lets startup finish an interrupted operation. */
    public CompletableFuture<Long> discardPendingAsync(long expectedRevision, String actor, Executor ioExecutor, Executor gameExecutor) {
        final DiscardTransaction transaction;
        synchronized (this) {
            if (stageInProgress) throw new IllegalStateException("Há outra gravação em andamento.");
            if (expectedRevision != editRevision) throw new IllegalStateException("A revisão mudou. Atualize o painel.");
            if (pending == null) return CompletableFuture.completedFuture(editRevision);
            String safeActor = actor == null ? "unknown" : actor.replaceAll("[\\r\\n\\t]", " ");
            if (safeActor.length() > 64) safeActor = safeActor.substring(0, 64);
            transaction = new DiscardTransaction(pending.revision, Math.addExact(editRevision, 1), active.revision, safeActor);
            stageInProgress = true;
        }
        CompletableFuture<Long> result = new CompletableFuture<>();
        try {
            ioExecutor.execute(() -> {
                Path journal = directory.resolve("discard-transaction.json");
                Path pendingFile = directory.resolve("pending.json");
                boolean committed = false;
                try {
                    if (!pendingMatchesRevision(transaction.pendingRevision())) throw new IOException("A revisão pendente mudou antes do descarte.");
                    write(journal, transaction);
                    Files.deleteIfExists(pendingFile);
                    if (Files.exists(pendingFile)) throw new IOException("Não foi possível remover a configuração pendente.");
                    committed = true;
                    try {
                        write(directory.resolve("edit-revision.json"), transaction.nextEditRevision());
                        try { audit("DISCARD", transaction.actor(), transaction.pendingRevision(), transaction.activeRevision()); }
                        catch (IOException ignored) { }
                        Files.deleteIfExists(journal);
                    } catch (IOException ignored) {
                        // Startup replays the durable journal; deleting pending.json is the commit point.
                    }
                } catch (Throwable failure) {
                    if (committed || !Files.exists(pendingFile)) committed = true;
                    if (!committed) {
                        try { Files.deleteIfExists(journal); } catch (IOException ignored) { }
                        completeOnGameThread(gameExecutor, result, () -> {
                            failStage(failure);
                            throw new java.util.concurrent.CompletionException(failure);
                        });
                        return;
                    }
                }
                completeOnGameThread(gameExecutor, result, () -> commitDiscard(transaction));
            });
        } catch (RuntimeException rejected) {
            failStage(rejected);
            result.completeExceptionally(rejected);
        }
        return result;
    }

    private boolean pendingMatchesRevision(long revision) {
        try { return Files.isRegularFile(directory.resolve("pending.json")) && read(directory.resolve("pending.json")).revision == revision; }
        catch (IOException | RuntimeException invalid) { return false; }
    }

    private synchronized long commitDiscard(DiscardTransaction transaction) {
        if (!stageInProgress || editRevision + 1 != transaction.nextEditRevision())
            throw new IllegalStateException("A revisão mudou antes da confirmação do descarte.");
        pending = null;
        editRevision = transaction.nextEditRevision();
        stageInProgress = false;
        return editRevision;
    }

    private <T> void completeOnGameThread(Executor gameExecutor, CompletableFuture<T> result,
                                          java.util.concurrent.Callable<T> action) {
        try {
            gameExecutor.execute(() -> {
                try { result.complete(action.call()); }
                catch (Throwable failure) { result.completeExceptionally(failure); }
            });
        } catch (RuntimeException rejected) {
            failStage(rejected);
            result.completeExceptionally(rejected);
        }
    }

    private synchronized void failStage(Throwable failure) {
        stageInProgress = false;
    }

    private synchronized StagePlan prepareStage(GenerationConfig candidate, long expectedRevision, String actor) {
        if (stageInProgress) throw new IllegalStateException("Outro salvamento está em andamento. Aguarde antes de alterar a configuração.");
        if (expectedRevision != editRevision()) throw new IllegalStateException("Outro administrador alterou a configuração. Reabra o painel.");
        GenerationConfig next = candidate.copy().validated();
        next.revision = Math.addExact(editRevision(), 1);
        String safeActor = actor == null ? "unknown" : actor.replaceAll("[\\r\\n\\t]", " ");
        if (safeActor.length() > 64) safeActor = safeActor.substring(0, 64);
        StagePlan plan = new StagePlan(next, expectedRevision, active.revision, editRevision,
                pending == null ? active.copy() : pending.copy(), safeActor, Instant.now().toString());
        stageInProgress = true;
        return plan;
    }

    private void persistStage(StagePlan plan) throws IOException {
        GenerationConfig next = plan.next();
        Path journal = directory.resolve("stage-transaction.json");
        try {
            write(journal, plan);
            write(directory.resolve("history/revision-" + next.revision + ".json"), next);
            write(directory.resolve("pending.json"), next);
        } catch (IOException failure) {
            // The pending file is the commit point. If its atomic replacement succeeded despite a late I/O error,
            // preserve the committed revision and let startup reconcile the monotonic counter.
            if (!pendingMatches(next)) {
                Files.deleteIfExists(journal);
                Files.deleteIfExists(directory.resolve("history/change-" + next.revision + ".json"));
                Files.deleteIfExists(directory.resolve("history/revision-" + next.revision + ".json"));
                throw failure;
            }
        }
        try {
            write(directory.resolve("history/change-" + next.revision + ".json"), java.util.Map.of(
                    "author", plan.actor(), "time", plan.time(), "previousActiveRevision", plan.previousActiveRevision(),
                    "previousEditRevision", plan.previousEditRevision(), "before", plan.before(), "after", next));
            write(directory.resolve("edit-revision.json"), next.revision);
            Files.deleteIfExists(journal);
        } catch (IOException ignored) {
            // Startup replays the change record from the journal if its commit marker is already durable.
        }
        try { audit("STAGE", plan.actor(), plan.previousActiveRevision(), next.revision); }
        catch (IOException ignored) { /* The history record remains the durable audit source. */ }
    }

    private void recoverStageTransaction() throws IOException {
        Path journal = directory.resolve("stage-transaction.json");
        if (!Files.isRegularFile(journal)) return;
        StagePlan plan = null;
        try {
            if (Files.size(journal) > 262144) throw new IOException("Transação excede o limite.");
            plan = GSON.fromJson(Files.readString(journal, StandardCharsets.UTF_8), StagePlan.class);
            if (plan == null || plan.next() == null || plan.before() == null || plan.next().revision < 1)
                throw new IOException("Transação incompleta.");
        } catch (RuntimeException | IOException invalid) {
            Path rejected = directory.resolve("rejected/stage-transaction-" + System.currentTimeMillis() + ".json");
            Files.createDirectories(rejected.getParent());
            Files.move(journal, rejected, StandardCopyOption.REPLACE_EXISTING);
            recordStartupWarning("Transação de configuração incompleta preservada para inspeção. A configuração pendente continua sendo a fonte confirmada.");
            return;
        }
        long revision = plan.next().revision;
        Path pendingFile = directory.resolve("pending.json");
        boolean committed = Files.isRegularFile(pendingFile) && pendingMatches(plan.next());
        Path snapshot = directory.resolve("history/revision-" + revision + ".json");
        Path change = directory.resolve("history/change-" + revision + ".json");
        if (committed) {
            write(snapshot, plan.next());
            write(change, java.util.Map.of("author", plan.actor(), "time", plan.time(),
                    "previousActiveRevision", plan.previousActiveRevision(), "previousEditRevision", plan.previousEditRevision(),
                    "before", plan.before(), "after", plan.next()));
            write(directory.resolve("edit-revision.json"), revision);
        } else {
            Files.deleteIfExists(change);
            if (Files.isRegularFile(snapshot)) {
                try {
                    if (GSON.toJson(read(snapshot)).equals(GSON.toJson(plan.next()))) Files.deleteIfExists(snapshot);
                } catch (IOException ignored) { Files.deleteIfExists(snapshot); }
            }
        }
        Files.deleteIfExists(journal);
    }

    private void recoverDiscardTransaction() throws IOException {
        Path journal = directory.resolve("discard-transaction.json");
        if (!Files.isRegularFile(journal)) return;
        DiscardTransaction transaction;
        try {
            if (Files.size(journal) > 32768) throw new IOException("Transação de descarte excede o limite.");
            transaction = GSON.fromJson(Files.readString(journal, StandardCharsets.UTF_8), DiscardTransaction.class);
            if (transaction == null || transaction.pendingRevision() < 1
                    || transaction.nextEditRevision() <= transaction.pendingRevision() || transaction.actor() == null)
                throw new IOException("Transação de descarte incompleta.");
        } catch (RuntimeException | IOException invalid) {
            Path rejected = directory.resolve("rejected/discard-transaction-" + System.currentTimeMillis() + ".json");
            Files.createDirectories(rejected.getParent());
            Files.move(journal, rejected, StandardCopyOption.REPLACE_EXISTING);
            recordStartupWarning("Transação de descarte inválida preservada para inspeção; a configuração pendente foi mantida.");
            return;
        }

        Path pendingFile = directory.resolve("pending.json");
        if (Files.isRegularFile(pendingFile)) {
            GenerationConfig onDisk = read(pendingFile);
            if (onDisk.revision == transaction.pendingRevision()) Files.delete(pendingFile);
            else if (onDisk.revision < transaction.nextEditRevision()) {
                Path rejected = directory.resolve("rejected/discard-transaction-" + System.currentTimeMillis() + ".json");
                Files.createDirectories(rejected.getParent());
                Files.move(journal, rejected, StandardCopyOption.REPLACE_EXISTING);
                recordStartupWarning("Transação de descarte não corresponde à configuração pendente; ambas foram preservadas.");
                return;
            }
        }
        Path editFile = directory.resolve("edit-revision.json");
        long storedRevision = Files.isRegularFile(editFile) ? GSON.fromJson(Files.readString(editFile), Long.class) : 0;
        editRevision = Math.max(Math.max(storedRevision, transaction.nextEditRevision()), active.revision);
        pending = Files.isRegularFile(pendingFile) ? read(pendingFile) : null;
        if (pending != null) editRevision = Math.max(editRevision, pending.revision);
        write(editFile, editRevision);
        try { audit("DISCARD", transaction.actor(), transaction.pendingRevision(), transaction.activeRevision()); }
        catch (IOException ignored) { }
        Files.deleteIfExists(journal);
    }

    private boolean pendingMatches(GenerationConfig expected) {
        try { return Files.isRegularFile(directory.resolve("pending.json"))
                && GSON.toJson(read(directory.resolve("pending.json"))).equals(GSON.toJson(expected)); }
        catch (IOException | RuntimeException invalid) { return false; }
    }

    private synchronized GenerationConfig commitStage(StagePlan plan) {
        if (!stageInProgress || editRevision != plan.expectedRevision())
            throw new IllegalStateException("A revisão mudou antes da confirmação do salvamento.");
        pending = plan.next().copy();
        editRevision = plan.next().revision;
        stageInProgress = false;
        return pending.copy();
    }

    /** Called before world resources load. Readiness validation belongs to the caller. */
    public synchronized void activatePending(String actor) throws IOException {
        if (stageInProgress) throw new IllegalStateException("Há um salvamento em andamento.");
        if (pending == null) return;
        write(directory.resolve("previous.json"), active);
        write(directory.resolve("active.json"), pending);
        long before = active.revision;
        active = pending;
        pending = null;
        Files.deleteIfExists(directory.resolve("pending.json"));
        audit("ACTIVATE", actor, before, active.revision);
    }

    public synchronized GenerationConfig stagePrevious(long expectedRevision, String actor) throws IOException {
        Path previous = directory.resolve("previous.json");
        if (!Files.exists(previous)) throw new IllegalStateException("Não há revisão anterior para restaurar.");
        return stage(read(previous), expectedRevision, actor);
    }

    public synchronized GenerationConfig stageRevision(long revision, long expectedRevision, String actor) throws IOException {
        if (revision < 1) throw new IllegalArgumentException("Revisão inválida.");
        Path snapshot = directory.resolve("history/revision-" + revision + ".json").normalize();
        if (!snapshot.getParent().equals(directory.resolve("history")) || !Files.isRegularFile(snapshot))
            throw new IllegalStateException("A revisão escolhida não existe no histórico.");
        return stage(read(snapshot), expectedRevision, actor);
    }

    /** Read at most the newest 50 bounded change records for the web history page. */
    public List<HistoryEntry> recentChanges() throws IOException {
        Path history = directory.resolve("history");
        if (!Files.isDirectory(history)) return List.of();
        List<Path> files;
        try (Stream<Path> stream = Files.list(history)) {
            files = stream.filter(path -> path.getFileName().toString().matches("change-[1-9][0-9]*\\.json"))
                    .sorted(Comparator.comparingLong(ConfigStore::historyRevision).reversed()).limit(50).toList();
        }
        List<HistoryEntry> entries = new ArrayList<>(files.size());
        for (Path file : files) {
            try {
                ChangeRecord change = readChange(file);
                entries.add(new HistoryEntry(change.revision(), safeAuthor(change.author), safeTime(change.time),
                        change.previousActiveRevision, changedFields(change.before, change.after).size()));
            } catch (IOException invalidRecord) {
                // One damaged history file must not hide the remaining valid revisions.
            }
        }
        return List.copyOf(entries);
    }

    public HistoryDetail historyDetail(long revision) throws IOException {
        if (revision < 1) throw new IllegalArgumentException("Revisão inválida.");
        Path file = directory.resolve("history/change-" + revision + ".json").normalize();
        if (!file.getParent().equals(directory.resolve("history")) || !Files.isRegularFile(file))
            throw new IllegalStateException("A alteração não existe no histórico.");
        ChangeRecord change = readChange(file);
        return new HistoryDetail(revision, safeAuthor(change.author), safeTime(change.time),
                change.previousActiveRevision, changedFields(change.before, change.after));
    }

    public List<PresetInfo> listPresets() throws IOException {
        synchronized (PRESET_IO_LOCK) {
            Path presets = directory.resolve("presets");
            if (!Files.isDirectory(presets)) return List.of();
            List<Path> files;
            try (Stream<Path> stream = Files.list(presets)) {
                files = stream.filter(path -> path.getFileName().toString().matches("[a-z0-9]+(?:-[a-z0-9]+)*\\.json"))
                        .sorted(Comparator.comparing(path -> path.getFileName().toString())).limit(MAX_PRESETS + 1L).toList();
            }
            if (files.size() > MAX_PRESETS) throw new IOException("O diretório contém mais presets do que o limite permitido.");
            List<PresetInfo> result = new ArrayList<>(files.size());
            for (Path file : files) {
                SavedPreset preset = readPresetFile(file);
                String filename = file.getFileName().toString();
                result.add(new PresetInfo(filename.substring(0, filename.length() - 5), preset.name(), preset.updatedAt()));
            }
            return List.copyOf(result);
        }
    }

    public GenerationConfig preset(String id) throws IOException {
        synchronized (PRESET_IO_LOCK) {
            Path file = presetPath(id);
            if (!Files.isRegularFile(file)) throw new IllegalStateException("O preset não existe.");
            return readPresetFile(file).config().copy();
        }
    }

    public PresetInfo savePreset(String name, GenerationConfig candidate) throws IOException {
        String cleanName = validatePresetName(name);
        String id = presetId(cleanName);
        GenerationConfig config = candidate.copy().validated();
        synchronized (PRESET_IO_LOCK) {
            Path presets = directory.resolve("presets");
            Files.createDirectories(presets);
            Path target = presetPath(id);
            if (!Files.isRegularFile(target)) {
                try (Stream<Path> stream = Files.list(presets)) {
                    long count = stream.filter(path -> path.getFileName().toString().matches("[a-z0-9]+(?:-[a-z0-9]+)*\\.json")).count();
                    if (count >= MAX_PRESETS) throw new IllegalStateException("O servidor permite até " + MAX_PRESETS + " presets nomeados.");
                }
            } else {
                SavedPreset previous = readPresetFile(target);
                if (!previous.name().equalsIgnoreCase(cleanName))
                    throw new IllegalStateException("Esse nome gera o mesmo identificador de outro preset.");
            }
            String updatedAt = Instant.now().toString();
            write(target, new SavedPreset(cleanName, updatedAt, config));
            return new PresetInfo(id, cleanName, updatedAt);
        }
    }

    public void deletePreset(String id) throws IOException {
        synchronized (PRESET_IO_LOCK) {
            Path target = presetPath(id);
            if (!Files.isRegularFile(target)) throw new IllegalStateException("O preset não existe.");
            Files.delete(target);
        }
    }

    private Path presetPath(String id) {
        if (id == null || id.length() > 48 || !id.matches("[a-z0-9]+(?:-[a-z0-9]+)*"))
            throw new IllegalArgumentException("Identificador de preset inválido.");
        Path presets = directory.resolve("presets").normalize();
        Path target = presets.resolve(id + ".json").normalize();
        if (!target.getParent().equals(presets)) throw new IllegalArgumentException("Identificador de preset inválido.");
        return target;
    }

    private SavedPreset readPresetFile(Path file) throws IOException {
        if (!Files.isRegularFile(file) || Files.size(file) > 65536) throw new IOException("Preset ausente ou acima do limite de 64 KiB.");
        SavedPreset preset;
        try { preset = GSON.fromJson(Files.readString(file, StandardCharsets.UTF_8), SavedPreset.class); }
        catch (RuntimeException invalid) { throw new IOException("Preset inválido.", invalid); }
        if (preset == null || preset.name() == null || preset.config() == null || preset.updatedAt() == null)
            throw new IOException("Preset incompleto.");
        try { preset.config().validated(); }
        catch (RuntimeException invalid) { throw new IOException("Configuração do preset inválida.", invalid); }
        return preset;
    }

    private static String validatePresetName(String value) {
        String name = value == null ? "" : value.trim();
        if (name.isEmpty() || name.length() > 48 || !name.codePoints().allMatch(c -> Character.isLetterOrDigit(c) || c == ' ' || c == '_' || c == '-'))
            throw new IllegalArgumentException("Use um nome de 1 a 48 letras, números, espaços, hífens ou sublinhados.");
        String id = presetId(name);
        if (id.isEmpty() || id.length() > 48)
            throw new IllegalArgumentException("O nome precisa gerar um identificador de até 48 caracteres.");
        return name;
    }

    private static String presetId(String name) {
        String ascii = Normalizer.normalize(name, Normalizer.Form.NFD).replaceAll("\\p{M}+", "").toLowerCase(java.util.Locale.ROOT);
        return ascii.replaceAll("[^a-z0-9]+", "-").replaceAll("^-|-$", "");
    }

    private record ChangeRecord(String author, String time, long previousActiveRevision,
                                long previousEditRevision, GenerationConfig before, GenerationConfig after) {
        long revision() { return after == null ? 0 : after.revision; }
    }

    private ChangeRecord readChange(Path file) throws IOException {
        if (Files.size(file) > 262144) throw new IOException("Registro de histórico excede o tamanho permitido.");
        ChangeRecord change;
        try { change = GSON.fromJson(Files.readString(file, StandardCharsets.UTF_8), ChangeRecord.class); }
        catch (RuntimeException invalid) { throw new IOException("Registro de histórico inválido.", invalid); }
        if (change == null || change.before == null || change.after == null || change.after.revision < 1)
            throw new IOException("Registro de histórico incompleto.");
        if (change.after.revision != historyRevision(file)) throw new IOException("Revisão do registro de histórico não corresponde ao arquivo.");
        return change;
    }

    private static long historyRevision(Path file) {
        String name = file.getFileName().toString();
        try { return Long.parseLong(name.substring("change-".length(), name.length() - ".json".length())); }
        catch (NumberFormatException invalid) { return -1; }
    }

    private static List<FieldChange> changedFields(GenerationConfig before, GenerationConfig after) {
        Map<String, String> previous = flatten(GSON.toJsonTree(before).getAsJsonObject());
        Map<String, String> next = flatten(GSON.toJsonTree(after).getAsJsonObject());
        java.util.TreeSet<String> keys = new java.util.TreeSet<>();
        keys.addAll(previous.keySet()); keys.addAll(next.keySet());
        List<FieldChange> changes = new ArrayList<>();
        for (String key : keys) {
            if (key.equals("revision")) continue;
            String left = previous.get(key), right = next.get(key);
            if (!Objects.equals(left, right)) {
                changes.add(new FieldChange(key, valuePreview(left), valuePreview(right)));
                if (changes.size() == 128) break;
            }
        }
        return List.copyOf(changes);
    }

    private static Map<String, String> flatten(JsonObject object) {
        LinkedHashMap<String, String> result = new LinkedHashMap<>();
        flattenInto("", object, result);
        return result;
    }

    private static void flattenInto(String prefix, JsonElement value, Map<String, String> result) {
        if (value != null && value.isJsonObject()) {
            for (var entry : value.getAsJsonObject().entrySet())
                flattenInto(prefix.isEmpty() ? entry.getKey() : prefix + "." + entry.getKey(), entry.getValue(), result);
        } else if (value != null) result.put(prefix, value.isJsonPrimitive() ? value.getAsString() : value.toString());
    }

    private static String valuePreview(String value) {
        if (value == null) return "(ausente)";
        return value.length() > 160 ? value.substring(0, 157) + "…" : value;
    }

    private static String safeAuthor(String value) {
        if (value == null || value.isBlank()) return "desconhecido";
        String clean = value.replaceAll("[\\r\\n\\t]", " ");
        return clean.length() > 64 ? clean.substring(0, 64) : clean;
    }

    private static String safeTime(String value) { return value == null ? "" : value.length() > 40 ? value.substring(0, 40) : value; }

    public synchronized void discardPending(String actor) throws IOException {
        if (stageInProgress) throw new IllegalStateException("Há um salvamento em andamento. Aguarde antes de descartar a configuração.");
        if (pending == null) return;
        long revision = pending.revision;
        Files.deleteIfExists(directory.resolve("pending.json"));
        pending = null;
        editRevision = Math.addExact(editRevision, 1);
        write(directory.resolve("edit-revision.json"), editRevision);
        audit("DISCARD", actor, revision, active.revision);
    }

    private void audit(String action, String actor, long before, long after) throws IOException {
        String name = actor.replaceAll("[\\r\\n\\t]", " ");
        Files.writeString(directory.resolve("audit.log"), Instant.now() + "\t" + action + "\t" + name + "\t" + before + "\t" + after + "\n",
                StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }

    public static GenerationConfig read(Path file) throws IOException {
        try {
            if (Files.size(file) > 262144) throw new IOException("Configuração excede o limite: " + file);
            GenerationConfig config = GSON.fromJson(Files.readString(file, StandardCharsets.UTF_8), GenerationConfig.class);
            if (config == null) throw new IllegalArgumentException("Arquivo vazio.");
            return config.validated();
        } catch (RuntimeException e) { throw new IOException("Configuração inválida em " + file + ": " + e.getMessage(), e); }
    }

    public static void write(Path file, Object value) throws IOException {
        Files.createDirectories(file.getParent());
        Path temporary = file.resolveSibling(file.getFileName() + ".tmp");
        Files.writeString(temporary, GSON.toJson(value) + "\n", StandardCharsets.UTF_8);
        try { Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE); }
        catch (AtomicMoveNotSupportedException e) { Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING); }
    }
}
