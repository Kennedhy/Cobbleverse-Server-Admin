package br.com.cobbleverse.generations.web.market;

import br.com.cobbleverse.generations.GenerationControl;
import br.com.cobbleverse.generations.config.ConfigStore;
import br.com.cobbleverse.generations.market.CobbleDollarsEconomy;
import br.com.cobbleverse.generations.market.MarketPlayerData;
import br.com.cobbleverse.generations.integration.CobblemonIntegration;
import br.com.cobbleverse.generations.integration.LegendaryLimitManager;
import br.com.cobbleverse.generations.mixin.PlayerDataSaverAccessor;
import br.com.cobbleverse.generations.mixin.StoreFactoryAccessor;
import br.com.cobbleverse.generations.mixin.StoreManagerAccessor;
import com.cobblemon.mod.common.Cobblemon;
import com.cobblemon.mod.common.api.storage.factory.FileBackedPokemonStoreFactory;
import com.cobblemon.mod.common.api.storage.adapter.flatfile.FileStoreAdapter;
import com.cobblemon.mod.common.api.storage.adapter.flatfile.OneToOneFileStoreAdapter;
import com.cobblemon.mod.common.api.storage.party.PlayerPartyStore;
import com.cobblemon.mod.common.api.storage.pc.PCPosition;
import com.cobblemon.mod.common.api.storage.pc.PCStore;
import com.cobblemon.mod.common.api.storage.PokemonStore;
import com.cobblemon.mod.common.pokemon.Pokemon;
import com.google.gson.JsonObject;
import net.minecraft.item.ItemStack;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.NbtComponent;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.server.MinecraftServer;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;

import java.io.IOException;
import java.io.File;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

