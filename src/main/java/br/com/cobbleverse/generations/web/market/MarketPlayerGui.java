package br.com.cobbleverse.generations.web.market;

import br.com.cobbleverse.generations.config.ConfigStore;
import br.com.cobbleverse.generations.integration.PcInspection;
import com.cobblemon.mod.common.api.moves.Move;
import com.cobblemon.mod.common.api.pokemon.stats.Stats;
import com.cobblemon.mod.common.item.PokemonItem;
import com.cobblemon.mod.common.pokemon.Pokemon;
import com.cobblemon.mod.common.pokemon.PokemonStats;
import com.cobblemon.mod.common.util.LocalizationUtilsKt;
import eu.pb4.sgui.api.elements.GuiElement;
import eu.pb4.sgui.api.elements.GuiElementBuilder;
import eu.pb4.sgui.api.gui.AnvilInputGui;
import eu.pb4.sgui.api.gui.SimpleGui;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.screen.ScreenHandlerType;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.MutableText;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;

/** Player-facing, server-authoritative screens for the Cobbleverse market. */
public final class MarketPlayerGui {
    private static final int PAGE_ITEMS = 45;
    private static final int PAGE_SIZE = 45;

    private MarketPlayerGui() {}

    public static void open(ServerPlayerEntity player) {
        MarketService.current().reconcilePlayer(player);
        showHome(player, "");
    }

    private static void showHome(ServerPlayerEntity player, String notice) {
        MarketService market = MarketService.current();
        MarketSettings settings = market.settings();
        SimpleGui gui = menu(player, "Mercado Cobbleverse");
        fill(gui);
        gui.setSlot(10, element(Items.BOOK, "Explorar anúncios", List.of("Pokémon e itens à venda"), () -> showBrowse(player, 0)));
        gui.setSlot(12, element(Items.EMERALD, "Vender Pokémon", List.of("Escolher uma caixa carregada do PC"), () -> showBoxes(player, 0)));
        gui.setSlot(14, element(Items.CHEST, "Vender item da mão", List.of("Anuncia a pilha na mão principal"), () -> {
            try {
                String expectedItemFingerprint = market.heldItemFingerprint(player);
                chooseMode(player, null, null, null, expectedItemFingerprint, false);
            } catch (RuntimeException failure) {
                showHome(player, failure.getMessage() == null ? "Segure o item que deseja anunciar." : failure.getMessage());
            }
        }));
        gui.setSlot(16, element(Items.WRITABLE_BOOK, "Meus anúncios", List.of("Consultar e cancelar anúncios ativos"), () -> showMyListings(player, 0)));
        gui.setSlot(31, element(Items.HOPPER, "Retiradas", List.of("Receber compras e reembolsos"), () -> showClaims(player, 0)));
        if (!settings.enabled) {
            gui.setSlot(22, element(Items.BARRIER, "Mercado fechado para novas operações", List.of("Consultas e retiradas continuam disponíveis"), null));
        } else if (!market.economyAvailable()) {
            gui.setSlot(22, element(Items.BARRIER, "Economia indisponível", List.of("CobbleDollars não está disponível"), null));
        }
        if (!notice.isBlank()) gui.setSlot(49, element(Items.PAPER, "Aviso", List.of(notice), null));
        gui.open();
    }

    private static void showBrowse(ServerPlayerEntity player, int page) {
        MarketService market = MarketService.current();
        SimpleGui gui = menu(player, "GTS · Página " + (page + 1));
        fill(gui);
        List<MarketListing> listings = market.browse(page);
        if (listings.isEmpty() && page > 0) {
            showBrowse(player, page - 1);
            return;
        }
        for (int index = 0; index < listings.size() && index < PAGE_ITEMS; index++) {
            MarketListing listing = listings.get(index);
            gui.setSlot(index, listingElement(player, listing, () -> showListing(player, listing.id, page), false));
        }
        gui.setSlot(45, element(Items.ARROW, "Voltar ao mercado", List.of(), () -> showHome(player, "")));
        if (page > 0) gui.setSlot(48, element(Items.SPECTRAL_ARROW, "Página anterior", List.of(), () -> showBrowse(player, page - 1)));
        if (listings.size() > PAGE_ITEMS)
            gui.setSlot(50, element(Items.SPECTRAL_ARROW, "Próxima página", List.of(), () -> showBrowse(player, page + 1)));
        gui.setSlot(53, element(Items.BARRIER, "Fechar", List.of(), () -> gui.close(false)));
        gui.open();
    }

