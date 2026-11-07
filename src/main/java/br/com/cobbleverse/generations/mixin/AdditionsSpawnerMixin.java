package br.com.cobbleverse.generations.mixin;

import br.com.cobbleverse.generations.integration.TrialGuard;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;

/** Both BCA spawners create uncatchable challenge opponents. */
@Pseudo
@Mixin(targets={"io.strikeknight57.bca.block.entity.PokemonSpawnerBlockEntity",
    "io.strikeknight57.bca.block.entity.PokemonTrialSpawnerBlockEntity"},remap=false)
public abstract class AdditionsSpawnerMixin {
    @ModifyArg(method="checkTicks",at=@At(value="INVOKE",target="Lcom/cobblemon/mod/common/api/pokemon/PokemonProperties$Companion;parse(Ljava/lang/String;)Lcom/cobblemon/mod/common/api/pokemon/PokemonProperties;"),index=0)
    private static String generationControl$challenge(String original) { return TrialGuard.legalEncounter(original); }
}
