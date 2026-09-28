package dev.rancraft.rf;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class RfEngineTest {

    private static final BandTable BANDS = BandTable.of(Band.DEFAULT_900);
    private static final RfConfig CONFIG = RfConfig.DEFAULTS;

    /** A mast whose radiating voxel is the origin, with Phase 1 omni defaults. */
    private static CellParams mast(long id, int x, int y, int z) {
        return CellParams.omniDefaults(id, x, y, z);
    }

    @Test
    @DisplayName("Free space monotonicity: RSRP strictly decreases with distance")
    void freeSpaceMonotonicity() {
        CellParams cell = mast(1L, 0, 0, 0);
        double previous = Double.POSITIVE_INFINITY;

        for (int d = 1; d <= 500; d += 7) {
            SignalSample sample = RfEngine.evaluate(
                    WorldProbe.AIR, cell.centerX() + d, cell.centerY(), cell.centerZ(),
                    List.of(cell), BANDS, CONFIG, 0L);

            Optional<CellSample> serving = sample.serving();
            assertTrue(serving.isPresent(), "expected service at " + d + " blocks");

            double rsrp = serving.get().rsrpDbm();
            assertTrue(rsrp < previous, "RSRP did not decrease at " + d + " blocks: " + rsrp + " >= " + previous);
            previous = rsrp;
        }
    }

    @Test
    @DisplayName("Known value through the engine: 100 blocks clear -> -75.52 dBm")
    void knownValueThroughEngine() {
        CellParams cell = mast(1L, 0, 0, 0);

        // Receiver placed so the centre-to-receiver distance is exactly 100.0 blocks.
        SignalSample sample = RfEngine.evaluate(
                WorldProbe.AIR, 100.5, 0.5, 0.5,
                List.of(cell), BANDS, CONFIG, 0L);

        CellSample serving = sample.serving().orElseThrow();
        assertEquals(100.0, serving.distanceBlocks(), 1e-9);
        assertEquals(101.52, serving.pathLossDb(), 0.01);
        assertEquals(-75.52, serving.rsrpDbm(), 0.01);
        assertEquals(0.0, serving.obstructionDb(), 1e-9);
    }

    @Test
    @DisplayName("Obstruction additivity through the engine: 3 x 12 dB costs exactly 36 dB")
    void obstructionAdditivityThroughEngine() {
        CellParams cell = mast(1L, 0, 0, 0);

        // 30 blocks, not 100: at 100 the clear link is already -75.5 dBm, so 36 dB of wall would
        // push it under the -105 dBm floor and the engine would (correctly) drop the cell
        // entirely, leaving nothing to compare against.
        SignalSample clear = RfEngine.evaluate(
                WorldProbe.AIR, 30.5, 0.5, 0.5, List.of(cell), BANDS, CONFIG, 0L);

        TestProbes.Sparse walls = new TestProbes.Sparse(12.0).add(10, 0, 0).add(11, 0, 0).add(12, 0, 0);
        SignalSample blocked = RfEngine.evaluate(
                walls, 30.5, 0.5, 0.5, List.of(cell), BANDS, CONFIG, 0L);

        double clearRsrp = clear.serving().orElseThrow().rsrpDbm();
        CellSample blockedCell = blocked.serving().orElseThrow();

        assertEquals(36.0, blockedCell.obstructionDb(), 1e-9);
        assertEquals(36.0, clearRsrp - blockedCell.rsrpDbm(), 1e-9);
    }

    @Test
    @DisplayName("Sorting: cells come back strongest first, and sub -105 dBm cells are dropped")
    void sortingAndSensitivityFloor() {
        CellParams near = mast(1L, 0, 0, 0);
        CellParams far = mast(2L, 300, 0, 0);

        // Same geometry as 'near' but transmitting far too weakly to be heard.
        CellParams weak = new CellParams(3L, 5, 0, 0, CellParams.DEFAULT_BAND_ID,
                -60.0, 6.0, 0.0, 0.0, 360.0, 90.0, 0);

        SignalSample sample = RfEngine.evaluate(
                WorldProbe.AIR, 50.5, 0.5, 0.5,
                List.of(far, weak, near), BANDS, CONFIG, 0L);

        List<CellSample> cells = sample.cells();
        assertEquals(2, cells.size(), "the -60 dBm cell should have been dropped");

        for (int i = 1; i < cells.size(); i++) {
            assertTrue(cells.get(i - 1).rsrpDbm() >= cells.get(i).rsrpDbm(), "not sorted descending");
        }
        for (CellSample cell : cells) {
            assertTrue(cell.rsrpDbm() >= RfMath.NO_SERVICE_DBM);
            assertFalse(cell.cellId() == 3L, "weak cell survived the sensitivity floor");
        }

        // At x=50.5 the near mast (x=0) is 50 blocks away and the far one (x=300) is 250.
        assertEquals(1L, sample.serving().orElseThrow().cellId());
    }

    @Test
    @DisplayName("Distance clamp: a receiver inside the antenna voxel is finite, not NaN or Infinity")
    void distanceClamp() {
        CellParams cell = mast(1L, 0, 0, 0);

        SignalSample sample = RfEngine.evaluate(
                WorldProbe.AIR, cell.centerX(), cell.centerY(), cell.centerZ(),
                List.of(cell), BANDS, CONFIG, 0L);

        CellSample serving = sample.serving().orElseThrow();
        assertTrue(Double.isFinite(serving.rsrpDbm()), "RSRP was " + serving.rsrpDbm());
        assertTrue(Double.isFinite(serving.pathLossDb()));
        assertEquals(0.0, serving.distanceBlocks(), 1e-9);
        assertEquals(RfMath.fsplAt1mDb(900.0), serving.pathLossDb(), 1e-9);
    }

    @Test
    @DisplayName("Evaluation is capped at maxCellsEvaluated, nearest first")
    void evaluationCap() {
        List<CellParams> many = new java.util.ArrayList<>();
        for (int i = 0; i < 20; i++) {
            many.add(mast(i, i * 5, 0, 0));
        }

        SignalSample sample = RfEngine.evaluate(
                WorldProbe.AIR, 0.5, 0.5, 0.5, many, BANDS, CONFIG, 0L);

        assertTrue(sample.cells().size() <= CONFIG.maxCellsEvaluated(),
                "evaluated " + sample.cells().size() + " cells");
    }

    @Test
    @DisplayName("No candidates in range yields an empty NO SERVICE sample")
    void outOfRangeIsEmpty() {
        CellParams cell = mast(1L, 0, 0, 0);

        SignalSample sample = RfEngine.evaluate(
                WorldProbe.AIR, 5000.5, 0.5, 0.5, List.of(cell), BANDS, CONFIG, 0L);

        assertTrue(sample.isNoService());
        assertSame(Optional.empty(), Optional.empty());
        assertTrue(sample.serving().isEmpty());
    }

    @Test
    @DisplayName("Tuning check: the -105 dBm floor, not the range cap, now bounds an omni mast")
    void maxRangeLandsInTargetBand() {
        CellParams cell = mast(1L, 0, 0, 0);

        // Phase 1 capped evaluation at 600 blocks, which cut the link off before the sensitivity
        // floor ever bit. Phase 2 raised the cap to 1400 so that band choice actually changes
        // range (see NOTES.md), which means a 6 dBi omni mast on band_900 is now bounded by
        // physics at ~695 blocks instead of by the config.
        SignalSample atEdge = RfEngine.evaluate(
                WorldProbe.AIR, 690.5, 0.5, 0.5, List.of(cell), BANDS, CONFIG, 0L);
        assertTrue(atEdge.serving().isPresent(), "expected service at 690 blocks");
        assertTrue(atEdge.serving().orElseThrow().rsrpDbm() >= RfMath.NO_SERVICE_DBM);

        // Past the floor the cell drops out on RSRP, still well inside the evaluation range.
        SignalSample past = RfEngine.evaluate(
                WorldProbe.AIR, 700.5, 0.5, 0.5, List.of(cell), BANDS, CONFIG, 0L);
        assertTrue(past.isNoService(), "expected NO SERVICE past the sensitivity floor");
        assertTrue(700.0 < CONFIG.maxEvaluationRangeBlocks(),
                "the drop-out must be the floor talking, not the range filter");
    }
}