    private static void showListing(ServerPlayerEntity player, String id, int page) {
        MarketListing listing = MarketService.current().findListing(id);
        if (listing == null || !"active".equals(listing.status)) {
            showHome(player, "Este anúncio não está mais disponível.");
            return;
        }
        SimpleGui gui = menu(player, "Detalhes do anúncio");
        fill(gui);
        gui.setSlot(22, listingElement(player, listing, null, true));
        if ("auction".equals(listing.mode)) {
            long minimumBid = minimumBid(listing);
            String bidLabel = listing.highestBid > 0
                    ? "Maior lance: " + listing.highestBid
                    : "Lance inicial: " + listing.price;
            gui.setSlot(30, element(Items.GOLD_INGOT, "Dar lance", List.of(bidLabel, "Próximo mínimo: " + minimumBid),
                    () -> inputAmount(player, "Valor do lance", minimumBid, amount -> run(player,
                            () -> MarketService.current().bid(player, listing.id, amount), () -> showBrowse(player, page)))));
        } else {
            gui.setSlot(30, element(Items.EMERALD, "Comprar", List.of("Preço: " + listing.price + " CobbleDollars"),
                    () -> confirm(player, "Confirmar compra", "Comprar por " + listing.price + " CobbleDollars?",
                            () -> run(player, () -> MarketService.current().buy(player, listing.id), () -> showBrowse(player, page)))));
        }
        gui.setSlot(45, element(Items.ARROW, "Voltar aos anúncios", List.of(), () -> showBrowse(player, page)));
        gui.setSlot(53, element(Items.BARRIER, "Fechar", List.of(), () -> gui.close(false)));
        gui.open();
    }

    private static void showBoxes(ServerPlayerEntity player, int boxIndex) {
        SimpleGui gui = menu(player, "Selecione um Pokémon do PC");
        fill(gui);
        PcInspection.Box box;
        Map<Integer, Pokemon> boxPokemon;
        try {
            PcInspection.LiveBox lookup = PcInspection.lookupLiveBox(player.getUuid(), boxIndex);
            box = lookup.box();
            boxPokemon = lookup.pokemon();
        } catch (RuntimeException failure) {
            showHome(player, "Não foi possível acessar o PC agora.");
            return;
        }
        if (box == null) {
            gui.setSlot(22, element(Items.BARRIER, "PC não carregado", List.of("Abra o PC no jogo e tente novamente."), null));
        } else {
            for (PcInspection.Slot slot : box.slots()) {
                int position = slot.position();
                Pokemon pokemon = boxPokemon.get(position);
                List<String> lore = new ArrayList<>(pokemon == null ? pokemonLore(slot) : pokemonLore(pokemon));
                lore.add("Clique para anunciar");
                Runnable choose = () -> chooseMode(player, box.index(), position, slot.pokemonUuid(), null, true);
                if (pokemon != null) {
                    gui.setSlot(position, pokemonElement(pokemon, lore, choose));
                } else {
                    String title = speciesLabel(slot.species()) + (slot.shiny() ? " ★" : "");
                    gui.setSlot(position, element(slot.shiny() ? Items.NETHER_STAR : Items.SNOWBALL, title, lore, choose));
                }
            }
            if (box.index() > 0) gui.setSlot(45, element(Items.SPECTRAL_ARROW, "Caixa anterior", List.of(), () -> showBoxes(player, box.index() - 1)));
            if (box.index() + 1 < box.boxCount()) gui.setSlot(48, element(Items.SPECTRAL_ARROW, "Próxima caixa", List.of(), () -> showBoxes(player, box.index() + 1)));
            gui.setSlot(50, element(Items.PAPER, "Caixa " + (box.index() + 1), List.of(box.name() == null ? "" : box.name()), null));
        }
        gui.setSlot(53, element(Items.ARROW, "Voltar ao mercado", List.of(), () -> showHome(player, "")));
        gui.open();
    }

