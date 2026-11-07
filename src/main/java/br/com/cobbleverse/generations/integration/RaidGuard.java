package br.com.cobbleverse.generations.integration;

import br.com.cobbleverse.generations.GenerationControl;
import com.necro.raid.dens.common.data.raid.RaidBoss;
import com.necro.raid.dens.common.registry.RaidRegistry;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.random.Random;
import java.util.*;

/** Covers both configured encounters and boss IDs persisted in existing dens. */
public final class RaidGuard {
    private RaidGuard() {}
    public static boolean allowed(RaidBoss boss) { return allowed(boss,Set.of()); }
    public static boolean allowed(RaidBoss boss, Set<String> extraAspects) {
        if (!GenerationControl.ready()) return true;
        if (boss == null || boss.getReward() == null) return false;
        if (!CobblemonIntegration.allowed(boss.getReward())) return false;
        var properties = boss.getBossProperties().copy();
        if (extraAspects != null && !extraAspects.isEmpty()) {
            Set<String> aspects = new HashSet<>(properties.getAspects());
            aspects.addAll(extraAspects);
            properties.setAspects(aspects);
        }
        return CobblemonIntegration.allowed(properties);
    }

    /** Upstream binary search can choose a zero-weight entry when the roll is zero.
     * Build a positive-weight distribution containing only legal encounters. */
    public static Identifier roll(Random random,float[] cumulative,int[] indexes) {
        if (cumulative.length != indexes.length) throw new IllegalArgumentException("Distribuição de raid inconsistente.");
        double total = 0;
        List<Identifier> candidates = new ArrayList<>();
        List<Double> bounds = new ArrayList<>();
        for (int i=0; i<indexes.length; i++) {
            double weight = cumulative[i]-(i == 0 ? 0 : cumulative[i-1]);
            if (weight <= 0 || !Double.isFinite(weight)) continue;
            Identifier id = RaidRegistry.RAID_LIST.get(indexes[i]);
            if (!allowed(RaidRegistry.getRaidBoss(id))) continue;
            total += weight;
            candidates.add(id);
            bounds.add(total);
        }
        if (total <= 0) return null;
        double target = random.nextDouble()*total;
        for (int i=0; i<bounds.size(); i++) if (target < bounds.get(i)) return candidates.get(i);
        return candidates.getLast();
    }
}
