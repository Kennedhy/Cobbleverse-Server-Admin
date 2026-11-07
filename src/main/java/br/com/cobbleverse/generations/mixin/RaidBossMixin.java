package br.com.cobbleverse.generations.mixin;

import br.com.cobbleverse.generations.integration.RaidGuard;
import com.necro.raid.dens.common.data.raid.RaidBoss;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Pseudo
@Mixin(value=RaidBoss.class,remap=false)
public abstract class RaidBossMixin {
    @Inject(method="getWeight",at=@At("HEAD"),cancellable=true)
    private void generationControl$weight(CallbackInfoReturnable<Double> cir) {
        if (!RaidGuard.allowed((RaidBoss)(Object)this)) cir.setReturnValue(0.0);
    }
}