    private static void chooseMode(ServerPlayerEntity player, Integer box, Integer slot, String expectedPokemonUuid,
                                   String expectedItemFingerprint, boolean pokemonSale) {
        MarketSettings settings = MarketService.current().settings();
        boolean enabled = pokemonSale ? settings.pokemonSales : settings.itemSales;
        if (!enabled) {
            showHome(player, pokemonSale ? "A venda de Pokémon está desativada." : "A venda de itens está desativada.");
            return;
        }
        SimpleGui gui = menu(player, "Tipo de anúncio");
        fill(gui);
        gui.setSlot(20, element(Items.EMERALD, "Preço fixo", List.of("O primeiro comprador conclui a venda"),
                () -> enterPrice(player, box, slot, expectedPokemonUuid, expectedItemFingerprint, "fixed", pokemonSale)));
        if (settings.auctions)
            gui.setSlot(24, element(Items.GOLD_INGOT, "Leilão", List.of("Informe o valor inicial e a duração"),
                    () -> enterPrice(player, box, slot, expectedPokemonUuid, expectedItemFingerprint, "auction", pokemonSale)));
        gui.setSlot(45, element(Items.ARROW, "Voltar", List.of(), () -> {
            if (pokemonSale) showBoxes(player, box == null ? 0 : box);
            else showHome(player, "");
        }));
        gui.open();
    }

    private static void enterPrice(ServerPlayerEntity player, Integer box, Integer slot, String expectedPokemonUuid,
                                   String expectedItemFingerprint, String mode, boolean pokemonSale) {
        inputAmount(player, "Preço em CobbleDollars", 100, amount -> {
            if ("auction".equals(mode)) inputDuration(player,
                    duration -> createListing(player, box, slot, expectedPokemonUuid, expectedItemFingerprint, mode, amount, duration, pokemonSale));
            else createListing(player, box, slot, expectedPokemonUuid, expectedItemFingerprint, mode, amount, 0, pokemonSale);
        });
    }

    private static void createListing(ServerPlayerEntity player, Integer box, Integer slot, String expectedPokemonUuid,
                                      String expectedItemFingerprint, String mode, long amount,
                                      int durationMinutes, boolean pokemonSale) {
        run(player, () -> {
            if (pokemonSale) MarketService.current().listPokemon(player, box, slot, mode, amount, durationMinutes, expectedPokemonUuid);
            else MarketService.current().listHeldItem(player, mode, amount, durationMinutes, expectedItemFingerprint);
        }, () -> showHome(player, pokemonSale
                ? "Pokémon em custódia; o anúncio será publicado após confirmar o salvamento do PC."
                : "Anúncio criado."));
    }

    private static void inputDuration(ServerPlayerEntity player, Consumer<Integer> accepted) {
        MarketSettings settings = MarketService.current().settings();
        long min = settings.auctionMinDurationMinutes;
        long max = (long) settings.auctionMaxDurationHours * 60;
        int initial = (int) Math.min(max, Math.max(min, 60));
        inputAmount(player, "Duração do leilão em minutos", initial, amount -> {
            if (amount < min || amount > max) {
                tell(player, "A duração precisa ficar entre " + min + " e " + max + " minutos.");
                inputDuration(player, accepted);
                return;
            }
            accepted.accept(Math.toIntExact(amount));
        });
    }

    private static void showMyListings(ServerPlayerEntity player, int page) {
        SimpleGui gui = menu(player, "Meus anúncios · Página " + (page + 1));
        fill(gui);
        List<MarketListing> listings = MarketService.current().myListings(player.getUuid(), page);
        if (listings.isEmpty() && page > 0) {
            showMyListings(player, page - 1);
            return;
        }
        for (int index = 0; index < listings.size() && index < PAGE_ITEMS; index++) {
            MarketListing listing = listings.get(index);
            gui.setSlot(index, listingElement(player, listing, () -> {
                if ("active".equals(listing.status) && "auction".equals(listing.mode) && listing.highestBid > 0)
                    tell(player, "Este leilão já recebeu lance e não pode ser cancelado.");
                else if ("active".equals(listing.status)) confirm(player, "Cancelar anúncio", "Devolver este anúncio?", () -> run(player,
                        () -> MarketService.current().cancelListing(player, listing.id), () -> showMyListings(player, page)));
                else showListing(player, listing.id, 0);
            }, false));
        }
        gui.setSlot(45, element(Items.ARROW, "Voltar ao mercado", List.of(), () -> showHome(player, "")));
        if (page > 0) gui.setSlot(48, element(Items.SPECTRAL_ARROW, "Página anterior", List.of(), () -> showMyListings(player, page - 1)));
        gui.setSlot(49, element(Items.PAPER, "Página " + (page + 1), List.of(), null));
        if (listings.size() > PAGE_ITEMS)
            gui.setSlot(50, element(Items.SPECTRAL_ARROW, "Próxima página", List.of(), () -> showMyListings(player, page + 1)));
        gui.setSlot(53, element(Items.BARRIER, "Fechar", List.of(), () -> gui.close(false)));
        gui.open();
    }

