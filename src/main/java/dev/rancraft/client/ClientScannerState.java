package dev.rancraft.client;

import dev.rancraft.net.ScannerPayload;

/**
 * Whatever the server last told this client its held Proximity Scanner shows (Phase 3 slice 14, §3C.4).
 *
 * <p>Holds no Minecraft client types, so the common payload handler can touch it without a class load
 * on a dedicated server (the same rule as {@link ClientSignalState}). Nothing here is ever computed on
 * the client: the status, the list and every distance and bearing are server values.
 *
 * <p><b>When a reading stops being current</b>, by the Network Locator's two rules
 * ({@link ClientLocatorState}):
 * <ul>
 *   <li><b>The scanner leaves the hand.</b> The server sends only while one is held, so a list kept after
 *       that is of mobs near where the player was. {@link ClientEvents#onClientTick} drops it every tick no
 *       scanner is held ({@link #putAway()}), so taking it back shows "no reading" until the first payload
 *       sent after it is held again.</li>
 *   <li><b>The server stops sending</b> (a stalled connection, a lagging server): the time limit in
 *       {@link #isStale()}, learned from the gaps between payloads.</li>
 * </ul>
 */
public final class ClientScannerState {

    private ClientScannerState() {
    }

    /** The longest gap between two payloads that is learned as the send cadence (as the Locator's). */
    private static final long MAX_LEARNED_GAP_MILLIS = 30_000L;

    private static volatile ScannerPayload latest = null;
    private static volatile long lastUpdateMillis = 0L;
    /** The last observed gap between payloads, or 0 before two have arrived in a row. */
    private static volatile long gapMillis = 0L;

    public static void accept(ScannerPayload payload) {
        acceptAt(payload, System.currentTimeMillis());
    }

    /** {@link #accept} at a given wall-clock time; package-private for the headless test. */
    static void acceptAt(ScannerPayload payload, long nowMillis) {
        long previous = lastUpdateMillis;
        if (previous != 0L) {
            long gap = nowMillis - previous;
            if (gap > 0L && gap <= MAX_LEARNED_GAP_MILLIS) {
                gapMillis = gap;
            }
        }
        latest = payload;
        lastUpdateMillis = nowMillis;
    }

    /** The last payload received, or {@code null} before the first (or since it was dropped). */
    public static ScannerPayload latest() {
        return latest;
    }

    /**
     * True before any payload, and once the latest is older than 2.5 learned gaps, clamped to [5 s, 30 s]
     * ({@link ClientLensState#staleAfterMillis}): the server sends once per evaluation interval, a COMMON
     * setting the client cannot read.
     */
    public static boolean isStale() {
        return isStaleAt(System.currentTimeMillis());
    }

    /** {@link #isStale} at a given wall-clock time; package-private for the headless test. */
    static boolean isStaleAt(long nowMillis) {
        return latest == null || nowMillis - lastUpdateMillis > ClientLensState.staleAfterMillis(gapMillis);
    }

    /** No scanner in a hand: drop the reading, keep the learned cadence (it is the server's). */
    public static void putAway() {
        latest = null;
        lastUpdateMillis = 0L;
    }

    /** Cleared on disconnect, respawn and dimension change; this also forgets the learned cadence. */
    public static void clear() {
        latest = null;
        lastUpdateMillis = 0L;
        gapMillis = 0L;
    }
}
