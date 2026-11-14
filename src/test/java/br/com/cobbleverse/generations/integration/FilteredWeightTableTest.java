package br.com.cobbleverse.generations.integration;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class FilteredWeightTableTest {
    @Test
    void preservesRelativeWeightsWhileSkippingZeroAndDisallowedEntries() {
        FilteredWeightTable<Integer> table = FilteredWeightTable.build(
                new float[]{0.1f, 0.5f, 0.5f, 1.0f}, new int[]{10, 20, 30, 40},
                index -> index, index -> index != 10 && index != 30);

        assertEquals(2, table.size());
        assertEquals(20, table.select(0.2));
        assertEquals(40, table.select(0.8));
        assertEquals(40, table.select(1.0));
    }

    @Test
    void emptyFilteredTableReturnsNoSelection() {
        FilteredWeightTable<Integer> table = FilteredWeightTable.build(
                new float[]{0.5f, 1.0f}, new int[]{1, 2}, index -> index, ignored -> false);

        assertEquals(0, table.size());
        assertNull(table.select(0.5));
    }

    @Test
    void rejectsMismatchedInputArrays() {
        assertThrows(IllegalArgumentException.class, () -> FilteredWeightTable.build(
                new float[]{1.0f}, new int[0], index -> index, ignored -> true));
    }
}
