package br.com.cobbleverse.generations.integration;

import br.com.cobbleverse.generations.GenerationControl;
import com.cobblemon.mod.common.api.pokemon.PokemonProperties;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.event.player.UseItemCallback;
import net.minecraft.block.Block;
import net.minecraft.entity.Entity;
import net.minecraft.entity.ItemEntity;
import net.minecraft.registry.Registries;
import net.minecraft.util.ActionResult;
import net.minecraft.util.TypedActionResult;
import java.lang.reflect.Method;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/** Prevents known invocations before their activation item can be consumed. */
public final class InvocationGuard {
    private static final Map<Class<?>, Optional<Method>> ALTAR_METHODS = new ConcurrentHashMap<>();
    private static final Map<String,String> SPECIAL_BLOCKS=Map.ofEntries(
        Map.entry("com.lumyverse.lumymon.block.custom.RegigigasStatue","regigigas"),
        Map.entry("github.jorgaomc.blocks.EternatusCocoonBlock","eternatus"),
        Map.entry("github.jorgaomc.blocks.shrine.FirescourgeShrine","chiyu"),
        Map.entry("github.jorgaomc.blocks.shrine.GrasswitherShrine","wochien"),
        Map.entry("github.jorgaomc.blocks.shrine.GroundblightShrine","tinglu"),
        Map.entry("github.jorgaomc.blocks.shrine.IcerendShrine","chienpao")
    );
    private static final Map<String,String> FOSSILS = Map.ofEntries(
        Map.entry("helix_fossil","omanyte"), Map.entry("dome_fossil","kabuto"), Map.entry("old_amber","aerodactyl"),
        Map.entry("root_fossil","lileep"), Map.entry("claw_fossil","anorith"), Map.entry("skull_fossil","cranidos"),
        Map.entry("armor_fossil","shieldon"), Map.entry("cover_fossil","tirtouga"), Map.entry("plume_fossil","archen"),
        Map.entry("jaw_fossil","tyrunt"), Map.entry("sail_fossil","amaura")
    );

    private InvocationGuard() {}

    public static void register() {
        UseBlockCallback.EVENT.register((player,world,hand,hit) -> {
            if (world.isClient() || !GenerationControl.ready()) return ActionResult.PASS;
            Block block = world.getBlockState(hit.getBlockPos()).getBlock();
            String target = altarSpecies(block);
            var itemId = Registries.ITEM.getId(player.getStackInHand(hand).getItem());
            var blockId = Registries.BLOCK.getId(block);
            if (target == null && itemId.getNamespace().equals("cobblemon") && blockId.getNamespace().equals("cobblemon")
                    && (blockId.getPath().contains("fossil") || blockId.getPath().contains("restoration"))) target = FOSSILS.get(itemId.getPath());
            if (target != null && !speciesAllowed(target)) {
                player.sendMessage(CobblemonIntegration.blockMessage(), true);
                return ActionResult.FAIL;
            }
            return ActionResult.PASS;
        });
        UseItemCallback.EVENT.register((player,world,hand) -> {
            if (world.isClient() || !GenerationControl.ready()) return TypedActionResult.pass(player.getStackInHand(hand));
            String className = player.getStackInHand(hand).getItem().getClass().getName();
            String target = className.equals("com.lumyverse.lumymon.item.custom.AzureFlute") ? "arceus" : null;
            if (target != null && !speciesAllowed(target)) {
                player.sendMessage(CobblemonIntegration.blockMessage(), true);
                return TypedActionResult.fail(player.getStackInHand(hand));
            }
            return TypedActionResult.pass(player.getStackInHand(hand));
        });
    }

    public static boolean allowCollision(Block block, Entity entity) {
        if (!GenerationControl.ready() || entity.getWorld().isClient() || !(entity instanceof ItemEntity item)
                || !block.getClass().getName().equals("com.lumyverse.lumymon.block.custom.SummonTrigger")) return true;
        String itemId = Registries.ITEM.getId(item.getStack().getItem()).toString();
        return switch (itemId) {
            case "lumymon:iceroot_carrot" -> speciesAllowed("glastrier");
            case "lumymon:shaderoot_carrot" -> speciesAllowed("spectrier");
            default -> true;
        };
    }

    public static boolean speciesAllowed(String species) {
        PokemonProperties properties = new PokemonProperties();
        properties.setSpecies(species);
        return CobblemonIntegration.allowed(properties);
    }

    private static String altarSpecies(Block block) {
        Class<?> type = block.getClass();
        if (SPECIAL_BLOCKS.containsKey(type.getName())) return SPECIAL_BLOCKS.get(type.getName());
        if (!type.getName().startsWith("com.lumyverse.lumymon.block.custom.")) return null;
        Optional<Method> method = ALTAR_METHODS.computeIfAbsent(type, key -> {
            try { return Optional.of(key.getMethod("getPokemonName")); }
            catch (NoSuchMethodException e) { return Optional.empty(); }
        });
        if (method.isEmpty()) return null;
        try { return (String)method.get().invoke(block); }
        catch (ReflectiveOperationException e) { throw new IllegalStateException("Não foi possível validar o altar " + type.getSimpleName(), e); }
    }
}
