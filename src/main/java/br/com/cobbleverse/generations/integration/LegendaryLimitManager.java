package br.com.cobbleverse.generations.integration;

import br.com.cobbleverse.generations.GenerationControl;
import br.com.cobbleverse.generations.rules.GameplaySettings;
import br.com.cobbleverse.generations.rules.LegendaryCatalog;
import br.com.cobbleverse.generations.web.market.MarketPokemonSnapshot;
import br.com.cobbleverse.generations.web.market.MarketService;
import com.cobblemon.mod.common.api.events.CobblemonEvents;
import com.cobblemon.mod.common.api.pokemon.PokemonProperties;
import com.cobblemon.mod.common.api.storage.PokemonStore;
import com.cobblemon.mod.common.api.storage.StoreCoordinates;
import com.cobblemon.mod.common.api.storage.factory.FileBackedPokemonStoreFactory;
import com.cobblemon.mod.common.api.storage.party.PlayerPartyStore;
import com.cobblemon.mod.common.api.storage.pc.PCStore;
import com.cobblemon.mod.common.pokemon.Pokemon;
import com.cobblemon.mod.common.pokemon.Species;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.WorldSavePath;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/** World-wide legendary ownership index, rebuilt once at startup and maintained from Cobblemon events. */
public final class LegendaryLimitManager {
    private enum State { STOPPED, SCANNING, READY, FAILED }
    private record Tracked(String key, UUID owner) {}
    private record Pending(Pokemon pokemon, UUID owner, boolean removed) {}
    private static final Map<UUID, Tracked> TRACKED = new HashMap<>();
    private static final Map<String, Integer> COUNTS = new TreeMap<>();
    private static final LinkedHashMap<UUID, Pending> PENDING = new LinkedHashMap<>();
    private static final List<Map<String, Object>> CATALOG_VIEW = catalogView();
    private static volatile MinecraftServer server;
    private static Scan scan;
    private static State state = State.STOPPED;
    private static String failureMessage = "";
    private static int scannedPlayers;
    private static int totalPlayers;

    private LegendaryLimitManager() {}

    public static void register() {
        ServerTickEvents.END_SERVER_TICK.register(LegendaryLimitManager::tick);
        CobblemonEvents.POKEMON_GAINED.subscribe(event -> dispatch(() ->
                gained(event.getPokemon(), event.getPlayerId())));
        CobblemonEvents.POKEMON_RELEASED_EVENT_POST.subscribe(event -> dispatch(() ->
                released(event.getPokemon(), event.getPlayer().getUuid())));
        CobblemonEvents.EVOLUTION_ACCEPTED.subscribe(event -> {
            if (!canEvolve(event.getPokemon(), event.getEvolution().getResult())) {
                event.cancel();
                ServerPlayerEntity owner = event.getPokemon().getOwnerPlayer();
                if (owner != null) owner.sendMessage(Text.literal("O limite global deste lendário já foi atingido ou a contagem do mundo ainda está carregando."));
            }
        });
        CobblemonEvents.EVOLUTION_COMPLETE.subscribe(event -> dispatch(() -> {
            UUID owner = ownerOf(event.getPokemon());
            if (owner != null) gained(event.getPokemon(), owner);
        }));
    }

    public static void start(MinecraftServer activeServer, boolean marketReady) {
        stop();
        server = activeServer;
        if (!marketReady) {
            fail("O mercado não pôde ser lido; a contagem global de Pokémon em custódia não pode ser confirmada.", null);
            return;
        }
        try {
            state = State.SCANNING;
            scan = new Scan(activeServer);
        } catch (Exception failure) {
            fail("Não foi possível iniciar a leitura dos PCs e equipes dos jogadores.", failure);
        }
    }

    public static void stop(MinecraftServer activeServer) {
        if (server == activeServer) stop();
    }

    private static void stop() {
        if (scan != null) scan.close();
        scan = null;
        server = null;
        state = State.STOPPED;
        scannedPlayers = 0;
        totalPlayers = 0;
        TRACKED.clear();
        COUNTS.clear();
        PENDING.clear();
        failureMessage = "";
    }

