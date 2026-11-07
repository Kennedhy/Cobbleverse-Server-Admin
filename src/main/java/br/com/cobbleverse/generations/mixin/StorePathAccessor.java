package br.com.cobbleverse.generations.mixin;

import com.cobblemon.mod.common.api.storage.adapter.flatfile.OneToOneFileStoreAdapter;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(value=OneToOneFileStoreAdapter.class,remap=false)
public interface StorePathAccessor {
    @Accessor("rootFolder") String generationControl$root();
    @Accessor("useNestedFolders") boolean generationControl$nested();
    @Accessor("folderPerClass") boolean generationControl$perClass();
    @Accessor("fileExtension") String generationControl$extension();
}
