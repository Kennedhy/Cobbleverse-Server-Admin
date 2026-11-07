package br.com.cobbleverse.generations.integration;

import br.com.cobbleverse.generations.GenerationControl;
import br.com.cobbleverse.generations.config.GenerationConfig;
import br.com.cobbleverse.generations.rules.GenerationRules;
import com.cobblemon.mod.common.api.events.CobblemonEvents;
import com.cobblemon.mod.common.entity.pokemon.PokemonEntity;
import com.gitlab.srcmc.rctmod.api.utils.LevelUtils;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;

/** Event-driven management; no world scans or background tick tasks. */
public final class ExperienceManager {
    private static GenerationRules cachedRules;
    private static GenerationConfig cachedConfig;
    private ExperienceManager() {}

    private static GenerationConfig config() {
        if (!GenerationControl.ready()) return null;
        GenerationRules current = GenerationControl.rules();
        if (current != cachedRules) { cachedRules = current; cachedConfig = current.config(); }
        return cachedConfig;
    }

    public static boolean managesExperience() {
        var c = config();
        return c != null && c.manageExperience;
    }

    /** Null means the RCT's own progress calculation remains authoritative. */
    public static Integer globalCapOverride(net.minecraft.entity.player.PlayerEntity player) {
        return serverGlobalCap();
    }

    public static Integer serverGlobalCap() {
        var c = config();
        return c != null && c.manageExperience && c.levelCapMode.equals("global") ? c.globalLevelCap : null;
    }
    public static void clear() { cachedRules = null; cachedConfig = null; }

    public static void register() {
        CobblemonEvents.EXPERIENCE_GAINED_EVENT_PRE.subscribe(com.cobblemon.mod.common.api.Priority.LOWEST,event -> {
            var c = config();
            if (c == null || !c.manageExperience) return;
            var pokemon = event.getPokemon();
            var owner = pokemon.getOwnerPlayer();
            if (owner == null) return;
            int cap = cap(c, owner);
            // RCT returns 0 while progress is loading. Do not consume XP during that interval.
            if (cap == 0) { event.setExperience(0); return; }
            int remaining = Math.max(0, pokemon.getExperienceToLevel(cap));
            event.setExperience(br.com.cobbleverse.generations.rules.ExperienceRules.award(c,owner.getUuidAsString(),event.getExperience(),remaining));
        });
        CobblemonEvents.POKEMON_ENTITY_SPAWN.subscribe(event -> {
            var c = config();
            PokemonEntity entity = event.getEntity();
            var pokemon = entity.getPokemon();
            if (!br.com.cobbleverse.generations.rules.ExperienceRules.controlsWildLevels(c) || !pokemon.isWild()
                    || pokemon.getOwnerUUID() != null || !(entity.getWorld() instanceof ServerWorld world)) return;
            int level = pokemon.getLevel();
            if (level <= 1) return;
            int limit = c.levelCapMode.equals("global") ? c.globalLevelCap : 0;
            if (c.levelCapMode.equals("rct")) {
                // World players only, on a spawn event; no entity/chunk scans. Highest eligible cap wins.
                for (ServerPlayerEntity player : world.getPlayers()) {
                    if (player.isSpectator() || player.squaredDistanceTo(entity) > 128.0 * 128.0) continue;
                    limit = Math.max(limit, cap(c, player));
                    // The maximum only matters if every nearby cap is below the encounter's level.
                    if (limit >= level) return;
                    if (limit == 100) break;
                }
            }
            // No nearby player or RCT still loading: preserve the original encounter.
            if (limit <= 0 || level <= limit) return;
            if (c.wildLevelBehavior.equals("block")) event.cancel();
            else pokemon.setLevel(limit);
        });
    }

    private static int cap(GenerationConfig c, ServerPlayerEntity player) {
        if (c.levelCapMode.equals("global")) return c.globalLevelCap;
        var state=com.gitlab.srcmc.rctmod.api.data.sync.PlayerState.get(player);
        if (state==null || state.isLoading()) return 0;
        return Math.max(0, Math.min(100, LevelUtils.levelCap(player)));
    }
}
