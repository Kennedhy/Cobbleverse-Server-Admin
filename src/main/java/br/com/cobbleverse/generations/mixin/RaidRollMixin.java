package br.com.cobbleverse.generations.mixin;

import br.com.cobbleverse.generations.GenerationControl;
import br.com.cobbleverse.generations.integration.RaidGuard;
import com.necro.raid.dens.common.registry.RaidRegistry;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.random.Random;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Pseudo
@Mixin(value=RaidRegistry.class,remap=false)
public abstract class RaidRollMixin {
    @Inject(method="roll",at=@At("HEAD"),cancellable=true)
    private static void generationControl$roll(Random random,float[] weights,int[] indexes,CallbackInfoReturnable<Identifier> cir) {
        if (GenerationControl.ready()) cir.setReturnValue(RaidGuard.roll(random,weights,indexes));
    }
}
