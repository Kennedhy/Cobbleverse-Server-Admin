package br.com.cobbleverse.generations.mixin;

import br.com.cobbleverse.generations.integration.InvocationGuard;
import net.minecraft.block.AbstractBlock;
import net.minecraft.entity.Entity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(AbstractBlock.AbstractBlockState.class)
public abstract class BlockCollisionMixin {
    @Inject(method = "onEntityCollision", at = @At("HEAD"), cancellable = true)
    private void generationControl$collision(World world, BlockPos pos, Entity entity, CallbackInfo ci) {
        if (!InvocationGuard.allowCollision(((AbstractBlock.AbstractBlockState)(Object)this).getBlock(),entity)) ci.cancel();
    }
}
