package br.com.cobbleverse.generations.web.market;

/** Limits for the player market. Changes are validated before becoming active. */
public final class MarketSettings {
    public boolean enabled = true;
    public boolean pokemonSales = true;
    public boolean itemSales = true;
    public boolean auctions = true;
    public int maxListingsPerPlayer = 8;
    public int listingDurationHours = 72;
    public int auctionMinDurationMinutes = 30;
    public int auctionMaxDurationHours = 168;
    public long minBidIncrement = 100;

    public MarketSettings copy() {
        MarketSettings result = new MarketSettings();
        result.enabled = enabled;
        result.pokemonSales = pokemonSales;
        result.itemSales = itemSales;
        result.auctions = auctions;
        result.maxListingsPerPlayer = maxListingsPerPlayer;
        result.listingDurationHours = listingDurationHours;
        result.auctionMinDurationMinutes = auctionMinDurationMinutes;
        result.auctionMaxDurationHours = auctionMaxDurationHours;
        result.minBidIncrement = minBidIncrement;
        return result;
    }

    public void validate() {
        if (maxListingsPerPlayer < 1 || maxListingsPerPlayer > 50)
            throw new IllegalArgumentException("O limite de anúncios deve ficar entre 1 e 50.");
        if (listingDurationHours < 1 || listingDurationHours > 720)
            throw new IllegalArgumentException("A duração de anúncios deve ficar entre 1 e 720 horas.");
        if (auctionMinDurationMinutes < 1 || auctionMinDurationMinutes > 10_080)
            throw new IllegalArgumentException("A duração mínima do leilão é inválida.");
        if (auctionMaxDurationHours < 1 || auctionMaxDurationHours > 720
                || auctionMaxDurationHours * 60 < auctionMinDurationMinutes)
            throw new IllegalArgumentException("A duração máxima do leilão é inválida.");
        if (minBidIncrement < 1 || minBidIncrement > 1_000_000_000L)
            throw new IllegalArgumentException("O incremento mínimo do lance deve ficar entre 1 e 1.000.000.000.");
    }
}
