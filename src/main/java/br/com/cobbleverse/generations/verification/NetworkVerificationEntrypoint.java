package br.com.cobbleverse.generations.verification;

import br.com.cobbleverse.generations.GenerationControl;
import br.com.cobbleverse.generations.net.AdminNetworking;
import br.com.cobbleverse.generations.integration.ValidationService;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.minecraft.util.WorldSavePath;

/** Local protocol test fixture: explicitly omits gameplay mixins and trainer loading. */
public final class NetworkVerificationEntrypoint implements ModInitializer {
    public void onInitialize() {
        AdminNetworking.register(); ValidationService.register();
        ServerLifecycleEvents.SERVER_STARTED.register(server -> {
            GenerationControl.openWorld(server.getSavePath(WorldSavePath.ROOT));
            try { var field=GenerationControl.class.getDeclaredField("server"); field.setAccessible(true); field.set(null,server); }
            catch(ReflectiveOperationException e) { throw new IllegalStateException(e); }
            GenerationControl.LOGGER.info("Isolated network fixture ready.");
        });
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> { ValidationService.clear(); AdminNetworking.clear(); });
    }
}
