package br.com.cobbleverse.generations.mixin;

import br.com.cobbleverse.generations.integration.*;
import com.necro.raid.dens.common.blocks.block.RaidCrystalBlock;
import com.necro.raid.dens.common.blocks.entity.RaidCrystalBlockEntity;
import net.minecraft.block.BlockState;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Pseudo
@Mixin(value=RaidCrystalBlock.class,remap=false)
public abstract class RaidEntryMixin {
    @Inject(method="startOrJoinRaid",at=@At("HEAD"),cancellable=true)
    private void generationControl$enter(PlayerEntity player,BlockState state,RaidCrystalBlockEntity den,ItemStack key,CallbackInfoReturnable<Boolean> cir) {
        if (!RaidGuard.allowed(den.getRaidBoss())) {
            player.sendMessage(CobblemonIntegration.blockMessage(),true);
            cir.setReturnValue(false);
        }
    }
}
