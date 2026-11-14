package br.com.cobbleverse.generations.web.market;

import org.junit.jupiter.api.Test;

import java.math.BigInteger;

import static org.junit.jupiter.api.Assertions.*;

class MarketBalanceRecoveryTest {
    private static final BigInteger BEFORE = BigInteger.valueOf(1_000);

    @Test
    void onlyExactPersistedBeforeOrAfterBalancesResolveADebit() {
        assertEquals(MarketBalanceRecovery.DebitState.NOT_APPLIED,
                MarketBalanceRecovery.classifyDebit(BEFORE, BEFORE, 250));
        assertEquals(MarketBalanceRecovery.DebitState.APPLIED,
                MarketBalanceRecovery.classifyDebit(BigInteger.valueOf(750), BEFORE, 250));
        assertEquals(MarketBalanceRecovery.DebitState.UNKNOWN,
                MarketBalanceRecovery.classifyDebit(BigInteger.valueOf(900), BEFORE, 250));
        assertEquals(MarketBalanceRecovery.DebitState.UNKNOWN,
                MarketBalanceRecovery.classifyDebit(null, BEFORE, 250));
    }

    @Test
    void invalidDebitDataCannotBeReconciled() {
        assertThrows(IllegalArgumentException.class,
                () -> MarketBalanceRecovery.classifyDebit(BigInteger.ZERO, BEFORE, 0));
        assertThrows(IllegalArgumentException.class,
                () -> MarketBalanceRecovery.classifyDebit(BigInteger.ZERO, null, 1));
    }

    @Test
    void offlineDebitChecksCanRotatePastEarlierUnresolvedTransactions() {
        assertArrayEquals(new int[]{8, 9, 0, 1, 2, 3, 4, 5, 6, 7},
                MarketBalanceRecovery.rotatedOrder(10, 8));
        assertArrayEquals(new int[0], MarketBalanceRecovery.rotatedOrder(0, 8));
    }
}
