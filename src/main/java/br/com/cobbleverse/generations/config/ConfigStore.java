package br.com.cobbleverse.generations.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Instant;
import java.util.Objects;

/** One policy directory per world. A staged revision never changes the live policy. */
public final class ConfigStore {
    public static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
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
        if (expectedRevision != editRevision()) throw new IllegalStateException("Outro administrador alterou a configuração. Reabra o painel.");
        GenerationConfig next = candidate.copy().validated();
        next.revision = Math.addExact(editRevision(), 1);
        write(directory.resolve("history/change-"+next.revision+".json"),java.util.Map.of("author",actor,"time",Instant.now().toString(),"previousActiveRevision",active.revision,
            "previousEditRevision",editRevision,"before",pending==null ? active : pending,"after",next));
        write(directory.resolve("history/revision-" + next.revision + ".json"), next);
        write(directory.resolve("pending.json"), next);
        pending = next;
        editRevision = next.revision;
        write(directory.resolve("edit-revision.json"), editRevision);
        audit("STAGE", actor, active.revision, next.revision);
        return next.copy();
    }

    /** Called before world resources load. Readiness validation belongs to the caller. */
    public synchronized void activatePending(String actor) throws IOException {
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

    public synchronized void discardPending(String actor) throws IOException {
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
