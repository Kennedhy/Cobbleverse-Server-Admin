package br.com.cobbleverse.generations.verification;

import br.com.cobbleverse.generations.GenerationControl;
import br.com.cobbleverse.generations.config.ConfigStore;
import br.com.cobbleverse.generations.integration.CobblemonIntegration;
import br.com.cobbleverse.generations.market.CobbleDollarsEconomy;
import br.com.cobbleverse.generations.mixin.PlayerDataSaverAccessor;
import br.com.cobbleverse.generations.web.market.MarketItemSnapshot;
import br.com.cobbleverse.generations.web.market.MarketListing;
import br.com.cobbleverse.generations.web.market.MarketPlayerGui;
import br.com.cobbleverse.generations.web.market.MarketService;
import br.com.cobbleverse.generations.web.market.MarketStore;
import br.com.cobbleverse.generations.web.market.MarketTransaction;
import com.cobblemon.mod.common.Cobblemon;
import com.cobblemon.mod.common.api.pokemon.PokemonSpecies;
import com.cobblemon.mod.common.api.storage.pc.PCPosition;
import com.cobblemon.mod.common.api.storage.pc.PCStore;
import com.cobblemon.mod.common.pokemon.Pokemon;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.server.MinecraftServer;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.math.BigInteger;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/** Disposable connected-player fixture for GTS custody and the player GUI. */
public final class ConnectedMarketVerification implements ModInitializer {
    private static final String PLAYER_NAME = "MarketSmoke";

