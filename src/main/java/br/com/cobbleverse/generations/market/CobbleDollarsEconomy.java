package br.com.cobbleverse.generations.market;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import br.com.cobbleverse.generations.mixin.PlayerDataSaverAccessor;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.File;
import java.io.IOException;
import java.io.Reader;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Optional bridge to the CobbleDollars API shipped by the server's economy mod. */
public final class CobbleDollarsEconomy {
    private static final String API_CLASS = "fr.harmex.cobbledollars.common.utils.extensions.PlayerExtensionKt";
    private static volatile Api api;

    private final MinecraftServer server;

    public CobbleDollarsEconomy(MinecraftServer server) {
        this.server = Objects.requireNonNull(server);
    }

    public boolean isAvailable() {
        return resolveApi() != null;
    }

    /** Returns the balance only while the player is online, when the live value is authoritative. */
    public Optional<BigInteger> readBalance(UUID playerId) {
        requireServerThread();
        ServerPlayerEntity player = server.getPlayerManager().getPlayer(playerId);
        if (player == null) return Optional.empty();
        Api resolved = resolveApi();
        if (resolved == null) return Optional.empty();
        return Optional.of(readPlayerBalance(resolved, player));
    }

    /** Debits an online player if the current CobbleDollars balance covers the amount. */
    public boolean tryDebitOnline(UUID playerId, BigInteger amount) {
        requireServerThread();
        requirePositive(amount);
        ServerPlayerEntity player = server.getPlayerManager().getPlayer(playerId);
        Api resolved = resolveApi();
        if (player == null || resolved == null) return false;

        BigInteger before = readPlayerBalance(resolved, player);
        if (before.compareTo(amount) < 0) return false;
        BigInteger after = before.subtract(amount);
        try {
            if (writePlayerBalance(resolved, player, after)) return true;
        } catch (RuntimeException writeFailure) {
            try {
                if (writePlayerBalance(resolved, player, before)) return false;
            } catch (RuntimeException rollbackFailure) {
                rollbackFailure.addSuppressed(writeFailure);
                throw new IllegalStateException("O débito não foi confirmado e o estorno também não pôde ser confirmado; mantenha a transação pendente para reconciliação.", rollbackFailure);
            }
            throw new IllegalStateException("O débito não foi confirmado nem pôde ser estornado com segurança.", writeFailure);
        }

        if (writePlayerBalance(resolved, player, before)) return false;
        throw new IllegalStateException("O débito não foi confirmado e o estorno também não pôde ser confirmado; mantenha a transação pendente para reconciliação.");
    }

    /** Credits an online account immediately or queues the amount for an offline account's next login. */
    public boolean credit(UUID playerId, BigInteger amount) {
        requireServerThread();
        Objects.requireNonNull(playerId);
        requirePositive(amount);
        Api resolved = resolveApi();
        if (resolved == null) return false;

        ServerPlayerEntity player = server.getPlayerManager().getPlayer(playerId);
        if (player != null) {
            BigInteger before = readPlayerBalance(resolved, player);
            BigInteger after = before.add(amount);
            try {
                if (writePlayerBalance(resolved, player, after)) return true;
            } catch (RuntimeException writeFailure) {
                try {
                    if (writePlayerBalance(resolved, player, before)) return false;
                } catch (RuntimeException rollbackFailure) {
                    rollbackFailure.addSuppressed(writeFailure);
                    throw new IllegalStateException("O crédito não foi confirmado e o estorno também não pôde ser confirmado; mantenha a retirada pendente para reconciliação.", rollbackFailure);
                }
                throw new IllegalStateException("O crédito não foi confirmado nem pôde ser estornado com segurança.", writeFailure);
            }
            if (writePlayerBalance(resolved, player, before)) return false;
            throw new IllegalStateException("O crédito não foi confirmado e o estorno também não pôde ser confirmado; mantenha a retirada pendente para reconciliação.");
        }

        BigInteger before = readPendingOfflineCredit(playerId).orElse(BigInteger.ZERO);
        if (!invokeOfflineCredit(resolved, playerId, amount)) return false;
        BigInteger after = readPendingOfflineCredit(playerId).orElse(BigInteger.ZERO);
        return after.equals(before.add(amount));
    }

    /** Reads CobbleDollars' queued offline earnings field for transaction reconciliation. */
    public Optional<BigInteger> readPendingOfflineCredit(UUID playerId) {
        requireServerThread();
        Api resolved = resolveApi();
        if (resolved == null) return Optional.empty();
        try {
            Object value = resolved.getOffline.invoke(null, playerId, server);
            return Optional.ofNullable((BigInteger) value).or(() -> Optional.of(BigInteger.ZERO));
        } catch (ReflectiveOperationException | ClassCastException e) {
            throw integrationFailure("Não foi possível consultar os créditos offline do CobbleDollars.", e);
        }
    }

    /** Confirms an online balance against both persisted CobbleDollars copies. */
    public boolean isPersistedBalance(UUID playerId, BigInteger expected) {
        requireServerThread();
        return readConsistentPersistedBalance(playerId).filter(expected::equals).isPresent();
    }

    /** Reads the two persisted balance copies without loading a player; safe for the bounded market I/O worker. */
    public Optional<BigInteger> readConsistentPersistedBalance(UUID playerId) {
        Api resolved = resolveApi();
        if (resolved == null) return Optional.empty();
        try {
            Optional<BigInteger> account = readPersistedBalance(resolved, playerId, server);
            Optional<BigInteger> playerData = MarketPlayerData.cobbleDollars(server, playerId);
            return account.isPresent() && account.equals(playerData) ? account : Optional.empty();
        } catch (IOException failure) {
            throw integrationFailure("Não foi possível confirmar o saldo no playerdata do CobbleDollars.", failure);
        }
    }

