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
import dev.rancraft.rf.Band;
import dev.rancraft.rf.BandTable;
import dev.rancraft.rf.BinTraversal;
import dev.rancraft.rf.CellParams;
import dev.rancraft.rf.ReceiverState;
import dev.rancraft.rf.RfConfig;
import dev.rancraft.rf.RfEngine;
import dev.rancraft.rf.SignalSample;
import dev.rancraft.rf.WorldProbe;
import dev.rancraft.world.SignalTicker.Cached;
import java.util.List;
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
 * <b>Slice 7</b> replaced the per-dimension block epoch with the epochs of the evaluation's
 * dependency bins ({@link RegionEpochs}); the other conditions are unchanged and still pinned here.
 * Headless: the payloads are the real records, built empty; no game starts.
 */
class SignalTickerCacheTest {

    // Exactly representable, so the 0.5-block boundary below is tested exactly.
    private static final double X = -1234.5;
    private static final double Y = 71.5;
    private static final double Z = 987.25;
    private static final long SITES = 9L;
    private static final int CONFIGURED_MAX_LINKS = 8;
    private static final int BIN = RegionEpochs.BIN_SIZE;

    /** The receiver's bin and the one east of it: the dependency set of the entries below. */
    private static final long HOME_BIN = BinTraversal.keyOfBlock((int) Math.floor(X), (int) Math.floor(Z), BIN);
    private static final long EAST_BIN = BinTraversal.key(BinTraversal.binX(HOME_BIN) + 1, BinTraversal.binZ(HOME_BIN));
    private static final long[] DEPENDENCIES = BinTraversal.sortedDistinct(new long[] {HOME_BIN, EAST_BIN});

    private static final SignalSample SAMPLE = SignalSample.empty(1_000L, 2);
    private static final SignalSamplePayload PAYLOAD = SignalSamplePayload.empty(1_000L, 2, X, Y, Z);
    private static final LensLinksPayload LINKS = LensLinksPayload.empty(1_000L);

    /** This test's dimension. JUnit makes a new instance per test, so every test starts at epoch 0. */
    private final RegionEpochs epochs = new RegionEpochs();

    /** What a player evaluated without link rays leaves in the cache (meter, trail or a device). */
    private Cached withoutLinks() {
        return new Cached(SAMPLE, PAYLOAD, null, LensSettings.ALL_BANDS, 0, X, Y, Z,
                epochs.snapshot(DEPENDENCIES), SITES);
    }

    /** What a LINKS wearer leaves in the cache, links chosen under {@code filter} and {@code cap}. */
    private Cached withLinks(String filter, int cap) {
        return new Cached(SAMPLE, PAYLOAD, LINKS, filter, cap, X, Y, Z, epochs.snapshot(DEPENDENCIES), SITES);
    }

    private static LensSettings lens(LensLayers layers) {
        return LensSettings.DEFAULT.withLayers(layers);
    }

    /** The replay decision as {@code evaluate} asks it, for a player whose lens is {@code worn}. */
    private boolean replays(Cached cached, boolean candidateArmed, double x, double y, double z,
            long sites, LensSettings worn, int configuredMaxLinks) {
        LensSettings linkLens = SignalTicker.linkLensOf(worn);
        return SignalTicker.canReplay(cached, true, candidateArmed, x, y, z, epochs, sites, linkLens,
                SignalTicker.linkCap(linkLens, configuredMaxLinks));
    }

    private boolean replays(Cached cached, LensSettings worn) {
        return replays(cached, false, X, Y, Z, SITES, worn, CONFIGURED_MAX_LINKS);
    }

    private boolean replays(Cached cached) {
        return replays(cached, null);
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
        assertFalse(replays(withoutLinks(), true, X, Y, Z, SITES, null, CONFIGURED_MAX_LINKS));
        assertFalse(replays(withLinks(LensSettings.ALL_BANDS, 8), true, X, Y, Z, SITES,
                lens(LensLayers.LINKS), CONFIGURED_MAX_LINKS));
    }

    @Test
    @DisplayName("caching switched off, or nothing cached yet: evaluate")
    void noCachingNoEntry() {
        assertFalse(SignalTicker.canReplay(withoutLinks(), false, false, X, Y, Z, epochs, SITES, null, 0));
        assertFalse(SignalTicker.canReplay(null, true, false, X, Y, Z, epochs, SITES, null, 0));
    }

    // ---- the cache key ----------------------------------------------------------------------------

    @Test
    @DisplayName("a move of 0.5 blocks or more (in 3D) re-evaluates; less replays")
    void moveEpsilon() {
        assertTrue(replays(withoutLinks(), false, X + 0.49, Y, Z, SITES, null, CONFIGURED_MAX_LINKS));
        assertFalse(replays(withoutLinks(), false, X + 0.5, Y, Z, SITES, null, CONFIGURED_MAX_LINKS),
                "the threshold is strict");
        assertFalse(replays(withoutLinks(), false, X, Y - 0.5, Z, SITES, null, CONFIGURED_MAX_LINKS),
                "height counts: the eye is the receiver");
        assertFalse(replays(withoutLinks(), false, X + 0.3, Y + 0.3, Z + 0.3, SITES, null, CONFIGURED_MAX_LINKS),
                "0.52 blocks diagonally");
    }

