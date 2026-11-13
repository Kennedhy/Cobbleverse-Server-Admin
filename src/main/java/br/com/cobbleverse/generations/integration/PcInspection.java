package br.com.cobbleverse.generations.integration;

import br.com.cobbleverse.generations.mixin.StoreCacheAccessor;
import br.com.cobbleverse.generations.mixin.StoreFactoryAccessor;
import br.com.cobbleverse.generations.mixin.StoreManagerAccessor;
import br.com.cobbleverse.generations.mixin.StorePathAccessor;
import com.cobblemon.mod.common.Cobblemon;
import com.cobblemon.mod.common.api.storage.adapter.flatfile.NBTStoreAdapter;
import com.cobblemon.mod.common.api.storage.party.PlayerPartyStore;
import com.cobblemon.mod.common.api.storage.pc.PCStore;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonElement;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtElement;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtSizeTracker;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.WorldSavePath;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Locale;
import java.util.UUID;

/** On-demand PC projection. Never creates, initializes or repairs a Pokémon store. */
public final class PcInspection {
    private static final int SLOTS = 30;
    private static final int PAGE_SIZE = 40;
    private static final int MAX_INDEXED_PLAYERS = 200_000;
    private static final int MAX_CACHED_NAMES = 100_000;
    private static volatile PlayerIndex cachedPlayers;

    private PcInspection() {}

    /** Dashboard summary reads at most the six slots in an already-loaded party. */
    public static Integer loadedPartySize(UUID player) {
        for (var factory : ((StoreManagerAccessor) (Object) Cobblemon.INSTANCE.getStorage()).generationControl$factories()) {
            if (!(factory instanceof StoreFactoryAccessor access)) continue;
            Object cache = access.generationControl$caches().get(PlayerPartyStore.class);
            if (cache instanceof StoreCacheAccessor stores
                    && stores.generationControl$cacheMap().get(player) instanceof PlayerPartyStore party) {
                return party.occupied();
            }
        }
        return null;
    }

    public record Player(String name, UUID uuid, boolean online) {}
    public record PlayerPage(List<Player> players, String nextCursor) {}
    public record Directory(List<Player> online, Path savedPlayers, Path userCache) {}
    public record Slot(int position, String pokemonUuid, String species, String form, String nickname,
                       int level, boolean shiny, String nature, String ability, List<String> moves,
                       Map<String, Integer> ivs, Map<String, Integer> evs) {}
    public record Box(int index, int boxCount, String name, String source, List<Slot> slots) {}
    public record FileStore(Path path, boolean nbt) {}
    public record Lookup(Box live, List<FileStore> files) {}
    private record JsonBoxRead(int count, JsonObject requestedBox, JsonObject lastBox, int lastBoxIndex) {}
    private record PlayerIndex(Path directory, Path userCache, long directoryModified, long userCacheModified, long userCacheSize,
                               long createdAtNanos,
                               List<UUID> savedPlayers, Map<UUID, String> names) {}

    /** Capture the live list and paths on the game thread. */
    public static Directory directory(MinecraftServer server) {
        var online = server.getPlayerManager().getPlayerList().stream()
                .map(player -> new Player(player.getGameProfile().getName(), player.getUuid(), true)).toList();
        return new Directory(online, server.getSavePath(WorldSavePath.PLAYERDATA), Path.of("usercache.json"));
    }

    /** Compatibility helper for callers that only need the first bounded page. */
    public static List<Player> players(Directory directory, String search) throws IOException {
        return players(directory, search, null).players();
    }

