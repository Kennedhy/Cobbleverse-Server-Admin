package br.com.cobbleverse.generations.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Disposable fixture only: never listed in the shipped mixin configuration. */
@Mixin(targets="com.gitlab.srcmc.rctmod.api.service.TrainerManager",remap=false)
public abstract class VerificationSkipTrainerLoadMixin {
    @Inject(method="loadTrainers",at=@At("HEAD"),cancellable=true,remap=false)
    private void generationControl$skipFixtureTrainerLoad(CallbackInfo ci) { ci.cancel(); }
}
