package br.com.cobbleverse.generations.web;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.loader.api.FabricLoader;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Narrow, on-demand bridge to the trainer spawning and CobbleDollars settings
 * exposed by the versions shipped with the supported Cobbleverse pack.
 * Call only from an authenticated admin request on the Minecraft server thread.
 */
final class TrainerOperationsSettings {
    private static final Object LOCK = new Object();
    private static final String RCT_API = "com.gitlab.srcmc.rctmod.api.RCTMod";
    private static final String DOLLARS_API = "fr.harmex.cobbledollars.common.CobbleDollars";
    private static final String DOLLARS_CONFIG = "fr.harmex.cobbledollars.common.config.CommonConfig";

    private TrainerOperationsSettings() {}

    static Map<String, Object> read() {
        synchronized (LOCK) {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("spawning", readSpawning());
            result.put("rewards", readRewards());
            return result;
        }
    }

    /**
     * Updates only the fields present in the request. RCT and CobbleDollars
     * expose live config values and save APIs; no external config file is
     * edited while either mod is running.
     */
    static Map<String, Object> update(JsonObject request, boolean administrator) {
        if (!administrator) throw new WebPanelService.Problem(403, "Apenas administradores podem alterar treinadores e recompensas.");
        if (request == null) throw new WebPanelService.Problem(400, "Configuração de treinadores inválida.");

        synchronized (LOCK) {
            Map<String, Object> beforeSpawning = readSpawning();
            Map<String, Object> beforeRewards = readRewards();
            Map<String, Object> nextSpawning = mergeSpawning(beforeSpawning, object(request, "spawning"));
            Map<String, Object> nextRewards = mergeRewards(beforeRewards, object(request, "rewards"));

            boolean changeSpawning = !nextSpawning.equals(beforeSpawning);
            boolean changeRewards = !nextRewards.equals(beforeRewards);
            boolean spawningAttempted = false;
            boolean rewardsAttempted = false;
            try {
                if (changeSpawning) {
                    spawningAttempted = true;
                    writeSpawning(nextSpawning, beforeSpawning);
                }
                if (changeRewards) {
                    rewardsAttempted = true;
                    writeRewards(nextRewards);
                }
            } catch (RuntimeException failure) {
                if (spawningAttempted) {
                    try { writeSpawning(beforeSpawning); }
                    catch (RuntimeException rollbackFailure) { failure.addSuppressed(rollbackFailure); }
                }
                if (rewardsAttempted) {
                    try { writeRewards(beforeRewards); }
                    catch (RuntimeException rollbackFailure) { failure.addSuppressed(rollbackFailure); }
                }
                throw new WebPanelService.Problem(503, "Não foi possível confirmar as configurações dos mods integrados. Atualize o painel e confira os valores antes de tentar novamente.");
            }
            return read();
        }
    }

    private static Map<String, Object> readSpawning() {
        Object config = rctServerConfig();
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("globalSpawnChance", number(config, "globalSpawnChance"));
        values.put("globalSpawnChanceMinimum", number(config, "globalSpawnChanceMinimum"));
        values.put("spawnIntervalTicks", integer(config, "spawnIntervalTicks"));
        values.put("spawnIntervalTicksMaximum", integer(config, "spawnIntervalTicksMaximum"));
        values.put("despawnTicksIfUnseen", integer(config, "despawnTicksIfUnseen"));
        values.put("maxHorizontalDistanceToPlayers", integer(config, "maxHorizontalDistanceToPlayers"));
        values.put("minHorizontalDistanceToPlayers", integer(config, "minHorizontalDistanceToPlayers"));
        values.put("maxVerticalDistanceToPlayers", integer(config, "maxVerticalDistanceToPlayers"));
        values.put("uniqueTrainerRadius", integer(config, "uniqueTrainerRadius"));
        values.put("maxTrainersPerPlayer", integer(config, "maxTrainersPerPlayer"));
        values.put("maxTrainersTotal", integer(config, "maxTrainersTotal"));
        values.put("maxLevelDiff", integer(config, "maxLevelDiff"));
        values.put("spawningRequiresTrainerCard", bool(config, "spawningRequiresTrainerCard"));
        values.put("spawnTrainerAssociation", bool(config, "spawnTrainerAssociation"));
        return values;
    }