    @Override
    public void onInitialize() {
        ServerTickEvents.END_SERVER_TICK.register(new ServerTickEvents.EndTick() {
            private int phase;
            private int ticks;
            private boolean done;
            private String pokemonClaimId;
            private String itemClaimId;
            private String itemListingId;
            private String pokemonListingId;
            private Pokemon pokemon;
            private PCStore pc;
            private int box;
            private int slot;

            @Override
            public void onEndTick(MinecraftServer server) {
                if (done) return;
                var player = server.getPlayerManager().getPlayer(PLAYER_NAME);
                if (player == null) return;
                if (++ticks > 1200) {
                    complete(server, Map.of("success", false, "fatal", "Timeout aguardando a operação do mercado.", "phase", phase));
                    return;
                }

                try {
                    MarketService market = MarketService.current();
                    if (!market.economyAvailable()) throw new IllegalStateException("CobbleDollars não foi detectado no pack de teste.");

                    if (phase == 0) {
                        // This sends the real player-facing SGUI screen to the connected Fabric client.
                        MarketPlayerGui.open(player);
                        pokemon = PokemonSpecies.getImplemented().stream()
                                .map(species -> species.create(12))
                                .filter(CobblemonIntegration::allowed)
                                .findFirst().orElseThrow(() -> new IllegalStateException("Não há espécie permitida para o teste."));
                        pc = Cobblemon.INSTANCE.getStorage().getPC(player);
                        if (pc.getBoxes().isEmpty()) {
                            pc.resize(30, false, ignored -> kotlin.Unit.INSTANCE);
                            pc.initialize();
                        }
                        boolean found = false;
                        for (int candidateBox = 0; candidateBox < pc.getBoxes().size() && !found; candidateBox++) {
                            for (int candidateSlot = 0; candidateSlot < 30; candidateSlot++) {
                                PCPosition position = new PCPosition(candidateBox, candidateSlot);
                                if (pc.get(position) == null) {
                                    box = candidateBox;
                                    slot = candidateSlot;
                                    pc.set(position, pokemon);
                                    found = true;
                                    break;
                                }
                            }
                        }
                        if (!found) throw new IllegalStateException("O PC de teste está cheio.");
                        market.listPokemon(player, box, slot, "fixed", 100, 0, pokemon.getUuid().toString());
                        phase = 1;
                        return;
                    }

                    if (phase == 1) {
                        MarketListing listing = market.myListings(player.getUuid()).stream()
                                .filter(value -> "pokemon".equals(value.type) && "active".equals(value.status))
                                .findFirst().orElse(null);
                        if (listing == null) return; // Cobblemon confirms the PC save asynchronously.
                        if (listing.pokemonDetails == null || listing.pokemonDetails.ivs().isEmpty()
                                || listing.pokemonDetails.evs().isEmpty())
                            throw new IllegalStateException("O anúncio não preservou os dados IV/EV do Pokémon.");
                        pokemonListingId = listing.id;
                        market.cancelListing(player, listing.id);
                        MarketTransaction claim = market.claims(player.getUuid()).stream()
                                .filter(value -> "pokemon".equals(value.assetType)).findFirst()
                                .orElseThrow(() -> new IllegalStateException("O cancelamento não criou retirada do Pokémon."));
                        pokemonClaimId = claim.id;
                        market.claim(player, claim.id);
                        phase = 2;
                        return;
                    }

                    if (phase == 2) {
                        boolean stillPending = market.claims(player.getUuid()).stream()
                                .anyMatch(value -> pokemonClaimId.equals(value.id));
                        if (stillPending) return; // The Cobblemon file adapter confirms the save asynchronously.
                        if (pc.get(new PCPosition(box, slot)) == null
                                && Cobblemon.INSTANCE.getStorage().getPC(player).get(pokemon.getUuid()) == null
                                && Cobblemon.INSTANCE.getStorage().getParty(player).get(pokemon.getUuid()) == null)
                            throw new IllegalStateException("A retirada não restaurou o Pokémon ao PC/equipe.");

                        player.getInventory().setStack(player.getInventory().selectedSlot, new ItemStack(Items.DIAMOND, 3));
                        String fingerprint = market.heldItemFingerprint(player);
                        market.listHeldItem(player, "fixed", 100, 0, fingerprint);
                        phase = 3;
                        return;
                    }

                    if (phase == 3) {
                        MarketListing listing = market.myListings(player.getUuid()).stream()
                                .filter(value -> "item".equals(value.type) && "active".equals(value.status))
                                .findFirst().orElse(null);
                        if (listing == null) return;
                        if (!"minecraft:diamond".equals(listing.itemId) || listing.itemCount != 3)
                            throw new IllegalStateException("O anúncio não preservou o item e a quantidade.");
                        itemListingId = listing.id;
                        market.cancelListing(player, listing.id);
                        MarketTransaction claim = market.claims(player.getUuid()).stream()
                                .filter(value -> "item".equals(value.assetType)).findFirst()
                                .orElseThrow(() -> new IllegalStateException("O cancelamento não criou retirada do item."));
                        itemClaimId = claim.id;
                        market.claim(player, claim.id);
                        if (market.claims(player.getUuid()).stream().anyMatch(value -> itemClaimId.equals(value.id)))
                            throw new IllegalStateException("A retirada do item não foi concluída após a confirmação do inventário.");
                        int diamonds = 0;
                        for (int index = 0; index < player.getInventory().size(); index++) {
                            ItemStack stack = player.getInventory().getStack(index);
                            if (stack.isOf(Items.DIAMOND)) diamonds += stack.getCount();
                            if (MarketItemSnapshot.hasClaimMarker(stack))
                                throw new IllegalStateException("Um marcador interno permaneceu no inventário.");
                        }
                        if (diamonds != 3) throw new IllegalStateException("A pilha de diamantes não foi restaurada exatamente uma vez.");

                        MarketTransactionResults sales = exerciseSalesAndAuction(server, market, player, diamonds);
                        // Leave the GUI visible on the smoke client until the harness collects its result.
                        MarketPlayerGui.open(player);
                        Map<String, Object> result = new LinkedHashMap<>();
                        result.put("success", true);
                        result.put("connected", player.networkHandler != null);
                        result.put("marketGuiOpened", true);
                        result.put("economyAvailable", market.economyAvailable());
                        result.put("pokemonListingCancelledAndClaimed", pokemonListingId != null && pokemonClaimId != null);
                        result.put("pokemonStatsDisplayed", true);
                        result.put("itemListingCancelledAndClaimed", itemListingId != null && itemClaimId != null);
                        result.put("restoredDiamondCount", diamonds);
                        result.put("fixedPricePurchaseSettled", sales.fixedPurchase());
                        result.put("auctionBidsAndOutbidRefundSettled", sales.bidsAndRefund());
                        result.put("expiredAuctionDeliveredAndPaid", sales.auctionSettlement());
                        result.put("finalCobbleDollarsBalance", sales.balance());
                        result.put("finalDiamondCount", sales.finalDiamonds());
                        complete(server, result);
                    }
                } catch (Throwable failure) {
                    failure.printStackTrace();
                    complete(server, Map.of("success", false,
                            "fatal", failure.getMessage() == null ? failure.toString() : failure.getMessage(),
                            "phase", phase));
                }
            }

            private void complete(MinecraftServer server, Map<String, Object> result) {
                done = true;
                writeResult(result);
            }
        });
    }

