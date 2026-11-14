package br.com.cobbleverse.generations.integration;

import br.com.cobbleverse.generations.GenerationControl;
import com.necro.raid.dens.common.data.raid.RaidBoss;
import com.necro.raid.dens.common.registry.RaidRegistry;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.random.Random;
import java.util.*;

/** Covers both configured encounters and boss IDs persisted in existing dens. */
public final class RaidGuard {
    private static final int MAX_CACHED_ROLL_TABLES = 4;
    private static final ArrayList<RollTable> ROLL_TABLES = new ArrayList<>(MAX_CACHED_ROLL_TABLES);

    private RaidGuard() {}

    public static synchronized void clearCache() {
        ROLL_TABLES.clear();
    }

    private record RollTable(br.com.cobbleverse.generations.rules.GenerationRules rules,
                             float[] weights, int[] indexes,
                             FilteredWeightTable<Identifier> distribution) {
        boolean matches(br.com.cobbleverse.generations.rules.GenerationRules candidate,
                        float[] candidateWeights, int[] candidateIndexes) {
            return rules == candidate && Arrays.equals(weights, candidateWeights)
                    && Arrays.equals(indexes, candidateIndexes);
        }
    }

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
        var rules = br.com.cobbleverse.generations.GenerationControl.rules();
        FilteredWeightTable<Identifier> distribution = rollTable(rules, cumulative, indexes);
        return distribution.select(random.nextDouble());
    }

    private static synchronized FilteredWeightTable<Identifier> rollTable(
            br.com.cobbleverse.generations.rules.GenerationRules rules, float[] cumulative, int[] indexes) {
        if (cumulative == null || indexes == null || cumulative.length != indexes.length)
            throw new IllegalArgumentException("Distribuição de raid inconsistente.");
        for (int index = 0; index < ROLL_TABLES.size(); index++) {
            RollTable cached = ROLL_TABLES.get(index);
            if (!cached.matches(rules, cumulative, indexes)) continue;
            if (index > 0) {
                ROLL_TABLES.remove(index);
                ROLL_TABLES.add(0, cached);
            }
            return cached.distribution();
        }

        float[] weightsCopy = Arrays.copyOf(cumulative, cumulative.length);
        int[] indexesCopy = Arrays.copyOf(indexes, indexes.length);
        FilteredWeightTable<Identifier> distribution = FilteredWeightTable.build(weightsCopy, indexesCopy,
                index -> RaidRegistry.RAID_LIST.get(index),
                id -> allowed(RaidRegistry.getRaidBoss(id)));
        ROLL_TABLES.add(0, new RollTable(rules, weightsCopy, indexesCopy, distribution));
        if (ROLL_TABLES.size() > MAX_CACHED_ROLL_TABLES) ROLL_TABLES.remove(ROLL_TABLES.size() - 1);
        return distribution;
    }
}