    private void requireServerThread() {
        if (!server.isOnThread()) throw new IllegalStateException("As operações CobbleDollars do mercado exigem a thread do servidor.");
    }

    private static BigInteger readPlayerBalance(Api resolved, ServerPlayerEntity player) {
        try {
            return (BigInteger) resolved.getOnline.invoke(null, player);
        } catch (ReflectiveOperationException | ClassCastException e) {
            throw integrationFailure("Não foi possível consultar o saldo CobbleDollars do jogador.", e);
        }
    }

    private static boolean writePlayerBalance(Api resolved, ServerPlayerEntity player, BigInteger balance) {
        try {
            resolved.setOnline.invoke(null, player, balance);
            resolved.updateOnline.invoke(null, player);
        } catch (ReflectiveOperationException e) {
            throw integrationFailure("Não foi possível alterar o saldo CobbleDollars do jogador.", e);
        }
        if (!readPlayerBalance(resolved, player).equals(balance)) return false;
        ((PlayerDataSaverAccessor) (Object) player.getServer().getPlayerManager())
                .generationControl$savePlayerData(player);
        try {
            return readPlayerBalance(resolved, player).equals(balance)
                    && readPersistedBalance(resolved, player.getUuid(), player.getServer()).filter(balance::equals).isPresent()
                    && MarketPlayerData.cobbleDollars(player.getServer(), player.getUuid()).filter(balance::equals).isPresent();
        } catch (IOException failure) {
            throw integrationFailure("Não foi possível confirmar o saldo no playerdata do CobbleDollars.", failure);
        }
    }

    private static Optional<BigInteger> readPersistedBalance(Api resolved, UUID playerId, MinecraftServer server) {
        try {
            File file = (File) resolved.getAccountFile.invoke(null, playerId, server);
            if (file == null || !file.isFile()) return Optional.empty();
            try (Reader reader = Files.newBufferedReader(file.toPath(), StandardCharsets.UTF_8)) {
                JsonObject account = JsonParser.parseReader(reader).getAsJsonObject();
                JsonElement value = account.get("cobbledollars");
                if (value == null || value.isJsonNull()) return Optional.empty();
                return Optional.of(new BigInteger(value.getAsString()));
            }
        } catch (ReflectiveOperationException | IOException | RuntimeException failure) {
            throw integrationFailure("Não foi possível confirmar o saldo no arquivo do CobbleDollars.", failure);
        }
    }

    private boolean invokeOfflineCredit(Api resolved, UUID playerId, BigInteger amount) {
        try {
            return Boolean.TRUE.equals(resolved.addOffline.invoke(null, playerId, server, amount));
        } catch (ReflectiveOperationException e) {
            throw integrationFailure("Não foi possível registrar o crédito offline no CobbleDollars.", e);
        }
    }

    private static Api resolveApi() {
        if (!FabricLoader.getInstance().isModLoaded("cobbledollars")) return null;
        Api resolved = api;
        if (resolved != null) return resolved;
        synchronized (CobbleDollarsEconomy.class) {
            resolved = api;
            if (resolved != null) return resolved;
            try {
                Class<?> extension = Class.forName(API_CLASS, true, CobbleDollarsEconomy.class.getClassLoader());
                Method getOnline = find(extension, "getCobbleDollars", 1);
                Method setOnline = find(extension, "setCobbleDollars", 2);
                Method updateOnline = find(extension, "updateCobbleDollarsAccount", 1);
                Method getAccountFile = extension.getMethod("getAccountFile", UUID.class, MinecraftServer.class);
                Method getOffline = extension.getMethod("getOfflineCobbleDollars", UUID.class, MinecraftServer.class);
                Method addOffline = extension.getMethod("addOfflineCobbleDollars", UUID.class, MinecraftServer.class, BigInteger.class);
                api = resolved = new Api(getOnline, setOnline, updateOnline, getAccountFile, getOffline, addOffline);
                return resolved;
            } catch (ReflectiveOperationException e) {
                throw integrationFailure("A versão instalada do CobbleDollars não expõe a API de saldo esperada.", e);
            }
        }
    }

    private static Method find(Class<?> owner, String name, int parameterCount) throws NoSuchMethodException {
        for (Method method : owner.getMethods()) {
            if (!method.getName().equals(name) || !Modifier.isStatic(method.getModifiers())
                    || method.getParameterCount() != parameterCount) continue;
            Class<?>[] parameters = method.getParameterTypes();
            if ((name.equals("getCobbleDollars") || name.equals("updateCobbleDollarsAccount"))
                    && parameters[0].isAssignableFrom(ServerPlayerEntity.class)) return method;
            if (name.equals("setCobbleDollars") && parameters[0].isAssignableFrom(ServerPlayerEntity.class)
                    && parameters[1] == BigInteger.class) return method;
        }
        throw new NoSuchMethodException(name);
    }

    private static void requirePositive(BigInteger amount) {
        Objects.requireNonNull(amount);
        if (amount.signum() <= 0) throw new IllegalArgumentException("O valor precisa ser maior que zero.");
    }

    private static IllegalStateException integrationFailure(String message, Exception cause) {
        Throwable root = cause instanceof java.lang.reflect.InvocationTargetException invocation && invocation.getCause() != null
                ? invocation.getCause() : cause;
        return new IllegalStateException(message, root);
    }

    private record Api(Method getOnline, Method setOnline, Method updateOnline, Method getAccountFile,
                       Method getOffline, Method addOffline) {}
}