    private static void showClaims(ServerPlayerEntity player, int page) {
        SimpleGui gui = menu(player, "Retiradas e reembolsos · Página " + (page + 1));
        fill(gui);
        List<MarketTransaction> claims = MarketService.current().claims(player.getUuid(), page);
        if (claims.isEmpty() && page > 0) {
            showClaims(player, page - 1);
            return;
        }
        for (int index = 0; index < claims.size() && index < PAGE_ITEMS; index++) {
            MarketTransaction claim = claims.get(index);
            boolean cash = "cash_pending".equals(claim.status) || "cash_claim_pending".equals(claim.status);
            String instruction = "cash_claim_pending".equals(claim.status)
                    ? "Pagamento em reconciliação · clique para verificar"
                    : "asset_claim_pending".equals(claim.status)
                    ? "Salvamento em confirmação · clique para verificar"
                    : cash ? "Clique para receber" : "Clique para retirar";
            gui.setSlot(index, element(cash ? Items.EMERALD : "item".equals(claim.assetType) ? Items.CHEST : Items.SNOWBALL,
                    cash ? "Reembolso de " + claim.amount + " CobbleDollars" : safe(claim.assetName),
                    List.of(safe(claim.kind), instruction),
                    () -> run(player, () -> MarketService.current().claim(player, claim.id), () -> showClaims(player, page))));
        }
        if (claims.isEmpty()) gui.setSlot(22, element(Items.BARRIER, "Nenhuma retirada pendente", List.of(), null));
        gui.setSlot(45, element(Items.ARROW, "Voltar ao mercado", List.of(), () -> showHome(player, "")));
        if (page > 0) gui.setSlot(48, element(Items.SPECTRAL_ARROW, "Página anterior", List.of(), () -> showClaims(player, page - 1)));
        gui.setSlot(49, element(Items.PAPER, "Página " + (page + 1), List.of(), null));
        if (claims.size() > PAGE_ITEMS)
            gui.setSlot(50, element(Items.SPECTRAL_ARROW, "Próxima página", List.of(), () -> showClaims(player, page + 1)));
        gui.setSlot(53, element(Items.BARRIER, "Fechar", List.of(), () -> gui.close(false)));
        gui.open();
    }

    private static void inputAmount(ServerPlayerEntity player, String title, long initial, Consumer<Long> accepted) {
        AnvilInputGui gui = new AnvilInputGui(player, false);
        gui.setTitle(Text.literal(title));
        gui.setDefaultInputValue(Long.toString(initial));
        gui.setSlot(2, element(Items.EMERALD, "Confirmar valor", List.of("Digite apenas números inteiros positivos"),
                () -> parseAmount(player, gui.getInput(), accepted)));
        gui.open();
    }

    private static void parseAmount(ServerPlayerEntity player, String input, Consumer<Long> accepted) {
        try {
            long amount = Long.parseLong(input.strip());
            if (amount <= 0) throw new NumberFormatException();
            accepted.accept(amount);
        } catch (NumberFormatException invalid) {
            tell(player, "Digite um valor inteiro maior que zero.");
        }
    }

    private static void confirm(ServerPlayerEntity player, String title, String prompt, Runnable action) {
        SimpleGui gui = menu(player, title);
        fill(gui);
        gui.setSlot(21, element(Items.LIME_DYE, "Confirmar", List.of(prompt), action));
        gui.setSlot(23, element(Items.RED_DYE, "Voltar", List.of(), () -> showHome(player, "")));
        gui.open();
    }

    private static void run(ServerPlayerEntity player, MarketAction action, Runnable success) {
        try {
            action.run();
            success.run();
        } catch (Exception failure) {
            String message = failure.getMessage();
            showHome(player, message == null || message.isBlank() ? "Não foi possível concluir a operação." : message);
        }
    }

    @FunctionalInterface
    private interface MarketAction {
        void run() throws Exception;
    }

