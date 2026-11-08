package br.com.cobbleverse.generations.verification;

import br.com.cobbleverse.generations.config.ConfigStore;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Disposable pack check for the active generation, spawn-pool, trainer, and gym policy. */
public final class RuntimePolicyVerificationEntrypoint implements ModInitializer {
    @Override
    public void onInitialize() {
        ServerLifecycleEvents.SERVER_STARTED.register(server -> {
            Map<String, Object> output = new LinkedHashMap<>();
            try {
                Map<String, Object> report = RuntimeVerification.run(server);
                output.put("verification", report);
                Object failed = report.get("failed");
                output.put("success", Boolean.TRUE.equals(report.get("completed"))
                        && failed instanceof List<?> failures && failures.isEmpty()
                        && report.get("spawnEntriesInspected") instanceof Number spawns && spawns.intValue() > 0
                        && report.get("gymStructuresInspected") instanceof Number gyms && gyms.intValue() >= 36);
            } catch (Throwable failure) {
                output.put("success", false);
                output.put("fatal", failure.toString());
                failure.printStackTrace();
            } finally {
                try {
                    ConfigStore.write(Path.of("runtime-policy-result.json").toAbsolutePath(), output);
                } catch (Exception failure) {
                    failure.printStackTrace();
                }
                server.stop(false);
            }
        });
    }
}
