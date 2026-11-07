package br.com.cobbleverse.generations.mixin;

import br.com.cobbleverse.generations.GenerationControl;
import br.com.cobbleverse.generations.integration.CobblemonIntegration;
import com.cobblemon.mod.common.pokemon.FormData;
import com.cobblemon.mod.common.pokemon.Pokemon;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import java.util.*;
import kotlin.Pair;

@Pseudo
@Mixin(targets = "ludichat.cobbreeding.BreedingUtilities", remap = false)
public abstract class BreedingMixin {
    @Inject(method = "getPossibleEggs", at = @At("RETURN"), cancellable = true)
    private static void generationControl$eggs(CallbackInfoReturnable<Collection<Map.Entry<FormData, List<Pair<Pokemon,Pokemon>>>>> cir) {
        if (!GenerationControl.ready()) return;
        List<Map.Entry<FormData,List<Pair<Pokemon,Pokemon>>>> result = new ArrayList<>();
        for (var entry : cir.getReturnValue()) {
            FormData form = entry.getKey();
            var species = form.getSpecies();
            if (!GenerationControl.rules().allowed(species.getResourceIdentifier().toString(), species.getNationalPokedexNumber(), form.getName(), Set.of())) continue;
            var parents = entry.getValue().stream().filter(pair -> !GenerationControl.rules().restrictExisting()
                || (CobblemonIntegration.allowed(pair.getFirst()) && CobblemonIntegration.allowed(pair.getSecond()))).toList();
            if (!parents.isEmpty()) result.add(Map.entry(form,parents));
        }
        cir.setReturnValue(result);
    }
}