    /** Resolve names from one bounded cache and return a stable UUID-cursor page off the game thread. */
    public static PlayerPage players(Directory directory, String search, String cursor) throws IOException {
        String term = search.trim().toLowerCase(Locale.ROOT);
        UUID after = null;
        if (cursor != null && !cursor.isBlank()) {
            try {
                after = UUID.fromString(cursor);
                if (!after.toString().equalsIgnoreCase(cursor)) throw new IllegalArgumentException();
            } catch (IllegalArgumentException invalid) { throw new IllegalArgumentException("Cursor de jogadores inválido.", invalid); }
        }
        PlayerIndex index = playerIndex(directory);
        List<Player> online = directory.online().stream().sorted(java.util.Comparator.comparing(Player::uuid)).toList();
        List<Player> result = new ArrayList<>(PAGE_SIZE);
        int onlineIndex = after == null ? 0 : firstAfterOnline(online, after);
        int savedIndex = after == null ? 0 : firstAfterSaved(index.savedPlayers(), after);
        String nextCursor = null;
        while (onlineIndex < online.size() || savedIndex < index.savedPlayers().size()) {
            Player candidate;
            if (onlineIndex >= online.size()) {
                UUID id = index.savedPlayers().get(savedIndex++);
                candidate = new Player(index.names().getOrDefault(id, id.toString()), id, false);
            } else if (savedIndex >= index.savedPlayers().size()) {
                candidate = online.get(onlineIndex++);
            } else {
                Player live = online.get(onlineIndex);
                UUID saved = index.savedPlayers().get(savedIndex);
                int order = live.uuid().compareTo(saved);
                if (order < 0) candidate = online.get(onlineIndex++);
                else if (order > 0) {
                    savedIndex++;
                    candidate = new Player(index.names().getOrDefault(saved, saved.toString()), saved, false);
                } else {
                    onlineIndex++; savedIndex++; candidate = live;
                }
            }
            if (!matches(candidate, term)) continue;
            if (result.size() == PAGE_SIZE) {
                nextCursor = result.get(result.size() - 1).uuid().toString();
                break;
            }
            result.add(candidate);
        }
        return new PlayerPage(List.copyOf(result), nextCursor);
    }

    private static int firstAfterOnline(List<Player> players, UUID cursor) {
        int low = 0, high = players.size();
        while (low < high) {
            int middle = (low + high) >>> 1;
            if (players.get(middle).uuid().compareTo(cursor) <= 0) low = middle + 1;
            else high = middle;
        }
        return low;
    }

    private static int firstAfterSaved(List<UUID> players, UUID cursor) {
        int low = 0, high = players.size();
        while (low < high) {
            int middle = (low + high) >>> 1;
            if (players.get(middle).compareTo(cursor) <= 0) low = middle + 1;
            else high = middle;
        }
        return low;
    }

    private static PlayerIndex playerIndex(Directory directory) throws IOException {
        Path folder = directory.savedPlayers().toAbsolutePath().normalize();
        Path userCache = directory.userCache().toAbsolutePath().normalize();
        long directoryModified = Files.isDirectory(folder) ? Files.getLastModifiedTime(folder).toMillis() : 0;
        long userCacheModified = Files.isRegularFile(userCache) ? Files.getLastModifiedTime(userCache).toMillis() : 0;
        long userCacheSize = Files.isRegularFile(userCache) ? Files.size(userCache) : 0;
        PlayerIndex current = cachedPlayers;
        if (matchesIndex(current, folder, userCache, directoryModified, userCacheModified, userCacheSize)) return current;
        synchronized (PcInspection.class) {
            current = cachedPlayers;
            if (matchesIndex(current, folder, userCache, directoryModified, userCacheModified, userCacheSize)) return current;
            List<UUID> ids = new ArrayList<>();
            if (Files.isDirectory(folder)) try (var paths = Files.list(folder)) {
                var iterator = paths.iterator();
                while (iterator.hasNext()) {
                    String name = iterator.next().getFileName().toString();
                    if (name.length() != 40 || !name.endsWith(".dat")) continue;
                    UUID id = parseUuid(name.substring(0, 36));
                    if (id == null) continue;
                    if (ids.size() == MAX_INDEXED_PLAYERS)
                        throw new IOException("O índice do PC excede o limite de " + MAX_INDEXED_PLAYERS + " jogadores.");
                    ids.add(id);
                }
            }
            ids.sort(UUID::compareTo);
            Map<UUID, String> names = readNames(userCache, userCacheSize);
            PlayerIndex rebuilt = new PlayerIndex(folder, userCache, directoryModified, userCacheModified, userCacheSize,
                    System.nanoTime(),
                    java.util.Collections.unmodifiableList(ids), names);
            cachedPlayers = rebuilt;
            return rebuilt;
        }
    }

