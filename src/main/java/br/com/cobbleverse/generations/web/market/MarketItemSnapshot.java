package br.com.cobbleverse.generations.web.market;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import net.minecraft.nbt.NbtElement;
import net.minecraft.item.ItemStack;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.NbtComponent;
import net.minecraft.registry.DynamicRegistryManager;
import net.minecraft.registry.Registries;
import net.minecraft.registry.RegistryOps;

/** Registry-aware snapshot of an item escrowed by a marketplace listing. */
public record MarketItemSnapshot(String itemId, String displayName, int count, String itemJson) {
    private static final int MAX_SERIALIZED_CHARS = 16_384;
    public static final String CLAIM_MARKER_KEY = "cobbleverse:market_claim";
    private static final String LEGACY_CLAIM_MARKER_KEY = "cobbleverse_market_claim";

    public static boolean hasClaimMarker(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return false;
        NbtComponent customData = stack.get(DataComponentTypes.CUSTOM_DATA);
        return customData != null && (customData.getNbt().contains(CLAIM_MARKER_KEY)
                || customData.getNbt().contains(LEGACY_CLAIM_MARKER_KEY));
    }

    public static boolean hasClaimMarker(ItemStack stack, String transactionId) {
        if (stack == null || stack.isEmpty() || transactionId == null) return false;
        NbtComponent customData = stack.get(DataComponentTypes.CUSTOM_DATA);
        if (customData == null) return false;
        String marker = customData.getNbt().getString(CLAIM_MARKER_KEY);
        if (marker.isBlank()) marker = customData.getNbt().getString(LEGACY_CLAIM_MARKER_KEY);
        return transactionId.equals(marker);
    }

    public static MarketItemSnapshot capture(ItemStack stack, DynamicRegistryManager registries) {
        if (stack == null || stack.isEmpty()) throw new IllegalArgumentException("Selecione um item válido.");
        if (stack.getCount() > stack.getMaxCount()) throw new IllegalArgumentException("A quantidade do item excede o limite permitido.");
        if (containsStoredItems(stack))
            throw new IllegalArgumentException("Recipientes com itens guardados não podem ser anunciados.");

        JsonElement encoded = ItemStack.CODEC.encodeStart(RegistryOps.of(JsonOps.INSTANCE, registries), stack.copy())
                .getOrThrow(error -> new IllegalArgumentException("O item não pôde ser serializado com segurança: " + error));
        String serialized = encoded.toString();
        if (serialized.length() > MAX_SERIALIZED_CHARS) throw new IllegalArgumentException("Os dados deste item são grandes demais para um anúncio.");
        return new MarketItemSnapshot(Registries.ITEM.getId(stack.getItem()).toString(),
                stack.getName().getString(), stack.getCount(), serialized);
    }

    private static boolean containsStoredItems(ItemStack stack) {
        var container = stack.get(DataComponentTypes.CONTAINER);
        if (container != null && container.streamNonEmpty().findAny().isPresent()) return true;
        var bundle = stack.get(DataComponentTypes.BUNDLE_CONTENTS);
        if (bundle != null && !bundle.isEmpty()) return true;
        NbtComponent blockEntityData = stack.get(DataComponentTypes.BLOCK_ENTITY_DATA);
        return blockEntityData != null
                && blockEntityData.getNbt().contains("Items", NbtElement.LIST_TYPE)
                && !blockEntityData.getNbt().getList("Items", NbtElement.COMPOUND_TYPE).isEmpty();
    }

    public ItemStack restore(DynamicRegistryManager registries) {
        if (itemJson == null || itemJson.length() > MAX_SERIALIZED_CHARS)
            throw new IllegalStateException("Os dados salvos deste item são inválidos.");
        JsonElement json;
        try { json = JsonParser.parseString(itemJson); }
        catch (RuntimeException invalid) { throw new IllegalStateException("Os dados salvos deste item são inválidos.", invalid); }

        ItemStack stack = ItemStack.CODEC.parse(RegistryOps.of(JsonOps.INSTANCE, registries), json)
                .getOrThrow(error -> new IllegalStateException("O item do anúncio não pôde ser restaurado: " + error));
        if (stack.isEmpty() || stack.getCount() != count || stack.getCount() > stack.getMaxCount()
                || !Registries.ITEM.getId(stack.getItem()).toString().equals(itemId))
            throw new IllegalStateException("O item restaurado não corresponde ao anúncio.");
        return stack;
    }
}
