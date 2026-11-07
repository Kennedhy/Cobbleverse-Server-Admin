package br.com.cobbleverse.generations.mixin;

import br.com.cobbleverse.generations.GenerationControl;
import br.com.cobbleverse.generations.integration.RaidGuard;
import com.necro.raid.dens.common.blocks.entity.RaidCrystalBlockEntity;
import com.necro.raid.dens.common.registry.RaidRegistry;
import net.minecraft.block.BlockState;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.*;
import java.util.*;

@Pseudo
@Mixin(value=RaidCrystalBlockEntity.class,remap=false)
public abstract class RaidCrystalMixin {
    @Shadow private Set<String> aspects;
    @Unique private long generationControl$retryAfter;

    @ModifyVariable(method="setRaidBoss",at=@At("HEAD"),argsOnly=true)
    private Identifier generationControl$assign(Identifier requested) {
        if (GenerationControl.ready() && requested != null && !RaidGuard.allowed(RaidRegistry.getRaidBoss(requested))) return null;
        return requested;
    }

    @Inject(method="tick",at=@At("HEAD"))
    private void generationControl$persisted(World world,BlockPos pos,BlockState state,CallbackInfo ci) {
        if (world.isClient() || !GenerationControl.ready()) return;
        var crystal = (RaidCrystalBlockEntity)(Object)this;
        if (crystal.getRaidBossLocation()!=null && !crystal.isInProgress() && !RaidGuard.allowed(crystal.getRaidBoss(),aspects)) crystal.setRaidBoss(null,world.getTime());
    }

    @Inject(method="spawnRaidBoss",at=@At("HEAD"),cancellable=true)
    private void generationControl$spawn(UUID player,CallbackInfoReturnable<Boolean> cir) {
        if (!RaidGuard.allowed(((RaidCrystalBlockEntity)(Object)this).getRaidBoss(),aspects)) cir.setReturnValue(false);
    }

    @Inject(method="generateRaidBoss",at=@At("TAIL"))
    private void generationControl$retry(World world,BlockPos pos,BlockState state,CallbackInfo ci) {
        generationControl$retryAfter = ((RaidCrystalBlockEntity)(Object)this).getRaidBoss()==null ? world.getTime()+600 : 0;
    }

    @Inject(method="canGenerateBoss",at=@At("HEAD"),cancellable=true)
    private void generationControl$cooldown(BlockState state,CallbackInfoReturnable<Boolean> cir) {
        var world = ((RaidCrystalBlockEntity)(Object)this).getWorld();
        if (world!=null && world.getTime()<generationControl$retryAfter) cir.setReturnValue(false);
    }
}
