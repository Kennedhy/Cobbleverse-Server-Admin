package br.com.cobbleverse.generations.mixin;

import net.minecraft.server.PlayerManager;
import net.minecraft.server.network.ServerPlayerEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/** Invokes the vanilla player-data save after a market inventory custody change. */
@Mixin(PlayerManager.class)
public interface PlayerDataSaverAccessor {
    @Invoker("savePlayerData")
    void generationControl$savePlayerData(ServerPlayerEntity player);
}
