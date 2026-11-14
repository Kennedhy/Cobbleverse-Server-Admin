package br.com.cobbleverse.generations.web.market;

import java.util.UUID;

/** Persisted escrow entry. The asset payload stays server-owned until settlement. */
public final class MarketListing {
    public String id;
    public String type;
    public String mode;
    public String status;
    public UUID seller;
    public String sellerName;
    public String assetJson;
    public String assetName;
    public MarketPokemonDetails pokemonDetails;
    public String itemId;
    public int itemCount;
    public long price;
    public long highestBid;
    public UUID highestBidder;
    public String highestBidderName;
    public long createdAt;
    public long expiresAt;
    public long revision;

    public MarketListing copy() {
        MarketListing copy = new MarketListing();
        copy.id = id;
        copy.type = type;
        copy.mode = mode;
        copy.status = status;
        copy.seller = seller;
        copy.sellerName = sellerName;
        copy.assetJson = assetJson;
        copy.assetName = assetName;
        copy.pokemonDetails = pokemonDetails;
        copy.itemId = itemId;
        copy.itemCount = itemCount;
        copy.price = price;
        copy.highestBid = highestBid;
        copy.highestBidder = highestBidder;
        copy.highestBidderName = highestBidderName;
        copy.createdAt = createdAt;
        copy.expiresAt = expiresAt;
        copy.revision = revision;
        return copy;
    }
}
