package br.com.cobbleverse.generations.market;

import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.NbtComponent;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtElement;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtList;
import net.minecraft.nbt.NbtSizeTracker;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.WorldSavePath;

import java.io.IOException;
import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.UUID;

/** Bounded readback helpers for the vanilla playerdata file used by CobbleDollars and inventory saves. */
public final class MarketPlayerData {
    private static final long MAX_FILE_BYTES = 32L * 1024 * 1024;
    private static final long MAX_NBT_BYTES = 32L * 1024 * 1024;

    private MarketPlayerData() {}

    public static NbtCompound read(MinecraftServer server, UUID playerId) throws IOException {
        Path directory = server.getSavePath(WorldSavePath.PLAYERDATA).toAbsolutePath().normalize();
        Path file = directory.resolve(playerId + ".dat").normalize();
        if (!file.startsWith(directory)) throw new IOException("O caminho de playerdata não é válido.");
        if (!Files.isRegularFile(file)) throw new IOException("O arquivo playerdata ainda não está disponível.");
        long size = Files.size(file);
        if (size <= 0 || size > MAX_FILE_BYTES)
            throw new IOException("O arquivo playerdata excede o limite de leitura do mercado.");
        try {
            return NbtIo.readCompressed(file, NbtSizeTracker.of(MAX_NBT_BYTES));
        } catch (RuntimeException invalid) {
            throw new IOException("O arquivo playerdata não pôde ser verificado.", invalid);
        }
    }

    public static Optional<BigInteger> cobbleDollars(MinecraftServer server, UUID playerId) throws IOException {
        NbtCompound data = read(server, playerId);
        if (!data.contains("CobbleDollars", NbtElement.STRING_TYPE)) return Optional.empty();
        try {
            return Optional.of(new BigInteger(data.getString("CobbleDollars")));
        } catch (NumberFormatException invalid) {
            throw new IOException("O saldo CobbleDollars no playerdata é inválido.", invalid);
        }
    }

    public static boolean inventoryContainsMarker(ServerPlayerEntity player, String transactionId) throws IOException {
        NbtList inventory = read(player.getServer(), player.getUuid()).getList("Inventory", NbtElement.COMPOUND_TYPE);
        for (int index = 0; index < inventory.size(); index++) {
            NbtCompound serializedStack = inventory.getCompound(index);
            Optional<ItemStack> decoded;
            try {
                decoded = ItemStack.fromNbt(player.getRegistryManager(), serializedStack);
            } catch (RuntimeException invalid) {
                throw new IOException("O inventário salvo não pôde ser verificado.", invalid);
            }
            if (decoded.isPresent() && hasMarker(decoded.get(), transactionId)) return true;
        }
        return false;
    }

    private static boolean hasMarker(ItemStack stack, String transactionId) {
        NbtComponent customData = stack.get(DataComponentTypes.CUSTOM_DATA);
        return customData != null && (transactionId.equals(customData.getNbt().getString("cobbleverse:market_claim"))
                || transactionId.equals(customData.getNbt().getString("cobbleverse_market_claim")));
    }
}
