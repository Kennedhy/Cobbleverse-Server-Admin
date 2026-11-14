package br.com.cobbleverse.generations.verification;

import br.com.cobbleverse.generations.GenerationControl;
import br.com.cobbleverse.generations.config.ConfigStore;
import br.com.cobbleverse.generations.integration.RescueService;
import br.com.cobbleverse.generations.mixin.StoreFactoryAccessor;
import br.com.cobbleverse.generations.mixin.StoreManagerAccessor;
import com.cobblemon.mod.common.Cobblemon;
import com.cobblemon.mod.common.api.pokemon.PokemonProperties;
import com.cobblemon.mod.common.api.storage.party.PlayerPartyStore;
import com.cobblemon.mod.common.api.storage.factory.FileBackedPokemonStoreFactory;
import com.mojang.authlib.GameProfile;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.network.packet.c2s.common.SyncedClientOptions;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** Exercises the real storage executor with a delayed save in a disposable server. */
public final class RescueVerificationEntrypoint implements ModInitializer {
    private final CountDownLatch releaseSave = new CountDownLatch(1);
    private final Map<String, Object> result = new LinkedHashMap<>();
    private ServerPlayerEntity player;
    private PlayerPartyStore party;
    private Path receiptFile;
    private UUID rescuedId;
    private int ticks;
    private boolean done;
    private final boolean connectedMode = Boolean.getBoolean("generationcontrol.rescueConnected");
    private final String playerName = System.getProperty("generationcontrol.rescuePlayer", "RescueReconnect");
    private ServerPlayerEntity originalConnection;

    @Override public void onInitialize() {
        ServerTickEvents.END_SERVER_TICK.register(this::tick);
    }

    private void tick(MinecraftServer server) {
        if (done) return;
        try {
            if (connectedMode && ticks == 0) {
                player = server.getPlayerManager().getPlayer(playerName);
                if (player == null || player.age < 40) return;
            }
            if (ticks++ == 0) {
                if (!connectedMode) player = new ServerPlayerEntity(server, server.getOverworld(),
                        new GameProfile(UUID.randomUUID(), "RescueSmoke"), SyncedClientOptions.createDefault());
                originalConnection = player;
                party = Cobblemon.INSTANCE.getStorage().getParty(player);
                party.add(PokemonProperties.Companion.parse("treecko level=5").create(player));
                if (!RescueService.eligible(player)) throw new IllegalStateException("Fixture requires Kanto with rescue enabled.");
                FileBackedPokemonStoreFactory<?> factory = null;
                for (var candidate : ((StoreManagerAccessor) (Object) Cobblemon.INSTANCE.getStorage()).generationControl$factories())
                    if (candidate instanceof FileBackedPokemonStoreFactory<?> files && files.isCached(party)) factory = files;
                if (factory == null) throw new IllegalStateException("Missing file-backed party.");
                ((StoreFactoryAccessor) (Object) factory).generationControl$executor().execute(() -> {
                    try { releaseSave.await(15, TimeUnit.SECONDS); }
                    catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
                });
                long start = System.nanoTime();
                RescueService.claim(player, "bulbasaur");
                long elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
                result.put("claimMillisWithBusyStorage", elapsed);
                if (elapsed >= 2000) throw new IllegalStateException("Claim blocked the game thread.");
                receiptFile = GenerationControl.store().directory().resolve("rescues/" + player.getUuid() + ".json");
                var receipt = readReceipt();
                rescuedId = receipt.pokemonId;
                if (!"prepared".equals(receipt.state) || !RescueService.pending(party.get(rescuedId)))
                    throw new IllegalStateException("Pending save was reported as completed.");
                boolean duplicateDenied = false;
                try { RescueService.claim(player, "bulbasaur"); }
                catch (IllegalStateException expected) { duplicateDenied = true; }
                if (!duplicateDenied) throw new IllegalStateException("Repeated claim accepted.");
                RescueService.recover(player);
                result.put("duplicateClaimDenied", true);
                if (connectedMode) {
                    player.networkHandler.disconnect(net.minecraft.text.Text.literal("Disposable rescue reconnect test"));
                    ConfigStore.write(Path.of("rescue-reconnect-ready.json"), Map.of("pokemonId", rescuedId));
                }
            }
            if (ticks == 10) {
                result.put("ticksAdvancedWhileSavePending", ticks);
                releaseSave.countDown();
            }
            if (ticks > 10 && "delivered".equals(readReceipt().state)) {
                if (connectedMode) {
                    var reconnected = server.getPlayerManager().getPlayer(playerName);
                    if (reconnected == null || reconnected == originalConnection) {
                        if (ticks > 6000) throw new IllegalStateException("Reconnect timed out.");
                        return;
                    }
                    player = reconnected;
                    party = Cobblemon.INSTANCE.getStorage().getParty(player);
                    var rescued = party.get(rescuedId);
                    if (rescued == null) throw new IllegalStateException("Saved rescue missing after reconnect.");
                    if (RescueService.pending(rescued)) {
                        if (ticks > 6000) throw new IllegalStateException("Unlock after reconnect timed out.");
                        return;
                    }
                    if (!rescued.getTradeable()) throw new IllegalStateException("Delivered rescue remains untradeable.");
                    result.put("reconnectedPlayerUnlocked", true);
                }
                long matching = 0;
                for (var pokemon : party) if (pokemon.getUuid().equals(rescuedId)) matching++;
                if (matching != 1) throw new IllegalStateException("Rescue duplicated or lost.");
                result.put("samePokemonDeliveredOnce", true);
                result.put("success", true);
                finish(server);
            }
            if (ticks > (connectedMode ? 6000 : 400)) throw new IllegalStateException("Delivery confirmation timed out.");
        } catch (Throwable failure) {
            result.put("success", false);
            result.put("failure", failure.toString());
            failure.printStackTrace();
            finish(server);
        }
    }

    private RescueService.Receipt readReceipt() throws Exception {
        return ConfigStore.GSON.fromJson(Files.readString(receiptFile), RescueService.Receipt.class);
    }

    private void finish(MinecraftServer server) {
        done = true;
        releaseSave.countDown();
        try { ConfigStore.write(Path.of("rescue-review-result.json").toAbsolutePath(), result); }
        catch (Exception failure) { failure.printStackTrace(); }
        server.stop(false);
    }
}
