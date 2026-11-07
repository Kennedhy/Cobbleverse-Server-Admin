package br.com.cobbleverse.generations.integration;

import java.util.Set;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;

class RctIntegrationTest {
    @Test
    void restoringCircuitProgressDoesNotTransferDefeatsFromAnotherCircuit() {
        var ledger = new RctIntegration.ProgressLedger();
        ledger.defeatsBySeries.put("kanto", Set.of("boulder", "cascade"));
        ledger.defeatsBySeries.put("johto", Set.of("zephyr"));

        assertEquals(Set.of("zephyr"), RctIntegration.targetDefeats(ledger, "johto", false, Set.of("zephyr", "morty")));
        assertEquals(Set.of(), RctIntegration.targetDefeats(ledger, "hoenn", false, Set.of("wattson")));
        assertEquals(Set.of("zephyr", "morty"), RctIntegration.targetDefeats(ledger, "johto", true, Set.of("zephyr", "morty")));
    }
}
