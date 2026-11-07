package br.com.cobbleverse.generations.mixin;

import com.cobblemon.mod.common.api.storage.PokemonStore;
import java.util.Map;
import java.util.UUID;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(targets="com.cobblemon.mod.common.api.storage.factory.FileBackedPokemonStoreFactory$StoreCache",remap=false)
public interface StoreCacheAccessor {
    @Accessor("cacheMap") Map<UUID,PokemonStore<?>> generationControl$cacheMap();
}
