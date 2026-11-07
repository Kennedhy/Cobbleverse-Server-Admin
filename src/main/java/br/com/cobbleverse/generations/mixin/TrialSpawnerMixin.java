package br.com.cobbleverse.generations.mixin;

import br.com.cobbleverse.generations.integration.TrialGuard;
import github.jorgaomc.blocks.entity.PokemonTrialSpawnerBlockEntity;
import net.minecraft.server.world.ServerWorld;
import java.util.List;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;

@Pseudo
@Mixin(value=PokemonTrialSpawnerBlockEntity.class,remap=false)
public abstract class TrialSpawnerMixin {
    @ModifyArg(target=@Desc(value="spawnPokemon",args={ServerWorld.class,String.class,List.class,int.class}),at=@At(value="INVOKE",target="Lcom/cobblemon/mod/common/api/pokemon/PokemonProperties$Companion;parse(Ljava/lang/String;)Lcom/cobblemon/mod/common/api/pokemon/PokemonProperties;"),index=0)
    private String generationControl$trial(String original) { return TrialGuard.legalEncounter(original); }
}
