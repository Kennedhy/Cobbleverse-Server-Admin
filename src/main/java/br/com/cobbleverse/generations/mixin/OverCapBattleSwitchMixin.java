package br.com.cobbleverse.generations.mixin;

import br.com.cobbleverse.generations.GenerationControl;
import br.com.cobbleverse.generations.integration.ExperienceManager;
import com.cobblemon.mod.common.battles.ActiveBattlePokemon;
import com.cobblemon.mod.common.battles.ShowdownMoveset;
import com.cobblemon.mod.common.battles.SwitchActionResponse;
import com.cobblemon.mod.common.battles.pokemon.BattlePokemon;
import com.cobblemon.mod.common.pokemon.Pokemon;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Prevents over-cap Pokémon from being selected for PvE battles when the per-Pokémon policy is active. */
@Mixin(value = SwitchActionResponse.class, remap = false)
public abstract class OverCapBattleSwitchMixin {
    @Inject(method = "isValid", at = @At("HEAD"), cancellable = true, remap = false)
    private void generationControl$checkBattleLevelCap(ActiveBattlePokemon active, ShowdownMoveset moveset,
                                                        boolean forced, CallbackInfoReturnable<Boolean> cir) {
        if (active == null || active.getBattle() == null || active.getBattle().isPvP()) return;

        var settings = GenerationControl.gameplaySettings();
        if (!GenerationControl.ready() || settings == null || !settings.manageExperience()
                || !settings.blockOverCapPokemonInBattles()
                || !"pokemon".equals(settings.overCapBattleBehavior())) return;

        var requestedId = ((SwitchActionResponse) (Object) this).getNewPokemonId();
        Pokemon requested = null;
        for (BattlePokemon candidate : active.getActor().getPokemonList()) {
            if (candidate.getUuid().equals(requestedId)) {
                requested = candidate.getOriginalPokemon();
                break;
            }
        }
        if (requested == null || !(requested.getOwnerPlayer() instanceof ServerPlayerEntity player)) return;

        int cap = ExperienceManager.battleCap(settings, player);
        if (cap <= 0 || requested.getLevel() <= cap) return;

        player.sendMessage(Text.literal("Este Pokémon está acima do seu limite de nível e não pode entrar em uma batalha contra selvagens ou treinadores. Em PvP, esta regra não se aplica."));
        cir.setReturnValue(false);
    }
}
