package br.com.cobbleverse.generations.mixin;

import br.com.cobbleverse.generations.integration.InvocationGuard;
import com.lumyverse.lumymon.block.entity.custom.ArceusPedestalEntity;
import net.minecraft.util.math.BlockPos;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import java.util.List;

@Pseudo
@Mixin(value=ArceusPedestalEntity.class,remap=false)
public abstract class ArceusRitualMixin {
    @Shadow private boolean isRitualLeader;
    @Shadow private int spawnTimer;
    @Shadow private List<BlockPos> connectedPedestals;
    @Inject(method={"tryStartRitual","finishRitual"},at=@At("HEAD"),cancellable=true)
    private void generationControl$ritual(CallbackInfo ci) {
        if (InvocationGuard.speciesAllowed("arceus")) return;
        var pedestal=(ArceusPedestalEntity)(Object)this;
        if (pedestal.getWorld()!=null && !pedestal.getWorld().isClient()) {
            for (BlockPos pos:connectedPedestals) if (pedestal.getWorld().getBlockEntity(pos) instanceof ArceusPedestalEntity other) other.setLocked(false);
            isRitualLeader=false;
            spawnTimer=-1;
            pedestal.setLocked(false);
            pedestal.markDirty();
        }
        ci.cancel();
    }
}
