package br.com.cobbleverse.generations.mixin;

import br.com.cobbleverse.generations.GenerationControl;
import net.minecraft.resource.ResourcePackManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import java.util.*;

@Mixin(ResourcePackManager.class)
public abstract class ResourcePackManagerMixin {
    @ModifyVariable(method = "setEnabledProfiles", at = @At("HEAD"), argsOnly = true)
    private Collection<String> generationControl$regions(Collection<String> original) {
        if (!GenerationControl.ready()) return original;
        Set<String> result = new LinkedHashSet<>(original);
        var config = GenerationControl.rules().config();
        for (String id : ((ResourcePackManager)(Object)this).getIds()) {
            String lower = id.toLowerCase(Locale.ROOT);
            if (!lower.contains("cobbleverse") && !lower.contains("terralith-dp")) continue;
            if (config.regions.stream().anyMatch(region -> lower.contains(region)) || ((config.regions.contains("sinnoh") || config.regions.contains("hoenn")) && lower.contains("terralith-dp"))) result.add(id);
        }
        return result;
    }
}