    private static void tick(MinecraftServer activeServer) {
        Scan current = scan;
        if (current == null || current.server != activeServer) return;
        try {
            long budget = activeServer.getAverageTickTime() > 45.0f ? 1 : 3;
            if (current.step(System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(budget))) finish(current);
        } catch (Exception failure) {
            fail("A leitura dos Pokémon do mundo falhou; limites lendários finitos estão bloqueados até reiniciar e corrigir os dados.", failure);
        }
    }

    private static void finish(Scan completed) throws Exception {
        if (scan != completed) return;
        Set<UUID> escrowIds = new HashSet<>();
        for (MarketPokemonSnapshot snapshot : MarketService.current().escrowedPokemon()) {
            String key = LegendaryCatalog.keyForPokemon(snapshot.species(), snapshot.form());
            if (key == null) continue;
            escrowIds.add(snapshot.pokemonUuid());
            if (!TRACKED.containsKey(snapshot.pokemonUuid()))
                putTracked(snapshot.pokemonUuid(), new Tracked(key, null));
        }
        state = State.READY;
        for (var entry : PENDING.entrySet()) {
            Pending change = entry.getValue();
            if (change.removed()) {
                if (!escrowIds.contains(entry.getKey())) removeTracked(entry.getKey());
            }
            else applyGained(change.pokemon(), change.owner(), true);
        }
        PENDING.clear();
        completed.close();
        scan = null;
        GenerationControl.LOGGER.info("Índice global de lendários pronto: {} jogadores, {} Pokémon lendários registrados.",
                scannedPlayers, TRACKED.size());
    }

    private static void fail(String message, Throwable cause) {
        if (scan != null) scan.close();
        scan = null;
        TRACKED.clear();
        COUNTS.clear();
        PENDING.clear();
        state = State.FAILED;
        failureMessage = message;
        if (cause == null) GenerationControl.LOGGER.error(message);
        else GenerationControl.LOGGER.error(message, cause);
    }

    private static void dispatch(Runnable action) {
        MinecraftServer active = server;
        if (active == null) return;
        if (active.isOnThread()) action.run();
        else active.execute(() -> { if (server == active) action.run(); });
    }

    /** Result consumed at the start of the default Cobblemon capture calculation. */
    public static String captureBlockReason(Pokemon pokemon) {
        String key = keyOf(pokemon);
        if (key == null) return null;
        int limit = limit(key);
        if (limit < 0) return null;
        if (state != State.READY) return "A contagem global de lendários ainda não está pronta; a captura foi bloqueada por segurança.";
        int count = COUNTS.getOrDefault(key, 0);
        if (count >= limit) return "O limite global de " + entryName(key) + " foi atingido (" + count + "/" + limit + " neste mundo).";
        return null;
    }

    /** Rejects an admin PC edit before it mutates Cobblemon storage. */
    public static void requirePcEditAllowed(Pokemon previous, Pokemon replacement) {
        if (replacement == null) return;
        String key = keyOf(replacement);
        if (key == null) return;
        int limit = limit(key);
        if (limit < 0) return;
        if (previous != null && key.equals(keyOf(previous))) return;
        if (state != State.READY) throw new PcManagement.Unavailable("A contagem global de lendários ainda não está pronta; tente a alteração novamente depois.");
        int count = COUNTS.getOrDefault(key, 0);
        Tracked old = previous == null ? null : TRACKED.get(previous.getUuid());
        if (old != null && old.key().equals(key)) count--;
        if (count >= limit) throw new PcManagement.Conflict("O limite global de " + entryName(key) + " já foi atingido neste mundo.");
    }

    public static void recordPcPokemon(Pokemon pokemon, UUID player) {
        if (pokemon == null) return;
        if (state == State.SCANNING) PENDING.put(pokemon.getUuid(), new Pending(pokemon, player, false));
        else applyGained(pokemon, player, false);
    }