    @Test
    @DisplayName("any antenna change (site registry version) re-evaluates")
    void siteVersionIsAKey() {
        assertFalse(replays(withoutLinks(), false, X, Y, Z, SITES + 1, null, CONFIGURED_MAX_LINKS),
                "retilting an antenna changes no block, so the registry version must be in the key");
    }

    @Test
    @DisplayName("slice 7: a block change in any dependency bin re-evaluates")
    void dependencyBinIsAKey() {
        Cached home = withoutLinks();
        epochs.bumpBin(HOME_BIN);
        assertFalse(replays(home), "the receiver's own bin");

        Cached east = withoutLinks();
        assertTrue(replays(east), "a fresh entry records the bumped epoch");
        epochs.bumpBlock((int) Math.floor(X) + BIN, (int) Math.floor(Z));
        assertFalse(replays(east), "a block in the other dependency bin");
    }

    @Test
    @DisplayName("slice 7: a block change outside every dependency bin does not re-evaluate (the old epoch would have)")
    void otherBinsAreNotKeys() {
        Cached cached = withoutLinks();
        long totalBefore = epochs.total();
        epochs.bumpBin(BinTraversal.key(BinTraversal.binX(HOME_BIN) - 1, BinTraversal.binZ(HOME_BIN)));
        epochs.bumpBin(BinTraversal.key(BinTraversal.binX(HOME_BIN), BinTraversal.binZ(HOME_BIN) + 1));
        epochs.bumpBlock((int) Math.floor(X) + 500, (int) Math.floor(Z) + 500);
        assertTrue(replays(cached));
        assertEquals(totalBefore + 3, epochs.total(),
                "the dimension-wide sum still moves on every bump, which is what CoverageSurveyor reads");
    }

    @Test
    @DisplayName("slice 7: an evaluation that marched nothing depends on no bin; a block change anywhere keeps it")
    void emptyDependencySet() {
        Cached nothingMarched = new Cached(SAMPLE, PAYLOAD, null, LensSettings.ALL_BANDS, 0, X, Y, Z,
                epochs.snapshot(new long[0]), SITES);
        epochs.bumpBin(HOME_BIN);
        assertTrue(replays(nothingMarched));
        assertFalse(replays(nothingMarched, false, X, Y, Z, SITES + 1, null, CONFIGURED_MAX_LINKS),
                "a new antenna nearby is a site-registry change, which still re-evaluates");
    }

    // ---- 3B done-when, headless: 500 blocks away vs on the link path ------------------------------

    @Test
    @DisplayName("3B done-when: a block placed 500 blocks away keeps a real evaluation's cached sample; one on the link path does not")
    void farBlockKeepsTheCacheLinkPathBlockDoesNot() {
        // A real evaluation: one omni mast 300 blocks east of the receiver, heard in free space, so
        // its ray crosses three bins and the middle one holds neither end.
        double rxX = 10.5;
        double rxY = 70.5;
        double rxZ = 20.5;
        CellParams mast = CellParams.omniDefaults(1L, 310, 69, 20);
        RfEngine.Evaluation evaluation = RfEngine.evaluate(
                WorldProbe.AIR, rxX, rxY, rxZ, List.of(mast), BandTable.of(Band.DEFAULT_900),
                RfConfig.DEFAULTS, 1_000L, ReceiverState.NONE);
        assertFalse(evaluation.sample().isNoService(), "the mast is heard");

        long[] bins = evaluation.dependencyBins(BIN);
        assertEquals(3, bins.length, "x bins 0, 1 and 2 at z bin 0");
        Cached cached = new Cached(evaluation.sample(), PAYLOAD, null, LensSettings.ALL_BANDS, 0,
                rxX, rxY, rxZ, epochs.snapshot(bins), SITES);
        assertTrue(replayAt(cached, rxX, rxY, rxZ));

        // 500 blocks south of the receiver, and 500 north of the link's middle: other bins.
        epochs.bumpBlock(10, 520);
        epochs.bumpBlock(160, -480);
        assertTrue(replayAt(cached, rxX, rxY, rxZ), "a block 500 blocks away no longer invalidates the sample");

        // On the link path, in the middle bin (x 128-255): neither the receiver's nor the mast's.
        epochs.bumpBlock(200, 20);
        assertFalse(replayAt(cached, rxX, rxY, rxZ), "a block on the link path does");
    }

    private boolean replayAt(Cached cached, double x, double y, double z) {
        return SignalTicker.canReplay(cached, true, false, x, y, z, epochs, SITES, null, 0);
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
        assertFalse(replays(all8, false, X, Y, Z, SITES, lens(LensLayers.LINKS), 5), "lensMaxLinks changed");
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
