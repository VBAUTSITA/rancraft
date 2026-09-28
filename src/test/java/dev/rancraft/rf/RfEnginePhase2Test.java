package dev.rancraft.rf;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.rancraft.rf.RfEngine.Evaluation;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Phase 2 tests 17-19: pruning, the Phase 1 regression, and determinism. */
class RfEnginePhase2Test {

    private static final RfConfig CONFIG = RfConfig.DEFAULTS;
    private static final BandTable BANDS = BandTable.of(Band.DEFAULT_900,
            new Band("band_1800", 1800.0, 3.6, 1.1, -108.0, 2));

    // ---- Test 17 ---------------------------------------------------------------------------

    @Test
    @DisplayName("17. A backlobe cell at long range is pruned with zero WorldProbe calls")
    void backlobeIsPrunedWithoutMarching() {
        // Receiver 400 blocks due north of the antenna, which is pointing due south.
        CellParams facingAway = TestCells.sector(1L, 0, 64, 0, 180.0, 3.0);
        TestProbes.Counting probe = new TestProbes.Counting(0.0);

        Evaluation evaluation = RfEngine.evaluate(
                probe, 0.5, 64.5, -399.5, List.of(facingAway), BANDS, CONFIG, 0L, ReceiverState.NONE);

        assertEquals(0, probe.calls, "the ray march must never run for a cell that cannot be heard");
        assertEquals(1, evaluation.stats().prunedByBudget());
        assertEquals(0, evaluation.stats().rayMarched());
        assertTrue(evaluation.sample().isNoService());
    }

    @Test
    @DisplayName("17b. The same antenna turned to face the receiver IS marched -- pruning is selective")
    void mainLobeIsStillMarched() {
        CellParams facingReceiver = TestCells.sector(1L, 0, 64, 0, 0.0, 3.0);
        TestProbes.Counting probe = new TestProbes.Counting(0.0);

        Evaluation evaluation = RfEngine.evaluate(
                probe, 0.5, 64.5, -399.5, List.of(facingReceiver), BANDS, CONFIG, 0L, ReceiverState.NONE);

        assertTrue(probe.calls > 0, "a cell in the main lobe has to be marched");
        assertEquals(0, evaluation.stats().prunedByBudget());
        assertEquals(1, evaluation.stats().rayMarched());
        assertFalse(evaluation.sample().isNoService());
    }

    @Test
    @DisplayName("17c. Out-of-range cells are pruned before anything else touches them")
    void distancePruningComesFirst() {
        CellParams tooFar = TestCells.omni(1L, 0, 64, 0);
        TestProbes.Counting probe = new TestProbes.Counting(0.0);

        Evaluation evaluation = RfEngine.evaluate(
                probe, 0.5, 64.5, 5000.5, List.of(tooFar), BANDS, CONFIG, 0L, ReceiverState.NONE);

        assertEquals(0, probe.calls);
        assertEquals(1, evaluation.stats().prunedByDistance());
        assertEquals(0, evaluation.stats().prunedByBudget());
    }

    @Test
    @DisplayName("17d. Rays are spent on the strongest prospects, not merely the nearest")
    void raysGoToTheBestProspects() {
        // The receiver is due south of both cells. Cell 1 is nearer but points north, away from
        // the receiver; cell 2 is further but points south, at it. With one ray to spend, Phase 1
        // distance ordering would waste it on the near, deaf cell.
        //   cell 1 optimistic: 20 + (15 - 30) - 93.8 = -88.8 dBm
        //   cell 2 optimistic: 20 + 13.9        - 107.7 = -73.8 dBm
        List<CellParams> cells = List.of(
                TestCells.sector(1L, 0, 64, -60, 0.0, 3.0),
                TestCells.sector(2L, 0, 64, -150, 180.0, 3.0));

        RfConfig oneRay = withMaxCells(CONFIG, 1);
        Evaluation evaluation = RfEngine.evaluate(
                WorldProbe.AIR, 0.5, 64.5, 0.5, cells, BANDS, oneRay, 0L, ReceiverState.NONE);

        assertEquals(1, evaluation.stats().rayMarched());
        assertEquals(2L, evaluation.sample().serving().orElseThrow().cellId(),
                "the far cell facing the receiver is the one worth a ray");
    }

