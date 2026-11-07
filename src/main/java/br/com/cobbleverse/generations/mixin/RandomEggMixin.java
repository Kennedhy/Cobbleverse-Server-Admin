package br.com.cobbleverse.generations.mixin;

import br.com.cobbleverse.generations.GenerationControl;
import br.com.cobbleverse.generations.integration.BreedingGuard;
import com.cobblemon.mod.common.pokemon.FormData;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Pseudo
@Mixin(targets = "ludichat.cobbreeding.EggUtilities", remap = false)
public abstract class RandomEggMixin {
    @Inject(method = "randomEgg", at = @At("HEAD"), cancellable = true)
    private static void generationControl$random(CallbackInfoReturnable<FormData> cir) {
        if (GenerationControl.ready()) cir.setReturnValue(BreedingGuard.randomBaby());
    }
}
