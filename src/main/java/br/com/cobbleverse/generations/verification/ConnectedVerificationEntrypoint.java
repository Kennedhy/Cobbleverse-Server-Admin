package br.com.cobbleverse.generations.verification;

import net.fabricmc.api.ModInitializer;

/** Dispatches one disposable, connected-player verification scenario. */
public final class ConnectedVerificationEntrypoint implements ModInitializer {
    @Override
    public void onInitialize() {
        switch (System.getProperty("generationcontrol.verification", "")) {
            case "capture" -> new ConnectedCaptureVerification().onInitialize();
            case "wild-levels" -> new ConnectedWildLevelsVerification().onInitialize();
            default -> throw new IllegalStateException(
                    "Set -Dgenerationcontrol.verification to capture or wild-levels for this smoke fixture"
            );
        }
    }
}
