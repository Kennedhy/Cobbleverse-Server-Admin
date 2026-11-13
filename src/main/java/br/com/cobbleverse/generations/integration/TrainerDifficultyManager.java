package br.com.cobbleverse.generations.integration;

import br.com.cobbleverse.generations.GenerationControl;
import com.cobblemon.mod.common.api.battles.model.PokemonBattle;
import com.cobblemon.mod.common.api.battles.model.actor.BattleActor;
import com.cobblemon.mod.common.api.battles.model.actor.EntityBackedBattleActor;
import com.cobblemon.mod.common.api.events.CobblemonEvents;
import com.cobblemon.mod.common.api.pokemon.stats.Stat;
import com.cobblemon.mod.common.api.pokemon.stats.Stats;
import com.cobblemon.mod.common.battles.BattleSide;
import com.cobblemon.mod.common.battles.pokemon.BattlePokemon;
import com.cobblemon.mod.common.pokemon.EVs;
import com.cobblemon.mod.common.pokemon.IVs;
import com.cobblemon.mod.common.pokemon.Pokemon;
import com.gitlab.srcmc.rctmod.api.RCTMod;
import com.gitlab.srcmc.rctmod.api.data.pack.TrainerMobData;
import com.gitlab.srcmc.rctmod.world.entities.TrainerMob;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.server.network.ServerPlayerEntity;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.*;
import java.util.stream.Collectors;

/** Applies configured profiles only to RCT battle copies of trainer Pokémon. */
public final class TrainerDifficultyManager {
    private static final Logger LOGGER = GenerationControl.LOGGER;
    private static final int MAX_CONFIG_BYTES = 64 * 1024;
    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().setPrettyPrinting().create();
    private static final List<Stat> MAIN_STATS = List.of(Stats.HP, Stats.ATTACK, Stats.DEFENCE,
            Stats.SPECIAL_ATTACK, Stats.SPECIAL_DEFENCE, Stats.SPEED);

    private static volatile TrainerDifficultyConfig active = new TrainerDifficultyConfig();
    private static volatile Map<String, Object> trainerTypes = Map.of();
    private static volatile List<Map<String, String>> trainers = List.of();
    private static volatile boolean catalogReady;
    private static boolean registered;

    private TrainerDifficultyManager() {}

    public static Gson gson() { return GSON; }

    public static synchronized void register() {
        if (registered) return;
        registered = true;
        load();
        CobblemonEvents.BATTLE_STARTED_PRE.subscribe(event -> {
            try { apply(event.getBattle()); }
            catch (RuntimeException failure) {
                LOGGER.warn("Não foi possível aplicar o perfil de dificuldade da batalha; a equipe do treinador foi preservada.", failure);
            }
        });
        ServerLifecycleEvents.SERVER_STARTED.register(server -> refreshTrainerCatalog());
        ServerLifecycleEvents.END_DATA_PACK_RELOAD.register((server, resources, success) -> {
            if (success) {
                catalogReady = false;
                refreshTrainerCatalog();
            }
        });
    }

    private static Path configPath() {
        return FabricLoader.getInstance().getConfigDir().resolve("generation-control").resolve("trainer-difficulty.json");
    }

    private static synchronized void load() {
        Path file = configPath();
        if (!Files.exists(file)) return;
        try {
            long size = Files.size(file);
            if (size < 1 || size > MAX_CONFIG_BYTES) throw new IOException("Arquivo de dificuldade vazio ou maior que 64 KiB.");
            TrainerDifficultyConfig parsed = GSON.fromJson(Files.readString(file, StandardCharsets.UTF_8), TrainerDifficultyConfig.class);
            active = (parsed == null ? new TrainerDifficultyConfig() : parsed).validated();
        } catch (Exception failure) {
            active = new TrainerDifficultyConfig();
            LOGGER.error("Configuração de dificuldade de treinadores inválida; dificuldade padrão preservada. Corrija {}.", file, failure);
        }
    }

