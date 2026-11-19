package br.com.cobbleverse.generations.integration;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class FilteredWeightTableTest {
    @Test
    void skipsZeroWeightEntriesEvenWhenRandomFractionIsZero() {
        FilteredWeightTable<Integer> table = FilteredWeightTable.build(
                new float[]{0, 2, 2, 5},
                new int[]{0, 1, 2, 3},
                index -> index,
                ignored -> true);

        assertEquals(2, table.size());
        assertEquals(1, table.select(0));
        assertEquals(3, table.select(0.999));
    }

    @Test
    void returnsNoEncounterWhenEveryWeightIsZero() {
        FilteredWeightTable<Integer> table = FilteredWeightTable.build(
                new float[]{0, 0},
                new int[]{0, 1},
                index -> index,
                ignored -> true);

        assertEquals(0, table.size());
        assertNull(table.select(0));
        assertNull(table.select(0.5));
    }
}
