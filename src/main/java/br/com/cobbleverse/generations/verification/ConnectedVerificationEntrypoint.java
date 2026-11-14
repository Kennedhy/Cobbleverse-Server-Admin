package br.com.cobbleverse.generations.verification;

import net.fabricmc.api.ModInitializer;

/** Dispatches one disposable, connected-player verification scenario. */
public final class ConnectedVerificationEntrypoint implements ModInitializer {
    @Override
    public void onInitialize() {
        switch (System.getProperty("generationcontrol.verification", "")) {
            case "capture" -> new ConnectedCaptureVerification().onInitialize();
            case "market" -> new ConnectedMarketVerification().onInitialize();
            case "wild-levels" -> new ConnectedWildLevelsVerification().onInitialize();
            case "runtime-policy" -> new RuntimePolicyVerificationEntrypoint().onInitialize();
            case "rescue" -> new RescueVerificationEntrypoint().onInitialize();
            default -> throw new IllegalStateException(
                    "Set -Dgenerationcontrol.verification to capture, market, wild-levels, runtime-policy, or rescue for this smoke fixture"
            );
        }
    }
}