    public static void forgetPcPokemon(Pokemon pokemon, UUID player) {
        if (pokemon == null) return;
        UUID id = pokemon.getUuid();
        if (state == State.SCANNING) PENDING.put(id, new Pending(pokemon, player, true));
        else if (TRACKED.containsKey(id) && !isEscrowed(id)) removeTracked(id);
    }

    /** Keeps a just-deposited Pokémon counted after its PC slot is moved into GTS escrow. */
    public static void preserveEscrowedPokemon(Pokemon pokemon, UUID owner) {
        String key = keyOf(pokemon);
        if (key == null) return;
        if (state == State.SCANNING) PENDING.put(pokemon.getUuid(), new Pending(pokemon, owner, false));
        else if (state == State.READY) putTracked(pokemon.getUuid(), new Tracked(key, owner));
    }

    private static void gained(Pokemon pokemon, UUID owner) {
        if (pokemon == null || owner == null || (keyOf(pokemon) == null && !TRACKED.containsKey(pokemon.getUuid())
                && !PENDING.containsKey(pokemon.getUuid()))) return;
        if (state == State.SCANNING) {
            PENDING.put(pokemon.getUuid(), new Pending(pokemon, owner, false));
            return;
        }
        applyGained(pokemon, owner, true);
    }

    private static void applyGained(Pokemon pokemon, UUID owner, boolean enforceLimit) {
        String key = keyOf(pokemon);
        Tracked previous = TRACKED.get(pokemon.getUuid());
        if (key == null) {
            removeTracked(pokemon.getUuid());
            return;
        }
        int limit = limit(key);
        int count = COUNTS.getOrDefault(key, 0);
        if (previous != null && previous.key().equals(key)) count--;
        if (enforceLimit && previous == null && limit >= 0 && (state != State.READY || count >= limit)
                && !isEscrowed(pokemon.getUuid(), false)) {
            boolean removed = removeFromStore(pokemon);
            if (!removed) {
                putTracked(pokemon.getUuid(), new Tracked(key, owner));
                GenerationControl.LOGGER.error("Não foi possível remover {} após exceder o limite lendário; o Pokémon foi incluído na contagem.", entryName(key));
            } else {
                ServerPlayerEntity player = server == null ? null : server.getPlayerManager().getPlayer(owner);
                if (player != null) player.sendMessage(Text.literal(state == State.READY
                        ? "O limite global de " + entryName(key) + " já foi atingido. O Pokémon não foi adicionado à sua equipe/PC."
                        : "A contagem de lendários do mundo ainda está carregando. A obtenção deste Pokémon foi recusada."));
            }
            return;
        }
        putTracked(pokemon.getUuid(), new Tracked(key, owner));
    }

    private static boolean removeFromStore(Pokemon pokemon) {
        try {
            StoreCoordinates<?> coordinates = pokemon.getStoreCoordinates().get();
            return coordinates != null && coordinates.getStore().remove(pokemon);
        } catch (RuntimeException failure) {
            GenerationControl.LOGGER.warn("Não foi possível reverter a obtenção excedente de um lendário.", failure);
            return false;
        }
    }

    private static void released(Pokemon pokemon, UUID owner) {
        if (pokemon == null) return;
        UUID id = pokemon.getUuid();
        if (state == State.SCANNING && (keyOf(pokemon) != null || TRACKED.containsKey(id) || PENDING.containsKey(id)))
            PENDING.put(id, new Pending(pokemon, owner, true));
        else if (TRACKED.containsKey(id) && !isEscrowed(id)) removeTracked(id);
    }

    private static boolean canEvolve(Pokemon pokemon, PokemonProperties result) {
        String currentKey = keyOf(pokemon);
        String targetKey = keyOf(result, pokemon);
        if (targetKey == null || targetKey.equals(currentKey)) return true;
        int limit = limit(targetKey);
        if (limit < 0) return true;
        if (state != State.READY) return false;
        int count = COUNTS.getOrDefault(targetKey, 0);
        Tracked old = TRACKED.get(pokemon.getUuid());
        if (old != null && old.key().equals(targetKey)) count--;
        return count < limit;
    }

