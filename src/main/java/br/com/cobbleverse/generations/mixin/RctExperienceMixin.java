package br.com.cobbleverse.generations.mixin;

import br.com.cobbleverse.generations.integration.ExperienceManager;
import com.cobblemon.mod.common.api.events.pokemon.ExperienceGainedEvent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Replace RCT's XP clamp only while the administrator enables this manager. */
@Mixin(targets="com.gitlab.srcmc.rctmod.server.ModServer", remap=false)
public abstract class RctExperienceMixin {
    @Inject(method="onExperienceGained", at=@At("HEAD"), cancellable=true)
    private static void generationControl$experience(ExperienceGainedEvent.Pre event, CallbackInfo ci) {
        if (ExperienceManager.managesExperience()) ci.cancel();
    }
}