/** Bounded server-side API and transaction coordinator for the player marketplace. */
public final class MarketService {
    private static final AtomicReference<MarketService> CURRENT = new AtomicReference<>();
    private static final int PAGE_SIZE = 50;
    private static final int EXPIRATION_CHECK_INTERVAL_TICKS = 20;
    private static final long MAX_POKEMON_STORE_BYTES = 32L * 1024 * 1024;
    private static final int MAX_PENDING_OFFLINE_DEBIT_CHECKS = 8;
    private static final Set<String> PENDING_CLAIM_STATUSES = Set.of(
            "claim_pending", "asset_claim_pending", "cash_pending", "cash_claim_pending");
    private static final Set<String> TRANSACTION_HISTORY_KINDS = Set.of("purchase", "auction_sale", "bid",
            "seller_payout", "outbid_refund", "bid_refund", "admin_return", "listing_cancelled",
            "listing_expired", "purchase_delivery", "auction_delivery");
    private static final Set<String> PENDING_TRANSACTION_STATUSES = Set.of("deposit_pending", "buyer_debit_pending",
            "cash_pending", "cash_claim_pending", "claim_pending", "asset_claim_pending");
    private static final String ITEM_CLAIM_MARKER_MISSING_REASON =
            "Inventário da retirada não pôde ser confirmado; verificar com o jogador antes de qualquer intervenção.";
    private static final ThreadPoolExecutor SAVE_WAITER = new ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS,
            new LinkedBlockingQueue<>(32), task -> {
        Thread thread = new Thread(task, "cobbleverse-market-save-waiter");
        thread.setDaemon(true);
        return thread;
    }, new ThreadPoolExecutor.AbortPolicy());

    private final MinecraftServer server;
    private final MarketStore store;
    private final CobbleDollarsEconomy economy;
    private final Set<String> pendingPokemonClaims = ConcurrentHashMap.newKeySet();
    private final Set<String> pendingOfflineDebitChecks = ConcurrentHashMap.newKeySet();
    private int nextOfflineDebitCursor;
    private int expirationCheckTicks;
    private volatile long nextExpirationAt = Long.MAX_VALUE;

    public static void initialize(MinecraftServer server) throws IOException {
        CURRENT.set(new MarketService(server));
    }

    public static void registerRecovery() {
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> server.execute(() -> {
            MarketService active = CURRENT.get();
            if (active != null && active.server == server) active.recoverPlayer(handler.player);
        }));
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            MarketService active = CURRENT.get();
            if (active == null || active.server != server || active.nextExpirationAt == Long.MAX_VALUE) return;
            if (++active.expirationCheckTicks < EXPIRATION_CHECK_INTERVAL_TICKS) return;
            active.expirationCheckTicks = 0;
            if (System.currentTimeMillis() >= active.nextExpirationAt) active.processExpiredListings();
        });
    }

    public static void clear(MinecraftServer server) {
        MarketService active = CURRENT.get();
        if (active != null && active.server == server) CURRENT.compareAndSet(active, null);
    }

    public static MarketService current() {
        MarketService active = CURRENT.get();
        if (active == null) throw new IllegalStateException("O mercado do servidor ainda não foi inicializado.");
        return active;
    }

    private MarketService(MinecraftServer server) throws IOException {
        this.server = server;
        this.store = new MarketStore(GenerationControl.store().directory().resolve("market"));
        this.economy = new CobbleDollarsEconomy(server);
        processExpiredListings();
    }

    public boolean economyAvailable() { return economy.isAvailable(); }

    /** Small bounded snapshot used to include Pokémon in durable GTS custody in world-wide caps. */
    public List<MarketPokemonSnapshot> escrowedPokemon() {
        requireServerThread();
        Map<UUID, MarketPokemonSnapshot> escrow = new LinkedHashMap<>();
        for (MarketListing listing : store.listings()) {
            if ("pokemon".equals(listing.type) && "active".equals(listing.status))
                addPokemonEscrow(escrow, listing.assetJson);
        }
        for (MarketTransaction transaction : store.transactions()) {
            if ("pokemon".equals(transaction.assetType) && PENDING_TRANSACTION_STATUSES.contains(transaction.status))
                addPokemonEscrow(escrow, transaction.assetJson);
        }
        return List.copyOf(escrow.values());
    }

    private static void addPokemonEscrow(Map<UUID, MarketPokemonSnapshot> target, String serialized) {
        if (serialized == null || serialized.isBlank()) return;
        MarketPokemonSnapshot snapshot;
        try { snapshot = ConfigStore.GSON.fromJson(serialized, MarketPokemonSnapshot.class); }
        catch (RuntimeException invalid) { throw new IllegalStateException("Dados de Pokémon em custódia do mercado estão inválidos.", invalid); }
        if (snapshot == null || snapshot.pokemonUuid() == null || snapshot.species() == null)
            throw new IllegalStateException("Dados de Pokémon em custódia do mercado estão incompletos.");
        target.putIfAbsent(snapshot.pokemonUuid(), snapshot);
    }

    public void reconcilePlayer(ServerPlayerEntity player) {
        requireServerThread();
        recoverPlayer(player);
    }

    public Map<String, Object> settingsView() {
        requireServerThread();
        return settingsMap(store.settings());
    }

    public Map<String, Object> updateSettings(JsonObject payload) throws IOException {
        requireServerThread();
        if (payload == null) throw new IllegalArgumentException("Configuração do mercado ausente.");
        MarketSettings next = ConfigStore.GSON.fromJson(payload, MarketSettings.class);
        if (next == null) throw new IllegalArgumentException("Configuração do mercado inválida.");
        return settingsMap(store.updateSettings(next));
    }

    public Map<String, Object> listingsPage(String cursor, String search, String type, String status) {
        requireServerThread();
        int offset = parseCursor(cursor);
        String normalizedSearch = search == null ? "" : search.strip().toLowerCase(java.util.Locale.ROOT);
        String normalizedType = type == null || type.isBlank() ? "all" : type;
        String normalizedStatus = status == null || status.isBlank() ? "active" : status;
        if (!List.of("all", "pokemon", "item").contains(normalizedType))
            throw new IllegalArgumentException("Filtro de tipo inválido.");
        if (!List.of("all", "active", "sold", "removed", "expired", "cancelled").contains(normalizedStatus))
            throw new IllegalArgumentException("Filtro de estado inválido.");
        // Reuse one sorted snapshot for both the page and its summary. The old path
        // sorted the same listings twice on every dashboard refresh.
        List<MarketListing> allListings = store.listings();
        List<MarketListing> filtered = allListings.stream()
                .filter(listing -> normalizedType.equals("all") || normalizedType.equals(listing.type))
                .filter(listing -> normalizedStatus.equals("all") || normalizedStatus.equals(listing.status))
                .filter(listing -> normalizedSearch.isEmpty() || listingText(listing).contains(normalizedSearch))
                .toList();
        int end = Math.min(filtered.size(), offset + PAGE_SIZE);
        List<Map<String, Object>> page = filtered.subList(Math.min(offset, filtered.size()), end)
                .stream().map(this::listingView).toList();
        long active = allListings.stream().filter(listing -> "active".equals(listing.status)).count();
        long pokemon = allListings.stream().filter(listing -> "active".equals(listing.status) && "pokemon".equals(listing.type)).count();
        long items = allListings.stream().filter(listing -> "active".equals(listing.status) && "item".equals(listing.type)).count();
        List<MarketTransaction> transactionSnapshot = store.transactions();
        long claims = transactionSnapshot.stream().filter(tx -> PENDING_CLAIM_STATUSES.contains(tx.status)).count();
        LinkedHashMap<String, Object> response = new LinkedHashMap<>();
        response.put("listings", page);
        response.put("nextCursor", end < filtered.size() ? Integer.toString(end) : null);
        response.put("summary", Map.of("activeListings", active, "pokemonListings", pokemon,
                "itemListings", items, "pendingClaims", claims));
        response.put("economyAvailable", economy.isAvailable());
        return response;
    }

    public Map<String, Object> transactionsPage(String cursor) {
        return transactionsPage(cursor, null);
    }

    public Map<String, Object> transactionsPage(String cursor, String pendingCursor) {
        requireServerThread();
        int offset = parseCursor(cursor);
        int pendingOffset = parseCursor(pendingCursor);
        // transactions() returns an immutable point-in-time copy; use it for both
        // history and pending operations instead of copying the journal twice.
        List<MarketTransaction> transactionSnapshot = store.transactions();
        List<MarketTransaction> all = transactionSnapshot.stream()
                .filter(tx -> "complete".equals(tx.status) && TRANSACTION_HISTORY_KINDS.contains(tx.kind))
                .toList();
        int end = Math.min(all.size(), offset + PAGE_SIZE);
        List<Map<String, Object>> page = new ArrayList<>();
        for (int index = all.size() - 1 - offset; index >= Math.max(0, all.size() - end); index--) {
            MarketTransaction tx = all.get(index);
            MarketListing listing = store.listing(tx.listingId);
            LinkedHashMap<String, Object> row = new LinkedHashMap<>();
            row.put("id", nullSafe(tx.id));
            row.put("kind", nullSafe(tx.kind));
            row.put("type", nullSafe(tx.assetType));
            row.put("displayName", nullSafe(tx.assetName));
            row.put("sellerName", listing == null ? nullSafe(tx.counterpartyName) : nullSafe(listing.sellerName));
            row.put("buyerName", nullSafe(tx.actorName));
            row.put("actorName", nullSafe(tx.actorName));
            row.put("counterpartyName", nullSafe(tx.counterpartyName));
            row.put("reason", nullSafe(tx.reason));
            row.put("price", tx.amount);
            row.put("status", nullSafe(tx.status));
            row.put("createdAt", tx.createdAt);
            row.put("completedAt", tx.completedAt > 0 ? tx.completedAt : tx.createdAt);
            page.add(row);
        }
        List<MarketTransaction> pendingAll = transactionSnapshot.stream()
                .filter(tx -> PENDING_TRANSACTION_STATUSES.contains(tx.status))
                .sorted(java.util.Comparator.comparingLong((MarketTransaction tx) -> tx.createdAt).reversed())
                .toList();
        int pendingEnd = Math.min(pendingAll.size(), pendingOffset + PAGE_SIZE);
        List<Map<String, Object>> pending = pendingAll.subList(Math.min(pendingOffset, pendingAll.size()), pendingEnd)
                .stream().map(this::pendingTransactionView).toList();
        return Map.of("transactions", page,
                "pendingOperations", pending,
                "nextCursor", end < all.size() ? Integer.toString(end) : "",
                "nextPendingCursor", pendingEnd < pendingAll.size() ? Integer.toString(pendingEnd) : "");
    }

    private Map<String, Object> pendingTransactionView(MarketTransaction tx) {
        LinkedHashMap<String, Object> view = new LinkedHashMap<>();
        view.put("id", nullSafe(tx.id));
        view.put("kind", nullSafe(tx.kind));
        view.put("type", nullSafe(tx.assetType));
        view.put("displayName", nullSafe(tx.assetName));
        view.put("actorName", nullSafe(tx.actorName));
        view.put("counterpartyName", nullSafe(tx.counterpartyName));
        view.put("amount", tx.amount);
        view.put("status", nullSafe(tx.status));
        view.put("createdAt", tx.createdAt);
        view.put("reason", nullSafe(tx.reason));
        view.put("balanceBefore", nullSafe(tx.actorBalanceBefore));
        view.put("balanceOperation", nullSafe(tx.balanceOperation));
        if (tx.actorBalanceBefore != null && ("debit".equals(tx.balanceOperation) || "credit".equals(tx.balanceOperation))) {
            try {
                BigInteger before = new BigInteger(tx.actorBalanceBefore);
                BigInteger amount = BigInteger.valueOf(tx.amount);
                BigInteger expected = "debit".equals(tx.balanceOperation) ? before.subtract(amount) : before.add(amount);
                view.put("balanceExpected", expected.toString());
                BigInteger observed = tx.actor == null ? null : economy.readBalance(tx.actor).orElse(null);
                view.put("balanceObserved", observed == null ? "" : observed.toString());
                view.put("balanceState", observed == null ? "offline" : observed.equals(before) ? "before"
                        : observed.equals(expected) ? "expected" : "conflict");
            } catch (NumberFormatException ignored) {
                view.put("balanceExpected", "");
            }
        }
        return view;
    }

    public void removeListing(String id, String reason, UUID moderator, String moderatorName) throws IOException {
        requireServerThread();
        MarketListing listing = store.listing(id);
        if (listing == null || !"active".equals(listing.status))
            throw new IllegalArgumentException("O anúncio não está mais ativo.");
        String note = reason == null ? "" : reason.strip();
        if (note.isBlank() || note.length() > 160)
            throw new IllegalArgumentException("Informe um motivo com até 160 caracteres para remover o anúncio.");
        ensureMarketCapacity(outstandingListings(), 0);
        store.update(state -> {
            MarketListing current = state.listings.get(id);
            if (current == null || !"active".equals(current.status))
                throw new IllegalArgumentException("O anúncio não está mais ativo.");
            if (hasPendingDebit(state, id))
                throw new IllegalStateException("Este anúncio está conciliando um pagamento. Tente novamente após o jogador reconectar.");
            current.status = "removed";
            current.revision++;
            MarketTransaction claim = transaction("admin_return", "claim_pending", current, current.seller,
                    current.sellerName, moderator, moderatorName, 0);
            claim.assetJson = current.assetJson;
            claim.assetType = current.type;
            claim.assetName = current.assetName;
            claim.reason = note;
            state.transactions.add(claim);
            if (current.highestBidder != null && current.highestBid > 0) {
                MarketTransaction refund = transaction("bid_refund", "cash_pending", current,
                        current.highestBidder, current.highestBidderName, moderator, moderatorName, current.highestBid);
                refund.reason = note;
                state.transactions.add(refund);
            }
            current.assetJson = "";
        });
        refreshExpirationDeadline();
    }

    public List<MarketListing> browse(int page) {
        requireServerThread();
        if (page < 0 || page > 10_000) throw new IllegalArgumentException("Página inválida.");
        List<MarketListing> active = store.listings().stream().filter(listing -> "active".equals(listing.status)).toList();
        int from = Math.min(active.size(), page * 45);
        return active.subList(from, Math.min(active.size(), from + 46));
    }

    public MarketListing findListing(String id) {
        requireServerThread();
        return store.listing(id);
    }

    public List<MarketListing> myListings(UUID owner) {
        return myListings(owner, 0);
    }

    public List<MarketListing> myListings(UUID owner, int page) {
        requireServerThread();
        int offset = playerPageOffset(page);
        return store.listings().stream().filter(listing -> owner.equals(listing.seller)
                        && "active".equals(listing.status))
                .skip(offset).limit(46).toList();
    }

    public MarketSettings settings() {
        requireServerThread();
        return store.settings();
    }

    public void listPokemon(ServerPlayerEntity player, int boxIndex, int slotIndex,
                            String mode, long amount, int durationMinutes, String expectedPokemonUuid) throws IOException {
        requireServerThread();
        MarketSettings settings = requireListingSettings("pokemon", mode, amount, durationMinutes);
        ensureListingQuota(player.getUuid(), settings);
        PCStore pc = Cobblemon.INSTANCE.getStorage().getPC(player);
        if (boxIndex < 0 || boxIndex >= pc.getBoxes().size() || slotIndex < 0 || slotIndex >= 30)
            throw new IllegalArgumentException("Caixa ou espaço do PC inválido.");
        PCPosition position = new PCPosition(boxIndex, slotIndex);
        Pokemon pokemon = pc.get(position);
        if (pokemon == null) throw new IllegalArgumentException("O espaço selecionado do PC está vazio.");
        if (expectedPokemonUuid == null || !pokemon.getUuid().toString().equals(expectedPokemonUuid))
            throw new IllegalArgumentException("O Pokémon selecionado mudou de espaço. Atualize a lista do PC e tente novamente.");
        if (!CobblemonIntegration.allowed(pokemon))
            throw new IllegalArgumentException("Este Pokémon não atende às regras de espécies ativas do servidor.");
        MarketPokemonSnapshot snapshot = MarketPokemonSnapshot.capture(pokemon, player.getRegistryManager());
        ensureMarketCapacity(outstandingListings() + 1, 1);
        FileBackedPokemonStoreFactory<?> factory = provider(pc);
        String summary = ConfigStore.GSON.toJson(snapshot);
        MarketTransaction intent = depositIntent(player, "pokemon", mode, amount,
                expiration(settings, mode, durationMinutes), boxIndex, slotIndex, pokemon.getUuid().toString(),
                summary, summary, pokemon.getNickname() == null
                        ? pokemon.getSpecies().getName() : pokemon.getNickname().getString());
        store.update(state -> state.transactions.add(intent));
        if (!pc.remove(position)) {
            cancelDeposit(intent.id);
            throw new IllegalArgumentException("O Pokémon mudou de espaço. Atualize o PC e tente novamente.");
        }
        LegendaryLimitManager.preserveEscrowedPokemon(pokemon, player.getUuid());
        factory.save(pc, player.getRegistryManager());
        waitForSave(factory, PCStore.class, pc.getUuid(), pokemon.getUuid(), intent.id, player,
                "Pokémon reservado para o mercado.");
    }

    public String heldItemFingerprint(ServerPlayerEntity player) {
        requireServerThread();
        ItemStack held = player.getInventory().getStack(player.getInventory().selectedSlot);
        if (held.isEmpty()) throw new IllegalArgumentException("Segure o item que deseja anunciar.");
        if (MarketItemSnapshot.hasClaimMarker(held))
            throw new IllegalArgumentException("Este item contém um marcador interno de retirada e não pode ser anunciado.");
        return ConfigStore.GSON.toJson(MarketItemSnapshot.capture(held, player.getRegistryManager()));
    }

    public void listHeldItem(ServerPlayerEntity player, String mode, long amount, int durationMinutes,
                             String expectedFingerprint) throws IOException {
        requireServerThread();
        MarketSettings settings = requireListingSettings("item", mode, amount, durationMinutes);
        ensureListingQuota(player.getUuid(), settings);
        int slot = player.getInventory().selectedSlot;
        ItemStack held = player.getInventory().getStack(slot);
        if (held.isEmpty()) throw new IllegalArgumentException("Segure o item que deseja anunciar.");
        if (MarketItemSnapshot.hasClaimMarker(held))
            throw new IllegalArgumentException("Este item contém um marcador interno de retirada e não pode ser anunciado.");
        MarketItemSnapshot snapshot = MarketItemSnapshot.capture(held, player.getRegistryManager());
        if (expectedFingerprint == null || !expectedFingerprint.equals(ConfigStore.GSON.toJson(snapshot)))
            throw new IllegalArgumentException("O item selecionado mudou. Segure novamente o item que deseja anunciar.");
        ensureMarketCapacity(outstandingListings() + 1, 1);
        String summary = ConfigStore.GSON.toJson(snapshot);
        long expiresAt = expiration(settings, mode, durationMinutes);
        MarketTransaction intent = depositIntent(player, "item", mode, amount, expiresAt, -1, slot,
                null, summary, summary, snapshot.displayName());
        markClaimMarker(held, intent.id);
        savePlayerInventory(player);
        if (!MarketPlayerData.inventoryContainsMarker(player, intent.id)) {
            removeClaimMarker(held);
            savePlayerInventory(player);
            throw new IllegalStateException("O servidor não confirmou a reserva do item. Tente novamente.");
        }
        try {
            store.update(state -> state.transactions.add(intent));
        } catch (IOException | RuntimeException failure) {
            removeClaimMarker(held);
            savePlayerInventory(player);
            throw failure;
        }
        held.decrement(held.getCount());
        player.getInventory().markDirty();
        savePlayerInventory(player);
        if (MarketPlayerData.inventoryContainsMarker(player, intent.id)) {
            throw new IllegalStateException("A custódia do item ainda está sendo reconciliada. Não repita o anúncio.");
        }
        finishDeposit(intent.id);
    }

    public List<MarketTransaction> claims(UUID owner) {
        return claims(owner, 0);
    }

    public List<MarketTransaction> claims(UUID owner, int page) {
        requireServerThread();
        int offset = playerPageOffset(page);
        return store.transactions().stream().filter(tx -> ("claim_pending".equals(tx.status)
                || "asset_claim_pending".equals(tx.status) || "cash_pending".equals(tx.status)
                || "cash_claim_pending".equals(tx.status))
                && owner.equals(tx.actor)).sorted(java.util.Comparator.comparingLong((MarketTransaction tx) -> tx.createdAt).reversed())
                .skip(offset).limit(46).toList();
    }

    public void cancelListing(ServerPlayerEntity owner, String id) throws IOException {
        requireServerThread();
        MarketListing listing = store.listing(id);
        if (listing == null || !owner.getUuid().equals(listing.seller) || !"active".equals(listing.status))
            throw new IllegalArgumentException("Esse anúncio não está ativo ou não pertence a você.");
        if (hasPendingDebit(id))
            throw new IllegalStateException("Este anúncio está conciliando um pagamento. Tente novamente após a reconexão do jogador.");
        if (listing.highestBid > 0) throw new IllegalArgumentException("Um leilão com lance não pode ser cancelado.");
        ensureMarketCapacity(outstandingListings(), 0);
        store.update(state -> {
            MarketListing current = state.listings.get(id);
            if (current == null || !"active".equals(current.status) || current.highestBid > 0)
                throw new IllegalArgumentException("O anúncio mudou. Atualize o mercado e tente novamente.");
            if (hasPendingDebit(state, id))
                throw new IllegalStateException("Este anúncio está conciliando um pagamento. Tente novamente após a reconexão do jogador.");
            current.status = "cancelled";
            current.revision++;
            addAssetClaim(state, current, owner.getUuid(), owner.getGameProfile().getName(), "listing_cancelled");
            current.assetJson = "";
        });
        refreshExpirationDeadline();
    }

    public void claim(ServerPlayerEntity player, String id) throws IOException {
        requireServerThread();
        MarketTransaction claim = store.transactions().stream().filter(tx -> id.equals(tx.id)
                && player.getUuid().equals(tx.actor)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Retirada pendente não encontrada."));
        if ("cash_pending".equals(claim.status) || "cash_claim_pending".equals(claim.status)) {
            claimCash(player, claim);
            return;
        }
        if (!List.of("claim_pending", "asset_claim_pending").contains(claim.status))
            throw new IllegalArgumentException("Essa retirada já foi concluída.");
        if ("pokemon".equals(claim.assetType)) claimPokemon(player, claim);
        else if ("item".equals(claim.assetType)) claimItem(player, claim);
        else throw new IllegalStateException("O tipo do bem salvo na retirada é inválido.");
    }

    public void buy(net.minecraft.server.network.ServerPlayerEntity buyer, String id) throws IOException {
        requireServerThread();
        MarketSettings settings = store.settings();
        MarketListing listing = store.listing(id);
        if (!settings.enabled || listing == null || !"active".equals(listing.status))
            throw new IllegalArgumentException("Este anúncio não está disponível.");
        if (hasPendingDebit(id)) throw new IllegalStateException("Uma transação anterior deste anúncio ainda está sendo reconciliada.");
        if (!"fixed".equals(listing.mode)) throw new IllegalArgumentException("Use Dar lance para comprar este leilão.");
        if (listing.seller.equals(buyer.getUuid())) throw new IllegalArgumentException("Você não pode comprar seu próprio anúncio.");
        if (hasPendingBalanceOperation(buyer.getUuid(), null))
            throw new IllegalStateException("Uma operação financeira anterior aguarda reconciliação antes de novas compras ou lances.");
        ensureMarketCapacity(outstandingListings(), 0);
        if (listing.expiresAt <= System.currentTimeMillis()) {
            expireListing(listing);
            throw new IllegalArgumentException("O anúncio expirou.");
        }
        BigInteger amount = BigInteger.valueOf(listing.price);
        BigInteger balance = economy.readBalance(buyer.getUuid())
                .orElseThrow(() -> new IllegalStateException("O CobbleDollars não está disponível para este jogador."));
        if (balance.compareTo(amount) < 0) throw new IllegalArgumentException("Saldo CobbleDollars insuficiente.");
        MarketTransaction intent = transaction("purchase", "buyer_debit_pending", listing,
                buyer.getUuid(), buyer.getGameProfile().getName(), listing.seller, listing.sellerName, listing.price);
        intent.actorBalanceBefore = balance.toString();
        intent.balanceOperation = "debit";
        intent.balanceOperationSequence = nextBalanceOperationSequence();
        intent.assetJson = listing.assetJson;
        store.update(state -> state.transactions.add(intent));
        refreshExpirationDeadline();
        if (!economy.tryDebitOnline(buyer.getUuid(), amount)) {
            updateTransaction(intent.id, tx -> {
                tx.status = "cancelled"; tx.assetJson = ""; tx.actorBalanceBefore = null;
                tx.balanceOperation = null; tx.balanceOperationSequence = 0;
            });
            refreshExpirationDeadline();
            throw new IllegalStateException("O saldo mudou antes da compra. Atualize o mercado e tente novamente.");
        }
        completePurchase(intent.id, listing.id);
        buyer.sendMessage(Text.literal("Compra registrada. Abra /mercado e selecione Retiradas para receber o Pokémon ou item."));
    }

    public void bid(net.minecraft.server.network.ServerPlayerEntity bidder, String id, long amount) throws IOException {
        requireServerThread();
        MarketSettings settings = store.settings();
        MarketListing listing = store.listing(id);
        if (!settings.enabled || !settings.auctions || listing == null || !"active".equals(listing.status)
                || !"auction".equals(listing.mode)) throw new IllegalArgumentException("Este leilão não está disponível.");
        if (hasPendingDebit(id)) throw new IllegalStateException("Um lance anterior ainda está sendo reconciliado.");
        if (listing.seller.equals(bidder.getUuid())) throw new IllegalArgumentException("Você não pode dar lance no próprio leilão.");
        if (hasPendingBalanceOperation(bidder.getUuid(), null))
            throw new IllegalStateException("Uma operação financeira anterior aguarda reconciliação antes de novas compras ou lances.");
        ensureMarketCapacity(outstandingListings(), 0);
        if (listing.expiresAt <= System.currentTimeMillis()) {
            expireListing(listing);
            throw new IllegalArgumentException("O leilão encerrou.");
        }
        long minimum = listing.highestBid > 0 ? listing.highestBid + settings.minBidIncrement : listing.price;
        if (amount < minimum || amount > 1_000_000_000L)
            throw new IllegalArgumentException("O próximo lance mínimo é " + minimum + " CobbleDollars.");
        ensureMarketCapacity(outstandingListings(), 2);
        BigInteger value = BigInteger.valueOf(amount);
        BigInteger balance = economy.readBalance(bidder.getUuid())
                .orElseThrow(() -> new IllegalStateException("O CobbleDollars não está disponível para este jogador."));
        if (balance.compareTo(value) < 0) throw new IllegalArgumentException("Saldo CobbleDollars insuficiente.");
        MarketTransaction intent = transaction("bid", "buyer_debit_pending", listing,
                bidder.getUuid(), bidder.getGameProfile().getName(), listing.seller, listing.sellerName, amount);
        intent.actorBalanceBefore = balance.toString();
        intent.balanceOperation = "debit";
        intent.balanceOperationSequence = nextBalanceOperationSequence();
        intent.assetJson = listing.assetJson;
        intent.reason = Long.toString(listing.highestBid);
        store.update(state -> state.transactions.add(intent));
        refreshExpirationDeadline();
        if (!economy.tryDebitOnline(bidder.getUuid(), value)) {
            updateTransaction(intent.id, tx -> {
                tx.status = "cancelled"; tx.assetJson = ""; tx.actorBalanceBefore = null;
                tx.balanceOperation = null; tx.balanceOperationSequence = 0;
            });
            refreshExpirationDeadline();
            throw new IllegalStateException("O saldo mudou antes do lance. Atualize o leilão e tente novamente.");
        }
        completeBid(intent.id, listing.id);
        bidder.sendMessage(Text.literal("Lance registrado. O valor fica reservado até o encerramento do leilão."));
    }

    private Map<String, Object> listingView(MarketListing listing) {
        LinkedHashMap<String, Object> view = new LinkedHashMap<>();
        view.put("id", listing.id);
        view.put("type", listing.type);
        view.put("mode", listing.mode);
        view.put("status", listing.status);
        view.put("seller", listing.seller == null ? "" : listing.seller.toString());
        view.put("sellerName", listing.sellerName);
        view.put("assetName", listing.assetName);
        view.put("price", listing.price);
        view.put("hasBids", listing.highestBid > 0);
        view.put("startingBid", listing.price);
        view.put("highestBid", listing.highestBid);
        view.put("currentBid", listing.highestBid);
        view.put("createdAt", listing.createdAt);
        view.put("expiresAt", listing.expiresAt);
        if ("pokemon".equals(listing.type)) {
            MarketPokemonDetails details = listing.pokemonDetails;
            if (details == null && listing.assetJson != null && !listing.assetJson.isBlank()) {
                MarketPokemonSnapshot pokemon = ConfigStore.GSON.fromJson(listing.assetJson, MarketPokemonSnapshot.class);
                if (pokemon != null) details = MarketPokemonDetails.from(pokemon);
            }
            if (details != null) {
                view.put("species", details.species());
                view.put("speciesName", details.species());
                view.put("nickname", details.nickname());
                view.put("form", details.form());
                view.put("level", details.level());
                view.put("shiny", details.shiny());
                view.put("nature", details.nature());
                view.put("size", details.scaleModifier());
                view.put("ivs", details.ivs());
                view.put("evs", details.evs());
            }
        } else {
            if (listing.itemCount > 0) {
                view.put("itemId", nullSafe(listing.itemId));
                view.put("itemName", listing.assetName);
                view.put("count", listing.itemCount);
            } else if (listing.assetJson != null && !listing.assetJson.isBlank()) {
                MarketItemSnapshot item = ConfigStore.GSON.fromJson(listing.assetJson, MarketItemSnapshot.class);
                if (item != null) {
                    view.put("itemId", item.itemId());
                    view.put("itemName", item.displayName());
                    view.put("count", item.count());
                }
            }
        }
        return view;
    }

    private MarketSettings requireListingSettings(String type, String mode, long amount, int durationMinutes) {
        MarketSettings settings = store.settings();
        if (!settings.enabled) throw new IllegalStateException("O mercado está desativado pelo servidor.");
        if (("pokemon".equals(type) && !settings.pokemonSales) || ("item".equals(type) && !settings.itemSales))
            throw new IllegalStateException("Esse tipo de anúncio está desativado pelo servidor.");
        if (!"fixed".equals(mode) && !"auction".equals(mode)) throw new IllegalArgumentException("Tipo de anúncio inválido.");
        if ("auction".equals(mode) && !settings.auctions) throw new IllegalStateException("Leilões estão desativados.");
        if (amount < 1 || amount > 1_000_000_000L) throw new IllegalArgumentException("O valor deve ficar entre 1 e 1.000.000.000 CobbleDollars.");
        if ("auction".equals(mode) && (durationMinutes < settings.auctionMinDurationMinutes
                || durationMinutes > settings.auctionMaxDurationHours * 60))
            throw new IllegalArgumentException("A duração deste leilão está fora dos limites do servidor.");
        if (!economy.isAvailable()) throw new IllegalStateException("CobbleDollars precisa estar instalado para usar o mercado.");
        return settings;
    }

    private void ensureListingQuota(UUID seller, MarketSettings settings) throws IOException {
        processExpiredListings();
        if (store.listings().size() >= 100) store.update(MarketService::pruneClosedListings);
        if (store.listings().size() >= 100) throw new IllegalStateException("O mercado atingiu o limite global de registros de anúncios.");
        long active = store.listings().stream().filter(listing -> "active".equals(listing.status)
                && seller.equals(listing.seller)).count()
                + store.transactions().stream().filter(tx -> "deposit_pending".equals(tx.status)
                && seller.equals(tx.actor)).count();
        if (active >= settings.maxListingsPerPlayer) throw new IllegalStateException("Você atingiu o limite de anúncios ativos.");
        long reserved = store.listings().stream().filter(listing -> "active".equals(listing.status)).count()
                + store.transactions().stream().filter(tx -> "deposit_pending".equals(tx.status)).count();
        if (reserved >= 100) throw new IllegalStateException("O mercado atingiu o limite global de anúncios ativos.");
    }

    private int outstandingListings() {
        int active = (int) store.listings().stream().filter(listing -> "active".equals(listing.status)).count();
        int deposits = (int) store.transactions().stream().filter(tx -> "deposit_pending".equals(tx.status)).count();
        return active + deposits;
    }

    private void ensureMarketCapacity(int futureListings, int additionalTransactions) throws IOException {
        long required = (long) futureListings * 3L + additionalTransactions;
        if (futureListings < 0 || required > MarketStore.MAX_TRANSACTIONS)
            throw new IllegalStateException("O mercado atingiu o limite de operações pendentes; conclua retiradas antes de continuar.");
        store.ensureFreeTransactions((int) required);
    }

    private static int playerPageOffset(int page) {
        if (page < 0 || page > 10_000) throw new IllegalArgumentException("Página inválida.");
        return page * 45;
    }

    private static long expiration(MarketSettings settings, String mode, int durationMinutes) {
        long durationMillis = "auction".equals(mode) ? TimeUnit.MINUTES.toMillis(durationMinutes)
                : TimeUnit.HOURS.toMillis(settings.listingDurationHours);
        return System.currentTimeMillis() + durationMillis;
    }

    private MarketTransaction depositIntent(ServerPlayerEntity player, String type, String mode,
                                            long amount, long expiresAt, int box, int slot,
                                            String pokemonUuid, String assetJson, String summary, String name) {
        MarketTransaction tx = new MarketTransaction();
        tx.id = UUID.randomUUID().toString();
        tx.kind = "deposit";
        tx.status = "deposit_pending";
        tx.actor = player.getUuid();
        tx.actorName = player.getGameProfile().getName();
        tx.assetType = type;
        tx.assetName = name;
        tx.assetJson = assetJson;
        tx.sourceFingerprint = summary;
        tx.sourceBox = box;
        tx.sourceSlot = slot;
        tx.reason = pokemonUuid;
        tx.listingMode = mode;
        tx.amount = amount;
        tx.expiresAt = expiresAt;
        tx.createdAt = System.currentTimeMillis();
        return tx;
    }

    private void waitForSave(FileBackedPokemonStoreFactory<?> factory,
                             Class<? extends PokemonStore<?>> storeType, UUID storeId, UUID pokemonId, String intentId,
                             ServerPlayerEntity player, String message) {
        Future<Boolean> verification;
        try {
            var registries = player.getRegistryManager();
            verification = ((StoreFactoryAccessor) (Object) factory).generationControl$executor()
                    .submit(() -> Boolean.FALSE.equals(persistedStoreContains(factory, storeType, storeId, pokemonId,
                            registries)));
        } catch (java.util.concurrent.RejectedExecutionException saturated) {
            GenerationControl.LOGGER.warn("A custódia do mercado aguardará recuperação: a fila de salvamento recusou o anúncio {}", intentId);
            player.sendMessage(Text.literal("A reserva ficou pendente porque o PC está ocupado. Ela será reconciliada ao reconectar."));
            return;
        }
        try {
            CompletableFuture.supplyAsync(() -> {
                try { return verification.get(10, TimeUnit.SECONDS); }
                catch (Exception failure) { throw new java.util.concurrent.CompletionException(failure); }
            }, SAVE_WAITER).whenComplete((persisted, failure) -> server.execute(() -> {
                if (failure != null || !Boolean.TRUE.equals(persisted)) {
                    GenerationControl.LOGGER.warn("A gravação do PC do anúncio {} não pôde ser confirmada; a custódia seguirá pendente.", intentId, failure);
                    player.sendMessage(Text.literal("A reserva foi registrada e será reconciliada ao reconectar. Não repita o anúncio."));
                    return;
                }
                try {
                    finishDeposit(intentId);
                    player.sendMessage(Text.literal(message + " O anúncio já está visível no GTS."));
                } catch (IOException persistFailure) {
                    GenerationControl.LOGGER.error("Falha ao concluir depósito do mercado {}", intentId, persistFailure);
                    player.sendMessage(Text.literal("A custódia foi salva. O anúncio será concluído após reconciliação."));
                }
            }));
        } catch (java.util.concurrent.RejectedExecutionException saturated) {
            player.sendMessage(Text.literal("A fila de gravação do mercado está ocupada. A reserva ficará pendente e será reconciliada."));
        }
    }

    private void finishDeposit(String intentId) throws IOException {
        MarketTransaction intent = store.transactions().stream().filter(tx -> intentId.equals(tx.id)).findFirst().orElse(null);
        if (intent == null || !"deposit_pending".equals(intent.status)) return;
        MarketListing listing = new MarketListing();
        listing.id = UUID.randomUUID().toString();
        listing.type = intent.assetType;
        listing.mode = intent.listingMode;
        listing.status = "active";
        listing.seller = intent.actor;
        listing.sellerName = intent.actorName;
        listing.assetJson = intent.assetJson;
        listing.assetName = intent.assetName;
        if ("pokemon".equals(intent.assetType)) {
            MarketPokemonSnapshot pokemon = ConfigStore.GSON.fromJson(intent.assetJson, MarketPokemonSnapshot.class);
            listing.pokemonDetails = MarketPokemonDetails.from(pokemon);
        } else if ("item".equals(intent.assetType)) {
            MarketItemSnapshot item = ConfigStore.GSON.fromJson(intent.assetJson, MarketItemSnapshot.class);
            listing.itemId = item.itemId();
            listing.itemCount = item.count();
        }
        listing.price = intent.amount;
        listing.createdAt = intent.createdAt;
        listing.expiresAt = intent.expiresAt;
        store.update(state -> {
            MarketTransaction currentIntent = findTransaction(state, intentId);
            if (currentIntent == null || !"deposit_pending".equals(currentIntent.status)) return;
            currentIntent.status = "complete";
            currentIntent.listingId = listing.id;
            currentIntent.assetJson = "";
            currentIntent.sourceFingerprint = "";
            state.listings.put(listing.id, listing);
        });
        refreshExpirationDeadline();
    }

    private void cancelDeposit(String id) throws IOException {
        updateTransaction(id, tx -> {
            tx.status = "cancelled";
            tx.assetJson = "";
            tx.sourceFingerprint = "";
        });
    }

    private void completePurchase(String transactionId, String listingId) throws IOException {
        store.update(state -> {
            MarketTransaction purchase = findTransaction(state, transactionId);
            MarketListing listing = state.listings.get(listingId);
            if (purchase == null || listing == null || !"buyer_debit_pending".equals(purchase.status))
                throw new IllegalStateException("A compra precisa ser reconciliada antes de continuar.");
            if (!"active".equals(listing.status) || listing.price != purchase.amount)
                throw new IllegalStateException("O anúncio mudou durante a compra.");
            purchase.status = "complete";
            purchase.completedAt = System.currentTimeMillis();
            listing.status = "sold";
            listing.revision++;
            addAssetClaim(state, listing, purchase.actor, purchase.actorName, "purchase_delivery");
            MarketTransaction payout = transaction("seller_payout", "cash_pending", listing,
                    listing.seller, listing.sellerName, purchase.actor, purchase.actorName, listing.price);
            state.transactions.add(payout);
            purchase.assetJson = "";
            listing.assetJson = "";
        });
        refreshExpirationDeadline();
    }

    private void completeBid(String transactionId, String listingId) throws IOException {
        store.update(state -> {
            MarketTransaction bid = findTransaction(state, transactionId);
            MarketListing listing = state.listings.get(listingId);
            if (bid == null || listing == null || !"buyer_debit_pending".equals(bid.status)
                    || !"active".equals(listing.status) || !"auction".equals(listing.mode))
                throw new IllegalStateException("O lance precisa ser reconciliado antes de continuar.");
            long previousBid = listing.highestBid;
            UUID previousBidder = listing.highestBidder;
            String previousName = listing.highestBidderName;
            if (previousBidder != null && previousBid > 0) {
                MarketTransaction refund = transaction("outbid_refund", "cash_pending", listing,
                        previousBidder, previousName, bid.actor, bid.actorName, previousBid);
                state.transactions.add(refund);
            }
            listing.highestBid = bid.amount;
            listing.highestBidder = bid.actor;
            listing.highestBidderName = bid.actorName;
            listing.revision++;
            bid.status = "complete";
            bid.assetJson = "";
        });
        refreshExpirationDeadline();
    }

    private void expireListing(MarketListing listing) {
        if (hasPendingDebit(listing.id)) {
            refreshExpirationDeadline();
            return;
        }
        try {
            store.update(state -> {
                MarketListing current = state.listings.get(listing.id);
                if (current == null || !"active".equals(current.status) || current.expiresAt > System.currentTimeMillis()) return;
                if (hasPendingDebit(state, current.id)) return;
                if ("auction".equals(current.mode) && current.highestBidder != null && current.highestBid > 0) {
                    current.status = "sold";
                    addAssetClaim(state, current, current.highestBidder, current.highestBidderName, "auction_delivery");
                    state.transactions.add(transaction("seller_payout", "cash_pending", current, current.seller,
                            current.sellerName, current.highestBidder, current.highestBidderName, current.highestBid));
                    MarketTransaction sale = transaction("auction_sale", "complete", current,
                            current.highestBidder, current.highestBidderName, current.seller,
                            current.sellerName, current.highestBid);
                    sale.completedAt = System.currentTimeMillis();
                    state.transactions.add(sale);
                    current.assetJson = "";
                } else {
                    current.status = "expired";
                    addAssetClaim(state, current, current.seller, current.sellerName, "listing_expired");
                    current.assetJson = "";
                }
                current.revision++;
            });
            refreshExpirationDeadline();
        } catch (IOException | RuntimeException failure) {
            nextExpirationAt = System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(30);
            GenerationControl.LOGGER.error("Não foi possível encerrar o anúncio expirado {}", listing.id, failure);
        }
    }

    private void processExpiredListings() {
        schedulePendingDebitRecovery();
        List<MarketListing> all = store.listings();
        long now = System.currentTimeMillis();
        java.util.Set<String> debitPending = store.transactions().stream()
                .filter(tx -> "buyer_debit_pending".equals(tx.status) && tx.listingId != null)
                .map(tx -> tx.listingId).collect(java.util.stream.Collectors.toSet());
        List<String> expired = all.stream().filter(listing -> "active".equals(listing.status)
                && listing.expiresAt <= now && !debitPending.contains(listing.id)).map(listing -> listing.id).toList();
        if (expired.isEmpty()) {
            refreshExpirationDeadline();
            return;
        }
        try {
            store.update(state -> {
                for (String id : expired) {
                    MarketListing listing = state.listings.get(id);
                    if (listing == null || !"active".equals(listing.status) || listing.expiresAt > now) continue;
                    if (hasPendingDebit(state, id)) continue;
                    if ("auction".equals(listing.mode) && listing.highestBidder != null && listing.highestBid > 0) {
                        listing.status = "sold";
                        addAssetClaim(state, listing, listing.highestBidder, listing.highestBidderName, "auction_delivery");
                        state.transactions.add(transaction("seller_payout", "cash_pending", listing,
                                listing.seller, listing.sellerName, listing.highestBidder,
                                listing.highestBidderName, listing.highestBid));
                        MarketTransaction sale = transaction("auction_sale", "complete", listing,
                                listing.highestBidder, listing.highestBidderName, listing.seller,
                                listing.sellerName, listing.highestBid);
                        sale.completedAt = now;
                        state.transactions.add(sale);
                    } else {
                        listing.status = "expired";
                        addAssetClaim(state, listing, listing.seller, listing.sellerName, "listing_expired");
                    }
                    listing.assetJson = "";
                    listing.revision++;
                }
            });
            refreshExpirationDeadline();
        } catch (IOException | RuntimeException failure) {
            nextExpirationAt = System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(30);
            GenerationControl.LOGGER.error("Não foi possível encerrar anúncios expirados do mercado.", failure);
        }
    }

    private void refreshExpirationDeadline() {
        java.util.Set<String> debitPending = store.transactions().stream()
                .filter(tx -> "buyer_debit_pending".equals(tx.status) && tx.listingId != null)
                .map(tx -> tx.listingId).collect(java.util.stream.Collectors.toSet());
        long now = System.currentTimeMillis();
        long retryAt = now + TimeUnit.SECONDS.toMillis(30);
        nextExpirationAt = store.listings().stream().filter(listing -> "active".equals(listing.status))
                .mapToLong(listing -> debitPending.contains(listing.id)
                        ? listing.expiresAt <= now ? retryAt : Math.min(listing.expiresAt, retryAt)
                        : listing.expiresAt).min().orElse(Long.MAX_VALUE);
        boolean pendingWithoutActiveListing = store.transactions().stream()
                .filter(tx -> "buyer_debit_pending".equals(tx.status) && tx.listingId != null)
                .anyMatch(tx -> {
                    MarketListing listing = store.listing(tx.listingId);
                    return listing == null || !"active".equals(listing.status);
                });
        if (pendingWithoutActiveListing) nextExpirationAt = Math.min(nextExpirationAt, retryAt);
    }

    private void schedulePendingDebitRecovery() {
        List<MarketTransaction> pending = store.transactions().stream()
                .filter(tx -> "buyer_debit_pending".equals(tx.status) && tx.actor != null
                        && tx.actorBalanceBefore != null && tx.id != null
                        && List.of("purchase", "bid").contains(tx.kind))
                .toList();
        if (pending.isEmpty()) {
            nextOfflineDebitCursor = 0;
            return;
        }
        for (int index : MarketBalanceRecovery.rotatedOrder(pending.size(), nextOfflineDebitCursor)) {
            MarketTransaction tx = pending.get(index);
            if (pendingOfflineDebitChecks.contains(tx.id)) continue;
            if (pendingOfflineDebitChecks.size() >= MAX_PENDING_OFFLINE_DEBIT_CHECKS) break;
            if (!pendingOfflineDebitChecks.add(tx.id)) continue;
            nextOfflineDebitCursor = (index + 1) % pending.size();
            String transactionId = tx.id;
            UUID actor = tx.actor;
            String balanceBefore = tx.actorBalanceBefore;
            long amount = tx.amount;
            String kind = tx.kind;
            String listingId = tx.listingId;
            try {
                CompletableFuture.supplyAsync(() -> economy.readConsistentPersistedBalance(actor).orElse(null), SAVE_WAITER)
                        .whenComplete((persisted, failure) -> {
                            try {
                                server.execute(() -> resolveOfflinePendingDebit(transactionId, actor, balanceBefore,
                                        amount, kind, listingId, persisted, failure));
                            } catch (RuntimeException stopping) {
                                pendingOfflineDebitChecks.remove(transactionId);
                            }
                        });
            } catch (java.util.concurrent.RejectedExecutionException saturated) {
                pendingOfflineDebitChecks.remove(transactionId);
                GenerationControl.LOGGER.warn("A reconciliação offline do débito {} aguardará a fila do mercado.", transactionId);
            }
        }
    }

    private void resolveOfflinePendingDebit(String transactionId, UUID actor, String balanceBefore, long amount,
                                           String kind, String listingId, BigInteger persisted, Throwable failure) {
        try {
            if (failure != null) {
                GenerationControl.LOGGER.warn("Não foi possível ler as cópias persistidas do débito {}; a transação continua pendente.", transactionId, failure);
                return;
            }
            MarketTransaction current = store.transactions().stream()
                    .filter(tx -> transactionId.equals(tx.id) && "buyer_debit_pending".equals(tx.status))
                    .findFirst().orElse(null);
            if (current == null) return;
            ServerPlayerEntity online = server.getPlayerManager().getPlayer(actor);
            if (online != null) {
                recoverBalanceOperations(online);
                return;
            }
            BigInteger before = new BigInteger(balanceBefore);
            MarketBalanceRecovery.DebitState state = MarketBalanceRecovery.classifyDebit(
                    persisted, before, amount);
            if (state == MarketBalanceRecovery.DebitState.APPLIED) {
                if ("purchase".equals(kind)) completePurchase(transactionId, listingId);
                else if ("bid".equals(kind)) completeBid(transactionId, listingId);
                else return;
            } else if (state == MarketBalanceRecovery.DebitState.NOT_APPLIED) {
                updateTransaction(transactionId, tx -> {
                    tx.status = "cancelled";
                    tx.assetJson = "";
                    tx.actorBalanceBefore = null;
                    tx.balanceOperation = null;
                    tx.balanceOperationSequence = 0;
                });
                refreshExpirationDeadline();
            }
        } catch (Exception reconciliationFailure) {
            GenerationControl.LOGGER.warn("O débito {} continua pendente porque os dados não permitem uma reconciliação segura.", transactionId, reconciliationFailure);
        } finally {
            pendingOfflineDebitChecks.remove(transactionId);
            refreshExpirationDeadline();
        }
    }

    private boolean hasPendingDebit(String listingId) {
        return store.transactions().stream().anyMatch(tx -> "buyer_debit_pending".equals(tx.status)
                && listingId.equals(tx.listingId));
    }

    private static boolean hasPendingDebit(MarketStore.State state, String listingId) {
        return state.transactions.stream().anyMatch(tx -> "buyer_debit_pending".equals(tx.status)
                && listingId.equals(tx.listingId));
    }

    private boolean hasPendingBalanceOperation(UUID playerId, String exceptTransactionId) {
        return store.transactions().stream().anyMatch(tx -> playerId.equals(tx.actor)
                && ("buyer_debit_pending".equals(tx.status) || "cash_claim_pending".equals(tx.status))
                && !java.util.Objects.equals(exceptTransactionId, tx.id));
    }

    private void claimCash(ServerPlayerEntity player, MarketTransaction claim) throws IOException {
        if (hasPendingBalanceOperation(player.getUuid(), claim.id))
            throw new IllegalStateException("Outra operação financeira aguarda reconciliação antes desta retirada.");
        if ("cash_claim_pending".equals(claim.status) && claim.actorBalanceBefore == null)
            throw new IllegalStateException("Esta retirada não tem saldo-base para reconciliação segura; peça revisão administrativa.");
        BigInteger balance = economy.readBalance(player.getUuid())
                .orElseThrow(() -> new IllegalStateException("CobbleDollars ainda não carregou sua conta."));
        if (!"cash_claim_pending".equals(claim.status)) {
            MarketTransaction prepared = copy(claim);
            prepared.status = "cash_claim_pending";
            prepared.actorBalanceBefore = balance.toString();
            prepared.balanceOperation = "credit";
            prepared.balanceOperationSequence = nextBalanceOperationSequence();
            store.update(state -> replaceTransaction(state, prepared));
        } else if (claim.actorBalanceBefore != null) {
            BigInteger before = new BigInteger(claim.actorBalanceBefore);
            BigInteger after = before.add(BigInteger.valueOf(claim.amount));
            if (balance.equals(after) && economy.isPersistedBalance(player.getUuid(), after)) {
                updateTransaction(claim.id, tx -> tx.status = "complete");
                player.sendMessage(Text.literal("Pagamento recuperado e confirmado."));
                return;
            }
            if (balance.equals(after))
                throw new IllegalStateException("O pagamento ainda não foi confirmado no arquivo do CobbleDollars; mantenha a retirada pendente.");
            if (!balance.equals(before)) throw new IllegalStateException("O saldo mudou durante a recuperação do pagamento; peça revisão administrativa.");
            if (!economy.isPersistedBalance(player.getUuid(), before))
                throw new IllegalStateException("O saldo salvo diverge do valor anterior ao pagamento; mantenha a retirada pendente para revisão.");
        }
        if (!economy.credit(player.getUuid(), BigInteger.valueOf(claim.amount)))
            throw new IllegalStateException("O CobbleDollars não confirmou o pagamento. A retirada continua pendente.");
        updateTransaction(claim.id, tx -> tx.status = "complete");
        player.sendMessage(Text.literal("Pagamento recebido: " + claim.amount + " CobbleDollars."));
    }

    private void claimPokemon(ServerPlayerEntity player, MarketTransaction claim) throws IOException {
        MarketPokemonSnapshot snapshot = ConfigStore.GSON.fromJson(claim.assetJson, MarketPokemonSnapshot.class);
        if (snapshot == null) throw new IllegalStateException("O Pokémon salvo na retirada está inválido.");
        for (var party : Cobblemon.INSTANCE.getStorage().getParties(player.getUuid(), player.getRegistryManager())) {
            if (party.get(snapshot.pokemonUuid()) != null) {
                queuePokemonClaimSave(provider(party), party, claim.id, player, snapshot.pokemonUuid());
                return;
            }
        }
        for (PCStore pc : Cobblemon.INSTANCE.getStorage().getPCs(player.getUuid(), player.getRegistryManager())) {
            if (pc.get(snapshot.pokemonUuid()) != null) {
                queuePokemonClaimSave(provider(pc), pc, claim.id, player, snapshot.pokemonUuid());
                return;
            }
        }
        Pokemon pokemon = snapshot.restore(player.getRegistryManager());
        PokemonStore<?> destination = Cobblemon.INSTANCE.getStorage().getParty(player);
        if (destination.getFirstAvailablePosition() == null) destination = Cobblemon.INSTANCE.getStorage().getPC(player);
        if (destination.getFirstAvailablePosition() == null)
            throw new IllegalStateException("Libere um espaço na equipe ou no PC antes de retirar o Pokémon.");
        updateTransaction(claim.id, tx -> tx.status = "asset_claim_pending");
        if (!destination.add(pokemon)) {
            updateTransaction(claim.id, tx -> tx.status = "claim_pending");
            throw new IllegalStateException("O espaço para o Pokémon foi ocupado. Tente novamente.");
        }
        LegendaryLimitManager.preserveEscrowedPokemon(pokemon, player.getUuid());
        queuePokemonClaimSave(provider(destination), destination, claim.id, player, snapshot.pokemonUuid());
    }

    private void queuePokemonClaimSave(FileBackedPokemonStoreFactory<?> factory, PokemonStore<?> destination,
                                       String claimId, ServerPlayerEntity player, UUID pokemonId) {
        if (!pendingPokemonClaims.add(claimId)) {
            player.sendMessage(Text.literal("O salvamento deste Pokémon já está sendo confirmado. Aguarde antes de tentar novamente."));
            return;
        }
        try {
            factory.save(destination, player.getRegistryManager());
            waitForClaimSave(factory, claimId, player, pokemonId);
        } catch (RuntimeException failure) {
            pendingPokemonClaims.remove(claimId);
            throw failure;
        }
    }

    private void claimItem(ServerPlayerEntity player, MarketTransaction claim) throws IOException {
        ItemStack delivered = findClaimMarker(player, claim.id);
        if (delivered != null) {
            boolean persisted;
            try {
                savePlayerInventory(player);
                persisted = MarketPlayerData.inventoryContainsMarker(player, claim.id);
            } catch (IOException | RuntimeException failure) {
                flagItemClaimNeedsReview(claim.id);
                player.sendMessage(Text.literal("A retirada do item aguarda confirmação do salvamento. Tente novamente em instantes."));
                return;
            }
            if (!persisted) {
                flagItemClaimNeedsReview(claim.id);
                player.sendMessage(Text.literal("A retirada do item aguarda confirmação do salvamento. Tente novamente em instantes."));
                return;
            }
            completeAssetClaim(claim.id);
            removeClaimMarker(delivered);
            savePlayerInventory(player);
            return;
        }
        if ("asset_claim_pending".equals(claim.status)) {
            flagItemClaimNeedsReview(claim.id);
            player.sendMessage(Text.literal("Esta retirada já tentou entregar o item, mas o marcador não está no inventário. Ela ficará pendente para evitar uma entrega duplicada; peça revisão administrativa."));
            return;
        }
        MarketItemSnapshot snapshot = ConfigStore.GSON.fromJson(claim.assetJson, MarketItemSnapshot.class);
        if (snapshot == null) throw new IllegalStateException("O item salvo na retirada está inválido.");
        ItemStack item = snapshot.restore(player.getRegistryManager());
        if (player.getInventory().getEmptySlot() < 0)
            throw new IllegalStateException("Libere ao menos um espaço no inventário antes de retirar o item.");
        updateTransaction(claim.id, tx -> tx.status = "asset_claim_pending");
        markClaimMarker(item, claim.id);
        if (!player.getInventory().insertStack(item))
            throw new IllegalStateException("Libere espaço no inventário antes de retirar o item.");
        boolean persisted;
        try {
            savePlayerInventory(player);
            persisted = MarketPlayerData.inventoryContainsMarker(player, claim.id);
        } catch (IOException | RuntimeException failure) {
            flagItemClaimNeedsReview(claim.id);
            player.sendMessage(Text.literal("O item foi entregue, mas a retirada continua pendente porque o inventário salvo não pôde ser conferido. Não repita nem mova o item; peça revisão administrativa."));
            return;
        }
        if (!persisted) {
            flagItemClaimNeedsReview(claim.id);
            player.sendMessage(Text.literal("O item foi entregue; a retirada continuará pendente até o salvamento ser confirmado."));
            return;
        }
        completeAssetClaim(claim.id);
        ItemStack marked = findClaimMarker(player, claim.id);
        if (marked != null) {
            removeClaimMarker(marked);
            savePlayerInventory(player);
        }
        player.sendMessage(Text.literal("Item retirado: " + snapshot.count() + " × " + snapshot.displayName() + "."));
    }

    private void waitForClaimSave(FileBackedPokemonStoreFactory<?> factory, String claimId,
                                  ServerPlayerEntity player, UUID pokemonId) {
        Future<Boolean> verification;
        try {
            var registries = player.getRegistryManager();
            verification = ((StoreFactoryAccessor) (Object) factory).generationControl$executor()
                    .submit(() -> persistedPlayerStoresContain(factory, player.getUuid(), registries, pokemonId));
        } catch (java.util.concurrent.RejectedExecutionException saturated) {
            pendingPokemonClaims.remove(claimId);
            GenerationControl.LOGGER.warn("A retirada {} será reconciliada depois; a fila do PC está cheia.", claimId);
            player.sendMessage(Text.literal("O Pokémon está no PC/equipe e sua gravação será reconciliada ao reconectar."));
            return;
        }
        try {
            CompletableFuture.supplyAsync(() -> {
                try { return verification.get(10, TimeUnit.SECONDS); }
                catch (Exception failure) { throw new java.util.concurrent.CompletionException(failure); }
            }, SAVE_WAITER).whenComplete((persisted, failure) -> server.execute(() -> {
                try {
                    if (failure != null) throw new IllegalStateException("O salvamento do PC ainda está pendente.", failure);
                    if (!Boolean.TRUE.equals(persisted)) throw new IllegalStateException("O arquivo salvo ainda não contém o Pokémon retirado.");
                    completeAssetClaim(claimId);
                    player.sendMessage(Text.literal("Pokémon retirado e salvo no seu PC/equipe."));
                } catch (Exception saveFailure) {
                    GenerationControl.LOGGER.error("Retirada de Pokémon pendente para {}", player.getUuid(), saveFailure);
                    player.sendMessage(Text.literal("O Pokémon foi adicionado e a retirada será confirmada ao reconectar."));
                } finally {
                    pendingPokemonClaims.remove(claimId);
                }
            }));
        } catch (java.util.concurrent.RejectedExecutionException saturated) {
            pendingPokemonClaims.remove(claimId);
            player.sendMessage(Text.literal("O salvamento foi iniciado. A retirada será confirmada depois que a fila esvaziar."));
        }
    }

    private static boolean persistedPlayerStoresContain(FileBackedPokemonStoreFactory<?> factory,
                                                         UUID playerId, net.minecraft.registry.DynamicRegistryManager registries,
                                                         UUID pokemonId) throws IOException {
        IOException unreadable = null;
        for (Class<? extends PokemonStore<?>> storeType : List.of(PCStore.class, PlayerPartyStore.class)) {
            try {
                if (Boolean.TRUE.equals(persistedStoreContains(factory, storeType, playerId, pokemonId, registries))) return true;
            } catch (IOException failure) {
                if (unreadable == null) unreadable = failure;
                else unreadable.addSuppressed(failure);
            }
        }
        if (unreadable != null) throw new IOException("Não foi possível confirmar os arquivos persistidos do PC/equipe.", unreadable);
        return false;
    }

    private static Boolean persistedStoreContains(FileBackedPokemonStoreFactory<?> factory,
                                                   Class<? extends PokemonStore<?>> storeType,
                                                   UUID storeId, UUID pokemonId,
                                                   net.minecraft.registry.DynamicRegistryManager registries) throws IOException {
        FileStoreAdapter<?> adapter = ((StoreFactoryAccessor) (Object) factory).generationControl$adapter();
        if (!(adapter instanceof OneToOneFileStoreAdapter<?> fileAdapter))
            throw new IOException("O adapter do Cobblemon não permite verificar o arquivo persistido.");
        File file = fileAdapter.getFile(storeType, storeId);
        if (!file.isFile() || file.length() == 0) return null;
        if (file.length() > MAX_POKEMON_STORE_BYTES)
            throw new IOException("O arquivo do PC/equipe excede o limite de verificação do mercado.");
        PokemonStore<?> persisted;
        try {
            persisted = loadPersistedStore(fileAdapter, file, storeType, storeId, registries);
        } catch (RuntimeException invalid) {
            throw new IOException("O arquivo persistido do Pokémon não pôde ser verificado.", invalid);
        }
        if (persisted == null) throw new IOException("O Cobblemon não conseguiu ler o arquivo persistido do Pokémon.");
        return persisted.get(pokemonId) != null;
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static PokemonStore<?> loadPersistedStore(OneToOneFileStoreAdapter<?> adapter, File file,
                                                       Class<? extends PokemonStore<?>> storeType, UUID storeId,
                                                       net.minecraft.registry.DynamicRegistryManager registries) {
        return adapter.load(file, (Class) storeType, storeId, registries);
    }

    private static void addAssetClaim(MarketStore.State state, MarketListing listing,
                                      UUID recipient, String recipientName, String kind) {
        MarketTransaction claim = transaction(kind, "claim_pending", listing, recipient, recipientName,
                listing.seller, listing.sellerName, 0);
        claim.assetJson = listing.assetJson;
        claim.assetType = listing.type;
        claim.assetName = listing.assetName;
        state.transactions.add(claim);
    }

    private void completeAssetClaim(String id) throws IOException {
        updateTransaction(id, tx -> {
            tx.status = "complete";
            tx.assetJson = "";
            if (ITEM_CLAIM_MARKER_MISSING_REASON.equals(tx.reason)) tx.reason = "";
        });
    }

    private void flagItemClaimNeedsReview(String id) throws IOException {
        MarketTransaction current = store.transactions().stream()
                .filter(tx -> id.equals(tx.id) && List.of("claim_pending", "asset_claim_pending").contains(tx.status))
                .findFirst().orElse(null);
        if (current != null && (!"asset_claim_pending".equals(current.status)
                || !ITEM_CLAIM_MARKER_MISSING_REASON.equals(current.reason)))
            updateTransaction(id, tx -> {
                tx.status = "asset_claim_pending";
                tx.reason = ITEM_CLAIM_MARKER_MISSING_REASON;
            });
    }

    private static void pruneClosedListings(MarketStore.State state) {
        List<String> closed = state.listings.values().stream()
                .filter(listing -> !"active".equals(listing.status))
                .filter(listing -> state.transactions.stream().noneMatch(tx -> listing.id.equals(tx.listingId)
                        && ("claim_pending".equals(tx.status) || "asset_claim_pending".equals(tx.status)
                        || "cash_pending".equals(tx.status) || "cash_claim_pending".equals(tx.status))))
                .sorted(java.util.Comparator.comparingLong(listing -> listing.createdAt))
                .map(listing -> listing.id).toList();
        for (String id : closed) {
            if (state.listings.size() < 75) break;
            state.listings.remove(id);
        }
    }

    private static FileBackedPokemonStoreFactory<?> provider(PokemonStore<?> store) {
        for (var factory : ((StoreManagerAccessor) (Object) Cobblemon.INSTANCE.getStorage()).generationControl$factories()) {
            if (factory instanceof FileBackedPokemonStoreFactory<?> files && files.isCached(store)) return files;
        }
        throw new IllegalStateException("O mercado requer o armazenamento padrão em arquivo do Cobblemon.");
    }

    private void updateTransaction(String id, java.util.function.Consumer<MarketTransaction> change) throws IOException {
        store.update(state -> {
            MarketTransaction transaction = findTransaction(state, id);
            if (transaction == null) throw new IllegalStateException("Transação do mercado não encontrada.");
            change.accept(transaction);
        });
    }

    private static MarketTransaction findTransaction(MarketStore.State state, String id) {
        return state.transactions.stream().filter(transaction -> id.equals(transaction.id)).findFirst().orElse(null);
    }

    private static void replaceTransaction(MarketStore.State state, MarketTransaction replacement) {
        for (int index = 0; index < state.transactions.size(); index++) {
            if (replacement.id.equals(state.transactions.get(index).id)) {
                state.transactions.set(index, replacement);
                return;
            }
        }
        throw new IllegalStateException("Transação do mercado não encontrada.");
    }

    private static MarketTransaction copy(MarketTransaction source) {
        return ConfigStore.GSON.fromJson(ConfigStore.GSON.toJson(source), MarketTransaction.class);
    }

    private long nextBalanceOperationSequence() {
        return Math.addExact(store.transactions().stream()
                .mapToLong(tx -> tx.balanceOperationSequence).max().orElse(0L), 1L);
    }

    private void recoverPlayer(ServerPlayerEntity player) {
        try {
            migrateLegacyBalanceOperations(player.getUuid());
            List<MarketTransaction> transactions = store.transactions();
            for (MarketTransaction tx : transactions) {
                if (!player.getUuid().equals(tx.actor)) continue;
                if ("deposit_pending".equals(tx.status)) recoverDeposit(player, tx);
                else if ("asset_claim_pending".equals(tx.status)) recoverAssetClaim(player, tx);
            }
            recoverBalanceOperations(player);
            processExpiredListings();
            cleanupClaimMarkers(player);
        } catch (Exception failure) {
            GenerationControl.LOGGER.error("Não foi possível reconciliar as transações de mercado de {}", player.getUuid(), failure);
            player.sendMessage(Text.literal("Uma operação do mercado aguarda reconciliação. Atualize a tela de retiradas antes de tentar novamente."));
        }
    }

    private void recoverDeposit(ServerPlayerEntity player, MarketTransaction tx) throws IOException {
        if ("item".equals(tx.assetType)) {
            if (MarketPlayerData.inventoryContainsMarker(player, tx.id)) {
                cancelDeposit(tx.id);
                ItemStack marked = findClaimMarker(player, tx.id);
                if (marked != null) {
                    removeClaimMarker(marked);
                    savePlayerInventory(player);
                }
            } else finishDeposit(tx.id);
            return;
        }
        if ("pokemon".equals(tx.assetType)) {
            UUID pokemonId = UUID.fromString(tx.reason);
            for (PCStore pc : Cobblemon.INSTANCE.getStorage().getPCs(player.getUuid(), player.getRegistryManager())) {
                if (pc.get(pokemonId) != null) {
                    cancelDeposit(tx.id);
                    return;
                }
            }
            finishDeposit(tx.id);
        }
    }

    private void recoverBalanceOperations(ServerPlayerEntity player) throws IOException {
        BigInteger balance = economy.readBalance(player.getUuid()).orElse(null);
        if (balance == null) return;
        List<MarketTransaction> operations = store.transactions().stream()
                .filter(tx -> player.getUuid().equals(tx.actor) && tx.balanceOperationSequence > 0
                        && tx.actorBalanceBefore != null && ("debit".equals(tx.balanceOperation) || "credit".equals(tx.balanceOperation))
                        && ("complete".equals(tx.status) || "buyer_debit_pending".equals(tx.status)
                        || "cash_claim_pending".equals(tx.status)))
                .sorted(java.util.Comparator.comparingLong((MarketTransaction tx) -> tx.balanceOperationSequence).reversed())
                .toList();

        BigInteger simulated = balance;
        for (MarketTransaction tx : operations) {
            BigInteger before = new BigInteger(tx.actorBalanceBefore);
            BigInteger after = "debit".equals(tx.balanceOperation)
                    ? before.subtract(BigInteger.valueOf(tx.amount))
                    : before.add(BigInteger.valueOf(tx.amount));
            if ("complete".equals(tx.status)) {
                if (!simulated.equals(after)) break;
                simulated = before;
                continue;
            }
            if ("debit".equals(tx.balanceOperation)) {
                if (simulated.equals(after)) {
                    if (!economy.isPersistedBalance(player.getUuid(), after)) break;
                    if ("purchase".equals(tx.kind)) completePurchase(tx.id, tx.listingId);
                    else if ("bid".equals(tx.kind)) completeBid(tx.id, tx.listingId);
                    else break;
                    simulated = before;
                } else if (simulated.equals(before)) {
                    if (!economy.isPersistedBalance(player.getUuid(), before)) break;
                    updateTransaction(tx.id, value -> {
                        value.status = "cancelled"; value.assetJson = ""; value.actorBalanceBefore = null;
                        value.balanceOperation = null; value.balanceOperationSequence = 0;
                    });
                    simulated = before;
                } else break;
            } else if (simulated.equals(after) && economy.isPersistedBalance(player.getUuid(), after)) {
                updateTransaction(tx.id, value -> value.status = "complete");
                simulated = before;
            } else if (simulated.equals(before)) {
                if (!economy.isPersistedBalance(player.getUuid(), before)) break;
                updateTransaction(tx.id, value -> {
                    value.status = "cash_pending"; value.actorBalanceBefore = null;
                    value.balanceOperation = null; value.balanceOperationSequence = 0;
                });
                simulated = before;
            } else break;
        }
    }

    private void migrateLegacyBalanceOperations(UUID playerId) throws IOException {
        List<MarketTransaction> legacy = store.transactions().stream()
                .filter(tx -> playerId.equals(tx.actor) && tx.balanceOperationSequence <= 0
                        && tx.actorBalanceBefore != null
                        && (("buyer_debit_pending".equals(tx.status) && List.of("purchase", "bid").contains(tx.kind))
                        || "cash_claim_pending".equals(tx.status)))
                .sorted(java.util.Comparator.comparingLong((MarketTransaction tx) -> tx.createdAt))
                .toList();
        if (legacy.isEmpty()) return;
        long[] sequence = { nextBalanceOperationSequence() };
        store.update(state -> {
            for (MarketTransaction tx : legacy) {
                MarketTransaction current = findTransaction(state, tx.id);
                if (current == null || current.balanceOperationSequence > 0) continue;
                current.balanceOperation = "buyer_debit_pending".equals(current.status) ? "debit" : "credit";
                current.balanceOperationSequence = sequence[0]++;
            }
        });
    }

    private void recoverAssetClaim(ServerPlayerEntity player, MarketTransaction tx) throws IOException {
        if ("pokemon".equals(tx.assetType)) {
            MarketPokemonSnapshot snapshot = ConfigStore.GSON.fromJson(tx.assetJson, MarketPokemonSnapshot.class);
            if (snapshot != null) {
                for (var party : Cobblemon.INSTANCE.getStorage().getParties(player.getUuid(), player.getRegistryManager())) {
                    if (party.get(snapshot.pokemonUuid()) != null) {
                        queuePokemonClaimSave(provider(party), party, tx.id, player, snapshot.pokemonUuid());
                        return;
                    }
                }
                for (PCStore pc : Cobblemon.INSTANCE.getStorage().getPCs(player.getUuid(), player.getRegistryManager())) {
                    if (pc.get(snapshot.pokemonUuid()) != null) {
                        queuePokemonClaimSave(provider(pc), pc, tx.id, player, snapshot.pokemonUuid());
                        return;
                    }
                }
            }
        } else if ("item".equals(tx.assetType)) {
            ItemStack delivered = findClaimMarker(player, tx.id);
            if (delivered != null) {
                savePlayerInventory(player);
                if (MarketPlayerData.inventoryContainsMarker(player, tx.id)) {
                    completeAssetClaim(tx.id);
                    removeClaimMarker(delivered);
                    savePlayerInventory(player);
                    return;
                }
            }
            if ("asset_claim_pending".equals(tx.status)) {
                flagItemClaimNeedsReview(tx.id);
                return;
            }
        }
        updateTransaction(tx.id, value -> value.status = "claim_pending");
    }

    private void cleanupClaimMarkers(ServerPlayerEntity player) throws IOException {
        List<MarketTransaction> transactions = store.transactions();
        Map<String, MarketTransaction> transactionsById = new java.util.HashMap<>(transactions.size());
        for (MarketTransaction transaction : transactions) {
            if (transaction.id != null) transactionsById.put(transaction.id, transaction);
        }
        boolean changed = false;
        for (int slot = 0; slot < player.getInventory().size(); slot++) {
            ItemStack stack = player.getInventory().getStack(slot);
            NbtComponent customData = stack.get(DataComponentTypes.CUSTOM_DATA);
            if (customData == null) continue;
            String marker = claimMarker(customData);
            if (marker.isBlank()) continue;
            MarketTransaction claim = transactionsById.get(marker);
            boolean legacyOnly = !customData.getNbt().contains(MarketItemSnapshot.CLAIM_MARKER_KEY);
            if ((!legacyOnly || claim != null) && (claim == null || "complete".equals(claim.status) || "cancelled".equals(claim.status))) {
                removeClaimMarker(stack);
                changed = true;
            }
        }
        if (changed) savePlayerInventory(player);
    }

    private static void markClaimMarker(ItemStack stack, String transactionId) {
        NbtComponent.set(DataComponentTypes.CUSTOM_DATA, stack,
                data -> data.putString(MarketItemSnapshot.CLAIM_MARKER_KEY, transactionId));
    }

    private static void removeClaimMarker(ItemStack stack) {
        NbtComponent.set(DataComponentTypes.CUSTOM_DATA, stack, data -> {
            data.remove(MarketItemSnapshot.CLAIM_MARKER_KEY);
            data.remove("cobbleverse_market_claim");
        });
        NbtComponent data = stack.get(DataComponentTypes.CUSTOM_DATA);
        if (data != null && data.isEmpty()) stack.remove(DataComponentTypes.CUSTOM_DATA);
    }

    private static ItemStack findClaimMarker(ServerPlayerEntity player, String transactionId) {
        for (int slot = 0; slot < player.getInventory().size(); slot++) {
            ItemStack stack = player.getInventory().getStack(slot);
            NbtComponent data = stack.get(DataComponentTypes.CUSTOM_DATA);
            if (data != null && transactionId.equals(claimMarker(data))) return stack;
        }
        return null;
    }

    private static String claimMarker(NbtComponent data) {
        String marker = data.getNbt().getString(MarketItemSnapshot.CLAIM_MARKER_KEY);
        return marker.isBlank() ? data.getNbt().getString("cobbleverse_market_claim") : marker;
    }

    private static void savePlayerInventory(ServerPlayerEntity player) {
        player.getInventory().markDirty();
        ((PlayerDataSaverAccessor) (Object) player.getServer().getPlayerManager()).generationControl$savePlayerData(player);
    }


    private static MarketTransaction transaction(String kind, String status, MarketListing listing,
                                                 UUID actor, String actorName, UUID counterparty,
                                                 String counterpartyName, long amount) {
        MarketTransaction tx = new MarketTransaction();
        tx.id = UUID.randomUUID().toString();
        tx.kind = kind;
        tx.status = status;
        tx.listingId = listing.id;
        tx.actor = actor;
        tx.counterparty = counterparty;
        tx.actorName = actorName;
        tx.counterpartyName = counterpartyName;
        tx.assetType = listing.type;
        tx.assetName = listing.assetName;
        tx.amount = amount;
        tx.createdAt = System.currentTimeMillis();
        return tx;
    }

    private static Map<String, Object> settingsMap(MarketSettings settings) {
        LinkedHashMap<String, Object> view = new LinkedHashMap<>();
        view.put("enabled", settings.enabled);
        view.put("pokemonSales", settings.pokemonSales);
        view.put("itemSales", settings.itemSales);
        view.put("auctions", settings.auctions);
        view.put("maxListingsPerPlayer", settings.maxListingsPerPlayer);
        view.put("listingDurationHours", settings.listingDurationHours);
        view.put("auctionMinDurationMinutes", settings.auctionMinDurationMinutes);
        view.put("auctionMaxDurationHours", settings.auctionMaxDurationHours);
        view.put("minBidIncrement", settings.minBidIncrement);
        view.put("maxGlobalListings", MarketStore.MAX_LISTINGS);
        return view;
    }

    private static int parseCursor(String cursor) {
        if (cursor == null || cursor.isBlank()) return 0;
        try {
            int value = Integer.parseInt(cursor);
            if (value < 0 || value > 100_000) throw new NumberFormatException();
            return value;
        } catch (NumberFormatException invalid) { throw new IllegalArgumentException("Cursor inválido."); }
    }

    private static String listingText(MarketListing listing) {
        MarketPokemonDetails details = listing.pokemonDetails;
        return (nullSafe(listing.assetName) + " " + nullSafe(listing.sellerName) + " "
                + (details == null ? "" : nullSafe(details.species()) + " " + nullSafe(details.form()) + " "
                + nullSafe(details.nickname())) + " " + nullSafe(listing.itemId))
                .toLowerCase(java.util.Locale.ROOT);
    }

    private static String nullSafe(String value) { return value == null ? "" : value; }

    private void requireServerThread() {
        if (!server.isOnThread()) throw new IllegalStateException("A operação de mercado exige a thread do servidor.");
    }
}
