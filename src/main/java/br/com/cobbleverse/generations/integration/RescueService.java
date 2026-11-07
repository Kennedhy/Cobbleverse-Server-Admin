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
import java.util.concurrent.TimeUnit;

public final class RescueService {
    private static final String PENDING="generation_control_rescue_pending";
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
        return Files.exists(path) ? ConfigStore.GSON.fromJson(Files.readString(path),Receipt.class) : null;
    }

    public static boolean eligible(ServerPlayerEntity player) throws java.io.IOException {
        if (!GenerationControl.ready() || !GenerationControl.rules().config().rescueStarter || !GenerationControl.rules().restrictExisting()) return false;
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
        durableSave(destination,player);
        receipt.state="delivered";
        ConfigStore.write(receiptPath(player.getUuid()),receipt);
        unlock(pokemon);
        durableSave(destination,player);
        return destination==party ? "Inicial entregue à equipe. Resgate único concluído." : "Inicial entregue ao PC. Troque um Pokémon da equipe pelo inicial para utilizá-lo.";
    }

    public static void recover(ServerPlayerEntity player) throws Exception {
        if (!GenerationControl.ready()) return;
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
            if (!"delivered".equals(receipt.state)) {
                durableSave(store,player);
                receipt.state="delivered";
                ConfigStore.write(receiptPath(player.getUuid()),receipt);
            }
            if (pending(found)) { unlock(found); durableSave(store,player); }
            return;
        }
        if ("delivered".equals(receipt.state)) return; // traded or deliberately released: never grant again
        Pokemon recovered=new Pokemon().loadFromJSON(player.getRegistryManager(),receipt.snapshot);
        if (!CobblemonIntegration.allowed(recovered)) throw new IllegalStateException("O inicial reservado está bloqueado pela configuração atual. Solicite revisão ao administrador.");
        var party=storage.getParty(player);
        var pc=storage.getPC(player);
        PokemonStore<?> destination=party.getFirstAvailablePosition()!=null ? party : pc;
        if (destination.getFirstAvailablePosition()==null || !destination.add(recovered)) throw new IllegalStateException("Libere espaço para concluir o resgate reservado.");
        durableSave(destination,player);
        receipt.state="delivered";
        ConfigStore.write(receiptPath(player.getUuid()),receipt);
        unlock(recovered);
        durableSave(destination,player);
    }

    private static void unlock(Pokemon pokemon) { pokemon.getPersistentData().remove(PENDING); pokemon.setTradeable(true); }
    private static FileBackedPokemonStoreFactory<?> provider(PokemonStore<?> store) {
        for (var factory:((StoreManagerAccessor)(Object)Cobblemon.INSTANCE.getStorage()).generationControl$factories())
            if (factory instanceof FileBackedPokemonStoreFactory<?> files && files.isCached(store)) return files;
        throw new IllegalStateException("O resgate requer o armazenamento padrão NBT/JSON do Cobblemon.");
    }
    @SuppressWarnings({"rawtypes","unchecked"})
    private static void durableSave(PokemonStore<?> store,ServerPlayerEntity player) throws Exception {
        var access=(StoreFactoryAccessor)(Object)provider(store);
        FileStoreAdapter adapter=access.generationControl$adapter();
        Object serialized=adapter.serialize(store,player.getRegistryManager());
        access.generationControl$executor().submit(() -> adapter.save(store.getClass(),store.getUuid(),serialized)).get(10,TimeUnit.SECONDS);
    }
    private static void send(ServerPlayerEntity player,String message,List<String> choices) {
        if (!choices.isEmpty()) message += " Iniciais disponíveis: " + String.join(", ",choices) + ". Use /geracoes resgate <especie>.";
        player.sendMessage(Text.literal(message));
    }
}
