package br.com.cobbleverse.generations.web.market;

import java.util.UUID;

/** Small durable journal entry used to recover interrupted market transfers. */
public final class MarketTransaction {
    public String id;
    public String kind;
    public String status;
    public String listingId;
    public UUID actor;
    public UUID counterparty;
    public String actorName;
    public String counterpartyName;
    public long amount;
    public String actorBalanceBefore;
    public String counterpartyBalanceBefore;
    /** Direction and sequence of a balance mutation, used to replay interrupted account operations backwards. */
    public String balanceOperation;
    public long balanceOperationSequence;
    public long createdAt;
    public long completedAt;
    public String assetJson;
    public String assetType;
    public String assetName;
    public String reason;
    public int sourceBox = -1;
    public int sourceSlot = -1;
    public String sourceFingerprint;
    public String listingMode;
    public long expiresAt;

    public MarketTransaction copy() {
        MarketTransaction copy = new MarketTransaction();
        copy.id = id;
        copy.kind = kind;
        copy.status = status;
        copy.listingId = listingId;
        copy.actor = actor;
        copy.counterparty = counterparty;
        copy.actorName = actorName;
        copy.counterpartyName = counterpartyName;
        copy.amount = amount;
        copy.actorBalanceBefore = actorBalanceBefore;
        copy.counterpartyBalanceBefore = counterpartyBalanceBefore;
        copy.balanceOperation = balanceOperation;
        copy.balanceOperationSequence = balanceOperationSequence;
        copy.createdAt = createdAt;
        copy.completedAt = completedAt;
        copy.assetJson = assetJson;
        copy.assetType = assetType;
        copy.assetName = assetName;
        copy.reason = reason;
        copy.sourceBox = sourceBox;
        copy.sourceSlot = sourceSlot;
        copy.sourceFingerprint = sourceFingerprint;
        copy.listingMode = listingMode;
        copy.expiresAt = expiresAt;
        return copy;
    }
}
