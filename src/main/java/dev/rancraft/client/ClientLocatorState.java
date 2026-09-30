package dev.rancraft.client;

import dev.rancraft.net.LocatorFixPayload;

/**
 * Whatever the server last told this client its Network Locator worked out (Phase 3 slice 5).
 *
 * <p>Holds no Minecraft client types, so the common payload handler can touch it without a class
 * load on a dedicated server (the same rule as {@link ClientSignalState}). Nothing here is ever
 * computed on the client: the fix, its HDOP and "±", the candidates and the rings are all server
 * values; the HUD and the world render only draw them.
 *
 * <p><b>When a reading stops being current</b> (Phase 3A review, round 1). Two separate rules:
 * <ul>
 *   <li><b>The Locator leaves the hand.</b> The server sends the payload only while a Locator is
 *       held, so a reading kept after that is from where the player was, not where they are.
 *       {@link ClientEvents#onClientTick} drops it every tick no Locator is held
 *       ({@link #putAway()}), so taking it back shows "no reading yet" until the first payload sent
 *       after it is held again, never an old fix (and its rings) drawn as current.
 *   <li><b>The server stops evaluating this player</b> (a stalled connection, a lagging server):
 *       the time limit in {@link #isStale()}, learned from the gaps between payloads.
 * </ul>
 */
public final class ClientLocatorState {

    private ClientLocatorState() {
    }

    /**
     * The longest gap between two payloads that is learned as the send cadence; a longer one is an
     * outage or a pause, not the interval. The same bound as {@link ClientLensState}'s links.
     */
    private static final long MAX_LEARNED_GAP_MILLIS = 30_000L;

    private static volatile LocatorFixPayload latest = null;
    private static volatile long lastUpdateMillis = 0L;
    /** The last observed gap between payloads, or 0 before two have arrived in a row. */
    private static volatile long gapMillis = 0L;

    public static void accept(LocatorFixPayload payload) {
        acceptAt(payload, System.currentTimeMillis());
    }

    /** {@link #accept} at a given wall-clock time; package-private for the headless test. */
    static void acceptAt(LocatorFixPayload payload, long nowMillis) {
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
    public static LocatorFixPayload latest() {
        return latest;
    }

    /**
     * True before any payload, and once the latest is older than the learned limit
     * ({@link #staleAfterMillis()}). This time limit covers only "the server stopped evaluating
     * this player"; putting the Locator away is handled by {@link #putAway()}.
     *
     * <p>The server sends one payload per evaluation while the Locator is held, every
     * {@code evaluationIntervalTicks} (1 to 200 ticks, so up to 10 s, and longer under
     * {@code /tick rate}). The client cannot read that COMMON setting, so it learns the cadence from
     * the gaps between payloads, as the lens does for its link rays
     * ({@link ClientLensState#staleAfterMillis}): 2.5 gaps, clamped to [5 s, 30 s]. A fixed 5 s
     * (slice 5) blanked the HUD and the rings for half of every 10 s interval while the server still
     * counted the fix as current (two intervals, {@code LocatorTracker.freshForTicks}) and saved
     * waypoints from it; 2.5 gaps is slightly longer than the server's window, which is the intended
     * slack.
     */
    public static boolean isStale() {
        return isStaleAt(System.currentTimeMillis());
    }

    /** {@link #isStale} at a given wall-clock time; package-private for the headless test. */
    static boolean isStaleAt(long nowMillis) {
        return latest == null || nowMillis - lastUpdateMillis > staleAfterMillis();
    }

    /** The current stale limit, from the last observed gap (the 5 s floor before one is known). */
    static long staleAfterMillis() {
        return ClientLensState.staleAfterMillis(gapMillis);
    }

    /**
     * The Locator is not in a hand: drop the reading, keep the learned cadence. Called every client
     * tick while no Locator is held ({@link ClientEvents#onClientTick}). The cadence is the
     * server's, not the player's, so a slow server's limit holds from the first payload after the
     * Locator is taken back; the gap across the put-away period itself is not learned
     * ({@code lastUpdateMillis} is reset).
     */
    public static void putAway() {
        latest = null;
        lastUpdateMillis = 0L;
    }

    /**
     * Cleared on disconnect, respawn and dimension change, so an old fix (and its rings) is never
     * drawn in a place it does not describe; and, through {@link #putAway()}, whenever no Locator is
     * held. This full clear also forgets the learned cadence (the next server may send at another
     * rate). The emergency record comes back with the next payload: the server keeps it on the
     * player.
     */
    public static void clear() {
        latest = null;
        lastUpdateMillis = 0L;
        gapMillis = 0L;
    }
}
