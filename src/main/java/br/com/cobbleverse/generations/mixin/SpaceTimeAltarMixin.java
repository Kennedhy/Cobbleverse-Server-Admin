package br.com.cobbleverse.generations.mixin;

import br.com.cobbleverse.generations.integration.*;
import com.lumyverse.lumymon.block.entity.custom.SpaceTimeAltarEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.registry.Registries;
import net.minecraft.util.Hand;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Pseudo
@Mixin(value=SpaceTimeAltarEntity.class,remap=false)
public abstract class SpaceTimeAltarMixin {
    @Inject(method="tryStartSummoning",at=@At("HEAD"),cancellable=true)
    private void generationControl$summon(PlayerEntity player,Hand hand,CallbackInfo ci) {
        var altar=(SpaceTimeAltarEntity)(Object)this;
        if (altar.getWorld()==null || altar.getWorld().isClient()) return;
        String orb=Registries.ITEM.getId(altar.getStack(0).getItem()).getPath();
        if (!InvocationGuard.speciesAllowed(orb.contains("adamant") ? "dialga" : "palkia")) {
            player.sendMessage(CobblemonIntegration.blockMessage(),true);
            ci.cancel();
        }
    }
}
