package br.com.cobbleverse.generations.mixin;

import br.com.cobbleverse.generations.GenerationControl;
import br.com.cobbleverse.generations.integration.CobblemonIntegration;
import com.cobblemon.mod.common.api.pokemon.PokemonProperties;
import com.cobblemon.mod.common.pokemon.Pokemon;
import com.github.yajatkaul.mega_showdown.utils.AspectUtils;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import java.util.*;

@Pseudo
@Mixin(value=AspectUtils.class,remap=false)
public abstract class AspectTransformationMixin {
    @Inject(method="applyAspects",at=@At("HEAD"),cancellable=true)
    private static void generationControl$aspects(Pokemon pokemon,List<String> aspects,CallbackInfo ci) {
        if (!GenerationControl.ready()) return;
        if (!aspects.isEmpty() && aspects.stream().allMatch(AspectTransformationMixin::generationControl$neutral)) return;
        if (!CobblemonIntegration.allowedResult(PokemonProperties.Companion.parse(String.join(" ",aspects)),pokemon)) ci.cancel();
    }
    @Inject(method="applyProperties",at=@At("HEAD"),cancellable=true)
    private static void generationControl$properties(Pokemon pokemon,Optional<String> properties,CallbackInfo ci) {
        if (properties.isPresent() && Arrays.stream(properties.get().strip().split("\\s+")).allMatch(AspectTransformationMixin::generationControl$neutral)) return;
        if (GenerationControl.ready() && properties.isPresent() && !CobblemonIntegration.allowedResult(PokemonProperties.Companion.parse(properties.get()),pokemon)) ci.cancel();
    }
    @Unique private static boolean generationControl$neutral(String raw) {
        String value=raw.toLowerCase(Locale.ROOT);
        return value.equals("mega_evolution=none") || value.equals("mega_evolution=false");
    }
}