    private static Map<String, Object> readRewards() {
        if (!classPresent(DOLLARS_API)) return Map.of("available", false, "message", "CobbleDollars não está instalado neste servidor.");
        Object config = dollarsConfig();
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("available", true);
        values.put("earnCobbleDollarsFromNPC", bool(config, "getEarnCobbleDollarsFromNPC"));
        values.put("earnCobbleDollarsFromWildPokemon", bool(config, "getEarnCobbleDollarsFromWildPokemon"));
        values.put("cobbleDollarsIncomeMultiplier", number(config, "getCobbleDollarsIncomeMultiplier"));
        return values;
    }

    private static Map<String, Object> mergeSpawning(Map<String, Object> current, JsonObject patch) {
        Map<String, Object> result = new LinkedHashMap<>(current);
        if (patch == null) return result;
        rejectUnknown(patch, List.of("globalSpawnChance", "globalSpawnChanceMinimum", "spawnIntervalTicks",
                "spawnIntervalTicksMaximum", "despawnTicksIfUnseen", "maxHorizontalDistanceToPlayers",
                "minHorizontalDistanceToPlayers", "maxVerticalDistanceToPlayers", "uniqueTrainerRadius",
                "maxTrainersPerPlayer", "maxTrainersTotal", "maxLevelDiff", "spawningRequiresTrainerCard",
                "spawnTrainerAssociation"));
        putDouble(patch, result, "globalSpawnChance", 0, 1);
        putDouble(patch, result, "globalSpawnChanceMinimum", 0, 1);
        putInt(patch, result, "spawnIntervalTicks", 20, Integer.MAX_VALUE - 1);
        putInt(patch, result, "spawnIntervalTicksMaximum", 0, Integer.MAX_VALUE - 1);
        putInt(patch, result, "despawnTicksIfUnseen", -1, Integer.MAX_VALUE - 1);
        putInt(patch, result, "maxHorizontalDistanceToPlayers", 1, 256);
        putInt(patch, result, "minHorizontalDistanceToPlayers", 1, 256);
        putInt(patch, result, "maxVerticalDistanceToPlayers", 1, 256);
        putInt(patch, result, "uniqueTrainerRadius", 0, 4096);
        // Deliberately bounded to keep an administrator from accidentally
        // authorizing an unbounded entity population on a lightweight server.
        putInt(patch, result, "maxTrainersPerPlayer", 0, 128);
        putInt(patch, result, "maxTrainersTotal", 0, 512);
        putInt(patch, result, "maxLevelDiff", 0, 100);
        putBool(patch, result, "spawningRequiresTrainerCard");
        putBool(patch, result, "spawnTrainerAssociation");

        if ((int) result.get("minHorizontalDistanceToPlayers") > (int) result.get("maxHorizontalDistanceToPlayers"))
            throw new WebPanelService.Problem(400, "A distância mínima de spawn não pode exceder a máxima.");
        int baseInterval = (int) result.get("spawnIntervalTicks");
        int maxInterval = (int) result.get("spawnIntervalTicksMaximum");
        if (maxInterval > 0 && maxInterval < baseInterval)
            throw new WebPanelService.Problem(400, "O intervalo máximo deve ser zero ou igual/superior ao intervalo inicial.");
        return result;
    }

    private static Map<String, Object> mergeRewards(Map<String, Object> current, JsonObject patch) {
        Map<String, Object> result = new LinkedHashMap<>(current);
        if (patch == null) return result;
        rejectUnknown(patch, List.of("earnCobbleDollarsFromNPC", "cobbleDollarsIncomeMultiplier"));
        if (!Boolean.TRUE.equals(current.get("available"))) {
            throw new WebPanelService.Problem(501, "As recompensas CobbleDollars só podem ser gerenciadas quando esse mod estiver instalado.");
        }
        putBool(patch, result, "earnCobbleDollarsFromNPC");
        putDouble(patch, result, "cobbleDollarsIncomeMultiplier", 0, 100);
        return result;
    }

    private static void writeSpawning(Map<String, Object> values) {
        writeSpawning(values, Map.of());
    }