    private static UUID ownerOf(Pokemon pokemon) {
        try {
            StoreCoordinates<?> coordinates = pokemon.getStoreCoordinates().get();
            if (coordinates == null) return null;
            PokemonStore<?> store = coordinates.getStore();
            if (store instanceof PlayerPartyStore || store instanceof PCStore) return store.getUuid();
        } catch (RuntimeException ignored) { }
        return null;
    }

    private static boolean isEscrowed(UUID id) {
        return isEscrowed(id, true);
    }

    private static boolean isEscrowed(UUID id, boolean whenUnavailable) {
        try {
            return MarketService.current().escrowedPokemon().stream().anyMatch(snapshot -> id.equals(snapshot.pokemonUuid()));
        } catch (RuntimeException unavailable) {
            return whenUnavailable;
        }
    }

    private static String keyOf(Pokemon pokemon) {
        if (pokemon == null || pokemon.getSpecies() == null || pokemon.getForm() == null) return null;
        return LegendaryCatalog.keyForPokemon(pokemon.getSpecies().getResourceIdentifier().toString(), pokemon.getForm().getName());
    }

    private static String keyOf(PokemonProperties properties, Pokemon base) {
        if (properties == null) return null;
        Species species = properties.getSpecies() == null ? base.getSpecies() : CobblemonIntegration.species(properties.getSpecies());
        if (species == null) return null;
        String form = properties.getForm();
        if (form == null || form.isBlank()) form = species.getStandardForm().getName();
        String resolved = CobblemonIntegration.resolveForm(species, form);
        return LegendaryCatalog.keyForPokemon(species.getResourceIdentifier().toString(), resolved == null ? form : resolved);
    }

    private static int limit(String key) {
        GameplaySettings settings = GenerationControl.gameplaySettings();
        if (settings == null) return -1;
        return settings.legendaryLimits().getOrDefault(key, -1);
    }

    private static String entryName(String key) {
        LegendaryCatalog.Entry entry = LegendaryCatalog.entry(key);
        return entry == null ? key : entry.name();
    }

    private static void putTracked(UUID id, Tracked pokemon) {
        if (id == null || pokemon == null) return;
        Tracked previous = TRACKED.put(id, pokemon);
        if (previous != null && previous.key().equals(pokemon.key())) return;
        if (previous != null) decrement(previous.key());
        COUNTS.merge(pokemon.key(), 1, Integer::sum);
    }

    private static void removeTracked(UUID id) {
        Tracked removed = TRACKED.remove(id);
        if (removed != null) decrement(removed.key());
    }

    private static void decrement(String key) {
        int next = COUNTS.getOrDefault(key, 0) - 1;
        if (next <= 0) COUNTS.remove(key);
        else COUNTS.put(key, next);
    }

