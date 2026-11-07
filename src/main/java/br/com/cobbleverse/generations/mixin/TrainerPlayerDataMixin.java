package br.com.cobbleverse.generations.mixin;

import br.com.cobbleverse.generations.integration.RctIntegration;
import com.gitlab.srcmc.rctmod.api.data.save.TrainerPlayerData;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = TrainerPlayerData.class, remap = false)
public abstract class TrainerPlayerDataMixin {
    @Inject(method = "setCurrentSeries(Ljava/lang/String;Z)V", at = @At("HEAD"), cancellable = true)
    private void generationControl$series(String requested,boolean keepProgress,CallbackInfo ci) {
        // Builder.of must deserialize against the ORIGINAL graph before join migration.
        // Redirecting the first setter loses saved defeats that do not belong to the target graph.
        if (requested==null) { ci.cancel(); return; }
        if (((TrainerPlayerData)(Object)this).getCurrentSeries().isEmpty()) return;
        // Reject the entire operation. Redirecting its string would still execute keepProgress=false
        // and erase the current circuit's victories when an excluded circuit is requested.
        if (!requested.equals(RctIntegration.permittedSeries(requested))) ci.cancel();
    }
}
