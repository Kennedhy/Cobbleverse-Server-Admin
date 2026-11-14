package br.com.cobbleverse.generations.web.market;

import java.math.BigInteger;

/** Classifies a journaled debit using only an exactly matching persisted balance. */
final class MarketBalanceRecovery {
    enum DebitState { APPLIED, NOT_APPLIED, UNKNOWN }

    private MarketBalanceRecovery() {}

    static DebitState classifyDebit(BigInteger persistedBalance, BigInteger balanceBefore, long amount) {
        if (balanceBefore == null || amount <= 0) throw new IllegalArgumentException("Invalid debit recovery data.");
        if (persistedBalance == null) return DebitState.UNKNOWN;
        BigInteger after = balanceBefore.subtract(BigInteger.valueOf(amount));
        if (persistedBalance.equals(after)) return DebitState.APPLIED;
        if (persistedBalance.equals(balanceBefore)) return DebitState.NOT_APPLIED;
        return DebitState.UNKNOWN;
    }

    static int[] rotatedOrder(int size, int cursor) {
        if (size < 0) throw new IllegalArgumentException("Invalid recovery queue size.");
        int[] order = new int[size];
        if (size == 0) return order;
        int start = Math.floorMod(cursor, size);
        for (int offset = 0; offset < size; offset++) order[offset] = (start + offset) % size;
        return order;
    }
}
