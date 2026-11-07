package br.com.cobbleverse.generations.mixin;

import com.gitlab.srcmc.rctmod.api.data.save.TrainerPlayerData;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(value=TrainerPlayerData.class,remap=false)
public interface TrainerProgressAccessor {
    @Accessor("currentSeriesCompleted") void generationControl$completed(boolean completed);
}
