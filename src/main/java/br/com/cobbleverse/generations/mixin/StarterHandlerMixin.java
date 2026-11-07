package br.com.cobbleverse.generations.mixin;

import br.com.cobbleverse.generations.GenerationControl;
import br.com.cobbleverse.generations.integration.CobblemonIntegration;
import com.cobblemon.mod.common.starter.CobblemonStarterHandler;
import com.cobblemon.mod.common.config.starter.StarterCategory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import java.util.*;

@Mixin(value = CobblemonStarterHandler.class, remap = false)
public abstract class StarterHandlerMixin {
    @Inject(method = "getStarterList", at = @At("RETURN"), cancellable = true)
    private void generationControl$starters(CallbackInfoReturnable<List<StarterCategory>> cir) {
        if (!GenerationControl.ready()) return;
        List<StarterCategory> filtered = new ArrayList<>();
        for (StarterCategory category : cir.getReturnValue()) {
            var pokemon = category.getPokemon().stream().filter(CobblemonIntegration::allowed).toList();
            if (!pokemon.isEmpty()) filtered.add(new StarterCategory(category.getName(), category.getDisplayName(), pokemon));
        }
        cir.setReturnValue(filtered);
    }
}