    // ---- Test 18 ---------------------------------------------------------------------------

    @Test
    @DisplayName("18. Phase 1 regression: an omni mast reads identically to the Phase 1 formula")
    void phase1OmniRegression() {
        CellParams mast = TestCells.omni(1L, 0, 64, 0);

        for (double distance : new double[] {1.0, 10.0, 50.0, 100.0, 250.0, 500.0}) {
            SignalSample sample = RfEngine.evaluate(
                    WorldProbe.AIR, 0.5 + distance, 64.5, 0.5, List.of(mast), BANDS, CONFIG, 0L);

            CellSample serving = sample.serving().orElseThrow(() -> new AssertionError("dropped at " + distance));

            // Exactly the Phase 1 link budget: Tx 20 + Gt 6 + Gr 0 - PL, no pattern, no obstruction.
            double expected = RfMath.rsrpDbm(
                    CellParams.DEFAULT_TX_DBM,
                    CellParams.DEFAULT_GAIN_DBI,
                    RfMath.pathLossDb(900.0, 3.5, distance),
                    0.0);

            assertEquals(expected, serving.rsrpDbm(), 0.01, "RSRP at " + distance + " blocks");
            assertEquals(CellParams.DEFAULT_GAIN_DBI, serving.effectiveGainDbi(), 1e-12,
                    "an omni mast must deliver its flat gain in every direction");
        }
    }

    @Test
    @DisplayName("18b. The Phase 1 known-value case is untouched: 100 m gives -75.52 dBm")
    void phase1KnownValueThroughTheEngine() {
        CellParams mast = TestCells.omni(1L, 0, 64, 0);
        SignalSample sample = RfEngine.evaluate(
                WorldProbe.AIR, 100.5, 64.5, 0.5, List.of(mast), BANDS, CONFIG, 0L);

        CellSample serving = sample.serving().orElseThrow();
        assertEquals(101.52, serving.pathLossDb(), 0.01);
        assertEquals(-75.52, serving.rsrpDbm(), 0.01);
    }

    @Test
    @DisplayName("18c. An omni mast reads the same whichever side of it the receiver stands on")
    void omniIsDirectionIndependent() {
        CellParams mast = TestCells.omni(1L, 0, 64, 0);
        double[][] offsets = {{100, 0}, {-100, 0}, {0, 100}, {0, -100}, {70.71, 70.71}};

        double reference = Double.NaN;
        for (double[] offset : offsets) {
            SignalSample sample = RfEngine.evaluate(
                    WorldProbe.AIR, 0.5 + offset[0], 64.5, 0.5 + offset[1],
                    List.of(mast), BANDS, CONFIG, 0L);
            double rsrp = sample.serving().orElseThrow().rsrpDbm();
            if (Double.isNaN(reference)) {
                reference = rsrp;
            } else {
                assertEquals(reference, rsrp, 0.01, "bearing dependence in an omni cell");
            }
        }
    }

    // ---- Test 19 ---------------------------------------------------------------------------

    @Test
    @DisplayName("19. Identical inputs give byte-identical output across 100 runs")
    void determinism() {
        // Deliberately messy: four cells, two bands, distinct azimuths and tilts, a scattering
        // world, and a receiver off every axis. Near the antennas' own height so the narrow
        // vertical beams do not simply saturate and flatten the fixture out.
        List<CellParams> cells = List.of(
                TestCells.sector(1L, 0, 80, 0, 135.0, 3.0),
                TestCells.withPci(TestCells.sector(2L, 60, 80, 60, 225.0, 5.0), 1),
                TestCells.withBand(TestCells.sector(3L, -40, 80, 20, 90.0, 0.0), "band_1800"),
                TestCells.omni(4L, 20, 72, 50));
        WorldProbe probe = new TestProbes.Scatter(1.0);

        Evaluation first = RfEngine.evaluate(
                probe, 40.5, 78.0, 30.5, cells, BANDS, CONFIG, 1234L, ReceiverState.NONE);

        for (int run = 0; run < 100; run++) {
            Evaluation again = RfEngine.evaluate(
                    probe, 40.5, 78.0, 30.5, cells, BANDS, CONFIG, 1234L, ReceiverState.NONE);
            assertEquals(first.sample(), again.sample(), "run " + run + " diverged");
            assertEquals(first.state(), again.state(), "run " + run + " diverged on state");
            assertEquals(first.stats(), again.stats(), "run " + run + " diverged on stats");
        }

        // The fixture has to exercise real work, not trivially return empty.
        assertEquals(4, first.sample().cells().size(), "all four cells should evaluate");
        assertEquals(4, first.stats().rayMarched());
        assertTrue(first.sample().serving().isPresent());

        // Incidentally: the two strongest cells here are co-channel and 1.6 dB apart, which drives
        // SINR to about -1.4 dB. Plenty of signal, no usable service. That is the Phase 2 lesson
        // falling out of an unrelated fixture.
        assertTrue(first.sample().sinrDb() < 0.0);
        assertEquals(ServiceLevel.NONE, first.sample().serviceLevel());
    }

