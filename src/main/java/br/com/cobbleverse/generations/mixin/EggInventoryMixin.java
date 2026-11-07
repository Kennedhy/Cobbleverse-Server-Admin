package br.com.cobbleverse.generations.mixin;

import br.com.cobbleverse.generations.integration.BreedingGuard;
import net.minecraft.item.ItemStack;
import net.minecraft.entity.Entity;
import net.minecraft.world.World;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Pseudo
@Mixin(targets = "ludichat.cobbreeding.PokemonEgg", remap = false)
public abstract class EggInventoryMixin {
    // Vanilla inventoryTick's intermediary name, verified in the supported production JAR.
    @Inject(method = "method_7888", at = @At("HEAD"), cancellable = true)
    private void generationControl$preserveEgg(ItemStack stack, World world, Entity entity, int slot, boolean selected, CallbackInfo ci) {
        if (!world.isClient() && !BreedingGuard.eggAllowed(stack)) ci.cancel();
    }
}
