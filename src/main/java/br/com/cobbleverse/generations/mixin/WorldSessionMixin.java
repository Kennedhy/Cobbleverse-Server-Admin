package br.com.cobbleverse.generations.mixin;

import br.com.cobbleverse.generations.GenerationControl;
import net.minecraft.world.level.storage.LevelStorage;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(LevelStorage.Session.class)
public abstract class WorldSessionMixin {
    @Inject(method = "<init>", at = @At("TAIL"))
    private void generationControl$open(CallbackInfo ci) {
        GenerationControl.openWorld(((LevelStorage.Session)(Object)this).getDirectory().path());
    }
}
