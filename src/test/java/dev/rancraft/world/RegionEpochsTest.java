package dev.rancraft.world;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.rancraft.rf.BinTraversal;
import java.util.List;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link RegionEpochs} without a game (Phase 3 slice 7, §3B.2): per-bin counters, the snapshot a
 * cached sample keeps, the dimension-wide sum {@code CoverageSurveyor} reads, and the bins each
 * event kind bumps. The event wiring itself is checked at runtime by {@code RegionEpochGameTests}.
 */
class RegionEpochsTest {

    private static final int BIN = RegionEpochs.BIN_SIZE;

    @Test
    @DisplayName("the bins are the site registry's 128-block bins")
    void binSize() {
        assertEquals(SiteRegistry.BIN_SIZE, BIN);
        assertEquals(128, BIN);
    }

    @Test
    @DisplayName("a bump moves its own bin's epoch and the dimension-wide sum, and no other bin")
    void bumpIsLocal() {
        RegionEpochs epochs = new RegionEpochs();
        epochs.bumpBlock(10, 20);
        epochs.bumpBlock(127, 127);
        epochs.bumpBlock(-1, 5);
        assertEquals(2, epochs.epochOf(BinTraversal.key(0, 0)));
        assertEquals(1, epochs.epochOf(BinTraversal.key(-1, 0)));
        assertEquals(0, epochs.epochOf(BinTraversal.key(1, 0)), "a bin nothing touched");
        assertEquals(0, epochs.epochAtBlock(500, 20), "500 blocks away");
        assertEquals(2, epochs.epochAtBlock(0, 0));
        assertEquals(3, epochs.total(), "the sum of every bin's epoch");
    }

    @Test
    @DisplayName("bumping several bins at once counts each bin once")
    void bumpBinsIsDistinct() {
        RegionEpochs epochs = new RegionEpochs();
        long a = BinTraversal.key(3, -4);
        long b = BinTraversal.key(-7, 2);
        epochs.bumpBins(new long[] {a, b, a, a});
        assertEquals(1, epochs.epochOf(a));
        assertEquals(1, epochs.epochOf(b));
        assertEquals(2, epochs.total());
    }

    @Test
    @DisplayName("a snapshot is unchanged until one of its bins is bumped; other bins do not matter")
    void snapshotWatchesItsBinsOnly() {
        RegionEpochs epochs = new RegionEpochs();
        long home = BinTraversal.key(0, 0);
        long east = BinTraversal.key(1, 0);
        epochs.bumpBin(east);
        RegionEpochs.Snapshot snapshot = epochs.snapshot(BinTraversal.sortedDistinct(new long[] {home, east}));
        assertEquals(2, snapshot.size());
        assertTrue(snapshot.covers(home));
        assertFalse(snapshot.covers(BinTraversal.key(0, 1)));
        assertTrue(epochs.unchanged(snapshot));

        epochs.bumpBin(BinTraversal.key(0, 1));
        epochs.bumpBin(BinTraversal.key(-4, 0));
        assertTrue(epochs.unchanged(snapshot), "neighbours are not dependencies");

        epochs.bumpBin(east);
        assertFalse(epochs.unchanged(snapshot), "a dependency moved");
    }

    @Test
    @DisplayName("the empty snapshot is never invalidated by blocks; a snapshot needs one epoch per bin")
    void emptySnapshot() {
        RegionEpochs epochs = new RegionEpochs();
        RegionEpochs.Snapshot none = epochs.snapshot(new long[0]);
        assertEquals(RegionEpochs.Snapshot.NONE, none);
        epochs.bumpBlock(0, 0);
        assertTrue(epochs.unchanged(none));
        assertThrows(IllegalArgumentException.class, () -> new RegionEpochs.Snapshot(new long[1], new long[0]));
    }

    @Test
    @DisplayName("a snapshot copies its bins: changing the caller's array afterwards changes nothing")
    void snapshotCopies() {
        RegionEpochs epochs = new RegionEpochs();
        long[] bins = {BinTraversal.key(0, 0)};
        RegionEpochs.Snapshot snapshot = epochs.snapshot(bins);
        bins[0] = BinTraversal.key(9, 9);
        epochs.bumpBin(BinTraversal.key(0, 0));
        assertFalse(epochs.unchanged(snapshot));
    }

