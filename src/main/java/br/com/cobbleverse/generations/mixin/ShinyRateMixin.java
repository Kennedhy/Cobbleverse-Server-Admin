package br.com.cobbleverse.generations.mixin;

import br.com.cobbleverse.generations.GenerationControl;
import com.cobblemon.mod.common.config.CobblemonConfig;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(value = CobblemonConfig.class, remap = false)
public abstract class ShinyRateMixin {
    @Inject(method = "getShinyRate", at = @At("HEAD"), cancellable = true)
    private void generationControl$shinyRate(CallbackInfoReturnable<Float> result) {
        var settings = GenerationControl.gameplaySettings();
        if (settings != null && settings.shinyRate() != null) {
            result.setReturnValue(settings.shinyRate().floatValue());
        }
    }
}