    // ---- The headline Phase 2 behaviour -----------------------------------------------------

    @Test
    @DisplayName("A fourth co-channel site lowers SINR; moving it to another band raises it back")
    void fourthCoChannelSiteDegradesSinr() {
        CellParams serving = TestCells.omni(1L, 0, 80, 0);
        List<CellParams> cluster = new ArrayList<>(List.of(serving));

        double alone = sinrAt(cluster);

        // Three more masts at a similar distance, all on band_900.
        cluster.add(TestCells.omni(2L, 60, 80, 20));
        cluster.add(TestCells.omni(3L, -50, 80, 40));
        cluster.add(TestCells.omni(4L, 20, 80, -55));
        double crowded = sinrAt(cluster);

        assertTrue(crowded < alone,
                "co-channel neighbours must cost SINR: " + crowded + " vs " + alone);

        // Move the fourth site to another band. Adjacent-channel rejection makes it nearly harmless.
        cluster.set(3, TestCells.withBand(cluster.get(3), "band_1800"));
        double rebanded = sinrAt(cluster);

        assertTrue(rebanded > crowded,
                "re-banding an interferer must recover SINR: " + rebanded + " vs " + crowded);
    }

    @Test
    @DisplayName("Strong but noisy: high RSRP with low SINR still classifies as degraded service")
    void strongButNoisy() {
        List<CellParams> cluster = List.of(
                TestCells.omni(1L, 0, 80, 0),
                TestCells.omni(2L, 6, 80, 0),
                TestCells.omni(3L, -6, 80, 0));

        SignalSample sample = RfEngine.evaluate(
                WorldProbe.AIR, 0.5, 70.0, 0.5, cluster, BANDS, CONFIG, 0L);

        CellSample serving = sample.serving().orElseThrow();
        assertEquals(ServiceLevel.EXCELLENT, serving.rsrpLevel(), "plenty of signal");
        assertTrue(sample.sinrDb() < ServiceLevel.SINR_EXCELLENT_DB, "but not clean");
        assertTrue(sample.serviceLevel().bars() < ServiceLevel.EXCELLENT.bars(),
                "the reported level must follow the worse of the two");
        assertEquals(2, sample.coChannelCount());
    }

    @Test
    @DisplayName("Downtilt trades far coverage for near coverage, and the meter can see both")
    void downtiltTradesFarForNear() {
        // A 10-degree vertical beamwidth saturates at SLA_v within about 16 degrees of boresight,
        // so the geometry has to put the receiver near the horizon for tilt to mean anything at
        // all. Antenna 16 blocks up: 91 blocks out is 10 degrees down, 458 blocks out is 2 degrees.
        CellParams level = TestCells.sector(1L, 0, 80, 0, 0.0, 0.0);
        CellParams tilted = TestCells.withTilt(level, 10.0);

        double nearLevel = rsrpAt(level, 0.5, 64.5, -90.5);
        double nearTilted = rsrpAt(tilted, 0.5, 64.5, -90.5);
        assertTrue(nearTilted > nearLevel,
                "downtilt must help close in: " + nearTilted + " vs " + nearLevel);

        double farLevel = rsrpAt(level, 0.5, 64.5, -457.5);
        double farTilted = rsrpAt(tilted, 0.5, 64.5, -457.5);
        assertTrue(farTilted < farLevel,
                "and must cost range: " + farTilted + " vs " + farLevel);
    }