    private static boolean matchesIndex(PlayerIndex index, Path folder, Path userCache, long directoryModified, long userCacheModified, long userCacheSize) {
        return index != null && index.directory().equals(folder) && index.userCache().equals(userCache)
                && System.nanoTime() - index.createdAtNanos() < java.util.concurrent.TimeUnit.SECONDS.toNanos(30)
                && index.directoryModified() == directoryModified
                && index.userCacheModified() == userCacheModified && index.userCacheSize() == userCacheSize;
    }

    private static Map<UUID, String> readNames(Path userCache, long userCacheSize) {
        Map<UUID, String> names = new HashMap<>();
        if (!Files.isRegularFile(userCache) || userCacheSize > 4 * 1024 * 1024) return Map.of();
        try (var input = new JsonReader(Files.newBufferedReader(userCache))) {
            if (input.peek() != JsonToken.BEGIN_ARRAY) return Map.of();
            input.beginArray();
            while (input.hasNext()) {
                if (names.size() >= MAX_CACHED_NAMES) break;
                String rawUuid = null, name = null;
                if (input.peek() != JsonToken.BEGIN_OBJECT) { input.skipValue(); continue; }
                input.beginObject();
                while (input.hasNext()) {
                    switch (input.nextName()) {
                        case "uuid" -> rawUuid = input.nextString();
                        case "name" -> name = input.nextString();
                        default -> input.skipValue();
                    }
                }
                input.endObject();
                UUID uuid = rawUuid == null ? null : parseUuid(rawUuid);
                if (uuid != null && name != null) names.put(uuid, name);
            }
        } catch (IOException | RuntimeException ignored) {
            names.clear();
        }
        return java.util.Collections.unmodifiableMap(names);
    }

    private static boolean matches(Player player, String term) {
        return containsIgnoreCase(player.name(), term) || player.uuid().toString().contains(term);
    }

    private static boolean containsIgnoreCase(String value, String term) {
        if (term.isEmpty()) return true;
        if (term.length() > value.length()) return false;
        for (int start = 0; start <= value.length() - term.length(); start++)
            if (value.regionMatches(true, start, term, 0, term.length())) return true;
        return false;
    }

    private static UUID parseUuid(String value) {
        try { return UUID.fromString(value); }
        catch (IllegalArgumentException invalid) { return null; }
    }

    /** Resolve providers and read at most 30 cached slots on the game thread. */
    public static Lookup lookup(UUID id, int index) {
        List<FileStore> files = new ArrayList<>();
        for (var factory : PlayerImpact.providers()) {
            var access = (StoreFactoryAccessor) (Object) factory;
            Object cache = access.generationControl$caches().get(PCStore.class);
            if (cache != null && ((StoreCacheAccessor) cache).generationControl$cacheMap().get(id) instanceof PCStore store) {
                return new Lookup(liveBox(store, index), List.of());
            }
            var adapter = access.generationControl$adapter();
            var info = (StorePathAccessor) (Object) adapter;
            Path path = StorePaths.path(info.generationControl$root(), info.generationControl$nested(),
                    info.generationControl$perClass(), info.generationControl$extension(), PCStore.class.getSimpleName(), id);
            files.add(new FileStore(path, adapter instanceof NBTStoreAdapter));
        }
        return new Lookup(null, List.copyOf(files));
    }

