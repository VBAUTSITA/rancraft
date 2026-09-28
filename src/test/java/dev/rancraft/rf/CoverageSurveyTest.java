package dev.rancraft.rf;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.rancraft.rf.CoverageSurvey.Result;
import dev.rancraft.rf.CoverageSurvey.Spec;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class CoverageSurveyTest {

    private static final RfConfig CONFIG = RfConfig.DEFAULTS;
    private static final BandTable BANDS = BandTable.of(Band.DEFAULT_900,
            new Band("band_1800", 1800.0, 3.6, 1.1, -108.0, 2));

    /** Flat ground: y = 64 is where feet stand, and everything below it is 20 dB stone. */
    private static final int GROUND = 64;
    private static final SurfaceProbe FLAT = (x, z) -> GROUND;
    private static final WorldProbe FLAT_TERRAIN = (x, y, z) -> y < GROUND ? 20.0 : 0.0;

    /** Rolling hills, with the world probe agreeing with the surface probe about where they are. */
    private static int hillHeight(int x, int z) {
        return GROUND + (int) Math.round(4.0 * Math.sin(x / 11.0) * Math.cos(z / 13.0));
    }

    private static final WorldProbe HILLS = (x, y, z) -> y < hillHeight(x, z) ? 20.0 : 0.0;

    /** Hills with a sprinkling of unloaded columns, so every code path runs. */
    private static final SurfaceProbe PATCHY_HILLS =
            (x, z) -> Math.floorMod(x * 7 + z * 3, 23) == 0 ? SurfaceProbe.UNLOADED : hillHeight(x, z);

    private static CellParams mast(long id, int x, int z) {
        return TestCells.omni(id, x, GROUND + 6, z);
    }

    private static Result survey(Spec spec, List<CellParams> cells, WorldProbe probe, SurfaceProbe surface) {
        CoverageSurvey survey = new CoverageSurvey(spec, cells, BANDS, CONFIG);
        survey.step(probe, surface, Integer.MAX_VALUE);
        assertTrue(survey.isComplete());
        return survey.result();
    }

    private static long servingIdAt(Result result, int index) {
        CellParams serving = result.servingAt(index);
        return serving == null ? ReceiverState.NO_CELL : serving.cellId();
    }

    // ---- The grid ------------------------------------------------------------------------------

    @Test
    @DisplayName("centredOn snaps the origin: two centres within one snap cell share a grid")
    void centredOnSnapping() {
        Spec a = CoverageSurvey.centredOn(100.1, -37.2, 4, 48, "");
        Spec b = CoverageSurvey.centredOn(101.9, -35.4, 4, 48, "");
        assertEquals(a, b, "a sub-step move must not reshuffle the grid");

        Spec moved = CoverageSurvey.centredOn(104.1, -37.2, 4, 48, "");
        assertEquals(a.originX() + 4, moved.originX(), "a whole-step move shifts the grid by one step");
        assertEquals(a.originZ(), moved.originZ());

        for (double x = -300.0; x <= 300.0; x += 0.37) {
            for (int step : new int[] {1, 3, 4, 16}) {
                Spec spec = CoverageSurvey.centredOn(x, -x * 0.5, step, 48, "");
                assertEquals(0, Math.floorMod(spec.originX(), step), "origin not on the step lattice");
                assertEquals(0, Math.floorMod(spec.originZ(), step));
                assertTrue(Math.abs(spec.centreX() - x) <= step / 2.0 + 1e-9,
                        "centre " + spec.centreX() + " strayed from " + x + " at step " + step);
                assertTrue(Math.abs(spec.centreZ() + x * 0.5) <= step / 2.0 + 1e-9);
            }
        }
    }

    @Test
    @DisplayName("Spec arithmetic and validation")
    void specBasics() {
        Spec spec = new Spec(-16, 8, 4, 9, null);
        assertEquals("", spec.bandFilter(), "a null filter means all bands");
        assertFalse(spec.filtersBand());
        assertEquals(81, spec.pointCount());
        assertEquals(36.0, spec.spanBlocks());
        assertEquals(2.0, spec.centreX());
        assertEquals(26.0, spec.centreZ());
        assertEquals(-16 + 3 * 4, spec.blockX(3));
        assertEquals(8 + 5 * 4, spec.blockZ(5));
        assertEquals(5 * 9 + 3, spec.index(3, 5));

        // Even step: the east one of the middle two columns, receiver on the edge between them.
        assertEquals(-16 + 3 * 4 + 2, spec.sampleX(3));
        assertEquals(8 + 5 * 4 + 2, spec.sampleZ(5));
        assertEquals(-16 + 3 * 4 + 2.0, spec.receiverX(3));
        assertEquals(8 + 5 * 4 + 2.0, spec.receiverZ(5));

        // Odd step: the middle column, receiver over its centre.
        Spec odd = new Spec(-9, 6, 3, 5, "");
        assertEquals(-9 + 2 * 3 + 1, odd.sampleX(2));
        assertEquals(-9 + 2 * 3 + 1.5, odd.receiverX(2));
        assertEquals(6 + 4 * 3 + 1, odd.sampleZ(4));
        assertEquals(6 + 4 * 3 + 1.5, odd.receiverZ(4));

        // Step 1: the tile is one column, sampled at that column's centre.
        Spec fine = new Spec(10, -3, 1, 4, "");
        assertEquals(12, fine.sampleX(2));
        assertEquals(12.5, fine.receiverX(2));
        assertEquals(-2.5, fine.receiverZ(0));

        assertThrows(IllegalArgumentException.class, () -> new Spec(0, 0, 0, 9, ""));
        assertThrows(IllegalArgumentException.class, () -> new Spec(0, 0, 4, 0, ""));
        assertThrows(IllegalArgumentException.class, () -> CoverageSurvey.centredOn(0, 0, 0, 9, ""));
    }

    // ---- What the survey measures ----------------------------------------------------------------

    /**
     * Checks the palette and serving cell against the engine. Every point here is a few blocks from
     * one omni, so the levels agree whatever the receiver height: that is pinned, on terrain with a
     * spread of levels, by {@link #everyPointMatchesEngineOnHills()}.
     */
    @Test
    @DisplayName("One omni over flat ground: every point is served by palette 0, at exactly the engine's level")
    void singleOmniMatchesEngine() {
        assertEquals(1.62, CoverageSurvey.RECEIVER_EYE_HEIGHT, "standing eye height");
        CellParams omni = mast(1L, 0, 0);
        Spec spec = CoverageSurvey.centredOn(0.5, 0.5, 4, 9, "");

        Result result = survey(spec, List.of(omni), FLAT_TERRAIN, FLAT);

        assertEquals(List.of(omni), result.palette());
        for (int j = 0; j < spec.size(); j++) {
            for (int i = 0; i < spec.size(); i++) {
                int k = spec.index(i, j);
                int x = spec.blockX(i);
                int z = spec.blockZ(j);

                SignalSample direct = RfEngine.evaluate(
                        FLAT_TERRAIN,
                        spec.receiverX(i), GROUND + CoverageSurvey.RECEIVER_EYE_HEIGHT, spec.receiverZ(j),
                        List.of(omni), BANDS, CONFIG, 0L, ReceiverState.NONE).sample();

                assertEquals(0, result.cellIndex()[k], "point " + x + "," + z);
                assertEquals(GROUND, result.surfaceY()[k]);
                assertEquals(direct.serviceLevel().ordinal(), result.level()[k], "point " + x + "," + z);
                assertEquals(direct.serviceLevel(), result.levelAt(k));
                assertTrue(result.levelAt(k).atLeast(ServiceLevel.POOR), "a lone mast next door must serve");
            }
        }
    }

    /**
     * Pins where the receiver stands: at eye height above the ground of the tile's middle column,
     * over that column's centre. Written out by hand rather than through the {@link Spec} helpers,
     * so a wrong helper fails here too. The hills and the mixed network spread the points over
     * several service levels, so a receiver at the wrong height or off-centre changes some of them.
     */
    @Test
    @DisplayName("Every surveyed point matches a direct engine call at eye height over the tile's middle column, on varied terrain")
    void everyPointMatchesEngineOnHills() {
        assertEquals(1.62, CoverageSurvey.RECEIVER_EYE_HEIGHT, "standing eye height");
        Spec spec = CoverageSurvey.centredOn(3.3, -2.8, 3, 13, "");
        Result result = survey(spec, network(), HILLS, PATCHY_HILLS);

        Set<ServiceLevel> seen = new HashSet<>();
        for (int j = 0; j < spec.size(); j++) {
            for (int i = 0; i < spec.size(); i++) {
                int k = spec.index(i, j);
                // A 3-block tile's middle column is one in from its minimum corner.
                int x = spec.blockX(i) + 1;
                int z = spec.blockZ(j) + 1;
                assertEquals(x, spec.sampleX(i));
                assertEquals(z, spec.sampleZ(j));
                if (PATCHY_HILLS.surfaceY(x, z) == SurfaceProbe.UNLOADED) {
                    assertFalse(result.isSurveyed(k), "point " + x + "," + z);
                    continue;
                }
                SignalSample direct = RfEngine.evaluate(
                        HILLS, x + 0.5, hillHeight(x, z) + 1.62, z + 0.5,
                        network(), BANDS, CONFIG, 0L, ReceiverState.NONE).sample();
                long expectedId = direct.serving().isEmpty() ? ReceiverState.NO_CELL : direct.servingCellId();

                assertEquals(hillHeight(x, z), result.surfaceY()[k], "point " + x + "," + z);
                assertEquals(direct.serviceLevel().ordinal(), result.level()[k], "point " + x + "," + z);
                assertEquals(expectedId, servingIdAt(result, k), "point " + x + "," + z);
                seen.add(direct.serviceLevel());
            }
        }
        assertTrue(seen.size() >= 3, "premise: the scene must span several service levels, saw " + seen);
    }

    @Test
    @DisplayName("Two masts far apart: each serves the points nearer to it")
    void twoMastsSplitTheGrid() {
        CellParams west = mast(1L, 0, 0);
        CellParams east = mast(2L, 160, 0);
        Spec spec = new Spec(-20, -100, 10, 21, "");

        Result result = survey(spec, List.of(west, east), FLAT_TERRAIN, FLAT);

        // Point 0 is the far north-west corner, nearer the west mast, so it is seen first.
        assertEquals(west, result.palette().get(0));
        assertEquals(2, result.palette().size());

        int checked = 0;
        for (int j = 0; j < spec.size(); j++) {
            for (int i = 0; i < spec.size(); i++) {
                double x = spec.receiverX(i);
                double z = spec.receiverZ(j);
                double toWest = Math.hypot(x - west.centerX(), z - west.centerZ());
                double toEast = Math.hypot(x - east.centerX(), z - east.centerZ());
                if (Math.abs(toWest - toEast) < 5.0) {
                    continue; // Too close to the boundary to call.
                }
                long expected = toWest < toEast ? west.cellId() : east.cellId();
                assertEquals(expected, servingIdAt(result, spec.index(i, j)), "point " + x + "," + z);
                checked++;
            }
        }
        assertTrue(checked > spec.pointCount() / 2, "premise: most points should be decidable");
    }

    @Test
    @DisplayName("Out of range: every point is NONE with no cell, but still surveyed")
    void outOfRange() {
        Spec spec = CoverageSurvey.centredOn(0.5, 0.5, 4, 8, "");

        for (List<CellParams> cells : List.of(List.<CellParams>of(), List.of(mast(1L, 5000, 0)))) {
            Result result = survey(spec, cells, FLAT_TERRAIN, FLAT);
            assertTrue(result.palette().isEmpty());
            for (int k = 0; k < spec.pointCount(); k++) {
                assertEquals(ServiceLevel.NONE.ordinal(), result.level()[k]);
                assertEquals(CoverageSurvey.NO_CELL, result.cellIndex()[k]);
                assertEquals(GROUND, result.surfaceY()[k]);
                assertTrue(result.isSurveyed(k), "no service is a measurement, not a hole");
            }
        }
    }

    @Test
    @DisplayName("An unloaded column is UNSURVEYED, and an all-unloaded grid never touches the world")
    void unloadedIsUnsurveyed() {
        CellParams omni = mast(1L, 0, 0);
        Spec spec = CoverageSurvey.centredOn(0.5, 0.5, 4, 10, "");
        SurfaceProbe westUnloaded = (x, z) -> x < 0 ? SurfaceProbe.UNLOADED : GROUND;

        Result result = survey(spec, List.of(omni), FLAT_TERRAIN, westUnloaded);

        int holes = 0;
        for (int j = 0; j < spec.size(); j++) {
            for (int i = 0; i < spec.size(); i++) {
                int k = spec.index(i, j);
                if (spec.sampleX(i) < 0) {
                    holes++;
                    assertEquals(CoverageSurvey.UNSURVEYED, result.level()[k]);
                    assertEquals(CoverageSurvey.NO_CELL, result.cellIndex()[k]);
                    assertEquals(SurfaceProbe.UNLOADED, result.surfaceY()[k]);
                    assertFalse(result.isSurveyed(k));
                    assertNull(result.levelAt(k));
                    assertNull(result.servingAt(k));
                } else {
                    assertTrue(result.isSurveyed(k));
                    assertEquals(0, result.cellIndex()[k]);
                }
            }
        }
        assertTrue(holes > 0 && holes < spec.pointCount(), "premise: the grid straddles the edge");

        TestProbes.Counting probe = new TestProbes.Counting(0.0);
        CoverageSurvey blind = new CoverageSurvey(spec, List.of(omni), BANDS, CONFIG);
        blind.step(probe, (x, z) -> SurfaceProbe.UNLOADED, Integer.MAX_VALUE);
        assertEquals(0, probe.calls, "an unreadable column must cost no rays");
        for (byte level : blind.result().level()) {
            assertEquals(CoverageSurvey.UNSURVEYED, level);
        }
    }

    @Test
    @DisplayName("A band filter evaluates only that band's cells")
    void bandFilterExcludesOtherBands() {
        CellParams low = mast(1L, 0, 0);
        CellParams high = TestCells.withBand(mast(2L, 60, 0), "band_1800");
        List<CellParams> cells = List.of(low, high);

        // The grid includes the tile right under the band_900 mast.
        Spec highOnly = new Spec(-20, -20, 5, 21, "band_1800");
        CoverageSurvey filtered = new CoverageSurvey(highOnly, cells, BANDS, CONFIG);
        assertEquals(List.of(high), filtered.candidates());
        filtered.step(FLAT_TERRAIN, FLAT, Integer.MAX_VALUE);
        Result result = filtered.result();

        assertEquals(List.of(high), result.palette());
        int underLow = highOnly.index(4, 4);
        assertTrue(highOnly.blockX(4) <= low.x() && low.x() < highOnly.blockX(4) + highOnly.step(),
                "premise: tile 4 spans the band_900 mast's column in x");
        assertTrue(highOnly.blockZ(4) <= low.z() && low.z() < highOnly.blockZ(4) + highOnly.step(),
                "premise: tile 4 spans the band_900 mast's column in z");
        assertEquals(high.cellId(), servingIdAt(result, underLow),
                "the band_900 mast overhead must be invisible to a band_1800 survey");

        Result lowOnly = survey(new Spec(-20, -20, 5, 21, "band_900"), cells, FLAT_TERRAIN, FLAT);
        assertEquals(List.of(low), lowOnly.palette());

        Result all = survey(new Spec(-20, -20, 5, 21, ""), cells, FLAT_TERRAIN, FLAT);
        assertEquals(Set.of(low, high), new HashSet<>(all.palette()));
        assertEquals(low.cellId(), servingIdAt(all, underLow));

        Result nobody = survey(new Spec(-20, -20, 5, 21, "band_3500"), cells, FLAT_TERRAIN, FLAT);
        assertTrue(nobody.palette().isEmpty());
        for (int cell : nobody.cellIndex()) {
            assertEquals(CoverageSurvey.NO_CELL, cell);
        }
    }

    // ---- Slicing, determinism, bookkeeping ---------------------------------------------------------

    private static List<CellParams> network() {
        return List.of(
                mast(1L, -30, -10),
                TestCells.withBand(mast(2L, 25, 35), "band_1800"),
                TestCells.sector(3L, 5, GROUND + 12, -40, 180.0, 4.0),
                TestCells.withPci(mast(4L, 40, -25), 3));
    }

    @Test
    @DisplayName("Slicing in chunks of 7 gives exactly the one-shot result")
    void slicingIsInvisible() {
        Spec spec = CoverageSurvey.centredOn(3.3, -2.8, 3, 13, "");
        Result oneShot = survey(spec, network(), HILLS, PATCHY_HILLS);

        CoverageSurvey sliced = new CoverageSurvey(spec, network(), BANDS, CONFIG);
        assertEquals(169, sliced.total());
        int done = 0;
        int calls = 0;
        while (!sliced.isComplete()) {
            int processed = sliced.step(HILLS, PATCHY_HILLS, 7);
            assertEquals(Math.min(7, sliced.total() - done), processed);
            done += processed;
            calls++;
            assertEquals(done, sliced.processed());
        }
        assertEquals(25, calls, "169 points is 24 chunks of 7 and a remainder of 1");
        assertEquals(0, sliced.step(HILLS, PATCHY_HILLS, 7), "a complete survey does no more work");
        assertEquals(0, new CoverageSurvey(spec, network(), BANDS, CONFIG).step(HILLS, PATCHY_HILLS, 0));
        assertEquals(0, new CoverageSurvey(spec, network(), BANDS, CONFIG).step(HILLS, PATCHY_HILLS, -5));

        Result result = sliced.result();
        assertEquals(oneShot.palette(), result.palette());
        assertArrayEquals(oneShot.surfaceY(), result.surfaceY());
        assertArrayEquals(oneShot.cellIndex(), result.cellIndex());
        assertArrayEquals(oneShot.level(), result.level());
        assertEquals(oneShot, result);

        // Premise: the world was interesting enough for the comparison to mean something.
        assertTrue(result.palette().size() >= 2, "palette " + result.palette());
        boolean anyHole = false;
        for (int k = 0; k < spec.pointCount(); k++) {
            anyHole |= !result.isSurveyed(k);
        }
        assertTrue(anyHole, "premise: some columns should be unloaded");
    }

    @Test
    @DisplayName("result() before the survey is complete throws")
    void resultBeforeCompleteThrows() {
        CoverageSurvey survey = new CoverageSurvey(
                CoverageSurvey.centredOn(0, 0, 4, 6, ""), network(), BANDS, CONFIG);
        assertThrows(IllegalStateException.class, survey::result);

        survey.step(HILLS, PATCHY_HILLS, 35);
        assertFalse(survey.isComplete());
        assertThrows(IllegalStateException.class, survey::result);

        survey.step(HILLS, PATCHY_HILLS, 1);
        assertTrue(survey.isComplete());
        assertEquals(survey.result(), survey.result());
    }

    @Test
    @DisplayName("The palette has no duplicates, even when the candidates do, and every entry is used")
    void paletteHasNoDuplicates() {
        CellParams a = mast(1L, 0, 0);
        CellParams b = mast(2L, 50, 0);
        CellParams aAgain = mast(1L, 0, 0);
        CellParams aImpostor = TestCells.withPci(mast(1L, 0, 0), 7); // same id, different params
        List<CellParams> cells = List.of(a, b, aAgain, aImpostor, b);

        CoverageSurvey survey = new CoverageSurvey(new Spec(-20, -20, 5, 20, ""), cells, BANDS, CONFIG);
        assertEquals(List.of(a, b), survey.candidates(), "de-duplicated by cell id, first wins");
        survey.step(FLAT_TERRAIN, FLAT, Integer.MAX_VALUE);
        Result result = survey.result();

        Set<Long> ids = new HashSet<>();
        for (CellParams cell : result.palette()) {
            assertTrue(ids.add(cell.cellId()), "duplicate cell " + cell.cellId() + " in palette");
        }
        Set<Integer> used = new HashSet<>();
        for (int cell : result.cellIndex()) {
            if (cell != CoverageSurvey.NO_CELL) {
                assertTrue(cell >= 0 && cell < result.palette().size());
                used.add(cell);
            }
        }
        assertEquals(result.palette().size(), used.size(), "a palette entry that serves nothing");
    }

    @Test
    @DisplayName("Deterministic: the same world and network give the same result, run after run")
    void deterministic() {
        Spec spec = CoverageSurvey.centredOn(-7.6, 12.2, 4, 12, "");
        Result first = survey(spec, network(), HILLS, PATCHY_HILLS);
        Result second = survey(spec, network(), HILLS, PATCHY_HILLS);

        assertEquals(first, second);
        assertEquals(first.hashCode(), second.hashCode());

        Result elsewhere = survey(CoverageSurvey.centredOn(200.0, 12.2, 4, 12, ""), network(), HILLS, PATCHY_HILLS);
        assertNotEquals(first, elsewhere, "equality must compare content, not merely shape");
    }

    @Test
    @DisplayName("A Result rejects arrays that do not match its grid")
    void resultValidatesShape() {
        Spec spec = new Spec(0, 0, 4, 3, "");
        int[] nine = new int[9];
        byte[] levels = new byte[9];
        assertThrows(IllegalArgumentException.class,
                () -> new Result(spec, List.of(), new int[8], nine, levels));
        assertThrows(IllegalArgumentException.class,
                () -> new Result(spec, List.of(), nine, new int[] {0, 0, 0, 0, 0, 0, 0, 0, 0}, levels),
                "cell index 0 with an empty palette");
    }
}
