package br.com.cobbleverse.generations.mixin;

import br.com.cobbleverse.generations.integration.ExperienceManager;
import com.gitlab.srcmc.rctmod.api.utils.LevelUtils;
import net.minecraft.entity.player.PlayerEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Desc;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Keep the RCT trainer checks and its displayed cap aligned with global mode. */
@Mixin(value=LevelUtils.class, remap=false)
public abstract class RctLevelCapMixin {
    @Inject(target=@Desc(value="levelCap",args={PlayerEntity.class},ret=int.class), at=@At("HEAD"), cancellable=true, remap=false)
    private static void generationControl$globalCap(PlayerEntity player, CallbackInfoReturnable<Integer> cir) {
        Integer limit = ExperienceManager.globalCapOverride(player);
        if (limit != null) cir.setReturnValue(limit);
    }
}