    private static Box liveBox(PCStore store, int requested) {
        int count = store.getBoxes().size();
        if (count == 0) return new Box(0, 0, null, "live", List.of());
        int index = Math.min(requested, count - 1);
        var box = store.getBoxes().get(index);
        List<Slot> slots = new ArrayList<>();
        for (int position = 0; position < SLOTS; position++) {
            var pokemon = box.get(position);
            if (pokemon != null) {
                List<String> moves = pokemon.getMoveSet().getMoves().stream()
                        .map(move -> move.getTemplate().getName()).limit(4).toList();
                slots.add(new Slot(position, pokemon.getUuid().toString(), pokemon.getSpecies().getResourceIdentifier().toString(),
                        pokemon.getForm().formOnlyShowdownId(), pokemon.getNickname() == null ? "" : pokemon.getNickname().getString(),
                        pokemon.getLevel(), pokemon.getShiny(),
                        pokemon.getNature().getDisplayName(), pokemon.getAbility() == null ? "" : pokemon.getAbility().getName(),
                        moves, statValues(pokemon.getIvs()), statValues(pokemon.getEvs())));
            }
        }
        return new Box(index, count, box.getName(), "live", slots);
    }

    /** Parse the saved data on an HTTP worker. Never call the storage provider's repair path. */
    public static Box savedBox(List<FileStore> files, int requested) throws Exception {
        for (FileStore store : files) {
            Box box = ReadOnlyStores.read(store.path(), file -> {
                if (file.length() > 16L * 1024 * 1024) throw new IOException("O arquivo excede 16 MiB.");
                if (store.nbt()) return nbtBox(NbtIo.readCompressed(file.toPath(), NbtSizeTracker.of(32L * 1024 * 1024)), requested);
                return jsonBox(file.toPath(), requested);
            });
            if (box != null) return box;
        }
        return new Box(0, 0, null, "missing", List.of());
    }

    private static Box jsonBox(Path path, int requested) throws IOException {
        JsonBoxRead read = readJsonBox(path, requested);
        int count = validateCount(read.count());
        if (count == 0) return new Box(0, 0, null, "saved", List.of());
        int index = Math.min(requested, count - 1);
        JsonObject box = index == requested ? read.requestedBox()
                : read.lastBoxIndex() == index ? read.lastBox() : readJsonBox(path, index).requestedBox();
        if (box == null) throw invalidPc();
        return jsonBox(box, index, count);
    }

    /** Stream the store and retain only the requested box instead of the full PC JSON tree. */
    private static JsonBoxRead readJsonBox(Path path, int requested) throws IOException {
        try (var input = new JsonReader(Files.newBufferedReader(path))) {
            if (input.peek() != JsonToken.BEGIN_OBJECT) throw invalidPc();
            input.beginObject();
            int count = -1;
            JsonObject requestedBox = null;
            JsonObject lastBox = null;
            int lastBoxIndex = -1;
            while (input.hasNext()) {
                String key = input.nextName();
                if (key.equals("BoxCount")) {
                    count = input.nextInt();
                } else {
                    int boxIndex = boxIndex(key);
                    if (boxIndex == requested || (count > 0 && boxIndex == count - 1)) {
                        JsonElement value = JsonParser.parseReader(input);
                        if (!value.isJsonObject()) throw invalidPc();
                        JsonObject box = value.getAsJsonObject();
                        if (boxIndex == requested) requestedBox = box;
                        if (boxIndex == count - 1) {
                            lastBoxIndex = boxIndex;
                            lastBox = box;
                        }
                    } else {
                        input.skipValue();
                    }
                }
            }
            input.endObject();
            if (input.peek() != JsonToken.END_DOCUMENT || count < 0) throw invalidPc();
            return new JsonBoxRead(count, requestedBox, lastBox, lastBoxIndex);
        }
    }

    private static int boxIndex(String key) {
        if (!key.startsWith("Box") || key.length() <= 3) return -1;
        try {
            int index = Integer.parseInt(key.substring(3));
            return index >= 0 && index < 4096 ? index : -1;
        } catch (NumberFormatException invalid) {
            return -1;
        }
    }

