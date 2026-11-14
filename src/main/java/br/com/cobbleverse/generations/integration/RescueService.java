package br.com.cobbleverse.generations.integration;

import br.com.cobbleverse.generations.GenerationControl;
import br.com.cobbleverse.generations.config.ConfigStore;
import br.com.cobbleverse.generations.mixin.*;
import com.cobblemon.mod.common.Cobblemon;
import com.cobblemon.mod.common.api.events.CobblemonEvents;
import com.cobblemon.mod.common.api.pokemon.PokemonProperties;
import com.cobblemon.mod.common.api.storage.PokemonStore;
import com.cobblemon.mod.common.api.storage.adapter.flatfile.FileStoreAdapter;
import com.cobblemon.mod.common.api.storage.factory.FileBackedPokemonStoreFactory;
import com.cobblemon.mod.common.pokemon.Pokemon;
import com.google.gson.JsonObject;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.CompletableFuture;

public final class RescueService {
    private static final String PENDING="generation_control_rescue_pending";
    private static final Map<UUID, Object> deliveries = new HashMap<>();
    private static final List<String> STARTERS=List.of("bulbasaur","charmander","squirtle","chikorita","cyndaquil","totodile","treecko","torchic","mudkip","turtwig","chimchar","piplup","snivy","tepig","oshawott","chespin","fennekin","froakie","rowlet","litten","popplio","grookey","scorbunny","sobble","sprigatito","fuecoco","quaxly");
    public static final class Receipt {
        public UUID pokemonId;
        public String species;
        public String state="prepared";
        public String createdAt=Instant.now().toString();
        public JsonObject snapshot;
    }
    private RescueService() {}

    public static void register() {
        net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents.SERVER_STOPPED.register(server -> deliveries.clear());
        ServerPlayConnectionEvents.JOIN.register((handler,sender,server) -> server.execute(() -> {
            try {
                recover(handler.player);
                if (eligible(handler.player)) handler.player.sendMessage(Text.literal("Seus Pokémon foram preservados, mas estão bloqueados. Use /geracoes resgate para ver os iniciais disponíveis e /geracoes resgate <especie> para escolher."));
            } catch(Exception e) { GenerationControl.LOGGER.error("Falha ao verificar resgate de {}",handler.player.getUuid(),e); }
        }));
        CobblemonEvents.TRADE_EVENT_PRE.subscribe(event -> {
            if (pending(event.getTradeParticipant1Pokemon()) || pending(event.getTradeParticipant2Pokemon())) event.cancel();
        });
        CobblemonEvents.POKEMON_RELEASED_EVENT_PRE.subscribe(event -> {
            if (pending(event.getPokemon())) event.cancel();
        });
    }

    public static List<String> choices() {
        return STARTERS.stream().filter(InvocationGuard::speciesAllowed).toList();
    }
    public static boolean pending(Pokemon pokemon) { return pokemon!=null && pokemon.getPersistentData().getBoolean(PENDING); }
    private static Path receiptPath(UUID player) { return GenerationControl.store().directory().resolve("rescues").resolve(player+".json"); }
    private static Receipt read(UUID player) throws java.io.IOException {
        Path path=receiptPath(player);
        if (Files.notExists(path)) return null;
        if (Files.size(path) > 1024 * 1024) throw new java.io.IOException("Registro de resgate excede 1 MiB.");
        Receipt receipt = ConfigStore.GSON.fromJson(Files.readString(path), Receipt.class);
        if (receipt == null || receipt.pokemonId == null || receipt.snapshot == null
                || !("prepared".equals(receipt.state) || "delivered".equals(receipt.state)))
            throw new java.io.IOException("Registro de resgate inválido; peça ao administrador para verificar o histórico.");
        return receipt;
    }

