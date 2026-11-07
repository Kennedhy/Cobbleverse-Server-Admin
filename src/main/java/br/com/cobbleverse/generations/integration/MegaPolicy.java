package br.com.cobbleverse.generations.integration;

import br.com.cobbleverse.generations.GenerationControl;
import com.cobblemon.mod.common.api.pokemon.PokemonProperties;
import com.cobblemon.mod.common.pokemon.Pokemon;
import com.github.yajatkaul.mega_showdown.gimmick.MegaGimmick;
import com.github.yajatkaul.mega_showdown.utils.RegistryLocator;
import java.util.*;

/** Resolve the actual held stone, so a specific form exception has the same meaning everywhere. */
public final class MegaPolicy {
    private MegaPolicy() {}
    public static boolean allowed(Pokemon pokemon) {
        if(!GenerationControl.ready()) return true;
        if(pokemon==null) return GenerationControl.rules().allowMegas();
        if(!net.fabricmc.loader.api.FabricLoader.getInstance().isModLoaded("mega_showdown")) return GenerationControl.rules().allowMegas();
        String raw;
        if(pokemon.getSpecies().getName().equals("Rayquaza")) raw="mega_evolution=mega";
        else {
            var mega=RegistryLocator.getComponent(MegaGimmick.class,pokemon.heldItem());
            if(mega==null) return false;
            var apply=mega.aspect_conditions().aspectApply();
            raw=String.join(" ",apply.aspects())+" "+apply.pokemonProperties().orElse("");
        }
        return CobblemonIntegration.allowedResult(PokemonProperties.Companion.parse(raw),pokemon);
    }
}