    private static List<Map<String, Object>> catalogView() {
        List<Map<String, Object>> entries = new ArrayList<>(LegendaryCatalog.entries().size());
        for (LegendaryCatalog.Entry entry : LegendaryCatalog.entries()) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("key", entry.key());
            item.put("species", entry.species());
            item.put("form", entry.form());
            item.put("name", entry.name());
            item.put("generation", entry.generation());
            item.put("generationName", LegendaryCatalog.generationName(entry.generation()));
            item.put("defaultLimit", entry.defaultLimit());
            entries.add(java.util.Collections.unmodifiableMap(item));
        }
        return List.copyOf(entries);
    }

    public static Map<String, Object> view() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", state.name().toLowerCase(Locale.ROOT));
        result.put("ready", state == State.READY);
        result.put("message", state == State.FAILED ? failureMessage : state == State.SCANNING
                ? "Leitura inicial em segundo plano. Capturas sujeitas a limite ficam bloqueadas até a contagem terminar."
                : state == State.READY ? "Contagem global do mundo atualizada por eventos do Cobblemon." : "Servidor ainda não inicializado.");
        result.put("scannedPlayers", scannedPlayers);
        result.put("totalPlayers", totalPlayers);
        result.put("counts", state == State.READY ? Map.copyOf(COUNTS) : Map.of());
        GameplaySettings settings = GenerationControl.gameplaySettings();
        result.put("activeLimits", settings == null ? Map.of() : settings.legendaryLimits());
        result.put("entries", CATALOG_VIEW);
        return result;
    }

    private static final class Scan {
        private final MinecraftServer server;
        private final List<FileBackedPokemonStoreFactory<?>> factories;
        private final ValidationIo io = new ValidationIo();
        private final OfflineFileLedger ledger = new OfflineFileLedger();
        private final CompletableFuture<Set<UUID>> enumeration;
        private Iterator<UUID> players;
        private IncrementalPlayerScan playerScan;
        private List<Future<?>> barriers;
        private long barrierStarted;
        private CompletableFuture<Void> fileVerification;
        private final long startedAt = System.nanoTime();

        private Scan(MinecraftServer server) {
            this.server = server;
            this.factories = PlayerImpact.providers();
            Set<UUID> connected = new HashSet<>();
            server.getPlayerManager().getPlayerList().forEach(player -> connected.add(player.getUuid()));
            Path playerData = server.getSavePath(WorldSavePath.PLAYERDATA);
            this.enumeration = io.submit(() -> {
                Set<UUID> ids = new java.util.TreeSet<>(connected);
                try {
                    if (Files.isDirectory(playerData)) try (var paths = Files.list(playerData)) {
                        paths.filter(path -> path.getFileName().toString().endsWith(".dat")).forEach(path -> {
                            ValidationIo.checkCancelled();
                            String filename = path.getFileName().toString();
                            try { ids.add(UUID.fromString(filename.substring(0, filename.length() - 4))); }
                            catch (IllegalArgumentException ignored) { }
                        });
                    }
                    return ids;
                } catch (Exception failure) { throw new java.util.concurrent.CompletionException(failure); }
            });
        }

        private boolean step(long deadline) throws Exception {
            if (System.nanoTime() - startedAt > TimeUnit.MINUTES.toNanos(20))
                throw new IllegalStateException("A indexação excedeu vinte minutos.");
            if (!waitForStoreWrites()) return false;
            if (players == null) {
                if (!enumeration.isDone()) return false;
                players = enumeration.get().iterator();
                totalPlayers = enumeration.get().size();
            }
            if (playerScan != null) {
                if (playerScan.step(deadline)) {
                    scannedPlayers++;
                    playerScan = null;
                }
                return false;
            }
            if (players.hasNext()) {
                UUID id = players.next();
                playerScan = new IncrementalPlayerScan(server, factories, id, ledger, io,
                        pokemon -> {
                            String key = keyOf(pokemon);
                            if (key != null && !TRACKED.containsKey(pokemon.getUuid()))
                                putTracked(pokemon.getUuid(), new Tracked(key, id));
                        });
                return false;
            }
            if (fileVerification == null) {
                fileVerification = ledger.verify(io);
                return false;
            }
            if (!fileVerification.isDone()) return false;
            fileVerification.get();
            return true;
        }

        private boolean waitForStoreWrites() throws Exception {
            if (barriers == null) {
                barriers = new ArrayList<>();
                for (var factory : factories) barriers.add(((br.com.cobbleverse.generations.mixin.StoreFactoryAccessor) (Object) factory)
                        .generationControl$executor().submit(() -> { }));
                barrierStarted = System.nanoTime();
                return false;
            }
            if (barriers.stream().anyMatch(future -> !future.isDone())) {
                if (System.nanoTime() - barrierStarted > TimeUnit.SECONDS.toNanos(5))
                    throw new IllegalStateException("O armazenamento do Cobblemon continua ocupado salvando dados.");
                return false;
            }
            for (Future<?> barrier : barriers) barrier.get();
            barriers = List.of();
            return true;
        }

        private void close() {
            if (playerScan != null) playerScan.cancel();
            enumeration.cancel(false);
            if (fileVerification != null) fileVerification.cancel(false);
            io.close();
        }
    }
}
