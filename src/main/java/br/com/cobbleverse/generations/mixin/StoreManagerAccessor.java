package br.com.cobbleverse.generations.mixin;

import com.cobblemon.mod.common.api.PrioritizedList;
import com.cobblemon.mod.common.api.storage.PokemonStoreManager;
import com.cobblemon.mod.common.api.storage.factory.PokemonStoreFactory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(value=PokemonStoreManager.class,remap=false)
public interface StoreManagerAccessor {
    @Accessor("factories") PrioritizedList<PokemonStoreFactory> generationControl$factories();
}
