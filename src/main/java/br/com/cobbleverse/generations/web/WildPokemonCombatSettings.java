package br.com.cobbleverse.generations.web;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** On-demand editor for the existing Fight or Flight common server config. */
public final class WildPokemonCombatSettings {
    private static final String MOD_ID = "fightorflight";
    private static final long MAX_CONFIG_BYTES = 1L * 1024 * 1024;
    private static final String NUMBER = "-?(?:\\d+(?:\\.\\d*)?|\\.\\d+)(?:[eE][+-]?\\d+)?";
    private static volatile boolean restartRequired;

    private WildPokemonCombatSettings() { }

    private record Values(boolean attackWhenProvoked, boolean attackUnprovoked, boolean unprovokedOnlyInDark,
                          boolean wildPokemonRangedAttack, int minimumAttackLevel, int minimumUnprovokedLevel,
                          double aggressiveThreshold, double neutralThreshold, double aggressionLevelMultiplier,
                          double minimumAttackDamage, double maximumAttackDamage, double minimumRangedAttackDamage,
                          double maximumRangedAttackDamage) {
        Map<String, Object> toMap() {
            LinkedHashMap<String, Object> values = new LinkedHashMap<>();
            values.put("attackWhenProvoked", attackWhenProvoked);
            values.put("attackUnprovoked", attackUnprovoked);
            values.put("unprovokedOnlyInDark", unprovokedOnlyInDark);
            values.put("wildPokemonRangedAttack", wildPokemonRangedAttack);
            values.put("minimumAttackLevel", minimumAttackLevel);
            values.put("minimumUnprovokedLevel", minimumUnprovokedLevel);
            values.put("aggressiveThreshold", aggressiveThreshold);
            values.put("neutralThreshold", neutralThreshold);
            values.put("aggressionLevelMultiplier", aggressionLevelMultiplier);
            values.put("minimumAttackDamage", minimumAttackDamage);
            values.put("maximumAttackDamage", maximumAttackDamage);
            values.put("minimumRangedAttackDamage", minimumRangedAttackDamage);
            values.put("maximumRangedAttackDamage", maximumRangedAttackDamage);
            return values;
        }
    }

    public static synchronized Map<String, Object> read() throws IOException {
        if (!FabricLoader.getInstance().isModLoaded(MOD_ID))
            return unavailable("Fight or Flight não está carregado neste servidor.");
        Path file = configFile();
        if (!Files.isRegularFile(file))
            return unavailable("O servidor ainda não gerou config/fightorflight.json5. Inicie o modpack completo e tente novamente.");
        String source = readSource(file);
        Values values = parseSource(source);
        return response(values, fingerprint(source), restartRequired);
    }

    public static synchronized Map<String, Object> update(JsonObject payload) throws IOException {
        if (!FabricLoader.getInstance().isModLoaded(MOD_ID))
            throw new IllegalStateException("Fight or Flight não está carregado neste servidor.");
        if (payload == null || !payload.has("settings") || !payload.get("settings").isJsonObject())
            throw new IllegalArgumentException("As configurações de combate estão ausentes.");
        String expectedRevision = requiredString(payload, "revision");
        Path file = configFile();
        if (!Files.isRegularFile(file)) throw new IOException("A configuração de Fight or Flight não foi encontrada.");
        String source = readSource(file);
        String currentRevision = fingerprint(source);
        if (!MessageDigest.isEqual(expectedRevision.getBytes(StandardCharsets.US_ASCII),
                currentRevision.getBytes(StandardCharsets.US_ASCII)))
            throw new StaleConfigurationException();

        Values previous = parseSource(source);
        Values next = parsePayload(payload.getAsJsonObject("settings"));
        if (!previous.equals(next)) {
            String updated = source;
            updated = replaceIfChanged(updated, "do_pokemon_attack", Boolean.toString(previous.attackWhenProvoked), Boolean.toString(next.attackWhenProvoked), "true|false");
            updated = replaceIfChanged(updated, "do_pokemon_attack_unprovoked", Boolean.toString(previous.attackUnprovoked), Boolean.toString(next.attackUnprovoked), "true|false");
            updated = replaceIfChanged(updated, "light_dependent_unprovoked_attack", Boolean.toString(previous.unprovokedOnlyInDark), Boolean.toString(next.unprovokedOnlyInDark), "true|false");
            updated = replaceIfChanged(updated, "wild_pokemon_ranged_attack", Boolean.toString(previous.wildPokemonRangedAttack), Boolean.toString(next.wildPokemonRangedAttack), "true|false");
            updated = replaceIfChanged(updated, "minimum_attack_level", Integer.toString(previous.minimumAttackLevel), Integer.toString(next.minimumAttackLevel), "-?\\d+");
            updated = replaceIfChanged(updated, "minimum_attack_unprovoked_level", Integer.toString(previous.minimumUnprovokedLevel), Integer.toString(next.minimumUnprovokedLevel), "-?\\d+");
            updated = replaceIfChanged(updated, "aggressive_threshold", number(previous.aggressiveThreshold), number(next.aggressiveThreshold), NUMBER);
            updated = replaceIfChanged(updated, "neutral_threshold", number(previous.neutralThreshold), number(next.neutralThreshold), NUMBER);
            updated = replaceIfChanged(updated, "aggression_level_multiplier", number(previous.aggressionLevelMultiplier), number(next.aggressionLevelMultiplier), NUMBER);
            updated = replaceIfChanged(updated, "minimum_attack_damage", number(previous.minimumAttackDamage), number(next.minimumAttackDamage), NUMBER);
            updated = replaceIfChanged(updated, "maximum_attack_damage", number(previous.maximumAttackDamage), number(next.maximumAttackDamage), NUMBER);
            updated = replaceIfChanged(updated, "minimum_ranged_attack_damage", number(previous.minimumRangedAttackDamage), number(next.minimumRangedAttackDamage), NUMBER);
            updated = replaceIfChanged(updated, "maximum_ranged_attack_damage", number(previous.maximumRangedAttackDamage), number(next.maximumRangedAttackDamage), NUMBER);
            writeAtomically(file, updated);
            restartRequired = true;
            currentRevision = fingerprint(updated);
        }
        return response(next, currentRevision, restartRequired);
    }