    private static MarketTransactionResults exerciseSalesAndAuction(MinecraftServer server, MarketService market,
                                                                      net.minecraft.server.network.ServerPlayerEntity player,
                                                                      int diamondCountBeforeSales) throws Exception {
        BigInteger startingBalance = BigInteger.valueOf(2_000);
        seedBalance(server, player, startingBalance);
        CobbleDollarsEconomy economy = new CobbleDollarsEconomy(server);
        if (!economy.isPersistedBalance(player.getUuid(), startingBalance))
            throw new IllegalStateException("Não foi possível preparar um saldo CobbleDollars consistente para o teste.");

        MarketStore store = marketStore(market);
        MarketItemSnapshot snapshot = MarketItemSnapshot.capture(new ItemStack(Items.DIAMOND, 2), player.getRegistryManager());
        MarketListing fixed = testListing(snapshot, "fixed", 250, UUID.randomUUID(), "OfflineSeller");
        store.update(state -> state.listings.put(fixed.id, fixed));
        market.buy(player, fixed.id);
        if (!"sold".equals(market.findListing(fixed.id).status)
                || !economy.isPersistedBalance(player.getUuid(), BigInteger.valueOf(1_750)))
            throw new IllegalStateException("A compra fixa não concluiu a venda ou o débito CobbleDollars.");
        MarketTransaction purchaseClaim = market.claims(player.getUuid()).stream()
                .filter(value -> "purchase_delivery".equals(value.kind) && fixed.id.equals(value.listingId))
                .findFirst().orElseThrow(() -> new IllegalStateException("A compra não criou a retirada do comprador."));
        market.claim(player, purchaseClaim.id);
        if (diamondCount(player) != diamondCountBeforeSales + 2)
            throw new IllegalStateException("A compra não entregou exatamente dois diamantes.");

        MarketListing auction = testListing(snapshot, "auction", 100, UUID.randomUUID(), "OfflineAuctionSeller");
        auction.expiresAt = System.currentTimeMillis() + 60_000;
        store.update(state -> state.listings.put(auction.id, auction));
        market.bid(player, auction.id, 100);
        market.bid(player, auction.id, 200);
        MarketListing highest = market.findListing(auction.id);
        if (highest.highestBid != 200 || !player.getUuid().equals(highest.highestBidder)
                || !economy.isPersistedBalance(player.getUuid(), BigInteger.valueOf(1_450)))
            throw new IllegalStateException("O lance vencedor ou os débitos do leilão não foram salvos.");
        MarketTransaction refund = market.claims(player.getUuid()).stream()
                .filter(value -> "outbid_refund".equals(value.kind) && value.amount == 100)
                .findFirst().orElseThrow(() -> new IllegalStateException("O lance anterior não criou reembolso."));
        market.claim(player, refund.id);
        if (!economy.isPersistedBalance(player.getUuid(), BigInteger.valueOf(1_550)))
            throw new IllegalStateException("O reembolso do lance superado não foi confirmado.");

        store.update(state -> state.listings.get(auction.id).expiresAt = System.currentTimeMillis() - 1);
        var expiry = MarketService.class.getDeclaredMethod("processExpiredListings");
        expiry.setAccessible(true);
        expiry.invoke(market);
        MarketListing settled = market.findListing(auction.id);
        boolean saleRecorded = store.transactions().stream().anyMatch(value -> "auction_sale".equals(value.kind)
                && auction.id.equals(value.listingId) && "complete".equals(value.status) && value.amount == 200);
        boolean sellerPayoutPending = store.transactions().stream().anyMatch(value -> "seller_payout".equals(value.kind)
                && auction.id.equals(value.listingId) && "cash_pending".equals(value.status) && value.amount == 200);
        MarketTransaction auctionClaim = market.claims(player.getUuid()).stream()
                .filter(value -> "auction_delivery".equals(value.kind) && auction.id.equals(value.listingId))
                .findFirst().orElseThrow(() -> new IllegalStateException("O encerramento não criou a retirada do leilão."));
        if (!"sold".equals(settled.status) || !saleRecorded || !sellerPayoutPending)
            throw new IllegalStateException("A expiração não registrou venda e pagamento do leilão.");
        market.claim(player, auctionClaim.id);
        int finalDiamonds = diamondCount(player);
        if (finalDiamonds != diamondCountBeforeSales + 4 || !economy.isPersistedBalance(player.getUuid(), BigInteger.valueOf(1_550)))
            throw new IllegalStateException("A retirada do leilão ou o saldo final não confere.");
        return new MarketTransactionResults(true, true, true, BigInteger.valueOf(1_550), finalDiamonds);
    }

