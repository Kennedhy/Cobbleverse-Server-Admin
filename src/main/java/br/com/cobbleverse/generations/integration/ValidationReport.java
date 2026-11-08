package br.com.cobbleverse.generations.integration;

import java.util.*;

public final class ValidationReport {
    private static final int MAX_CACHED_STORE_ENTRIES = 100_000;
    static final int SEEN = 1;
    static final int SEEN_RESTRICTED = 2;
    static final int AVAILABLE = 4;
    static final int AVAILABLE_RESTRICTED = 8;
    static final int RESERVE = 16;

    record StoreImpact(Object source, Map<UUID, Byte> entries) {
        StoreImpact { entries = Collections.unmodifiableMap(new LinkedHashMap<>(entries)); }
    }

    private record PlayerImpactSnapshot(int pokemon, int restricted, int available, int availableRestricted,
                                        int reserve, String name, List<StoreImpact> stores) {}

    public int allowedSpecies;
    public int affectedPlayers;
    public int inspectedPlayers;
    public int playersWithoutUsablePokemon;
    public int affectedPokemon;
    public int playersWithReserve;
    public int reservePokemon;
    public int trainerTeams;
    public int adaptedTeams;
    public boolean playerImpactStale;
    public String playerImpactMessage;
    public final List<String> affectedPlayerNames = new ArrayList<>();
    public final List<String> errors = new ArrayList<>();
    public final List<String> warnings = new ArrayList<>();
    public final List<String> integrations = new ArrayList<>();
    private transient final Map<UUID, PlayerImpactSnapshot> rescannablePlayers = new HashMap<>();
    private transient final LinkedHashMap<UUID, String> affectedPlayerSamples = new LinkedHashMap<>();
    private transient int cachedStoreEntries;

    /** Replace one cached player's contribution after their loaded stores change during a preview. */
    void recordPlayerImpact(UUID id, int pokemon, int restricted, int available, int availableRestricted,
                            int reserve, String name, boolean hasLiveStore, List<StoreImpact> stores) {
        PlayerImpactSnapshot previous = rescannablePlayers.remove(id);
        if (previous == null) inspectedPlayers++;
        else {
            adjust(previous, -1);
            cachedStoreEntries -= storedEntries(previous.stores());
            affectedPlayerSamples.remove(id);
        }
        List<StoreImpact> retained = null;
        if (hasLiveStore && stores != null) {
            int size = storedEntries(stores);
            if (cachedStoreEntries + size <= MAX_CACHED_STORE_ENTRIES) {
                retained = List.copyOf(stores);
                cachedStoreEntries += size;
            }
        }
        PlayerImpactSnapshot current = new PlayerImpactSnapshot(pokemon, restricted, available,
                availableRestricted, reserve, name, retained);
        adjust(current, 1);
        if (hasLiveStore) rescannablePlayers.put(id, current);
        if (restricted > 0 && affectedPlayerSamples.size() < 30) affectedPlayerSamples.put(id, name);
        affectedPlayerNames.clear();
        affectedPlayerNames.addAll(affectedPlayerSamples.values());
    }

    boolean canRescanPlayer(UUID id) { return rescannablePlayers.containsKey(id); }
    boolean canCacheStoreImpacts() { return cachedStoreEntries < MAX_CACHED_STORE_ENTRIES; }
    boolean canRecheckStore(UUID id) {
        PlayerImpactSnapshot snapshot = rescannablePlayers.get(id);
        return snapshot != null && snapshot.stores() != null;
    }
    List<StoreImpact> storeImpacts(UUID id) {
        PlayerImpactSnapshot snapshot = rescannablePlayers.get(id);
        return snapshot == null ? null : snapshot.stores();
    }

    /** Replace selected source stores and recompute the player's de-duplicated contribution. */
    void replaceStoreImpacts(UUID id, List<StoreImpact> stores, String name) {
        PlayerImpactSnapshot previous = rescannablePlayers.get(id);
        if (previous == null || previous.stores() == null)
            throw new IllegalStateException("O snapshot dos stores desse jogador não está disponível.");
        int oldEntries = storedEntries(previous.stores());
        PlayerImpactSnapshot aggregated = aggregate(stores, name);
        int newEntries = storedEntries(stores);
        adjust(previous, -1);
        cachedStoreEntries -= oldEntries;
        List<StoreImpact> retained = newEntries <= MAX_CACHED_STORE_ENTRIES - cachedStoreEntries
                ? List.copyOf(stores) : null;
        if (retained != null) cachedStoreEntries += newEntries;
        PlayerImpactSnapshot current = new PlayerImpactSnapshot(aggregated.pokemon(),aggregated.restricted(),
                aggregated.available(),aggregated.availableRestricted(),aggregated.reserve(),name,retained);
        rescannablePlayers.put(id, current);
        adjust(current, 1);
        affectedPlayerSamples.remove(id);
        if (current.restricted() > 0 && affectedPlayerSamples.size() < 30) affectedPlayerSamples.put(id, name);
        affectedPlayerNames.clear();
        affectedPlayerNames.addAll(affectedPlayerSamples.values());
    }

    private static PlayerImpactSnapshot aggregate(List<StoreImpact> stores, String name) {
        Set<UUID> seen = new HashSet<>(), availableSeen = new HashSet<>(), reserveSeen = new HashSet<>();
        int pokemon = 0, restricted = 0, available = 0, availableRestricted = 0, reserve = 0;
        for (StoreImpact store : stores) for (var entry : store.entries().entrySet()) {
            UUID id = entry.getKey();
            int flags = entry.getValue();
            if ((flags & SEEN) != 0 && seen.add(id)) {
                pokemon++;
                if ((flags & SEEN_RESTRICTED) != 0) restricted++;
            }
            if ((flags & AVAILABLE) != 0 && availableSeen.add(id)) {
                available++;
                if ((flags & AVAILABLE_RESTRICTED) != 0) availableRestricted++;
            }
            if ((flags & RESERVE) != 0 && reserveSeen.add(id)) reserve++;
        }
        return new PlayerImpactSnapshot(pokemon, restricted, available, availableRestricted,
                reserve, name, List.copyOf(stores));
    }

    private static int storedEntries(List<StoreImpact> stores) {
        if (stores == null) return 0;
        int count = 0;
        for (StoreImpact store : stores) count += store.entries().size();
        return count;
    }

    private void adjust(PlayerImpactSnapshot impact, int direction) {
        if (impact.restricted() > 0) {
            affectedPlayers += direction;
            affectedPokemon += direction * impact.restricted();
        }
        if (impact.pokemon() > 0 && impact.available() == impact.availableRestricted())
            playersWithoutUsablePokemon += direction;
        if (impact.reserve() > 0) {
            playersWithReserve += direction;
            reservePokemon += direction * impact.reserve();
        }
    }

    public void clearPlayerImpact() {
        inspectedPlayers=0; affectedPlayers=0; playersWithoutUsablePokemon=0; affectedPokemon=0; playersWithReserve=0; reservePokemon=0;
        cachedStoreEntries = 0;
        affectedPlayerNames.clear();
        rescannablePlayers.clear();
        affectedPlayerSamples.clear();
    }
    void releaseRescanCache() {
        rescannablePlayers.clear();
        cachedStoreEntries = 0;
    }
    public void markPlayerImpactStale(String message) {
        clearPlayerImpact();
        playerImpactStale = true;
        playerImpactMessage = message;
        warnings.add(message);
    }
    @Override public String toString() {
        return "Espécies permitidas: " + allowedSpecies + "; erros: " + errors + "; observações: " + warnings;
    }
}