    @Test
    @DisplayName("Turning a sector 180 degrees away costs roughly the front-to-back ratio")
    void frontToBackIsObservable() {
        CellParams facing = TestCells.sector(1L, 0, 64, 0, 0.0, 0.0);
        CellParams away = TestCells.withAzimuth(facing, 180.0);

        double front = rsrpAt(facing, 0.5, 64.5, -100.5);
        double back = rsrpAt(away, 0.5, 64.5, -100.5);

        assertEquals(CONFIG.antennaFrontToBackDb(), front - back, 0.01);
    }

    @Test
    @DisplayName("A high band reaches less far and suffers more from walls than a low band")
    void highBandIsShorterAndMoreFragile() {
        BandTable fourBands = BandTable.of(
                new Band("band_700", 700.0, 3.2, 0.7, -112.0, 1),
                Band.DEFAULT_900,
                new Band("band_1800", 1800.0, 3.6, 1.1, -108.0, 2),
                new Band("band_3500", 3500.0, 4.0, 1.8, -106.0, 3));

        CellParams low = TestCells.withBand(TestCells.omni(1L, 0, 64, 0), "band_700");
        CellParams high = TestCells.withBand(TestCells.omni(1L, 0, 64, 0), "band_3500");

        // 50 blocks, not 100: band_3500 plus a thick wall at 100 blocks falls below the floor and
        // is correctly dropped, leaving nothing to compare. The engine is right; the geometry has
        // to stay inside the band's actual reach for the comparison to mean anything.
        double lowClear = rsrpAt(low, 50.5, 64.5, 0.5, WorldProbe.AIR, fourBands);
        double highClear = rsrpAt(high, 50.5, 64.5, 0.5, WorldProbe.AIR, fourBands);
        assertTrue(highClear < lowClear, "band_3500 must be weaker at the same range");

        // One 4 dB wooden block, on each band: penetrationFactor multiplies material attenuation,
        // so it costs band_700 2.8 dB and band_3500 7.2 dB.
        WorldProbe wall = new TestProbes.Sparse(4.0).add(25, 64, 0);
        double lowWalled = rsrpAt(low, 50.5, 64.5, 0.5, wall, fourBands);
        double highWalled = rsrpAt(high, 50.5, 64.5, 0.5, wall, fourBands);

        assertTrue((lowClear - lowWalled) < (highClear - highWalled),
                "the same stone must cost the high band more dB than the low band");
    }

    // ---- helpers ---------------------------------------------------------------------------

    private static double sinrAt(List<CellParams> cells) {
        return RfEngine.evaluate(WorldProbe.AIR, 0.5, 70.0, 0.5, cells, BANDS, CONFIG, 0L).sinrDb();
    }

    private static double rsrpAt(CellParams cell, double x, double y, double z) {
        return rsrpAt(cell, x, y, z, WorldProbe.AIR, BANDS);
    }

    private static double rsrpAt(CellParams cell, double x, double y, double z, WorldProbe probe, BandTable bands) {
        return RfEngine.evaluate(probe, x, y, z, List.of(cell), bands, CONFIG, 0L)
                .serving().orElseThrow().rsrpDbm();
    }

    private static RfConfig withMaxCells(RfConfig base, int maxCells) {
        return new RfConfig(
                base.metersPerBlock(), base.maxEvaluationRangeBlocks(), maxCells,
                base.maxObstructionDb(), base.maxRaySteps(), base.evaluationIntervalTicks(),
                base.requireRedstone(), base.enableSampleCaching(),
                base.antennaFrontToBackDb(), base.antennaSidelobeFloorDb(),
                base.adjacentChannelRejectionDb(), base.pciMod3PenaltyFactor(),
                base.enablePciMod3Penalty(), base.handoverHysteresisDb(), base.timeToTriggerTicks(),
                base.pciPlanningRadius(), base.pciMod3Radius());
    }
}
