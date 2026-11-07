package br.com.cobbleverse.generations.integration;

import br.com.cobbleverse.generations.mixin.StoreCacheAccessor;
import br.com.cobbleverse.generations.mixin.StoreFactoryAccessor;
import br.com.cobbleverse.generations.mixin.StorePathAccessor;
import com.cobblemon.mod.common.api.storage.adapter.flatfile.NBTStoreAdapter;
import com.cobblemon.mod.common.api.storage.pc.PCStore;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
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
import java.util.Locale;
import java.util.UUID;

/** On-demand PC projection. Never creates, initializes or repairs a Pokémon store. */
public final class PcInspection {
    private static final int SLOTS = 30;
    private static final int PLAYER_LIMIT = 40;

    private PcInspection() {}

    public record Player(String name, UUID uuid, boolean online) {}
    public record Directory(List<Player> online, Path savedPlayers, Path userCache) {}
    public record Slot(int position, String species, String form, int level, boolean shiny) {}
    public record Box(int index, int boxCount, String name, String source, List<Slot> slots) {}
    public record FileStore(Path path, boolean nbt) {}
    public record Lookup(Box live, List<FileStore> files) {}

    /** Capture the live list and paths on the game thread. */
    public static Directory directory(MinecraftServer server) {
        var online = server.getPlayerManager().getPlayerList().stream()
                .map(player -> new Player(player.getGameProfile().getName(), player.getUuid(), true)).toList();
        return new Directory(online, server.getSavePath(WorldSavePath.PLAYERDATA), Path.of("usercache.json"));
    }

    /** Resolve saved names and list a bounded set of matches off the game thread. */
    public static List<Player> players(Directory directory, String search) throws IOException {
        String term = search.trim().toLowerCase(Locale.ROOT);
        List<Player> result = new ArrayList<>();
        var onlineIds = new HashSet<UUID>();
        for (Player player : directory.online()) {
            onlineIds.add(player.uuid());
            if (matches(player, term) && result.size() < PLAYER_LIMIT) result.add(player);
        }
        if (result.size() == PLAYER_LIMIT || !Files.isDirectory(directory.savedPlayers())) return result;

        var names = new HashMap<UUID, String>();
        if (Files.isRegularFile(directory.userCache()) && Files.size(directory.userCache()) <= 4 * 1024 * 1024) {
            try (var reader = Files.newBufferedReader(directory.userCache())) {
                for (var entry : JsonParser.parseReader(reader).getAsJsonArray()) {
                    var profile = entry.getAsJsonObject();
                    UUID uuid = parseUuid(profile.get("uuid").getAsString());
                    if (uuid != null) names.put(uuid, profile.get("name").getAsString());
                }
            } catch (IOException | RuntimeException ignored) {
                // The cache is optional and may be rewritten while the world is running.
                names.clear();
            }
        }
        try (var files = Files.list(directory.savedPlayers())) {
            files.map(path -> path.getFileName().toString())
                    .filter(name -> name.length() == 40 && name.endsWith(".dat"))
                    .map(name -> parseUuid(name.substring(0, 36)))
                    .filter(id -> id != null && !onlineIds.contains(id))
                    .map(id -> new Player(names.getOrDefault(id, id.toString()), id, false))
                    .filter(player -> matches(player, term))
                    .limit(PLAYER_LIMIT - result.size()).forEach(result::add);
        }
        return result;
    }

    private static boolean matches(Player player, String term) {
        return player.name().toLowerCase(Locale.ROOT).contains(term) || player.uuid().toString().contains(term);
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
            if (pokemon != null) slots.add(new Slot(position, pokemon.getSpecies().getResourceIdentifier().toString(),
                    pokemon.getForm().getName(), pokemon.getLevel(), pokemon.getShiny()));
        }
        return new Box(index, count, box.getName(), "live", slots);
    }

    /** Parse the saved data on an HTTP worker. Never call the storage provider's repair path. */
    public static Box savedBox(List<FileStore> files, int requested) throws Exception {
        for (FileStore store : files) {
            Box box = ReadOnlyStores.read(store.path(), file -> {
                if (file.length() > 16L * 1024 * 1024) throw new IOException("O arquivo excede 16 MiB.");
                if (store.nbt()) return nbtBox(NbtIo.readCompressed(file.toPath(), NbtSizeTracker.of(32L * 1024 * 1024)), requested);
                try (var reader = Files.newBufferedReader(file.toPath())) {
                    return jsonBox(JsonParser.parseReader(reader).getAsJsonObject(), requested);
                }
            });
            if (box != null) return box;
        }
        return new Box(0, 0, null, "missing", List.of());
    }

    static Box jsonBox(JsonObject store, int requested) {
        int count = validateCount(store.get("BoxCount").getAsInt());
        if (count == 0) return new Box(0, 0, null, "saved", List.of());
        int index = Math.min(requested, count - 1);
        JsonObject box = store.getAsJsonObject("Box" + index);
        List<Slot> slots = new ArrayList<>();
        for (int position = 0; position < SLOTS; position++) {
            JsonObject pokemon = box.getAsJsonObject("Slot" + position);
            if (pokemon == null) continue;
            slots.add(new Slot(position, jsonText(pokemon, "Species"), jsonText(pokemon, "FormId"),
                    pokemon.get("Level").getAsInt(), pokemon.has("Shiny") && pokemon.get("Shiny").getAsBoolean()));
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
            slots.add(new Slot(position, pokemon.getString("Species"), pokemon.getString("FormId"),
                    pokemon.getInt("Level"), pokemon.getBoolean("Shiny")));
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

    private static IllegalArgumentException invalidPc() {
        return new IllegalArgumentException("Estrutura de PC inválida.");
    }
}
