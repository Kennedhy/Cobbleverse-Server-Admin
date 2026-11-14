package br.com.cobbleverse.generations.mixin;

import br.com.cobbleverse.generations.integration.CaptureManager;
import com.cobblemon.mod.common.entity.pokeball.EmptyPokeBallEntity;
import com.cobblemon.mod.common.entity.pokemon.PokemonEntity;
import kotlin.Unit;
import net.minecraft.server.network.ServerPlayerEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(value = EmptyPokeBallEntity.class, remap = false)
public abstract class CaptureDeliveryMixin {
    @Shadow
    private void breakFree() { throw new AssertionError(); }

    // Cobblemon schedules delivery after the shake animation. Check before it discards
    // the wild entity and completes the capture future.
    @Inject(method = "shakeBall$lambda$0", at = @At(value = "INVOKE",
            target = "Lcom/cobblemon/mod/common/entity/pokemon/PokemonEntity;discard()V"),
            cancellable = true)
    private static void generationControl$checkWorldLimit(PokemonEntity target, EmptyPokeBallEntity ball,
            ServerPlayerEntity player, CallbackInfoReturnable<Unit> callback) {
        if (CaptureManager.blockedByLegendaryLimit(player, target)) {
            ((CaptureDeliveryMixin) (Object) ball).breakFree();
            callback.setReturnValue(Unit.INSTANCE);
        }
    }
}
