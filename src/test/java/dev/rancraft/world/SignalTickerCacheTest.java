package dev.rancraft.world;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.rancraft.item.LensLayers;
import dev.rancraft.item.LensSettings;
import dev.rancraft.net.LensLinksPayload;
import dev.rancraft.net.SignalSamplePayload;
import dev.rancraft.rf.SignalSample;
import dev.rancraft.world.SignalTicker.Cached;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Phase 3 slice 4 regression gate, cache half: when the ticker replays a player's cached evaluation
 * instead of running a new one ({@link SignalTicker#canReplay}), and the lens link cap that is part
 * of the cache key ({@link SignalTicker#linkLensOf}, {@link SignalTicker#linkCap}).
 *
 * <p>These conditions were inline in {@code SignalTicker.evaluate} before slice 4 and are extracted
 * unchanged. Each test names the subtlety it keeps: the cache skip while a handover candidate is
 * armed, the site-registry-version key, the 0.5-block move, and "the cache must not starve links".
 * Headless: the payloads are the real records, built empty; no game starts.
 */
class SignalTickerCacheTest {

    // Exactly representable, so the 0.5-block boundary below is tested exactly.
    private static final double X = -1234.5;
    private static final double Y = 71.5;
    private static final double Z = 987.25;
    private static final long EPOCH = 41L;
    private static final long SITES = 9L;
    private static final int CONFIGURED_MAX_LINKS = 8;

    private static final SignalSample SAMPLE = SignalSample.empty(1_000L, 2);
    private static final SignalSamplePayload PAYLOAD = SignalSamplePayload.empty(1_000L, 2, X, Y, Z);
    private static final LensLinksPayload LINKS = LensLinksPayload.empty(1_000L);

    /** What a player evaluated without link rays leaves in the cache (meter, trail or a device). */
    private static Cached withoutLinks() {
        return new Cached(SAMPLE, PAYLOAD, null, LensSettings.ALL_BANDS, 0, X, Y, Z, EPOCH, SITES);
    }

    /** What a LINKS wearer leaves in the cache, links chosen under {@code filter} and {@code cap}. */
    private static Cached withLinks(String filter, int cap) {
        return new Cached(SAMPLE, PAYLOAD, LINKS, filter, cap, X, Y, Z, EPOCH, SITES);
    }

    private static LensSettings lens(LensLayers layers) {
        return LensSettings.DEFAULT.withLayers(layers);
    }

    /** The replay decision as {@code evaluate} asks it, for a player whose lens is {@code worn}. */
    private static boolean replays(Cached cached, boolean candidateArmed, double x, double y, double z,
            long epoch, long sites, LensSettings worn, int configuredMaxLinks) {
        LensSettings linkLens = SignalTicker.linkLensOf(worn);
        return SignalTicker.canReplay(cached, true, candidateArmed, x, y, z, epoch, sites, linkLens,
                SignalTicker.linkCap(linkLens, configuredMaxLinks));
    }

    private static boolean replays(Cached cached, LensSettings worn) {
        return replays(cached, false, X, Y, Z, EPOCH, SITES, worn, CONFIGURED_MAX_LINKS);
    }

    // ---- the plain replay -------------------------------------------------------------------------

    @Test
    @DisplayName("standing still with nothing changed replays the entry, with no lens or a lens that wants no links")
    void currentEntryIsReplayed() {
        assertTrue(replays(withoutLinks(), null));
        assertTrue(replays(withoutLinks(), lens(LensLayers.TRAIL)));
        assertTrue(replays(withoutLinks(), lens(LensLayers.ANTENNAS)));
    }

    @Test
    @DisplayName("an armed handover candidate skips the cache, or a player standing at a boundary would never hand over")
    void armedCandidateSkipsTheCache() {
        assertFalse(replays(withoutLinks(), true, X, Y, Z, EPOCH, SITES, null, CONFIGURED_MAX_LINKS));
        assertFalse(replays(withLinks(LensSettings.ALL_BANDS, 8), true, X, Y, Z, EPOCH, SITES,
                lens(LensLayers.LINKS), CONFIGURED_MAX_LINKS));
    }

    @Test
    @DisplayName("caching switched off, or nothing cached yet: evaluate")
    void noCachingNoEntry() {
        assertFalse(SignalTicker.canReplay(withoutLinks(), false, false, X, Y, Z, EPOCH, SITES, null, 0));
        assertFalse(SignalTicker.canReplay(null, true, false, X, Y, Z, EPOCH, SITES, null, 0));
    }

    // ---- the cache key ----------------------------------------------------------------------------

    @Test
    @DisplayName("a move of 0.5 blocks or more (in 3D) re-evaluates; less replays")
    void moveEpsilon() {
        assertTrue(replays(withoutLinks(), false, X + 0.49, Y, Z, EPOCH, SITES, null, CONFIGURED_MAX_LINKS));
        assertFalse(replays(withoutLinks(), false, X + 0.5, Y, Z, EPOCH, SITES, null, CONFIGURED_MAX_LINKS),
                "the threshold is strict");
        assertFalse(replays(withoutLinks(), false, X, Y - 0.5, Z, EPOCH, SITES, null, CONFIGURED_MAX_LINKS),
                "height counts: the eye is the receiver");
        assertFalse(replays(withoutLinks(), false, X + 0.3, Y + 0.3, Z + 0.3, EPOCH, SITES, null, CONFIGURED_MAX_LINKS),
                "0.52 blocks diagonally");
    }

    @Test
    @DisplayName("a block change in the dimension (epoch) or any antenna change (site registry version) re-evaluates")
    void epochAndSiteVersionAreKeys() {
        assertFalse(replays(withoutLinks(), false, X, Y, Z, EPOCH + 1, SITES, null, CONFIGURED_MAX_LINKS));
        assertFalse(replays(withoutLinks(), false, X, Y, Z, EPOCH, SITES + 1, null, CONFIGURED_MAX_LINKS),
                "retilting an antenna changes no block, so the registry version must be in the key");
    }

    // ---- the cache must not starve links ----------------------------------------------------------

    @Test
    @DisplayName("an entry without links is never replayed to a wearer who now wants links")
    void cacheMustNotStarveLinks() {
        assertFalse(replays(withoutLinks(), lens(LensLayers.LINKS)));
        assertFalse(replays(withoutLinks(), lens(LensLayers.ALL)), "ALL includes links");
    }

    @Test
    @DisplayName("an entry with links is replayed only under the same band filter and cap")
    void linksNeedTheSameFilterAndCap() {
        Cached all8 = withLinks(LensSettings.ALL_BANDS, 8);
        assertTrue(replays(all8, lens(LensLayers.LINKS)));
        assertFalse(replays(all8, lens(LensLayers.LINKS).withBandFilter("band_900")), "filter changed");
        assertFalse(replays(all8, false, X, Y, Z, EPOCH, SITES, lens(LensLayers.LINKS), 5), "lensMaxLinks changed");
        assertTrue(replays(withLinks("band_900", 8), lens(LensLayers.LINKS).withBandFilter("band_900")));
    }

    @Test
    @DisplayName("an entry with links still serves a player who no longer wants them (the replay then sends none)")
    void linksEntryServesALinklessPlayer() {
        assertTrue(replays(withLinks(LensSettings.ALL_BANDS, 8), null));
        assertTrue(replays(withLinks(LensSettings.ALL_BANDS, 8), lens(LensLayers.TRAIL)));
    }

    // ---- the lens link cap ------------------------------------------------------------------------

    @Test
    @DisplayName("only a lens showing links asks for link rays; the settings object is passed on as is")
    void linkLensOf() {
        assertNull(SignalTicker.linkLensOf(null));
        for (LensLayers layers : LensLayers.values()) {
            LensSettings worn = lens(layers);
            if (worn.showLinks()) {
                assertSame(worn, SignalTicker.linkLensOf(worn), layers.name());
            } else {
                assertNull(SignalTicker.linkLensOf(worn), layers.name());
            }
        }
    }

    @Test
    @DisplayName("the link cap is lensMaxLinks, never above what the payload carries, and 0 without links")
    void linkCap() {
        LensSettings links = lens(LensLayers.LINKS);
        assertEquals(0, SignalTicker.linkCap(null, 8));
        assertEquals(1, SignalTicker.linkCap(links, 1));
        assertEquals(8, SignalTicker.linkCap(links, 8));
        assertEquals(LensLinksPayload.MAX_LINKS, SignalTicker.linkCap(links, LensLinksPayload.MAX_LINKS));
        assertEquals(LensLinksPayload.MAX_LINKS, SignalTicker.linkCap(links, 500));
    }
}