    private static Path configFile() {
        return FabricLoader.getInstance().getConfigDir().resolve("fightorflight.json5");
    }

    private static Map<String, Object> unavailable(String message) {
        LinkedHashMap<String, Object> response = new LinkedHashMap<>();
        response.put("available", false);
        response.put("message", message);
        response.put("settings", null);
        response.put("revision", "");
        response.put("restartRequired", false);
        return response;
    }

    private static Map<String, Object> response(Values values, String revision, boolean needsRestart) {
        LinkedHashMap<String, Object> response = new LinkedHashMap<>();
        response.put("available", true);
        response.put("message", "Configuração carregada do Fight or Flight.");
        response.put("settings", values.toMap());
        response.put("revision", revision);
        response.put("restartRequired", needsRestart);
        return response;
    }

    private static String readSource(Path file) throws IOException {
        long size = Files.size(file);
        if (size <= 0 || size > MAX_CONFIG_BYTES)
            throw new IOException("O arquivo fightorflight.json5 está vazio ou excede 1 MiB.");
        return Files.readString(file, StandardCharsets.UTF_8);
    }

    private static Values parseSource(String source) throws IOException {
        try {
            return new Values(
                    readBoolean(source, "do_pokemon_attack"),
                    readBoolean(source, "do_pokemon_attack_unprovoked"),
                    readBoolean(source, "light_dependent_unprovoked_attack"),
                    readBoolean(source, "wild_pokemon_ranged_attack"),
                    readInteger(source, "minimum_attack_level"),
                    readInteger(source, "minimum_attack_unprovoked_level"),
                    readNumber(source, "aggressive_threshold"),
                    readNumber(source, "neutral_threshold"),
                    readNumber(source, "aggression_level_multiplier"),
                    readNumber(source, "minimum_attack_damage"),
                    readNumber(source, "maximum_attack_damage"),
                    readNumber(source, "minimum_ranged_attack_damage"),
                    readNumber(source, "maximum_ranged_attack_damage"));
        } catch (IllegalArgumentException invalid) {
            throw new IOException("O fightorflight.json5 não contém os campos esperados por esta versão do mod.", invalid);
        }
    }

    private static Values parsePayload(JsonObject settings) {
        Values values = new Values(
                requiredBoolean(settings, "attackWhenProvoked"),
                requiredBoolean(settings, "attackUnprovoked"),
                requiredBoolean(settings, "unprovokedOnlyInDark"),
                requiredBoolean(settings, "wildPokemonRangedAttack"),
                requiredInteger(settings, "minimumAttackLevel", 1, 1000),
                requiredInteger(settings, "minimumUnprovokedLevel", 1, 1000),
                requiredNumber(settings, "aggressiveThreshold", 0, 1000),
                requiredNumber(settings, "neutralThreshold", 0, 1000),
                requiredNumber(settings, "aggressionLevelMultiplier", 0, 10),
                requiredNumber(settings, "minimumAttackDamage", 0, 100),
                requiredNumber(settings, "maximumAttackDamage", 0, 100),
                requiredNumber(settings, "minimumRangedAttackDamage", 0, 100),
                requiredNumber(settings, "maximumRangedAttackDamage", 0, 100));
        if (values.aggressiveThreshold <= values.neutralThreshold)
            throw new IllegalArgumentException("O limiar de agressividade precisa ser maior que o limiar neutro.");
        if (values.minimumAttackDamage > values.maximumAttackDamage)
            throw new IllegalArgumentException("O dano mínimo precisa ser menor ou igual ao dano máximo.");
        if (values.minimumRangedAttackDamage > values.maximumRangedAttackDamage)
            throw new IllegalArgumentException("O dano mínimo à distância precisa ser menor ou igual ao dano máximo.");
        return values;
    }

