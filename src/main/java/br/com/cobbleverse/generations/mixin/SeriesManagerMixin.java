package br.com.cobbleverse.generations.mixin;

import br.com.cobbleverse.generations.GenerationControl;
import com.gitlab.srcmc.rctmod.api.service.SeriesManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import java.util.*;

@Mixin(value = SeriesManager.class, remap = false)
public abstract class SeriesManagerMixin {
    @Inject(method = "getSeriesIds", at = @At("RETURN"), cancellable = true)
    private void generationControl$visible(CallbackInfoReturnable<Set<String>> cir) {
        if (!GenerationControl.ready() || !GenerationControl.rules().filterGyms()) return;
        Set<String> allowed = new LinkedHashSet<>(cir.getReturnValue());
        allowed.removeIf(id -> !id.equals("freeroam") && !GenerationControl.rules().regionAllowed(id));
        cir.setReturnValue(allowed);
    }
}
