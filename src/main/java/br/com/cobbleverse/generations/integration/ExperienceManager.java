package br.com.cobbleverse.generations.integration;

import br.com.cobbleverse.generations.GenerationControl;
import br.com.cobbleverse.generations.config.GenerationConfig;
import br.com.cobbleverse.generations.rules.GameplaySettings;
import com.cobblemon.mod.common.api.events.CobblemonEvents;
import com.cobblemon.mod.common.entity.pokemon.PokemonEntity;
import com.gitlab.srcmc.rctmod.api.utils.LevelUtils;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;

/** Event-driven management; no world scans or background tick tasks. */
public final class ExperienceManager {
    private ExperienceManager() {}

    private static GameplaySettings settings() {
        if (!GenerationControl.ready()) return null;
        return GenerationControl.gameplaySettings();
    }

    public static boolean managesExperience() {
        var settings = settings();
        return settings != null && settings.manageExperience();
    }

    /** Null means the RCT's own progress calculation remains authoritative. */
    public static Integer globalCapOverride(net.minecraft.entity.player.PlayerEntity player) {
        return serverGlobalCap();
    }

    public static Integer serverGlobalCap() {
        var settings = settings();
        return settings != null && settings.manageExperience() && settings.levelCapMode().equals("global")
                ? settings.globalLevelCap() : null;
    }
    public static void register() {
        CobblemonEvents.EXPERIENCE_GAINED_EVENT_PRE.subscribe(com.cobblemon.mod.common.api.Priority.LOWEST,event -> {
            var settings = settings();
            if (settings == null || !settings.manageExperience()) return;
            var pokemon = event.getPokemon();
            var owner = pokemon.getOwnerPlayer();
            if (owner == null) return;
            int cap = cap(settings, owner);
            // RCT returns 0 while progress is loading. Do not consume XP during that interval.
            if (cap == 0) { event.setExperience(0); return; }
            int remaining = Math.max(0, pokemon.getExperienceToLevel(cap));
            event.setExperience(br.com.cobbleverse.generations.rules.ExperienceRules.award(
                    settings, owner.getUuidAsString(), event.getExperience(), remaining));
        });
        CobblemonEvents.POKEMON_ENTITY_SPAWN.subscribe(event -> {
            var settings = settings();
            PokemonEntity entity = event.getEntity();
            var pokemon = entity.getPokemon();
            if (!br.com.cobbleverse.generations.rules.ExperienceRules.controlsWildLevels(settings) || !pokemon.isWild()
                    || pokemon.getOwnerUUID() != null || !(entity.getWorld() instanceof ServerWorld world)) return;
            int level = pokemon.getLevel();
            if (level <= 1) return;
            int limit = settings.levelCapMode().equals("global") ? settings.globalLevelCap() : 0;
            if (settings.levelCapMode().equals("rct")) {
                // World players only, on a spawn event; no entity/chunk scans. Highest eligible cap wins.
                for (ServerPlayerEntity player : world.getPlayers()) {
                    if (player.isSpectator() || player.squaredDistanceTo(entity) > 128.0 * 128.0) continue;
                    limit = Math.max(limit, cap(settings, player));
                    // The maximum only matters if every nearby cap is below the encounter's level.
                    if (limit >= level) return;
                    if (limit == 100) break;
                }
            }
            // No nearby player or RCT still loading: preserve the original encounter.
            if (limit <= 0 || level <= limit) return;
            if (settings.wildLevelBehavior().equals("block")) event.cancel();
            else pokemon.setLevel(limit);
        });
    }

    private static int cap(GameplaySettings settings, ServerPlayerEntity player) {
        if (settings.levelCapMode().equals("global")) return settings.globalLevelCap();
        var state=com.gitlab.srcmc.rctmod.api.data.sync.PlayerState.get(player);
        if (state==null || state.isLoading()) return 0;
        return Math.max(0, Math.min(100, LevelUtils.levelCap(player)));
    }

    /** Uses the same cap calculation as XP awards and wild encounter adjustments. */
    public static int previewCap(GenerationConfig c, ServerPlayerEntity player) {
        return cap(GameplaySettings.from(c), player);
    }
}
