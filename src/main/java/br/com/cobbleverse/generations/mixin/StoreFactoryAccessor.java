package br.com.cobbleverse.generations.mixin;

import com.cobblemon.mod.common.api.storage.factory.FileBackedPokemonStoreFactory;
import com.cobblemon.mod.common.api.storage.adapter.flatfile.FileStoreAdapter;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import java.util.concurrent.ExecutorService;
import java.util.Map;

@Mixin(value=FileBackedPokemonStoreFactory.class,remap=false)
public interface StoreFactoryAccessor {
    @Accessor("adapter") FileStoreAdapter<?> generationControl$adapter();
    @Accessor("saveExecutor") ExecutorService generationControl$executor();
    @Accessor("storeCaches") Map<Class<?>,Object> generationControl$caches();
}
