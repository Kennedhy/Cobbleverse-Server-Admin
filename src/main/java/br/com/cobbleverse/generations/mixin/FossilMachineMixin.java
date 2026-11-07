package br.com.cobbleverse.generations.mixin;

import br.com.cobbleverse.generations.GenerationControl;
import br.com.cobbleverse.generations.integration.CobblemonIntegration;
import com.cobblemon.mod.common.api.fossil.*;
import com.cobblemon.mod.common.block.multiblock.FossilMultiblockStructure;
import net.minecraft.item.ItemStack;
import net.minecraft.world.World;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.*;
import java.util.*;

/** Prevents ingredient consumption and pauses old machines before the completion tick. */
@Mixin(value=FossilMultiblockStructure.class,remap=false)
public abstract class FossilMachineMixin {
    @Unique private boolean generationControl$blocked() {
        var fossil=((FossilMultiblockStructure)(Object)this).getResultingFossil();
        return GenerationControl.ready() && fossil!=null && !CobblemonIntegration.allowed(fossil.getResult());
    }

    @Inject(method="insertFossil",at=@At("HEAD"),cancellable=true)
    private void generationControl$ingredient(ItemStack stack,World world,CallbackInfoReturnable<Boolean> cir) {
        if (world.isClient() || !GenerationControl.ready()) return;
        var ingredients=new ArrayList<>(((FossilMultiblockStructure)(Object)this).getFossilInventory());
        ingredients.add(stack);
        var completed=Fossils.getFossilByItemStacks(ingredients);
        if (completed!=null && !CobblemonIntegration.allowed(completed.getResult())) { cir.setReturnValue(false); return; }
        boolean possible=Fossils.all().stream().anyMatch(f -> f.matchesIngredientsSubSet(ingredients) && CobblemonIntegration.allowed(f.getResult()));
        if (!possible) cir.setReturnValue(false);
    }

    @Inject(method="insertOrganicMaterial",at=@At("HEAD"),cancellable=true)
    private void generationControl$organic(ItemStack stack,World world,CallbackInfoReturnable<Boolean> cir) {
        if (!world.isClient() && generationControl$blocked()) cir.setReturnValue(false);
    }

    @Inject(method={"startMachine","tick"},at=@At("HEAD"),cancellable=true)
    private void generationControl$pause(World world,CallbackInfo ci) {
        if (!world.isClient() && generationControl$blocked()) ci.cancel();
    }

    @Inject(method="useWithoutItem",at=@At("HEAD"),cancellable=true)
    private void generationControl$withdraw(net.minecraft.block.BlockState state,World world,net.minecraft.util.math.BlockPos pos,
            net.minecraft.entity.player.PlayerEntity player,net.minecraft.util.hit.BlockHitResult hit,CallbackInfoReturnable<net.minecraft.util.ActionResult> cir) {
        if (!world.isClient() && generationControl$blocked() && ((FossilMultiblockStructure)(Object)this).getHasCreatedPokemon()) {
            player.sendMessage(CobblemonIntegration.blockMessage(),false);
            cir.setReturnValue(net.minecraft.util.ActionResult.FAIL);
        }
    }
}
