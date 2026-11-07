package br.com.cobbleverse.generations.mixin;

import br.com.cobbleverse.generations.GenerationControl;
import com.github.yajatkaul.mega_showdown.gimmick.MegaGimmick;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.*;

@Pseudo
@Mixin(value=MegaGimmick.class,remap=false)
public abstract class MegaGimmickMixin {
    @Inject(method="canMega",at=@At("HEAD"),cancellable=true)
    private static void generationControl$canMega(com.cobblemon.mod.common.pokemon.Pokemon pokemon,CallbackInfoReturnable<Boolean> cir) {
        if (!br.com.cobbleverse.generations.integration.MegaPolicy.allowed(pokemon)) cir.setReturnValue(false);
    }
    @Inject(method="megaEvolve",at=@At("HEAD"),cancellable=true)
    private static void generationControl$transform(com.cobblemon.mod.common.pokemon.Pokemon pokemon,CallbackInfo ci) {
        if (!br.com.cobbleverse.generations.integration.MegaPolicy.allowed(pokemon)) ci.cancel();
    }
    @Inject(method="megaEvolveInBattle",at=@At("HEAD"),cancellable=true)
    private static void generationControl$battle(com.cobblemon.mod.common.pokemon.Pokemon pokemon,com.cobblemon.mod.common.battles.pokemon.BattlePokemon battle,CallbackInfo ci) {
        if (!br.com.cobbleverse.generations.integration.MegaPolicy.allowed(pokemon)) ci.cancel();
    }
}