    private static GuiElement listingElement(ServerPlayerEntity player, MarketListing listing, Runnable click, boolean details) {
        boolean pokemonListing = "pokemon".equals(listing.type);
        Pokemon pokemon = pokemonListing ? restoreListingPokemon(player, listing) : null;
        Item icon = "item".equals(listing.type) ? Items.CHEST
                : listing.pokemonDetails != null && listing.pokemonDetails.shiny() ? Items.NETHER_STAR : Items.SNOWBALL;
        boolean auction = "auction".equals(listing.mode);
        boolean hasBid = listing.highestBid > 0;
        long amount = auction && hasBid ? listing.highestBid : listing.price;
        String price = amount + " CobbleDollars";
        List<String> lore = new ArrayList<>();
        lore.add("Vendedor: " + safe(listing.sellerName));
        if (!auction) lore.add("Preço: " + price);
        else lore.add((hasBid ? "Maior lance: " : "Lance inicial: ") + price);
        if (pokemonListing) lore.addAll(pokemon == null ? listingSummary(listing) : pokemonLore(pokemon));
        else if (details) lore.addAll(listingSummary(listing));
        if (listing.highestBid > 0) lore.add("Leilão com lance: não pode ser cancelado pelo vendedor.");
        if (listing.expiresAt > 0) lore.add("Encerra: " + new java.text.SimpleDateFormat("dd/MM HH:mm").format(listing.expiresAt));
        if (pokemon != null) return pokemonElement(pokemon, lore, click);
        return element(icon, safe(listing.assetName), lore, click);
    }