    static Box jsonBox(JsonObject store, int requested) {
        int count = validateCount(store.get("BoxCount").getAsInt());
        if (count == 0) return new Box(0, 0, null, "saved", List.of());
        int index = Math.min(requested, count - 1);
        JsonObject box = store.getAsJsonObject("Box" + index);
        if (box == null) throw invalidPc();
        return jsonBox(box, index, count);
    }

    private static Box jsonBox(JsonObject box, int index, int count) {
        List<Slot> slots = new ArrayList<>();
        for (int position = 0; position < SLOTS; position++) {
            JsonObject pokemon = box.getAsJsonObject("Slot" + position);
            if (pokemon == null) continue;
            slots.add(new Slot(position, jsonUuid(pokemon), jsonText(pokemon, "Species"), jsonText(pokemon, "FormId"),
                    jsonNickname(pokemon),
                    pokemon.has("Level") ? pokemon.get("Level").getAsInt() : 1,
                    pokemon.has("Shiny") && pokemon.get("Shiny").getAsBoolean(),
                    jsonName(pokemon.get("Nature")), jsonName(pokemon.get("Ability")),
                    jsonMoves(pokemon), jsonStats(pokemon.get("IVs")), jsonStats(pokemon.get("EVs"))));
        }
        return new Box(index, count, jsonText(box, "BoxName"), "saved", slots);
    }

    static Box nbtBox(NbtCompound store, int requested) {
        if (store == null || !store.contains("BoxCount", NbtElement.NUMBER_TYPE)) throw invalidPc();
        int count = validateCount(store.getInt("BoxCount"));
        if (count == 0) return new Box(0, 0, null, "saved", List.of());
        int index = Math.min(requested, count - 1);
        if (!store.contains("Box" + index, NbtElement.COMPOUND_TYPE)) throw invalidPc();
        NbtCompound box = store.getCompound("Box" + index);
        List<Slot> slots = new ArrayList<>();
        for (int position = 0; position < SLOTS; position++) {
            String key = "Slot" + position;
            if (!box.contains(key)) continue;
            if (!box.contains(key, NbtElement.COMPOUND_TYPE)) throw invalidPc();
            NbtCompound pokemon = box.getCompound(key);
            slots.add(new Slot(position, nbtUuid(pokemon), pokemon.getString("Species"), pokemon.getString("FormId"),
                    nbtNickname(pokemon),
                    pokemon.contains("Level", NbtElement.NUMBER_TYPE) ? pokemon.getInt("Level") : 1,
                    pokemon.getBoolean("Shiny"), nbtName(pokemon.get("Nature")), nbtName(pokemon.get("Ability")),
                    nbtMoves(pokemon), nbtStats(pokemon.get("IVs")), nbtStats(pokemon.get("EVs"))));
        }
        return new Box(index, count, box.getString("BoxName"), "saved", slots);
    }

    private static int validateCount(int count) {
        if (count < 0 || count > 4096) throw invalidPc();
        return count;
    }

    private static String jsonText(JsonObject object, String key) {
        return object.has(key) ? object.get(key).getAsString() : "";
    }

    private static String jsonUuid(JsonObject pokemon) {
        for (String key : List.of("UUID", "uuid", "Uuid")) {
            if (!pokemon.has(key) || pokemon.get(key).isJsonNull()) continue;
            JsonElement value = pokemon.get(key);
            if (value.isJsonPrimitive()) {
                UUID parsed = parseUuid(value.getAsString());
                if (parsed != null) return parsed.toString();
            } else if (value.isJsonArray() && value.getAsJsonArray().size() == 4) {
                try {
                    var values = value.getAsJsonArray();
                    long most = ((long) values.get(0).getAsInt() << 32) | (values.get(1).getAsInt() & 0xffffffffL);
                    long least = ((long) values.get(2).getAsInt() << 32) | (values.get(3).getAsInt() & 0xffffffffL);
                    return new UUID(most, least).toString();
                } catch (RuntimeException ignored) { }
            }
        }
        if (pokemon.has("UUIDMost") && pokemon.has("UUIDLeast")) try {
            return new UUID(pokemon.get("UUIDMost").getAsLong(), pokemon.get("UUIDLeast").getAsLong()).toString();
        } catch (RuntimeException ignored) { }
        return "";
    }