    private static boolean readBoolean(String source, String key) {
        String value = readValue(source, key, "true|false");
        return Boolean.parseBoolean(value);
    }

    private static int readInteger(String source, String key) {
        String value = readValue(source, key, "-?\\d+");
        return Integer.parseInt(value);
    }

    private static double readNumber(String source, String key) {
        double value = Double.parseDouble(readValue(source, key, NUMBER));
        if (!Double.isFinite(value)) throw new IllegalArgumentException("Número inválido em " + key + ".");
        return value;
    }

    private static String readValue(String source, String key, String tokenPattern) {
        Matcher matcher = valuePattern(key, tokenPattern).matcher(source);
        if (!matcher.find()) throw new IllegalArgumentException("Campo ausente: " + key);
        String value = matcher.group(2);
        if (matcher.find()) throw new IllegalArgumentException("Campo duplicado: " + key);
        return value;
    }

    private static String replace(String source, String key, String value, String tokenPattern) throws IOException {
        Pattern pattern = valuePattern(key, tokenPattern);
        Matcher matcher = pattern.matcher(source);
        if (!matcher.find()) throw new IOException("Campo ausente na configuração: " + key);
        String replaced = matcher.group(1) + value + matcher.group(3) + matcher.group(4);
        if (matcher.find()) throw new IOException("Campo duplicado na configuração: " + key);
        return pattern.matcher(source).replaceFirst(Matcher.quoteReplacement(replaced));
    }

    private static String replaceIfChanged(String source, String key, String previousValue,
                                           String nextValue, String tokenPattern) throws IOException {
        return previousValue.equals(nextValue) ? source : replace(source, key, nextValue, tokenPattern);
    }

    private static Pattern valuePattern(String key, String tokenPattern) {
        return Pattern.compile("(?m)^([\\t ]*\"" + Pattern.quote(key)
                + "\"[\\t ]*:[\\t ]*)(" + tokenPattern + ")([\\t ]*,?[\\t ]*(?://[^\\r\\n]*)?)(\\r?)$");
    }

    private static boolean requiredBoolean(JsonObject object, String key) {
        JsonPrimitive primitive = primitive(object, key);
        if (!primitive.isBoolean()) throw new IllegalArgumentException("O campo " + key + " precisa ser verdadeiro ou falso.");
        return primitive.getAsBoolean();
    }

    private static int requiredInteger(JsonObject object, String key, int min, int max) {
        double value = requiredNumber(object, key, min, max);
        if (value != Math.rint(value)) throw new IllegalArgumentException("O campo " + key + " precisa ser um número inteiro.");
        return (int) value;
    }

    private static double requiredNumber(JsonObject object, String key, double min, double max) {
        JsonPrimitive primitive = primitive(object, key);
        if (!primitive.isNumber()) throw new IllegalArgumentException("O campo " + key + " precisa ser numérico.");
        double value;
        try { value = primitive.getAsDouble(); }
        catch (NumberFormatException invalid) { throw new IllegalArgumentException("O campo " + key + " é inválido."); }
        if (!Double.isFinite(value) || value < min || value > max)
            throw new IllegalArgumentException("O campo " + key + " precisa ficar entre " + min + " e " + max + ".");
        return value;
    }

    private static JsonPrimitive primitive(JsonObject object, String key) {
        JsonElement element = object.get(key);
        if (element == null || !element.isJsonPrimitive()) throw new IllegalArgumentException("O campo " + key + " está ausente.");
        return element.getAsJsonPrimitive();
    }

    private static String requiredString(JsonObject object, String key) {
        JsonElement element = object.get(key);
        if (element == null || !element.isJsonPrimitive() || !element.getAsJsonPrimitive().isString())
            throw new IllegalArgumentException("A revisão da configuração está ausente.");
        return element.getAsString();
    }

    private static String number(double value) {
        return java.math.BigDecimal.valueOf(value).stripTrailingZeros().toPlainString();
    }

    private static String fingerprint(String source) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(source.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 não está disponível.", impossible);
        }
    }

    private static void writeAtomically(Path file, String source) throws IOException {
        Path temporary = file.resolveSibling(file.getFileName() + ".generation-control.tmp");
        byte[] bytes = source.getBytes(StandardCharsets.UTF_8);
        try {
            try (FileChannel channel = FileChannel.open(temporary, StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)) {
                ByteBuffer buffer = ByteBuffer.wrap(bytes);
                while (buffer.hasRemaining()) channel.write(buffer);
                channel.force(true);
            }
            try { Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
            catch (java.nio.file.AtomicMoveNotSupportedException unsupported) {
                Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    public static final class StaleConfigurationException extends IllegalStateException {
        public StaleConfigurationException() {
            super("A configuração do Fight or Flight mudou fora do painel. Atualize a página antes de salvar.");
        }
    }
}