    @Test
    @DisplayName("piston: every bin within 14 blocks in x and z, so one bin in the middle, four at a corner")
    void pistonBins() {
        assertEquals(14, RegionEpochs.PISTON_REACH_BLOCKS, "12 pushed blocks, one gap for a sticky pull, one step");
        assertArrayEquals(new long[] {BinTraversal.key(0, 0)}, RegionEpochs.pistonBins(64, 64));
        assertArrayEquals(new long[] {BinTraversal.key(0, 0)}, RegionEpochs.pistonBins(14, 113));
        assertEquals(2, RegionEpochs.pistonBins(13, 64).length, "reaches x = -1");
        long[] corner = RegionEpochs.pistonBins(0, 127);
        assertEquals(4, corner.length);
        assertArrayEquals(BinTraversal.sortedDistinct(new long[] {
                BinTraversal.key(-1, 0), BinTraversal.key(-1, 1), BinTraversal.key(0, 0), BinTraversal.key(0, 1)}),
                BinTraversal.sortedDistinct(corner));
    }

    @Test
    @DisplayName("piston: bumped at once and again after the moved blocks settle, and only then")
    void pistonBumpsTwice() {
        RegionEpochs epochs = new RegionEpochs();
        long[] bins = RegionEpochs.pistonBins(64, 64);
        long firedAt = 1_000L;
        epochs.bumpNowAndLater(bins, firedAt + RegionEpochs.PISTON_SETTLE_TICKS);
        assertEquals(1, epochs.epochAtBlock(64, 64));
        assertEquals(1, epochs.pending());

        RegionEpochs.Snapshot duringTheMove = epochs.snapshot(bins);
        assertEquals(0, epochs.settle(firedAt + 2));
        assertTrue(epochs.unchanged(duringTheMove), "not before it is due");
        assertEquals(1, epochs.settle(firedAt + RegionEpochs.PISTON_SETTLE_TICKS));
        assertFalse(epochs.unchanged(duringTheMove), "an evaluation made mid-move is invalidated once the blocks settle");
        assertEquals(0, epochs.pending());
        assertEquals(0, epochs.settle(firedAt + 100), "each deferred bump runs once");
        assertEquals(2, epochs.total());
    }

    @Test
    @DisplayName("piston: moved blocks settle within the deferral (a move started at T is finished by T + 2)")
    void settleTicksCoverTheMove() {
        assertTrue(RegionEpochs.PISTON_SETTLE_TICKS >= 3,
                "0.5 progress per tick, placed on the tick after reaching 1.0: done by T + 2; the bump must come later");
    }

    @Test
    @DisplayName("tree growth: every bin within 16 blocks of the sapling, at most four")
    void featureBins() {
        assertEquals(16, RegionEpochs.FEATURE_REACH_BLOCKS);
        assertArrayEquals(new long[] {BinTraversal.key(0, 0)}, RegionEpochs.featureBins(64, 64));
        assertEquals(4, RegionEpochs.featureBins(-5, 130).length);
        assertEquals(2, RegionEpochs.featureBins(64, 120).length, "reaches z = 136");
    }

    @Test
    @DisplayName("explosion: the bin of every listed block and the centre's, each once")
    void explosionBins() {
        List<BlockPos> blown = List.of(
                new BlockPos(126, 60, 5), new BlockPos(127, 61, 5), new BlockPos(128, 60, 5),
                new BlockPos(129, 60, -1), new BlockPos(126, 60, 6));
        long[] bins = RegionEpochs.explosionBins(blown, 127.5, 5.5);
        assertArrayEquals(BinTraversal.sortedDistinct(new long[] {
                BinTraversal.key(0, 0), BinTraversal.key(1, 0), BinTraversal.key(1, -1)}), bins);
        assertArrayEquals(new long[] {BinTraversal.key(-1, 2)}, RegionEpochs.explosionBins(List.of(), -0.5, 300.0),
                "an explosion that broke nothing still bumps its own bin");
    }
}