    /** Returns config and an immutable, event-refreshed RCT catalog for the admin page. */
    public static Map<String, Object> view() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("config", active.copy());
        result.put("trainerTypes", trainerTypes);
        result.put("trainers", trainers);
        result.put("catalogReady", catalogReady);
        return result;
    }

    public static boolean catalogReady() { return catalogReady; }

    /** Validates and atomically replaces the standalone live policy. Caller must enforce admin authorization. */
    public static synchronized Map<String, Object> save(JsonObject candidate) throws IOException {
        if (candidate == null) throw new IllegalArgumentException("Configuração de dificuldade vazia.");
        if (candidate.toString().length() > MAX_CONFIG_BYTES) throw new IllegalArgumentException("A configuração de dificuldade excede 64 KiB.");
        TrainerDifficultyConfig parsed = GSON.fromJson(candidate, TrainerDifficultyConfig.class);
        if (parsed == null) throw new IllegalArgumentException("Configuração de dificuldade vazia.");
        parsed.validated();
        parsed.revision = Math.max(active.revision + 1, parsed.revision);
        String serialized = GSON.toJson(parsed);
        if (serialized.getBytes(StandardCharsets.UTF_8).length > MAX_CONFIG_BYTES)
            throw new IllegalArgumentException("A configuração de dificuldade excede 64 KiB.");
        Path file = configPath();
        Files.createDirectories(file.getParent());
        Path temporary = Files.createTempFile(file.getParent(), ".trainer-difficulty-", ".tmp");
        try {
            Files.writeString(temporary, serialized, StandardCharsets.UTF_8,
                    StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
            try {
                Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException unsupported) {
                Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
            }
            active = parsed;
        } finally {
            Files.deleteIfExists(temporary);
        }
        return view();
    }

    public static synchronized void refreshTrainerCatalog() {
        try {
            var manager = RCTMod.getInstance().getTrainerManager();
            if (manager.isLoading()) return;
            Map<String, Integer> counts = new TreeMap<>();
            List<Map<String, String>> allTrainers = new ArrayList<>();
            manager.getAllData().limit(5000).forEach(entry -> {
                TrainerMobData data = entry.getValue();
                if (data == null) return;
                String type = data.getType() == null ? "unknown" : data.getType().id();
                counts.merge(type, 1, Integer::sum);
                if (allTrainers.size() < 5000) {
                    Map<String, String> trainer = new LinkedHashMap<>();
                    trainer.put("id", entry.getKey());
                    trainer.put("type", type);
                    allTrainers.add(Map.copyOf(trainer));
                }
            });
            Map<String, Object> types = new LinkedHashMap<>();
            counts.forEach((id, count) -> types.put(id, count));
            trainerTypes = Map.copyOf(types);
            trainers = List.copyOf(allTrainers);
            // RCT can finish SERVER_STARTED before its resource reload registers
            // the datapack trainers. Keep the catalog retryable while it is empty.
            catalogReady = !allTrainers.isEmpty();
        } catch (RuntimeException failure) {
            LOGGER.warn("Não foi possível atualizar o catálogo de treinadores RCT.", failure);
        }
    }

    private static void apply(PokemonBattle battle) {
        TrainerDifficultyConfig policy = active;
        if (!policy.enabled || battle == null || battle.isPvP() || !battle.isPvN()) return;
        var manager = RCTMod.getInstance().getTrainerManager();
        for (BattleSide trainerSide : List.of(battle.getSide1(), battle.getSide2())) {
            List<UUID> opponents = playerUuids(trainerSide.getOppositeSide());
            for (BattleActor actor : trainerSide.getActors()) {
                if (!(actor instanceof EntityBackedBattleActor<?> backed)
                        || !(backed.getEntity() instanceof TrainerMob trainerMob)) continue;
                TrainerMobData data = manager.getData(trainerMob);
                if (data == null) continue;
                String type = data.getType() == null ? "unknown" : data.getType().id();
                String trainerGroup = trainerGroup(policy, trainerMob.getTrainerId(), type);
                TrainerDifficultyConfig.Profile profile = chooseProfile(policy, trainerGroup, opponents, actor.getPokemonList());
                for (BattlePokemon battlePokemon : actor.getPokemonList()) {
                    // RCT builds trainer teams as BattlePokemon(original, clone). Never touch shared/source data.
                    Pokemon source = battlePokemon.getOriginalPokemon();
                    Pokemon clone = battlePokemon.getEffectedPokemon();
                    if (clone == null || clone == source) continue;
                    tune(clone, profile);
                }
            }
        }
    }

    private static List<UUID> playerUuids(BattleSide side) {
        LinkedHashSet<UUID> result = new LinkedHashSet<>();
        for (BattleActor actor : side.getActors()) {
            for (UUID uuid : actor.getPlayerUUIDs()) if (uuid != null) result.add(uuid);
        }
        return List.copyOf(result);
    }

    private static String trainerGroup(TrainerDifficultyConfig policy, String trainerId, String type) {
        if (trainerId != null) {
            String id = trainerId.toLowerCase(Locale.ROOT);
            for (var group : policy.trainerGroups) if (group.trainerIds.contains(id)) return group.id;
        }
        String normalizedType = type == null ? "unknown" : type.toLowerCase(Locale.ROOT);
        for (var group : policy.trainerGroups) if (group.trainerTypes.contains(normalizedType)) return group.id;
        return null;
    }

    private static String playerGroup(TrainerDifficultyConfig policy, UUID player) {
        String uuid = player.toString();
        for (var group : policy.playerGroups) if (group.playerUuids.contains(uuid)) return group.id;
        return null;
    }

    private static TrainerDifficultyConfig.Profile chooseProfile(TrainerDifficultyConfig policy, String trainerGroup,
                                                                   List<UUID> opponents, List<BattlePokemon> team) {
        if (opponents.isEmpty()) return resolveProfile(policy, trainerGroup, null);
        List<TrainerDifficultyConfig.Profile> candidates = new ArrayList<>(opponents.size());
        for (UUID opponent : opponents) candidates.add(resolveProfile(policy, trainerGroup, playerGroup(policy, opponent)));
        // Cooperative battles use the hardest applicable player profile so a grouped teammate cannot lower it.
        return candidates.stream().max(Comparator
                .comparingLong((TrainerDifficultyConfig.Profile profile) -> levelScore(profile, team))
                .thenComparingInt(profile -> profile.minimumIv)
                .thenComparingInt(profile -> profile.evPerStat)).orElse(policy.defaultProfile);
    }

    private static TrainerDifficultyConfig.Profile resolveProfile(TrainerDifficultyConfig policy, String trainerGroup, String playerGroup) {
        TrainerDifficultyConfig.ProfileRule best = null;
        int bestScore = -1;
        for (var rule : policy.profiles) {
            boolean trainerMatches = rule.trainerGroup.equals("*") || Objects.equals(rule.trainerGroup, trainerGroup);
            boolean playerMatches = rule.playerGroup.equals("*") || Objects.equals(rule.playerGroup, playerGroup);
            if (!trainerMatches || !playerMatches) continue;
            // Pair > trainer-only > player-only; trainer group wins equal-specificity ties by design.
            int score = (rule.trainerGroup.equals("*") ? 0 : 2) + (rule.playerGroup.equals("*") ? 0 : 1);
            if (score > bestScore) { best = rule; bestScore = score; }
        }
        return best == null ? policy.defaultProfile : best.profile;
    }

    private static long levelScore(TrainerDifficultyConfig.Profile profile, List<BattlePokemon> team) {
        long score = 0;
        for (BattlePokemon pokemon : team) {
            int base = pokemon.getOriginalPokemon().getLevel();
            score += Math.max(1, Math.min(100, (int) Math.round(base * profile.levelMultiplier) + profile.levelOffset));
        }
        return score;
    }

    private static void tune(Pokemon pokemon, TrainerDifficultyConfig.Profile profile) {
        int initialMaxHealth = pokemon.getMaxHealth();
        float healthRatio = initialMaxHealth <= 0 ? 1.0f : Math.max(0.0f, Math.min(1.0f, pokemon.getCurrentHealth() / (float) initialMaxHealth));
        int level = Math.max(1, Math.min(100,
                (int) Math.round(pokemon.getLevel() * profile.levelMultiplier) + profile.levelOffset));
        if (level != pokemon.getLevel()) pokemon.setLevel(level);

        IVs ivs = pokemon.getIvs();
        if (ivs != null && profile.minimumIv > 0) {
            for (Stat stat : MAIN_STATS) {
                int current = ivs.getOrDefault(stat);
                if (current < profile.minimumIv) ivs.set(stat, profile.minimumIv);
            }
        }

        EVs evs = pokemon.getEvs();
        if (evs != null && profile.evPerStat > 0) {
            int remaining = Math.max(0, EVs.MAX_TOTAL_VALUE - evs.total());
            for (Stat stat : MAIN_STATS) {
                if (remaining == 0) break;
                int current = evs.getOrDefault(stat);
                int add = Math.min(remaining, Math.max(0, profile.evPerStat - current));
                if (add > 0) {
                    evs.set(stat, current + add);
                    remaining -= add;
                }
            }
        }
        pokemon.setCurrentHealth(Math.max(0, Math.min(pokemon.getMaxHealth(), (int) Math.ceil(healthRatio * pokemon.getMaxHealth()))));
    }
}
