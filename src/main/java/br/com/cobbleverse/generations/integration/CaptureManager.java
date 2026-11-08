package br.com.cobbleverse.generations.integration;

import br.com.cobbleverse.generations.GenerationControl;
import br.com.cobbleverse.generations.rules.CaptureRules;
import br.com.cobbleverse.generations.rules.GameplaySettings;
import com.cobblemon.mod.common.Cobblemon;
import com.cobblemon.mod.common.api.pokedex.CaughtCount;
import net.minecraft.entity.LivingEntity;
import net.minecraft.server.network.ServerPlayerEntity;

/** Only called once per capture calculation, never on a recurring tick. */
public final class CaptureManager {
    private static GameplaySettings cachedSettings;
    private static double multiplier=1;
    private CaptureManager() {}
    public static void clear() { cachedSettings=null; multiplier=1; }
    public static int threshold(int original,LivingEntity thrower,float modifiedCatchRate,boolean critical) {
        if(!GenerationControl.ready()) return original;
        GameplaySettings settings=GenerationControl.gameplaySettings();
        if(settings!=cachedSettings) { cachedSettings=settings; multiplier=settings==null ? 1 : settings.captureMultiplier(); }
        if(multiplier==1) return original;
        double criticalChance=0;
        if(thrower instanceof ServerPlayerEntity player) {
            int caught=((Number)Cobblemon.INSTANCE.getPlayerDataManager().getPokedexData(player).getGlobalCalculatedValue(CaughtCount.INSTANCE)).intValue();
            float factor=caught<=30 ? 0 : caught<=150 ? 0.5f : caught<=300 ? 1 : caught<=450 ? 1.5f : caught<=600 ? 2 : 2.5f;
            int threshold=kotlin.math.MathKt.roundToInt(modifiedCatchRate*factor/6.0f);
            criticalChance=Math.max(0,Math.min(256,threshold))/256.0;
        }
        return CaptureRules.threshold(original,critical,criticalChance,multiplier);
    }
}
