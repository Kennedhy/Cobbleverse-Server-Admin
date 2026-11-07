package br.com.cobbleverse.generations.mixin;

import br.com.cobbleverse.generations.integration.RctIntegration;
import com.gitlab.srcmc.rctmod.world.entities.TrainerMob;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(value = TrainerMob.class, remap = false)
public abstract class TrainerMobMixin {
    @Inject(method = {"canBattleAgainst", "couldBattleAgainst"}, at = @At("HEAD"), cancellable = true)
    private void generationControl$guard(CallbackInfoReturnable<Boolean> cir) {
        if (!RctIntegration.trainerAllowed(((TrainerMob)(Object)this).getTrainerId())) cir.setReturnValue(false);
    }
}
