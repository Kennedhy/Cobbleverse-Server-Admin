package br.com.cobbleverse.generations.mixin;

import br.com.cobbleverse.generations.integration.CaptureManager;
import com.cobblemon.mod.common.entity.pokeball.EmptyPokeBallEntity;
import com.cobblemon.mod.common.entity.pokemon.PokemonEntity;
import com.cobblemon.mod.common.pokeball.catching.calculators.CobblemonCaptureCalculator;
import com.cobblemon.mod.common.api.pokeball.catching.CaptureContext;
import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.sugar.Local;
import net.minecraft.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(value=CobblemonCaptureCalculator.class,remap=false)
public abstract class CaptureCalculatorMixin {
    @Inject(method="processCapture",at=@At("HEAD"),cancellable=true,remap=false)
    private void generationControl$notifyBlockedCapture(LivingEntity thrower,EmptyPokeBallEntity ball,PokemonEntity target,
            CallbackInfoReturnable<CaptureContext> cir) {
        CaptureManager.notifyBlockedCapture(thrower,target);
        if (CaptureManager.blockedByLegendaryLimit(thrower,target))
            cir.setReturnValue(new CaptureContext(0,false,false));
    }

    @ModifyExpressionValue(method="processCapture",at=@At(value="INVOKE",target="Lkotlin/math/MathKt;roundToInt(F)I"),remap=false)
    private int generationControl$captureChance(int original,LivingEntity thrower,EmptyPokeBallEntity ball,PokemonEntity target,
            @Local(index=13) float modifiedCatchRate,@Local(index=14) boolean critical) {
        return CaptureManager.threshold(original,thrower,target,modifiedCatchRate,critical);
    }
}
