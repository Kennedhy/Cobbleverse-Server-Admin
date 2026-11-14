package br.com.cobbleverse.generations.web.market;

import br.com.cobbleverse.generations.config.ConfigStore;
import com.google.gson.JsonParseException;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Comparator;
import java.util.List;
import java.util.function.Consumer;

/** Bounded, world-local market metadata and settlement journal. */
public final class MarketStore {
    private static final int FORMAT = 1;
    public static final int MAX_LISTINGS = 100;
    public static final int MAX_TRANSACTIONS = 500;
    private static final long MAX_FILE_BYTES = 64L * 1024 * 1024;
    private final Path file;
    private State state;
    private List<MarketListing> sortedListings;

    public static final class State {
        public int format = FORMAT;
        public MarketSettings settings = new MarketSettings();
        public LinkedHashMap<String, MarketListing> listings = new LinkedHashMap<>();
        public ArrayList<MarketTransaction> transactions = new ArrayList<>();

        private State copy() {
            State copy = new State();
            copy.format = format;
            copy.settings = settings.copy();
            listings.forEach((id, listing) -> copy.listings.put(id, listing.copy()));
            transactions.forEach(transaction -> copy.transactions.add(transaction.copy()));
            return copy;
        }
    }

    public MarketStore(Path root) throws IOException {
        this.file = root.resolve("market.json").toAbsolutePath().normalize();
        Files.createDirectories(file.getParent());
        if (Files.isRegularFile(file)) {
            try {
                if (Files.size(file) > MAX_FILE_BYTES)
                    throw new IOException("O arquivo do mercado excede 64 MiB; nenhum dado foi sobrescrito.");
                state = ConfigStore.GSON.fromJson(Files.readString(file, StandardCharsets.UTF_8), State.class);
                if (state == null || state.format != FORMAT || state.settings == null
                        || state.listings == null || state.transactions == null)
                    throw new IOException("O arquivo do mercado tem um formato desconhecido.");
                state.settings.validate();
                if (state.listings.size() > MAX_LISTINGS || state.transactions.size() > MAX_TRANSACTIONS)
                    throw new IOException("O arquivo do mercado excede os limites configurados.");
            } catch (JsonParseException | IllegalArgumentException invalid) {
                throw new IOException("O arquivo do mercado está inválido. Nenhum dado foi sobrescrito.", invalid);
            }
        } else {
            state = new State();
            save();
        }
    }

    public synchronized MarketSettings settings() { return state.settings.copy(); }

    public synchronized MarketSettings updateSettings(MarketSettings next) throws IOException {
        next.validate();
        MarketSettings previous = state.settings;
        state.settings = next.copy();
        try { save(); }
        catch (IOException failure) { state.settings = previous; throw failure; }
        return state.settings.copy();
    }

    public synchronized void update(Consumer<State> change) throws IOException {
        State previous = state.copy();
        try {
            change.accept(state);
            if (state.listings.size() > MAX_LISTINGS)
                throw new IllegalStateException("O mercado atingiu o limite global de anúncios.");
            while (state.transactions.size() > MAX_TRANSACTIONS) {
                int completed = -1;
                for (int index = 0; index < state.transactions.size(); index++) {
                    if ("complete".equals(state.transactions.get(index).status)
                            || "cancelled".equals(state.transactions.get(index).status)) {
                        completed = index;
                        break;
                    }
                }
                if (completed < 0) throw new IllegalStateException("O histórico atingiu o limite de transações pendentes.");
                state.transactions.remove(completed);
            }
            save();
        } catch (IOException | RuntimeException failure) {
            state = previous;
            throw failure;
        } finally {
            // A rollback also replaces the objects referenced by the previous projection.
            sortedListings = null;
        }
    }

    /**
     * Prune terminal history before a market operation reserves room for its settlement.
     * Pending entries are never discarded; callers must fail before touching assets or money
     * when the remaining capacity is occupied by unresolved operations.
     */
    public synchronized void ensureFreeTransactions(int requiredFreeSlots) throws IOException {
        if (requiredFreeSlots < 0 || requiredFreeSlots > MAX_TRANSACTIONS)
            throw new IllegalArgumentException("A reserva do histórico do mercado é inválida.");
        int targetSize = MAX_TRANSACTIONS - requiredFreeSlots;
        if (state.transactions.size() <= targetSize) return;

        ArrayList<MarketTransaction> previous = new ArrayList<>(state.transactions);
        while (state.transactions.size() > targetSize) {
            int terminal = -1;
            for (int index = 0; index < state.transactions.size(); index++) {
                String status = state.transactions.get(index).status;
                if ("complete".equals(status) || "cancelled".equals(status)) {
                    terminal = index;
                    break;
                }
            }
            if (terminal < 0) {
                state.transactions = previous;
                throw new IllegalStateException("O histórico está ocupado por operações pendentes; conclua retiradas antes de criar novas reservas.");
            }
            state.transactions.remove(terminal);
        }
        try { save(); }
        catch (IOException failure) {
            state.transactions = previous;
            throw failure;
        }
    }

    public synchronized List<MarketListing> listings() {
        if (sortedListings == null) {
            sortedListings = state.listings.values().stream()
                    .sorted(Comparator.comparingLong((MarketListing listing) -> listing.createdAt).reversed()).toList();
        }
        return sortedListings;
    }

    public synchronized List<MarketTransaction> transactions() {
        return List.copyOf(state.transactions);
    }

    public synchronized MarketListing listing(String id) {
        return state.listings.get(id);
    }

    public synchronized void save() throws IOException {
        Path temp = file.resolveSibling(file.getFileName() + ".tmp");
        byte[] bytes = ConfigStore.GSON.toJson(state).getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_FILE_BYTES) throw new IOException("O mercado excede o limite de armazenamento de 64 MiB.");
        try (FileChannel channel = FileChannel.open(temp, StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)) {
            java.nio.ByteBuffer buffer = java.nio.ByteBuffer.wrap(bytes);
            while (buffer.hasRemaining()) channel.write(buffer);
            channel.force(true);
        }
        try { Files.move(temp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
        catch (java.nio.file.AtomicMoveNotSupportedException unsupported) {
            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