    private static String jsonNickname(JsonObject pokemon) {
        JsonElement value = pokemon.has("Nickname") ? pokemon.get("Nickname") : pokemon.get("nickname");
        return jsonComponentText(value);
    }

    private static String jsonComponentText(JsonElement value) {
        if (value == null || value.isJsonNull()) return "";
        if (value.isJsonPrimitive()) return value.getAsString();
        if (!value.isJsonObject()) return "";
        JsonObject object = value.getAsJsonObject();
        if (object.has("text") && object.get("text").isJsonPrimitive()) return object.get("text").getAsString();
        if (object.has("Text") && object.get("Text").isJsonPrimitive()) return object.get("Text").getAsString();
        if (object.has("extra") && object.get("extra").isJsonArray()) {
            StringBuilder text = new StringBuilder();
            for (JsonElement part : object.getAsJsonArray("extra")) text.append(jsonComponentText(part));
            return text.toString();
        }
        return "";
    }

    private static String nbtUuid(NbtCompound pokemon) {
        for (String key : List.of("UUID", "uuid", "Uuid")) {
            if (!pokemon.contains(key)) continue;
            try {
                NbtElement value = pokemon.get(key);
                if (value instanceof net.minecraft.nbt.NbtString string) {
                    UUID parsed = parseUuid(string.asString());
                    if (parsed != null) return parsed.toString();
                } else if (value instanceof net.minecraft.nbt.NbtIntArray) {
                    return pokemon.getUuid(key).toString();
                }
            } catch (RuntimeException ignored) { }
        }
        if (pokemon.contains("UUIDMost", NbtElement.NUMBER_TYPE) && pokemon.contains("UUIDLeast", NbtElement.NUMBER_TYPE))
            return new UUID(pokemon.getLong("UUIDMost"), pokemon.getLong("UUIDLeast")).toString();
        return "";
    }

    private static String nbtNickname(NbtCompound pokemon) {
        NbtElement value = pokemon.contains("Nickname") ? pokemon.get("Nickname") : pokemon.get("nickname");
        return nbtComponentText(value);
    }

    private static String nbtComponentText(NbtElement value) {
        if (value instanceof net.minecraft.nbt.NbtString string) return string.asString();
        if (!(value instanceof NbtCompound object)) return "";
        for (String key : List.of("text", "Text")) if (object.contains(key, NbtElement.STRING_TYPE)) return object.getString(key);
        if (object.contains("extra", NbtElement.LIST_TYPE)) {
            StringBuilder text = new StringBuilder();
            for (NbtElement part : object.getList("extra", NbtElement.COMPOUND_TYPE)) text.append(nbtComponentText(part));
            return text.toString();
        }
        return "";
    }

    private static Map<String, Integer> statValues(com.cobblemon.mod.common.pokemon.PokemonStats stats) {
        LinkedHashMap<String, Integer> values = new LinkedHashMap<>();
        for (var entry : stats) {
            String id = entry.getKey().getShowdownId();
            if (id != null && !id.isBlank()) values.put(id, entry.getValue());
        }
        return Map.copyOf(values);
    }

    private static String jsonName(JsonElement value) {
        if (value == null || value.isJsonNull()) return "";
        if (value.isJsonPrimitive()) return value.getAsString();
        if (value.isJsonObject()) {
            JsonObject object = value.getAsJsonObject();
            for (String key : List.of("Name", "name", "Id", "id", "DisplayName", "displayName", "Move", "move", "Template", "template")) {
                if (object.has(key) && object.get(key).isJsonPrimitive()) return object.get(key).getAsString();
            }
        }
        return "";
    }

