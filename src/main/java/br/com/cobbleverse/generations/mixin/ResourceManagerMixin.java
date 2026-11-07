package br.com.cobbleverse.generations.mixin;

import br.com.cobbleverse.generations.data.PolicyResources;
import net.minecraft.resource.*;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import java.util.List;

@Mixin(LifecycledResourceManagerImpl.class)
public abstract class ResourceManagerMixin {
    @ModifyVariable(method = "<init>", at = @At("HEAD"), argsOnly = true)
    private static List<ResourcePack> generationControl$resources(List<ResourcePack> packs, ResourceType type, List<ResourcePack> original) {
        return PolicyResources.augment(type, packs);
    }
}
