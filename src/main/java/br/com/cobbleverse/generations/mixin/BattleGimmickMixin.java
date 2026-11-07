package br.com.cobbleverse.generations.mixin;

import br.com.cobbleverse.generations.GenerationControl;
import com.cobblemon.mod.common.battles.MoveActionResponse;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(value=MoveActionResponse.class,remap=false,priority=900)
public abstract class BattleGimmickMixin {
    @Inject(method="isValid",at=@At("HEAD"))
    private void generationControl$battleChoice(com.cobblemon.mod.common.battles.ActiveBattlePokemon active,com.cobblemon.mod.common.battles.ShowdownMoveset moves,boolean forceSwitch,CallbackInfoReturnable<Boolean> ci) {
        generationControl$sanitize(active);
    }
    @Inject(method="toShowdownString",at=@At("HEAD"))
    private void generationControl$serialize(com.cobblemon.mod.common.battles.ActiveBattlePokemon active,com.cobblemon.mod.common.battles.ShowdownMoveset moves,CallbackInfoReturnable<String> ci) {
        generationControl$sanitize(active);
    }
    @Unique private void generationControl$sanitize(com.cobblemon.mod.common.battles.ActiveBattlePokemon active) {
        if (!GenerationControl.ready()) return;
        var response=(MoveActionResponse)(Object)this;
        var pokemon=active==null || active.getBattlePokemon()==null ? null : active.getBattlePokemon().getOriginalPokemon();
        if (response.getGimmickID()!=null && response.getGimmickID().toLowerCase(java.util.Locale.ROOT).contains("mega")
            && !br.com.cobbleverse.generations.integration.MegaPolicy.allowed(pokemon)) response.setGimmickID(null);
    }
}
