package dev.rancraft.client;

import dev.rancraft.net.BackhaulLinksPayload;
import dev.rancraft.net.CoverageSurveyPayload;
import dev.rancraft.net.LensLinksPayload;

/**
 * Whatever the server last told this client for the RF Lens's Step 2 layers: the link rays and the
 * coverage survey.
 *
 * <p>Like {@link ClientSignalState}, it holds no Minecraft client types, so the common payload
 * handlers in {@code ModPayloads} can call it without risking a class load on a dedicated server.
 *
 * <p>Nothing here is ever recomputed client-side. The renderers are pure views of these payloads.
 */
public final class ClientLensState {

    private ClientLensState() {
    }

    /**
     * Links are never hidden as stale sooner than this after they arrive.
     *
     * <p>Links arrive once per server {@code evaluationIntervalTicks}: 1 Hz by default, but
     * configurable from 1 to 200 ticks (up to 10 s), and slower still on a lagging server. The
     * client cannot read that interval -- COMMON config is not synced -- so it learns the cadence
     * from the gaps between payloads: links go stale after {@value #STALE_AFTER_GAPS} times the
     * last gap, never sooner than this floor and never later than {@link #LINKS_STALE_MAX_MILLIS}.
     * A fixed five seconds would blink the rays off for part of every interval above 100 ticks.
     *
     * <p>Staleness is for a server that has stopped sending -- a stalling connection -- where a
     * frozen ray would lie. Taking the lens off or switching the links layer off hides the rays at
     * once regardless: the renderer checks the worn lens before it asks about staleness.
     */
    private static final long LINKS_STALE_AFTER_MILLIS = 5_000L;

    /**
     * The longest the staleness limit grows, so rays from a server that has stopped sending go
     * within this. Also the longest gap learned from: a longer one is a lens-off period or an
     * outage, not the send cadence.
     */
    private static final long LINKS_STALE_MAX_MILLIS = 30_000L;

    /** Links go stale after this many of the observed gaps between payloads. */
    private static final double STALE_AFTER_GAPS = 2.5;

    private static final LensLinksPayload NO_LINKS = LensLinksPayload.empty(0L);

    private static volatile LensLinksPayload links = NO_LINKS;
    private static volatile long linksReceivedMillis = 0L;
    /** The last observed gap between link payloads, or 0 before two have arrived. */
    private static volatile long linksGapMillis = 0L;
    private static volatile CoverageSurveyPayload coverage = null;
    /**
     * Phase 3 slice 12: the microwave hops near the wearer, with the state the server measured. Kept
     * until the next payload replaces it: the server sends the set when it changes, and an empty one
     * when it empties, so it never goes stale by age.
     */
    private static volatile BackhaulLinksPayload backhaul = BackhaulLinksPayload.empty();

    public static void acceptLinks(LensLinksPayload payload) {
        long now = System.currentTimeMillis();
        long previous = linksReceivedMillis;
        if (previous != 0L) {
            long gap = now - previous;
            if (gap > 0L && gap <= LINKS_STALE_MAX_MILLIS) {
                linksGapMillis = gap;
            }
        }
        links = payload;
        linksReceivedMillis = now;
    }

    /**
     * A survey is kept until the next one replaces it, however old: it describes the ground, not
     * the moment, and the server resurveys whenever it goes stale.
     */
    public static void acceptCoverage(CoverageSurveyPayload payload) {
        coverage = payload;
    }

    public static void acceptBackhaul(BackhaulLinksPayload payload) {
        backhaul = payload;
    }

    /** The latest microwave hops; never {@code null}, empty before the first arrives. */
    public static BackhaulLinksPayload backhaul() {
        return backhaul;
    }

    /** The latest links; never {@code null}, empty before the first arrives. */
    public static LensLinksPayload links() {
        return links;
    }

    /** True before any links arrive, and once the latest are older than the learned limit. */
    public static boolean linksStale() {
        long received = linksReceivedMillis;
        return received == 0L || System.currentTimeMillis() - received > staleAfterMillis(linksGapMillis);
    }

    /**
     * How long links stay fresh, given the last observed gap between payloads ({@code 0} when none
     * has been observed): {@value #STALE_AFTER_GAPS} gaps, clamped to
     * [{@value #LINKS_STALE_AFTER_MILLIS}, {@value #LINKS_STALE_MAX_MILLIS}] ms.
     */
    static long staleAfterMillis(long gapMillis) {
        long scaled = (long) (Math.max(0L, gapMillis) * STALE_AFTER_GAPS);
        return Math.min(LINKS_STALE_MAX_MILLIS, Math.max(LINKS_STALE_AFTER_MILLIS, scaled));
    }

    /** The latest coverage survey, or {@code null} when none has arrived since the last clear. */
    public static CoverageSurveyPayload coverage() {
        return coverage;
    }

    /** Cleared on disconnect and on respawn or dimension change, so nothing bleeds across. */
    public static void clear() {
        links = NO_LINKS;
        linksReceivedMillis = 0L;
        linksGapMillis = 0L;
        coverage = null;
        backhaul = BackhaulLinksPayload.empty();
    }
}