    private static void seedBalance(MinecraftServer server,
                                    net.minecraft.server.network.ServerPlayerEntity player,
                                    BigInteger balance) throws Exception {
        Class<?> extension = Class.forName("fr.harmex.cobbledollars.common.utils.extensions.PlayerExtensionKt");
        Method setter = extensionMethod(extension, "setCobbleDollars", 2);
        Method updater = extensionMethod(extension, "updateCobbleDollarsAccount", 1);
        setter.invoke(null, player, balance);
        updater.invoke(null, player);
        ((PlayerDataSaverAccessor) (Object) server.getPlayerManager()).generationControl$savePlayerData(player);
    }

    private static Method extensionMethod(Class<?> owner, String name, int count) throws NoSuchMethodException {
        for (Method method : owner.getMethods()) {
            if (method.getName().equals(name) && Modifier.isStatic(method.getModifiers())
                    && method.getParameterCount() == count
                    && method.getParameterTypes()[0].isAssignableFrom(
                    net.minecraft.server.network.ServerPlayerEntity.class)) return method;
        }
        throw new NoSuchMethodException(name);
    }

    private static MarketStore marketStore(MarketService market) throws ReflectiveOperationException {
        var field = MarketService.class.getDeclaredField("store");
        field.setAccessible(true);
        return (MarketStore) field.get(market);
    }

    private static MarketListing testListing(MarketItemSnapshot snapshot, String mode, long price,
                                             UUID seller, String sellerName) {
        MarketListing listing = new MarketListing();
        listing.id = UUID.randomUUID().toString();
        listing.type = "item";
        listing.mode = mode;
        listing.status = "active";
        listing.seller = seller;
        listing.sellerName = sellerName;
        listing.assetJson = ConfigStore.GSON.toJson(snapshot);
        listing.assetName = snapshot.displayName();
        listing.itemId = snapshot.itemId();
        listing.itemCount = snapshot.count();
        listing.price = price;
        listing.createdAt = System.currentTimeMillis();
        listing.expiresAt = listing.createdAt + 60_000;
        return listing;
    }

    private static int diamondCount(net.minecraft.server.network.ServerPlayerEntity player) {
        int count = 0;
        for (int index = 0; index < player.getInventory().size(); index++) {
            ItemStack stack = player.getInventory().getStack(index);
            if (stack.isOf(Items.DIAMOND)) count += stack.getCount();
        }
        return count;
    }

    private record MarketTransactionResults(boolean fixedPurchase, boolean bidsAndRefund,
                                            boolean auctionSettlement, BigInteger balance, int finalDiamonds) {}

    private static void writeResult(Map<String, Object> result) {
        try {
            ConfigStore.write(Path.of("connected-market-result.json").toAbsolutePath(), result);
        } catch (Exception failure) {
            failure.printStackTrace();
        }
    }
}
