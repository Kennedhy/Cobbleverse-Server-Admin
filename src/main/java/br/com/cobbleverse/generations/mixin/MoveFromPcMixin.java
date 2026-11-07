package br.com.cobbleverse.generations.mixin;

import br.com.cobbleverse.generations.GenerationControl;
import br.com.cobbleverse.generations.integration.CobblemonIntegration;
import com.cobblemon.mod.common.api.storage.pc.link.PCLinkManager;
import com.cobblemon.mod.common.net.messages.server.storage.pc.MovePCPokemonToPartyPacket;
import com.cobblemon.mod.common.net.serverhandling.storage.pc.MovePCPokemonToPartyHandler;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = MovePCPokemonToPartyHandler.class, remap = false)
public abstract class MoveFromPcMixin {
    @Inject(target = @Desc(value = "handle", args = {MovePCPokemonToPartyPacket.class, MinecraftServer.class, ServerPlayerEntity.class}), at = @At("HEAD"), cancellable = true)
    private void generationControl$withdraw(MovePCPokemonToPartyPacket packet, MinecraftServer server, ServerPlayerEntity player, CallbackInfo ci) {
        if (!GenerationControl.ready() || !GenerationControl.rules().restrictExisting()) return;
        var pc = PCLinkManager.INSTANCE.getPC(player);
        var pokemon = pc == null ? null : pc.get(packet.getPcPosition());
        if (pokemon != null && !CobblemonIntegration.allowed(pokemon)) {
            player.sendMessage(CobblemonIntegration.blockMessage());
            pc.sendTo(player);
            ci.cancel();
        }
    }
}