    private static void writeSpawning(Map<String, Object> values, Map<String, Object> previous) {
        Object config = rctServerConfig();
        Object spec = invoke(config, "getSpec");
        Object configValues = invoke(spec, "getValues");
        for (String key : List.of("globalSpawnChance", "globalSpawnChanceMinimum", "spawnIntervalTicks",
                "spawnIntervalTicksMaximum", "despawnTicksIfUnseen", "maxHorizontalDistanceToPlayers",
                "minHorizontalDistanceToPlayers", "maxVerticalDistanceToPlayers", "uniqueTrainerRadius",
                "maxTrainersPerPlayer", "maxTrainersTotal", "maxLevelDiff", "spawningRequiresTrainerCard",
                "spawnTrainerAssociation")) {
            if (Objects.equals(values.get(key), previous.get(key))) continue;
            Object configValue = pathValue(configValues, List.of("Spawning", key));
            if (configValue == null) throw new IllegalStateException("RCT não expõe a configuração de spawn: " + key);
            invoke(configValue, "set", new Class<?>[]{Object.class}, values.get(key));
        }
        invoke(spec, "save");
        invoke(config, "reload");
    }

    private static void writeRewards(Map<String, Object> values) {
        Object config = dollarsConfig();
        invoke(config, "setEarnCobbleDollarsFromNPC", new Class<?>[]{boolean.class}, values.get("earnCobbleDollarsFromNPC"));
        invoke(config, "setCobbleDollarsIncomeMultiplier", new Class<?>[]{double.class}, values.get("cobbleDollarsIncomeMultiplier"));
        try {
            Class<?> configClass = Class.forName(DOLLARS_CONFIG);
            Field companionField = configClass.getField("Companion");
            Object companion = companionField.get(null);
            invoke(companion, "save");
            Map<String, Object> saved = readRewards();
            if (!Objects.equals(saved.get("earnCobbleDollarsFromNPC"), values.get("earnCobbleDollarsFromNPC"))
                    || !Objects.equals(saved.get("cobbleDollarsIncomeMultiplier"), values.get("cobbleDollarsIncomeMultiplier")))
                throw new IllegalStateException("CobbleDollars não confirmou a configuração aplicada.");
            Path configFile = FabricLoader.getInstance().getConfigDir().resolve("cobbledollars/common.json");
            if (!Files.isRegularFile(configFile)) throw new IllegalStateException("O arquivo oficial common.json do CobbleDollars não foi encontrado após salvar.");
            JsonObject disk = JsonParser.parseString(Files.readString(configFile)).getAsJsonObject();
            if (!disk.has("earnCobbleDollarsFromNPC") || !disk.has("cobbleDollarsIncomeMultiplier")
                    || disk.get("earnCobbleDollarsFromNPC").getAsBoolean() != (boolean) values.get("earnCobbleDollarsFromNPC")
                    || Double.compare(disk.get("cobbleDollarsIncomeMultiplier").getAsDouble(), (double) values.get("cobbleDollarsIncomeMultiplier")) != 0)
                throw new IllegalStateException("O CobbleDollars não persistiu a nova configuração.");
        } catch (ReflectiveOperationException e) {
            throw integrationFailure("CobbleDollars", e);
        } catch (java.io.IOException | RuntimeException e) {
            throw integrationFailure("CobbleDollars", e);
        }
    }

    private static Object rctServerConfig() {
        try {
            Class<?> api = Class.forName(RCT_API);
            Object instance = api.getMethod("getInstance").invoke(null);
            Object config = api.getMethod("getServerConfig").invoke(instance);
            if (config == null) throw new IllegalStateException("A configuração do RCT ainda não está disponível.");
            return config;
        } catch (ReflectiveOperationException e) {
            throw integrationFailure("RCT", e);
        }
    }

    private static Object dollarsConfig() {
        try {
            Class<?> api = Class.forName(DOLLARS_API);
            Object instance = api.getField("INSTANCE").get(null);
            Object config = api.getMethod("getConfig").invoke(instance);
            if (config == null) throw new IllegalStateException("A configuração do CobbleDollars ainda não está disponível.");
            return config;
        } catch (ReflectiveOperationException e) {
            throw integrationFailure("CobbleDollars", e);
        }
    }

    private static Object pathValue(Object config, List<String> path) {
        try { return config.getClass().getMethod("get", List.class).invoke(config, path); }
        catch (ReflectiveOperationException e) { throw integrationFailure("RCT", e); }
    }

