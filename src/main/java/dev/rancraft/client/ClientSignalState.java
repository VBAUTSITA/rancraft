package dev.rancraft.client;

import dev.rancraft.net.SignalSamplePayload;

/**
 * Whatever the server last told this client about its signal.
 *
 * <p>Deliberately holds no Minecraft client types, so it is safe to touch from the common payload
 * handler without risking a class load on a dedicated server.
 *
 * <p>Nothing here is ever recomputed client-side. The HUD is a pure view of this state.
 */
public final class ClientSignalState {

    private ClientSignalState() {
    }

    /** A sample older than this is treated as stale and drawn as NO SERVICE. */
    private static final long STALE_AFTER_MILLIS = 5_000L;

    private static volatile SignalSamplePayload latest = SignalSamplePayload.empty(0L, 0);
    private static volatile long lastUpdateMillis = 0L;

    public static void accept(SignalSamplePayload payload) {
        latest = payload;
        lastUpdateMillis = System.currentTimeMillis();
    }

    public static SignalSamplePayload latest() {
        return latest;
    }

    /** Cleared on disconnect and on dimension change so a stale readout never bleeds across. */
    public static void clear() {
        latest = SignalSamplePayload.empty(0L, 0);
        lastUpdateMillis = 0L;
    }

    public static boolean isStale() {
        return lastUpdateMillis == 0L || System.currentTimeMillis() - lastUpdateMillis > STALE_AFTER_MILLIS;
    }
}