    public static boolean eligible(ServerPlayerEntity player) throws java.io.IOException {
        if (!GenerationControl.ready() || !GenerationControl.rules().rescueStarter() || !GenerationControl.rules().restrictExisting()) return false;
        if (read(player.getUuid())!=null) return false;
        boolean ownsPokemon=false;
        var storage=Cobblemon.INSTANCE.getStorage();
        for (var party:storage.getParties(player.getUuid(),player.getRegistryManager())) for (var pokemon:party) {
            ownsPokemon=true;
            if (CobblemonIntegration.allowed(pokemon)) return false;
        }
        for (var pc:storage.getPCs(player.getUuid(),player.getRegistryManager())) for (var pokemon:pc) {
            ownsPokemon=true;
            if (CobblemonIntegration.allowed(pokemon)) return false;
        }
        return ownsPokemon && !choices().isEmpty();
    }

    public static void open(ServerPlayerEntity player) {
        try {
            recover(player);
            if (!eligible(player)) { send(player,"O resgate é único e está disponível somente quando todos os seus Pokémon estão bloqueados.",List.of()); return; }
            send(player,"Escolha um inicial de nível 5. Seus Pokémon atuais serão preservados.",choices());
        } catch(Exception e) { player.sendMessage(Text.literal("Não foi possível abrir o resgate: "+e.getMessage())); }
    }

    public static String claim(ServerPlayerEntity player,String species) throws Exception {
        Receipt receipt=read(player.getUuid());
        if (receipt!=null) {
            recover(player);
            throw new IllegalStateException("Seu resgate único já foi registrado.");
        }
        if (!eligible(player) || !choices().contains(species)) throw new IllegalArgumentException("Este inicial não está disponível para resgate.");
        var storage=Cobblemon.INSTANCE.getStorage();
        var party=storage.getParty(player);
        var pc=storage.getPC(player);
        PokemonStore<?> destination=party.getFirstAvailablePosition()!=null ? party : pc;
        if (destination.getFirstAvailablePosition()==null) throw new IllegalStateException("Libere um espaço na equipe ou no PC antes de resgatar.");
        provider(destination); // refuse before reserving the receipt if durable saving is unsupported
        var properties=PokemonProperties.Companion.parse(species+" level=5");
        Pokemon pokemon=properties.create(player);
        pokemon.setTradeable(false);
        pokemon.getPersistentData().putBoolean(PENDING,true);
        receipt=new Receipt();
        receipt.pokemonId=pokemon.getUuid();
        receipt.species=species;
        receipt.snapshot=pokemon.saveToJSON(player.getRegistryManager(),new JsonObject());
        ConfigStore.write(receiptPath(player.getUuid()),receipt);
        if (!destination.add(pokemon)) throw new IllegalStateException("O resgate está reservado; tente novamente após liberar espaço.");
        completeDelivery(destination, player, pokemon, receipt);
        return destination==party ? "Inicial reservado na equipe. Aguardando confirmação da gravação."
                : "Inicial reservado no PC. Aguardando confirmação da gravação.";
    }

    public static void recover(ServerPlayerEntity player) throws Exception {
        if (!GenerationControl.ready() || deliveries.containsKey(player.getUuid())) return;
        Receipt receipt=read(player.getUuid());
        if (receipt==null) return;
        if (receipt.pokemonId==null || receipt.snapshot==null) throw new IllegalStateException("Registro de resgate inválido; peça ao administrador para verificar o histórico.");
        var storage=Cobblemon.INSTANCE.getStorage();
        List<PokemonStore<?>> stores=new ArrayList<>();
        storage.getParties(player.getUuid(),player.getRegistryManager()).forEach(stores::add);
        storage.getPCs(player.getUuid(),player.getRegistryManager()).forEach(stores::add);
        for (var store:stores) {
            Pokemon found=store.get(receipt.pokemonId);
            if (found==null) continue;
            if (!"delivered".equals(receipt.state) || pending(found))
                completeDelivery(store, player, found, receipt);
            return;
        }
        if ("delivered".equals(receipt.state)) return; // traded or deliberately released: never grant again
        Pokemon recovered=new Pokemon().loadFromJSON(player.getRegistryManager(),receipt.snapshot);
        if (!CobblemonIntegration.allowed(recovered)) throw new IllegalStateException("O inicial reservado está bloqueado pela configuração atual. Solicite revisão ao administrador.");
        var party=storage.getParty(player);
        var pc=storage.getPC(player);
        PokemonStore<?> destination=party.getFirstAvailablePosition()!=null ? party : pc;
        if (destination.getFirstAvailablePosition()==null || !destination.add(recovered)) throw new IllegalStateException("Libere espaço para concluir o resgate reservado.");
        completeDelivery(destination, player, recovered, receipt);
    }

