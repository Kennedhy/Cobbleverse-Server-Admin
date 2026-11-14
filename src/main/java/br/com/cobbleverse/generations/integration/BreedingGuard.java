package br.com.cobbleverse.generations.integration;

import br.com.cobbleverse.generations.GenerationControl;
import br.com.cobbleverse.generations.rules.GenerationRules;
import com.cobblemon.mod.common.api.pokemon.PokemonProperties;
import com.cobblemon.mod.common.api.pokemon.PokemonSpecies;
import com.cobblemon.mod.common.pokemon.FormData;
import net.minecraft.item.ItemStack;
import java.lang.reflect.Method;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;

/** Cobbreeding 2.2.2 consumes the egg after emitting its cancellable event.
 * Block the inventory tick before that point, preserving the complete item. */
public final class BreedingGuard {
    private static Method extract;
    private static volatile GenerationRules cachedRules;
    private static List<FormData> babies = List.of();
    private BreedingGuard() {}

    public static synchronized void clearCache() {
        cachedRules = null;
        babies = List.of();
    }

    public static boolean eggAllowed(ItemStack stack) {
        if (!GenerationControl.ready()) return true;
        try {
            if (extract == null) extract = Class.forName("ludichat.cobbreeding.EggUtilities").getMethod("extractProperties",ItemStack.class);
            PokemonProperties properties = (PokemonProperties)extract.invoke(null,stack);
            if (properties == null) return true;
            if ("random".equalsIgnoreCase(properties.getSpecies())) return !allowedBabies().isEmpty();
            return CobblemonIntegration.allowed(properties);
        } catch (ReflectiveOperationException e) { throw new IllegalStateException("Integração Cobbreeding incompatível; eclosão interrompida.",e); }
    }

    public static synchronized List<FormData> allowedBabies() {
        GenerationRules rules = GenerationControl.rules();
        if (cachedRules == rules) return babies;
        try {
            Class<?> utilities = Class.forName("ludichat.cobbreeding.EggUtilities");
            Method originalRestriction = utilities.getDeclaredMethod("isRandomSpeciesAllowed",FormData.class);
            originalRestriction.setAccessible(true);
            Class<?> breeding = Class.forName("ludichat.cobbreeding.BreedingUtilities");
            Object instance = breeding.getField("INSTANCE").get(null);
            Method getBaby = breeding.getMethod("getBaby",FormData.class);
            Set<FormData> allowed = new LinkedHashSet<>();
            for (var species : PokemonSpecies.getImplemented()) {
                Set<FormData> forms = new LinkedHashSet<>(species.getForms());
                forms.add(species.getStandardForm());
                for (FormData form : forms) {
                    if (!(Boolean)originalRestriction.invoke(null,form)) continue;
                    FormData baby = (FormData)getBaby.invoke(instance,form);
                    var babySpecies = baby.getSpecies();
                    if (rules.allowed(babySpecies.getResourceIdentifier().toString(),babySpecies.getNationalPokedexNumber(),baby.getName(),Set.of())) allowed.add(baby);
                }
            }
            babies = List.copyOf(allowed);
            cachedRules = rules;
            return babies;
        } catch (ReflectiveOperationException e) { throw new IllegalStateException("Não foi possível filtrar os ovos aleatórios do Cobbreeding.",e); }
    }

    public static FormData randomBaby() {
        List<FormData> allowed = allowedBabies();
        if (allowed.isEmpty()) throw new IllegalStateException("Nenhum resultado de reprodução aleatória permitido.");
        return allowed.get(ThreadLocalRandom.current().nextInt(allowed.size()));
    }
}