    private static Object invoke(Object receiver, String method) {
        return invoke(receiver, method, new Class<?>[0], new Object[0]);
    }

    private static Object invoke(Object receiver, String method, Class<?>[] signature, Object... args) {
        try { return receiver.getClass().getMethod(method, signature).invoke(receiver, args); }
        catch (ReflectiveOperationException e) { throw integrationFailure("Mod integrado", e); }
    }

    private static Object invoke(Object receiver, String method, Object... args) {
        Class<?>[] signature = new Class<?>[args.length];
        for (int i = 0; i < args.length; i++) signature[i] = args[i] instanceof Boolean ? boolean.class
                : args[i] instanceof Double ? double.class : Object.class;
        return invoke(receiver, method, signature, args);
    }

    private static double number(Object receiver, String getter) {
        Object value = invoke(receiver, getter);
        if (!(value instanceof Number number) || !Double.isFinite(number.doubleValue())) throw integrationFailure("Mod integrado", null);
        return number.doubleValue();
    }

    private static int integer(Object receiver, String getter) {
        Object value = invoke(receiver, getter);
        if (!(value instanceof Number number)) throw integrationFailure("Mod integrado", null);
        return number.intValue();
    }

    private static boolean bool(Object receiver, String getter) {
        Object value = invoke(receiver, getter);
        if (!(value instanceof Boolean result)) throw integrationFailure("Mod integrado", null);
        return result;
    }

    private static void putDouble(JsonObject patch, Map<String, Object> current, String key, double min, double max) {
        JsonElement element = patch.get(key);
        if (element == null) return;
        if (!element.isJsonPrimitive() || !element.getAsJsonPrimitive().isNumber()) throw invalid(key);
        double value;
        try { value = element.getAsDouble(); } catch (RuntimeException e) { throw invalid(key); }
        if (!Double.isFinite(value) || value < min || value > max) throw invalid(key);
        current.put(key, value);
    }

    private static void putInt(JsonObject patch, Map<String, Object> current, String key, int min, int max) {
        JsonElement element = patch.get(key);
        if (element == null) return;
        if (!element.isJsonPrimitive() || !element.getAsJsonPrimitive().isNumber()) throw invalid(key);
        double parsed;
        try { parsed = element.getAsDouble(); } catch (RuntimeException e) { throw invalid(key); }
        if (!Double.isFinite(parsed) || parsed != Math.rint(parsed) || parsed < min || parsed > max) throw invalid(key);
        current.put(key, (int) parsed);
    }

    private static void putBool(JsonObject patch, Map<String, Object> current, String key) {
        JsonElement element = patch.get(key);
        if (element == null) return;
        if (!element.isJsonPrimitive() || !element.getAsJsonPrimitive().isBoolean()) throw invalid(key);
        current.put(key, element.getAsBoolean());
    }

    private static JsonObject object(JsonObject root, String key) {
        JsonElement element = root.get(key);
        if (element == null || element.isJsonNull()) return null;
        if (!element.isJsonObject()) throw new WebPanelService.Problem(400, "Seção de configuração inválida: " + key + ".");
        return element.getAsJsonObject();
    }

    private static void rejectUnknown(JsonObject object, List<String> allowed) {
        for (String key : object.keySet()) {
            if (!allowed.contains(key)) throw new WebPanelService.Problem(400, "Configuração desconhecida: " + key + ".");
        }
    }

    private static WebPanelService.Problem invalid(String key) {
        return new WebPanelService.Problem(400, "Valor inválido para a configuração: " + key + ".");
    }

    private static boolean classPresent(String className) {
        try { Class.forName(className, false, TrainerOperationsSettings.class.getClassLoader()); return true; }
        catch (ClassNotFoundException missing) { return false; }
    }

    private static RuntimeException integrationFailure(String integration, Throwable failure) {
        Throwable cause = failure;
        if (failure instanceof InvocationTargetException invocation && invocation.getCause() != null) cause = invocation.getCause();
        if (cause instanceof WebPanelService.Problem problem) return problem;
        String detail = cause == null ? "API incompatível ou não carregada." : cause.getClass().getSimpleName();
        return new WebPanelService.Problem(503, integration + " não disponibiliza a API esperada (" + detail + ").");
    }
}