    private static Pokemon restoreListingPokemon(ServerPlayerEntity player, MarketListing listing) {
        if (listing.assetJson == null || listing.assetJson.isBlank()) return null;
        try {
            MarketPokemonSnapshot snapshot = ConfigStore.GSON.fromJson(listing.assetJson, MarketPokemonSnapshot.class);
            return snapshot == null ? null : snapshot.restore(player.getRegistryManager());
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private static List<String> listingSummary(MarketListing listing) {
        if (!"pokemon".equals(listing.type)) {
            if (listing.itemCount > 0) return List.of("Item: " + listing.assetName, "Quantidade: " + listing.itemCount);
            try {
                MarketItemSnapshot item = ConfigStore.GSON.fromJson(listing.assetJson, MarketItemSnapshot.class);
                return item == null ? List.of("Tipo: item") : List.of("Item: " + item.displayName(), "Quantidade: " + item.count());
            } catch (RuntimeException ignored) {
                return List.of("Dados detalhados indisponíveis");
            }
        }
        try {
            MarketPokemonDetails pokemon = listing.pokemonDetails;
            if (pokemon == null && listing.assetJson != null && !listing.assetJson.isBlank()) {
                MarketPokemonSnapshot snapshot = ConfigStore.GSON.fromJson(listing.assetJson, MarketPokemonSnapshot.class);
                if (snapshot != null) pokemon = MarketPokemonDetails.from(snapshot);
            }
            if (pokemon == null) return List.of();
            return List.of((pokemon.shiny() ? "Shiny · " : "") + pokemon.species() + " Nv. " + pokemon.level(),
                    "Forma: " + pokemon.form() + " · Natureza: " + pokemon.nature() + " · Tamanho: " + pokemon.scaleModifier(),
                    "IVs: " + pokemon.ivs(), "EVs: " + pokemon.evs());
        } catch (RuntimeException ignored) {
            return List.of("Dados detalhados indisponíveis");
        }
    }

    private static List<String> pokemonLore(PcInspection.Slot pokemon) {
        List<String> lore = new ArrayList<>();
        lore.add("Nível: " + pokemon.level() + (pokemon.shiny() ? " · Shiny" : ""));
        if (!pokemon.nickname().isBlank()) lore.add("Apelido: " + pokemon.nickname());
        lore.add("Natureza: " + safe(pokemon.nature()));
        if (!pokemon.ability().isBlank()) lore.add("Habilidade: " + pokemon.ability());
        if (!pokemon.form().isBlank()) lore.add("Forma: " + pokemon.form());
        lore.add("IVs: " + pokemon.ivs());
        lore.add("EVs: " + pokemon.evs());
        if (!pokemon.moves().isEmpty()) lore.add("Golpes: " + String.join(", ", pokemon.moves()));
        return lore;
    }

    private static List<String> pokemonLore(Pokemon pokemon) {
        List<String> lore = new ArrayList<>();
        lore.add("Nível: " + pokemon.getLevel() + (pokemon.getShiny() ? " · Shiny" : ""));
        if (pokemon.getNickname() != null && !pokemon.getNickname().getString().isBlank())
            lore.add("Apelido: " + pokemon.getNickname().getString());

        String ball = caughtBallName(pokemon);
        if (!ball.isBlank()) lore.add("Pokébola: " + ball);

        if (pokemon.getNature() != null)
            lore.add("Natureza: " + localizedCobblemonName(pokemon.getNature().getDisplayName()));
        if (pokemon.getAbility() != null)
            lore.add("Habilidade: " + localizedCobblemonName(pokemon.getAbility().getDisplayName()));
        if (pokemon.getForm() != null && !pokemon.getForm().getName().isBlank())
            lore.add("Forma: " + pokemon.getForm().getName());
        lore.add("Tamanho: " + String.format(Locale.ROOT, "%.2f", pokemon.getScaleModifier()));

        addStatLines(lore, "IVs", pokemon.getIvs());
        addStatLines(lore, "EVs", pokemon.getEvs());

        List<String> moves = new ArrayList<>(4);
        for (Move move : pokemon.getMoveSet().getMoves()) {
            if (move == null) continue;
            String name = move.getDisplayName().getString();
            if (!name.isBlank() && moves.size() < 4) moves.add(name);
        }
        if (!moves.isEmpty()) {
            lore.add("Golpes:");
            for (String move : moves) lore.add("  · " + move);
        }
        return lore;
    }

    private static void addStatLines(List<String> lore, String label, PokemonStats stats) {
        lore.add(label + ": HP " + stats.getOrDefault(Stats.HP)
                + " · Atq " + stats.getOrDefault(Stats.ATTACK)
                + " · Def " + stats.getOrDefault(Stats.DEFENCE));
        lore.add("  Atq. Esp. " + stats.getOrDefault(Stats.SPECIAL_ATTACK)
                + " · Def. Esp. " + stats.getOrDefault(Stats.SPECIAL_DEFENCE)
                + " · Vel " + stats.getOrDefault(Stats.SPEED));
    }

    private static String caughtBallName(Pokemon pokemon) {
        try {
            if (pokemon.getCaughtBall() == null || pokemon.getCaughtBall().item() == null) return "";
            return new ItemStack(pokemon.getCaughtBall().item()).getName().getString();
        } catch (RuntimeException ignored) {
            return "";
        }
    }

    private static String localizedCobblemonName(String name) {
        if (name == null || name.isBlank()) return "—";
        return LocalizationUtilsKt.lang(name.replace("cobblemon.", "")).getString();
    }

    private static GuiElement pokemonElement(Pokemon pokemon, List<String> lore, Runnable click) {
        GuiElementBuilder builder;
        try {
            builder = new GuiElementBuilder(PokemonItem.from(pokemon, 1)).hideDefaultTooltip();
        } catch (RuntimeException unavailableIcon) {
            builder = new GuiElementBuilder(pokemon.getShiny() ? Items.NETHER_STAR : Items.SNOWBALL);
        }
        builder.setName(pokemonTitle(pokemon));
        return element(builder, lore, click);
    }

    private static Text pokemonTitle(Pokemon pokemon) {
        MutableText title = pokemon.getSpecies().getTranslatedName().copy().formatted(Formatting.GRAY);
        if (pokemon.getShiny()) title.append(Text.literal(" ★").formatted(Formatting.GOLD));
        return title;
    }

    private static long minimumBid(MarketListing listing) {
        if (listing.highestBid <= 0) return listing.price;
        return Math.addExact(listing.highestBid, MarketService.current().settings().minBidIncrement);
    }

    private static String speciesLabel(String id) {
        int separator = id.indexOf(':');
        return separator >= 0 ? id.substring(separator + 1) : id;
    }

    private static String safe(String value) { return value == null ? "—" : value; }

    private static void tell(ServerPlayerEntity player, String message) {
        player.sendMessage(Text.literal(message), false);
    }

    private static SimpleGui menu(ServerPlayerEntity player, String title) {
        SimpleGui gui = new SimpleGui(ScreenHandlerType.GENERIC_9X6, player, false);
        gui.setTitle(Text.literal(title));
        return gui;
    }

    private static void fill(SimpleGui gui) {
        for (int slot = 0; slot < PAGE_SIZE; slot++)
            gui.setSlot(slot, new GuiElementBuilder(Items.GRAY_STAINED_GLASS_PANE).setName(Text.literal(" ")).build());
    }

    private static GuiElement element(Item item, String title, List<String> lore, Runnable action) {
        return element(new GuiElementBuilder(item).setName(Text.literal(title)), lore, action);
    }

    private static GuiElement element(GuiElementBuilder builder, List<String> lore, Runnable action) {
        List<Text> lines = new ArrayList<>();
        lore.stream().filter(line -> line != null && !line.isBlank()).forEach(line -> lines.add(Text.literal(line)));
        builder.setLore(lines);
        if (action != null) builder.setCallback((index, type, click, gui) -> action.run());
        return builder.build();
    }
}
