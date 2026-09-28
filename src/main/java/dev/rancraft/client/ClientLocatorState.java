package dev.rancraft.client;

import dev.rancraft.net.LocatorFixPayload;

/**
 * Whatever the server last told this client its Network Locator worked out (Phase 3 slice 5).
 *
 * <p>Holds no Minecraft client types, so the common payload handler can touch it without a class
 * load on a dedicated server (the same rule as {@link ClientSignalState}). Nothing here is ever
 * computed on the client: the fix, its HDOP and "±", the candidates and the rings are all server
 * values; the HUD and the world render only draw them.
 */
public final class ClientLocatorState {

    private ClientLocatorState() {
    }

    /**
     * A payload older than this is not drawn as current. The server sends one per evaluation while
     * a Locator is held (1 Hz by default), so 5 s is several missed evaluations: the Locator was
     * just taken into the hand, or the server stopped evaluating this player.
     */
    private static final long STALE_AFTER_MILLIS = 5_000L;

    private static volatile LocatorFixPayload latest = null;
    private static volatile long lastUpdateMillis = 0L;

    public static void accept(LocatorFixPayload payload) {
        latest = payload;
        lastUpdateMillis = System.currentTimeMillis();
    }

    /** The last payload received, or {@code null} before the first. */
    public static LocatorFixPayload latest() {
        return latest;
    }

    public static boolean isStale() {
        return latest == null || System.currentTimeMillis() - lastUpdateMillis > STALE_AFTER_MILLIS;
    }

    /**
     * Cleared on disconnect, respawn and dimension change, so an old fix (and its rings) is never
     * drawn in a place it does not describe. The emergency record comes back with the next payload:
     * the server keeps it on the player.
     */
    public static void clear() {
        latest = null;
        lastUpdateMillis = 0L;
    }
}