    private static List<String> jsonMoves(JsonObject pokemon) {
        JsonElement value = pokemon.has("MoveSet") ? pokemon.get("MoveSet") : pokemon.get("Moves");
        List<String> moves = new ArrayList<>();
        if (value == null || value.isJsonNull()) return List.of();
        if (value.isJsonArray()) {
            for (JsonElement move : value.getAsJsonArray()) {
                String name = jsonName(move);
                if (!name.isBlank() && moves.size() < 4) moves.add(name);
            }
        } else if (value.isJsonObject()) {
            JsonObject object = value.getAsJsonObject();
            JsonElement list = object.has("Moves") ? object.get("Moves") : object.get("moves");
            if (list != null && list.isJsonArray()) {
                for (JsonElement move : list.getAsJsonArray()) {
                    String name = jsonName(move);
                    if (!name.isBlank() && moves.size() < 4) moves.add(name);
                }
            } else {
                for (var entry : object.entrySet()) {
                    String name = jsonName(entry.getValue());
                    if (!name.isBlank() && moves.size() < 4) moves.add(name);
                }
            }
        }
        return List.copyOf(moves);
    }

    private static Map<String, Integer> jsonStats(JsonElement value) {
        LinkedHashMap<String, Integer> stats = new LinkedHashMap<>();
        if (value == null || !value.isJsonObject()) return Map.of();
        for (var entry : value.getAsJsonObject().entrySet()) {
            if (!entry.getValue().isJsonPrimitive() || !entry.getValue().getAsJsonPrimitive().isNumber()) continue;
            if (stats.size() == 6) break;
            stats.put(entry.getKey().toLowerCase(Locale.ROOT), entry.getValue().getAsInt());
        }
        return Map.copyOf(stats);
    }

    private static String nbtName(NbtElement value) {
        if (value instanceof net.minecraft.nbt.NbtString text) return text.asString();
        if (value instanceof NbtCompound object) {
            for (String key : List.of("Name", "name", "Id", "id", "DisplayName", "displayName", "Move", "move", "Template", "template")) {
                if (object.contains(key, NbtElement.STRING_TYPE)) return object.getString(key);
            }
        }
        return "";
    }

    private static List<String> nbtMoves(NbtCompound pokemon) {
        String key = pokemon.contains("MoveSet") ? "MoveSet" : "Moves";
        List<String> moves = new ArrayList<>();
        NbtElement value = pokemon.get(key);
        if (value instanceof net.minecraft.nbt.NbtList list) {
            for (NbtElement element : list) {
                String name = nbtName(element);
                if (!name.isBlank() && moves.size() < 4) moves.add(name);
            }
        } else if (value instanceof NbtCompound object) {
            NbtElement nested = object.contains("Moves") ? object.get("Moves") : object.get("moves");
            if (nested instanceof net.minecraft.nbt.NbtList list) {
                for (NbtElement element : list) {
                    String name = nbtName(element);
                    if (!name.isBlank() && moves.size() < 4) moves.add(name);
                }
            } else {
                for (String entry : object.getKeys()) {
                    String name = nbtName(object.get(entry));
                    if (!name.isBlank() && moves.size() < 4) moves.add(name);
                }
            }
        }
        return List.copyOf(moves);
    }

    private static Map<String, Integer> nbtStats(NbtElement value) {
        LinkedHashMap<String, Integer> stats = new LinkedHashMap<>();
        if (!(value instanceof NbtCompound object)) return Map.of();
        for (String key : object.getKeys()) {
            if (stats.size() == 6) break;
            if (object.contains(key, NbtElement.NUMBER_TYPE)) stats.put(key.toLowerCase(Locale.ROOT), object.getInt(key));
        }
        return Map.copyOf(stats);
    }

    private static IllegalArgumentException invalidPc() {
        return new IllegalArgumentException("Estrutura de PC inválida.");
    }
}