    private static void unlock(Pokemon pokemon) { pokemon.getPersistentData().remove(PENDING); pokemon.setTradeable(true); }
    private static FileBackedPokemonStoreFactory<?> provider(PokemonStore<?> store) {
        for (var factory:((StoreManagerAccessor)(Object)Cobblemon.INSTANCE.getStorage()).generationControl$factories())
            if (factory instanceof FileBackedPokemonStoreFactory<?> files && files.isCached(store)) return files;
        throw new IllegalStateException("O resgate requer o armazenamento padrão NBT/JSON do Cobblemon.");
    }
    @SuppressWarnings({"rawtypes","unchecked"})
    private static void completeDelivery(PokemonStore<?> store, ServerPlayerEntity player, Pokemon pokemon,
                                         Receipt receipt) throws Exception {
        UUID playerId = player.getUuid();
        if (deliveries.containsKey(playerId)) return;
        if (deliveries.size() >= 64) throw new IllegalStateException("A fila de resgates está ocupada. Seu inicial está reservado; tente novamente.");
        var access=(StoreFactoryAccessor)(Object)provider(store);
        FileStoreAdapter adapter=access.generationControl$adapter();
        Object serialized=adapter.serialize(store,player.getRegistryManager());
        var server = player.getServer();
        var worldConfig = GenerationControl.store();
        Path path = receiptPath(playerId);
        Object operation = new Object();
        deliveries.put(playerId, operation);
        CompletableFuture<Void> saved = new CompletableFuture<>();
        try {
            access.generationControl$executor().execute(() -> {
                try {
                    adapter.save(store.getClass(), store.getUuid(), serialized);
                    receipt.state = "delivered";
                    ConfigStore.write(path, receipt);
                    saved.complete(null);
                } catch (Exception failure) { saved.completeExceptionally(failure); }
            });
        } catch (RuntimeException failure) {
            deliveries.remove(playerId, operation);
            throw failure;
        }
        saved.whenComplete((ignored, failure) -> server.execute(() -> {
            if (!deliveries.remove(playerId, operation) || !GenerationControl.ready()
                    || GenerationControl.store() != worldConfig) return;
            var connected = server.getPlayerManager().getPlayer(playerId);
            if (failure != null) {
                GenerationControl.LOGGER.error("A confirmação do resgate de {} ficou pendente.", playerId, failure);
                if (connected != null) connected.sendMessage(Text.literal("Seu resgate está reservado. Use /geracoes resgate para tentar concluir a gravação."));
                return;
            }
            if (connected != player) {
                if (connected != null) try { recover(connected); }
                catch (Exception recoveryFailure) { GenerationControl.LOGGER.error("Falha ao retomar resgate de {}", playerId, recoveryFailure); }
                return;
            }
            try {
                var coordinates = pokemon.getStoreCoordinates().get();
                if (coordinates == null) throw new IllegalStateException("O Pokémon reservado mudou de armazenamento.");
                var currentStore = coordinates.getStore();
                var factory = provider(currentStore);
                unlock(pokemon);
                factory.save(currentStore, player.getRegistryManager());
                player.sendMessage(Text.literal("Gravação confirmada. Seu resgate único foi concluído."));
            } catch (Exception unlockFailure) {
                pokemon.getPersistentData().putBoolean(PENDING, true);
                pokemon.setTradeable(false);
                GenerationControl.LOGGER.error("Falha ao liberar resgate confirmado de {}", playerId, unlockFailure);
            }
        }));
    }
    private static void send(ServerPlayerEntity player,String message,List<String> choices) {
        if (!choices.isEmpty()) message += " Iniciais disponíveis: " + String.join(", ",choices) + ". Use /geracoes resgate <especie>.";
        player.sendMessage(Text.literal(message));
    }
}
