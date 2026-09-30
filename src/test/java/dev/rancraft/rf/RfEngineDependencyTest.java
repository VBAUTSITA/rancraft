package dev.rancraft.rf;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.rancraft.rf.RfEngine.Evaluation;
import dev.rancraft.rf.RfEngine.MarchedRay;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * An evaluation's dependency set (Phase 3 slice 7, §3B.2): the union of the bins along every ray it
 * actually marched ({@link Evaluation#dependencyBins}). The §3B test "a pruned cell contributes no
 * bins", asserted with a counting probe, and the two facts {@link BinTraversal}'s correctness
 * argument rests on: which cells are marched does not depend on the world, and every voxel the
 * engine reads lies in a dependency bin.
 */
class RfEngineDependencyTest {

    private static final int BIN = 128;
    private static final RfConfig CONFIG = RfConfig.DEFAULTS;
    private static final BandTable BANDS = BandTable.of(Band.DEFAULT_900);

    /** Counts every voxel the engine asks about and records its bin. */
    private static final class CountingBins implements WorldProbe {
        private final double valueDb;
        final Set<Long> bins = new TreeSet<>();
        int calls;

        CountingBins(double valueDb) {
            this.valueDb = valueDb;
        }

        @Override
        public double attenuationDbAt(int x, int y, int z) {
            calls++;
            bins.add(BinTraversal.keyOfBlock(x, z, BIN));
            return valueDb;
        }
    }

    private static Set<Long> setOf(long[] keys) {
        Set<Long> set = new TreeSet<>();
        for (long key : keys) {
            set.add(key);
        }
        return set;
    }

    private static long[] binsOfRay(CellParams cell, double rxX, double rxZ) {
        return BinTraversal.binsAlong(cell.centerX(), cell.centerZ(), rxX, rxZ, BIN);
    }

    @Test
    @DisplayName("a budget-pruned cell contributes no bins: the counting probe reads nothing there and the set is the marched cell's ray alone")
    void prunedCellContributesNoBins() {
        // Receiver in bin (0, 0). Cell 1: an omni 200 blocks east, heard (bins x 0-1). Cell 2: a
        // sector 400 blocks north pointing north, away from the receiver (test 17's backlobe,
        // mirrored), pruned by the budget; its ray would cross z bins -3 to 0.
        double rxX = 20.5;
        double rxY = 64.5;
        double rxZ = 30.5;
        CellParams heard = TestCells.omni(1L, 220, 64, 30);
        CellParams backlobe = TestCells.sector(2L, 20, 64, -370, 0.0, 3.0);
        CountingBins probe = new CountingBins(0.0);

        Evaluation evaluation = RfEngine.evaluate(
                probe, rxX, rxY, rxZ, List.of(heard, backlobe), BANDS, CONFIG, 0L, ReceiverState.NONE);

        assertEquals(1, evaluation.stats().prunedByBudget(), "the backlobe cell is pruned");
        assertEquals(1, evaluation.marched().size());
        assertEquals(1L, evaluation.marched().get(0).cellId());

        long[] dependencies = evaluation.dependencyBins(BIN);
        assertArrayEquals(BinTraversal.sortedDistinct(binsOfRay(heard, rxX, rxZ)), dependencies,
                "exactly the marched ray's bins");

        for (long bin : binsOfRay(backlobe, rxX, rxZ)) {
            if (bin == BinTraversal.keyOfBlock(20, 30, BIN)) {
                continue; // the receiver's own bin is on every ray
            }
            assertFalse(setOf(dependencies).contains(bin),
                    "the pruned cell's bin " + BinTraversal.binX(bin) + "," + BinTraversal.binZ(bin) + " is not a dependency");
            assertFalse(probe.bins.contains(bin), "and the engine never read a voxel there");
        }
        assertTrue(setOf(dependencies).containsAll(probe.bins), "every voxel read is in a dependency bin");
        assertTrue(probe.calls > 0);
    }

    @Test
    @DisplayName("a cell cut by maxCellsEvaluated contributes no bins either")
    void cappedCellContributesNoBins() {
        // Twelve omnis round the receiver inside bin (0, 0) take the twelve rays; a thirteenth,
        // 300 blocks south (bin z 2-3), is in service in open air but ranks last.
        double rxX = 60.5;
        double rxY = 64.5;
        double rxZ = 60.5;
        List<CellParams> cells = new ArrayList<>();
        for (int i = 0; i < CONFIG.maxCellsEvaluated(); i++) {
            cells.add(TestCells.omni(100L + i, 10 + 8 * i, 64, 10 + 4 * i));
        }
        CellParams far = TestCells.omni(999L, 60, 64, 360);
        cells.add(far);
        CountingBins probe = new CountingBins(0.0);

        Evaluation evaluation = RfEngine.evaluate(
                probe, rxX, rxY, rxZ, cells, BANDS, CONFIG, 0L, ReceiverState.NONE);

        assertEquals(CONFIG.maxCellsEvaluated(), evaluation.stats().rayMarched());
        assertTrue(evaluation.marched().stream().noneMatch(ray -> ray.cellId() == 999L), "the far cell got no ray");
        assertArrayEquals(new long[] {BinTraversal.key(0, 0)}, evaluation.dependencyBins(BIN));
        assertEquals(Set.of(BinTraversal.key(0, 0)), probe.bins);
    }

    @Test
    @DisplayName("which cells are marched does not depend on the world: open air and a world of walls march the same rays")
    void marchedCellsDoNotDependOnBlocks() {
        double rxX = 0.5;
        double rxY = 70.5;
        double rxZ = 0.5;
        List<CellParams> cells = List.of(
                TestCells.omni(1L, 150, 64, 10),
                TestCells.omni(2L, -90, 64, 260),
                TestCells.sector(3L, 10, 64, -300, 0.0, 3.0),
                TestCells.sector(4L, 400, 64, 400, 45.0, 3.0), // pointing away
                TestCells.omni(5L, -700, 64, -20)); // just under the floor even in open air

        Evaluation open = RfEngine.evaluate(
                WorldProbe.AIR, rxX, rxY, rxZ, cells, BANDS, CONFIG, 0L, ReceiverState.NONE);
        Evaluation walled = RfEngine.evaluate(
                new TestProbes.Counting(12.0), rxX, rxY, rxZ, cells, BANDS, CONFIG, 0L, ReceiverState.NONE);

        assertFalse(open.sample().isNoService());
        assertTrue(walled.sample().isNoService(), "every link is buried: nobody is heard");
        assertEquals(open.marched(), walled.marched(), "same rays, same order");
        assertArrayEquals(open.dependencyBins(BIN), walled.dependencyBins(BIN));
        assertTrue(open.marched().size() >= 2);
    }

    @Test
    @DisplayName("a marched cell that ends up unheard is still a dependency: removing the wall would bring it back")
    void unheardMarchedCellIsADependency() {
        CellParams cell = TestCells.omni(1L, 200, 64, 0);
        TestProbes.Sparse wall = new TestProbes.Sparse(60.0).add(150, 64, 0);

        Evaluation blocked = RfEngine.evaluate(
                wall, 0.5, 64.5, 0.5, List.of(cell), BANDS, CONFIG, 0L, ReceiverState.NONE);
        Evaluation clear = RfEngine.evaluate(
                WorldProbe.AIR, 0.5, 64.5, 0.5, List.of(cell), BANDS, CONFIG, 0L, ReceiverState.NONE);

        assertTrue(blocked.sample().isNoService(), "60 dB of bedrock silences it");
        assertFalse(clear.sample().isNoService(), "open air does not");
        assertArrayEquals(new long[] {BinTraversal.key(0, 0), BinTraversal.key(1, 0)}, blocked.dependencyBins(BIN),
                "the wall's bin (x 128-255) is watched although nothing was heard");
    }

    @Test
    @DisplayName("nothing marched (no cells, or all out of range): an empty dependency set")
    void nothingMarchedNoDependencies() {
        Evaluation none = RfEngine.evaluate(
                WorldProbe.AIR, 0.5, 64.5, 0.5, List.of(), BANDS, CONFIG, 0L, ReceiverState.NONE);
        Evaluation outOfRange = RfEngine.evaluate(
                WorldProbe.AIR, 0.5, 64.5, 5000.5, List.of(TestCells.omni(1L, 0, 64, 0)), BANDS, CONFIG, 0L,
                ReceiverState.NONE);
        assertEquals(0, none.dependencyBins(BIN).length);
        assertEquals(0, outOfRange.dependencyBins(BIN).length);
        assertTrue(outOfRange.marched().isEmpty());
    }

    @Test
    @DisplayName("marched rays run from each cell's radiating centre to the receiver, as the march does")
    void marchedRayEndpoints() {
        CellParams cell = TestCells.omni(7L, -40, 80, 25);
        Evaluation evaluation = RfEngine.evaluate(
                WorldProbe.AIR, 3.25, 64.5, -8.75, List.of(cell), BANDS, CONFIG, 0L, ReceiverState.NONE);
        assertEquals(List.of(new MarchedRay(7L, cell.centerX(), cell.centerY(), cell.centerZ(), 3.25, 64.5, -8.75)),
                evaluation.marched());
        Evaluation oldShape = new Evaluation(evaluation.sample(), evaluation.state(), false, evaluation.stats());
        assertEquals(0, oldShape.dependencyBins(BIN).length, "the old four-component shape records no rays");
    }
}
