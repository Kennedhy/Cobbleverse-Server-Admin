package br.com.cobbleverse.generations.integration;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.function.IntFunction;
import java.util.function.Predicate;

/** Immutable filtered cumulative distribution; construction belongs on cache misses. */
final class FilteredWeightTable<T> {
    private final Object[] values;
    private final double[] cumulative;
    private final double total;

    private FilteredWeightTable(Object[] values, double[] cumulative, double total) {
        this.values = values;
        this.cumulative = cumulative;
        this.total = total;
    }

    static <T> FilteredWeightTable<T> build(float[] cumulativeWeights, int[] indexes,
                                             IntFunction<T> resolve, Predicate<? super T> allowed) {
        if (cumulativeWeights == null || indexes == null || cumulativeWeights.length != indexes.length)
            throw new IllegalArgumentException("Distribuição de raid inconsistente.");

        ArrayList<T> values = new ArrayList<>(indexes.length);
        double[] cumulative = new double[indexes.length];
        double total = 0;
        for (int index = 0; index < indexes.length; index++) {
            double weight = cumulativeWeights[index] - (index == 0 ? 0 : cumulativeWeights[index - 1]);
            if (!(weight > 0) || !Double.isFinite(weight)) continue;
            T value = resolve.apply(indexes[index]);
            if (value == null || !allowed.test(value)) continue;
            total += weight;
            values.add(value);
            cumulative[values.size() - 1] = total;
        }
        return new FilteredWeightTable<>(values.toArray(), Arrays.copyOf(cumulative, values.size()), total);
    }

    T select(double fraction) {
        if (values.length == 0) return null;
        if (!(fraction > 0)) return valueAt(0);
        double target = fraction * total;
        if (target >= total) return valueAt(values.length - 1);
        int low = 0;
        int high = cumulative.length;
        while (low < high) {
            int middle = (low + high) >>> 1;
            if (target < cumulative[middle]) high = middle;
            else low = middle + 1;
        }
        return valueAt(Math.min(low, values.length - 1));
    }

    int size() { return values.length; }

    private T valueAt(int index) {
        @SuppressWarnings("unchecked") T value = (T) values[index];
        return value;
    }
}
