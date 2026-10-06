package dev.rancraft.rf;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.util.ArrayList;
import java.util.List;
import java.util.SplittableRandom;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Phase 3 tests 5-12 (§3A.5): the Network Locator's position solver, headless. */
class LocatorSolverTest {

    private static final double EYE = LocatorSolver.RECEIVER_EYE_HEIGHT;
    private static final int GROUND = 64;
    private static final SurfaceProbe FLAT = (x, z) -> GROUND;

    private static final Band BAND_900 = Band.DEFAULT_900;
    private static final Band BAND_1800 = new Band("band_1800", 1800.0, 3.6, 1.1, -108.0, 2, 20.0);
    private static final Band BAND_3500 = new Band("band_3500", 3500.0, 4.0, 1.8, -106.0, 3, 100.0);
    /** Resolution tending to zero: c / 10^9 MHz is 3e-7 blocks. "Perfect ranges". */
    private static final Band PERFECT = new Band("perfect", 900.0, 3.5, 1.0, -110.0, 1, 1e9);
    private static final BandTable BANDS = BandTable.of(BAND_900, BAND_1800, BAND_3500, PERFECT);

    /** Default tunables, except that HDOP never rejects: for tests that want to read HDOP itself. */
    private static final LocatorParams ANY_HDOP = new LocatorParams(-100.0, 8, Double.MAX_VALUE, 0.25);

    /** A receiver: its eye, where the engine measures from. */
    private record Rx(double x, double y, double z) {
        static Rx standingOn(SurfaceProbe ground, double x, double z) {
            return new Rx(x, ground.surfaceY((int) Math.floor(x), (int) Math.floor(z)) + EYE, z);
        }
    }

    /** What the engine would report for a cell radiating from voxel (x, y, z), heard at {@code rx}. */
    private static CellSample heard(long id, int x, int y, int z, Band band, double obstructionDb, Rx rx) {
        double dx = x + 0.5 - rx.x();
        double dy = y + 0.5 - rx.y();
        double dz = z + 0.5 - rx.z();
        return new CellSample(id, x, y, z, -80.0, Math.sqrt(dx * dx + dy * dy + dz * dz), 100.0,
                obstructionDb, band.id(), 0, 15.0, 0.0, 0.0);
    }

    private static CellSample heard(long id, int x, int y, int z, Band band, Rx rx) {
        return heard(id, x, y, z, band, 0.0, rx);
    }

    /** An exact range to an arbitrary (off-grid) radiating point, for pure geometry tests. */
    private static RangeMeasurement exact(long id, double x, double y, double z, Rx rx, double sigma) {
        double dx = x - rx.x();
        double dy = y - rx.y();
        double dz = z - rx.z();
        return new RangeMeasurement(id, x, y, z, Math.sqrt(dx * dx + dy * dy + dz * dz), sigma, "band_900", 0.0);
    }

    private static List<RangeMeasurement> measure(List<CellSample> cells) {
        return Ranging.measure(cells, BANDS, 1.0, LocatorParams.DEFAULTS);
    }

    private static LocatorFix solve(List<CellSample> cells, SurfaceProbe ground, LocatorParams params) {
        return LocatorSolver.solve(measure(cells), ground, null, params);
    }

    private static LocatorFix.Fix fix(LocatorFix result) {
        return assertInstanceOf(LocatorFix.Fix.class, result, "expected a FIX, got " + result);
    }

    private static double horizontalError(LocatorFix.Fix fix, Rx rx) {
        return Math.hypot(fix.x() - rx.x(), fix.z() - rx.z());
    }

    // ---- Test 5: perfect ranges ----------------------------------------------------------------

    @Test
    @DisplayName("Test 5: perfect ranges, 4 well-spread cells: the true (x, z) within 0.01 blocks")
    void perfectRangesRecoverTheTruth() {
        Rx rx = Rx.standingOn(FLAT, 12.3, -7.8);
        List<CellSample> cells = List.of(
                heard(1L, -90, 80, -100, PERFECT, rx),
                heard(2L, 110, 75, -85, PERFECT, rx),
                heard(3L, 95, 90, 120, PERFECT, rx),
                heard(4L, -120, 70, 95, PERFECT, rx));

        LocatorFix.Fix fix = fix(solve(cells, FLAT, LocatorParams.DEFAULTS));
        assertEquals(rx.x(), fix.x(), 0.01);
        assertEquals(rx.z(), fix.z(), 0.01);
        assertEquals(rx.y(), fix.y(), 1e-9, "altitude aiding: the eye above the flat ground");
        assertEquals(4, fix.cellsUsed());
        assertTrue(fix.hdop() < 1.5, "well spread: HDOP " + fix.hdop());
    }

    @Test
    @DisplayName("Test 5 on a slope: altitude aiding reads the column the estimate is in (floor, not round)")
    void perfectRangesOnASlope() {
        // One block of height per column in x: column 10 is at 74, column 11 at 75. A receiver at
        // x = 10.7 stands in column 10. Reading round(10.7) = 11 would put its eye a block too high.
        SurfaceProbe slope = (x, z) -> GROUND + x;
        Rx rx = Rx.standingOn(slope, 10.7, 3.6);
        assertEquals(GROUND + 10 + EYE, rx.y(), 1e-12, "fixture: the receiver stands in column 10");

        List<CellSample> cells = List.of(
                heard(1L, -60, 110, -70, PERFECT, rx),
                heard(2L, 80, 120, -50, PERFECT, rx),
                heard(3L, 70, 115, 90, PERFECT, rx),
                heard(4L, -75, 105, 60, PERFECT, rx));

        LocatorFix.Fix fix = fix(solve(cells, slope, LocatorParams.DEFAULTS));
        assertEquals(rx.x(), fix.x(), 0.01);
        assertEquals(rx.z(), fix.z(), 0.01);
        assertEquals(rx.y(), fix.y(), 1e-9);
    }

    /**
     * Pins the deliberate deviation from §3A.5's centroid-only start, kept by the project owner's
     * decision (NOTES.md, Phase 3 slice 3 and "Owner decisions on the slice 3 follow-ups"). From the
     * centroid alone, Gauss-Newton settles at (234, -7) here, a wrong local minimum with HDOP 4.3, so
     * the spec's solver would report FIX "± 0" nearly 400 blocks from the truth, with perfect ranges
     * (10.5-16.7 % of fixes outside the footprint do this). The extra starts from the strongest
     * cells' circle crossings find the real minimum.
     */
    @Test
    @DisplayName("Outside the cells' footprint the fit is not trapped in a wrong local minimum")
    void notTrappedOutsideTheFootprint() {
        Rx rx = Rx.standingOn(FLAT, -100.5, 200.5);
        List<CellSample> cells = List.of(
                heard(1L, -17, 80, -17, PERFECT, rx),
                heard(2L, 17, 80, -17, PERFECT, rx),
                heard(3L, 0, 80, 17, PERFECT, rx),
                heard(4L, 40, 80, 40, PERFECT, rx));

        LocatorFix.Fix fix = fix(solve(cells, FLAT, LocatorParams.DEFAULTS));
        assertEquals(rx.x(), fix.x(), 0.01);
        assertEquals(rx.z(), fix.z(), 0.01);
    }

    @Test
    @DisplayName("An unloaded column keeps the last known height instead of guessing or failing")
    void unloadedColumnsFallBack() {
        Rx rx = Rx.standingOn(FLAT, 5.5, 5.5);
        // Everything is unloaded except a 40-block square around the receiver, so the first column
        // read (the centroid of the distant cells) is unloaded.
        SurfaceProbe patchy = (x, z) -> Math.abs(x - 5) <= 20 && Math.abs(z - 5) <= 20 ? GROUND : SurfaceProbe.UNLOADED;
        List<CellSample> cells = List.of(
                heard(1L, 150, 80, 150, PERFECT, rx),
                heard(2L, 160, 85, -40, PERFECT, rx),
                heard(3L, -40, 75, 170, PERFECT, rx));
        assertEquals(SurfaceProbe.UNLOADED, patchy.surfaceY(90, 93), "fixture: the centroid is unloaded");

        LocatorFix.Fix fix = fix(solve(cells, patchy, ANY_HDOP));
        assertEquals(rx.x(), fix.x(), 0.01);
        assertEquals(rx.z(), fix.z(), 0.01);
        assertEquals(rx.y(), fix.y(), 1e-9, "once on loaded ground, the real surface takes over");
    }

    // ---- Test 6: collinear ---------------------------------------------------------------------

    @Test
    @DisplayName("Test 6: three collinear cells give POOR GEOMETRY, wherever the receiver stands")
    void collinearCellsArePoorGeometry() {
        Rx offTheLine = Rx.standingOn(FLAT, 30.5, 60.5);
        Rx onTheLine = Rx.standingOn(FLAT, 250.5, 0.5);
        Rx between = Rx.standingOn(FLAT, 60.5, 0.5);
        for (Rx rx : List.of(offTheLine, onTheLine, between)) {
            // In a row along x, evenly spaced.
            assertPoor(solve(List.of(
                    heard(1L, 0, 80, 0, BAND_900, rx),
                    heard(2L, 100, 80, 0, BAND_900, rx),
                    heard(3L, 200, 80, 0, BAND_900, rx)), FLAT, LocatorParams.DEFAULTS), 3);
            // Unevenly spaced, different heights and bands: still one line on the map.
            assertPoor(solve(List.of(
                    heard(1L, -40, 90, 0, BAND_1800, rx),
                    heard(2L, 30, 70, 0, BAND_900, rx),
                    heard(3L, 200, 85, 0, BAND_3500, rx)), FLAT, LocatorParams.DEFAULTS), 3);
            // A diagonal line (10, 7) per step.
            assertPoor(solve(List.of(
                    heard(1L, 0, 80, 0, BAND_900, rx),
                    heard(2L, 100, 80, 70, BAND_900, rx),
                    heard(3L, 200, 80, 140, BAND_900, rx)), FLAT, LocatorParams.DEFAULTS), 3);
        }
    }

    private static void assertPoor(LocatorFix result, int cellsUsed) {
        LocatorFix.PoorGeometry poor = assertInstanceOf(LocatorFix.PoorGeometry.class, result,
                "expected POOR GEOMETRY, got " + result);
        assertEquals(cellsUsed, poor.cellsUsed());
        assertTrue(poor.hdop() > LocatorParams.DEFAULT_MAX_HDOP && poor.hdop() <= LocatorSolver.HDOP_CEILING,
                "hdop " + poor.hdop());
    }

    @Test
    @DisplayName("HDOP above locatorMaxHdop is POOR GEOMETRY and reports that HDOP; below it is a FIX")
    void hdopThreshold() {
        // Three cells in a 6-degree wedge seen from far away: nearly parallel lines of sight. (Not
        // quite in a line: the middle one is nearer, so this is a narrow wedge, not a collinear set.)
        Rx rx = Rx.standingOn(FLAT, 0.5, 0.5);
        List<CellSample> cells = List.of(
                heard(1L, -300, 80, -16, PERFECT, rx),
                heard(2L, -285, 80, 0, PERFECT, rx),
                heard(3L, -300, 80, 16, PERFECT, rx));

        LocatorFix.Fix accepted = fix(solve(cells, FLAT, ANY_HDOP));
        double hdop = accepted.hdop();
        assertTrue(hdop > LocatorParams.DEFAULT_MAX_HDOP, "fixture: HDOP " + hdop + " is above the default limit");

        LocatorFix.PoorGeometry poor = assertInstanceOf(LocatorFix.PoorGeometry.class,
                solve(cells, FLAT, LocatorParams.DEFAULTS));
        assertEquals(Math.min(hdop, LocatorSolver.HDOP_CEILING), poor.hdop(), 1e-9);
        assertEquals(3, poor.cellsUsed());

        LocatorParams lenient = new LocatorParams(-100.0, 8, hdop + 0.01, 0.25);
        assertInstanceOf(LocatorFix.Fix.class, solve(cells, FLAT, lenient), "at the limit it is still a FIX");
    }

    // ---- Test 7: 120 degrees -------------------------------------------------------------------

    @Test
    @DisplayName("Test 7: three cells at 120 degree spacing give HDOP 2/sqrt(3) within 0.05")
    void hdopAt120Degrees() {
        double expected = 2.0 / Math.sqrt(3.0);
        Rx rx = Rx.standingOn(FLAT, 0.5, 0.5);

        // Exact geometry, exact ranges.
        List<RangeMeasurement> ranges = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            double bearing = Math.toRadians(90.0 + 120.0 * i);
            ranges.add(exact(i + 1L, rx.x() + 150.0 * Math.cos(bearing), 80.5, rx.z() + 150.0 * Math.sin(bearing),
                    rx, 4.33));
        }
        LocatorFix.Fix fix = fix(LocatorSolver.solve(ranges, FLAT, null, LocatorParams.DEFAULTS));
        assertEquals(expected, fix.hdop(), 0.05);
        assertEquals(expected, fix.hdop(), 1e-6, "and in fact exactly, at the true position");
        assertEquals(fix.hdop() * 4.33, fix.errorBlocks(), 1e-9, "one band: error = HDOP x sigma");

        // On the block grid with real band_1800 quantisation: the estimate moves a little, HDOP barely.
        List<CellSample> cells = List.of(
                heard(1L, 0, 80, 150, BAND_1800, rx),
                heard(2L, -130, 80, -75, BAND_1800, rx),
                heard(3L, 130, 80, -75, BAND_1800, rx));
        LocatorFix.Fix quantised = fix(solve(cells, FLAT, LocatorParams.DEFAULTS));
        assertEquals(expected, quantised.hdop(), 0.05);
        assertEquals(quantised.hdop() * Ranging.sigmaBlocks(Ranging.resolutionBlocks(20.0, 1.0)),
                quantised.errorBlocks(), 1e-9);
    }

    // ---- Test 8: two cells ---------------------------------------------------------------------

    @Test
    @DisplayName("Test 8: two cells give AMBIGUOUS, and the candidate nearer the previous fix is likely")
    void twoCellsAreAmbiguous() {
        Rx rx = Rx.standingOn(FLAT, 0.5, 40.5);
        List<RangeMeasurement> ranges = measure(List.of(
                heard(1L, -50, 80, 0, PERFECT, rx),
                heard(2L, 50, 80, 0, PERFECT, rx)));

        // Facing from cell 1 (west) to cell 2 (east), a is on the right: +z, south. That is the truth.
        LocatorFix.Ambiguous none = assertInstanceOf(LocatorFix.Ambiguous.class,
                LocatorSolver.solve(ranges, FLAT, null, LocatorParams.DEFAULTS));
        assertEquals(0.5, none.ax(), 0.01);
        assertEquals(40.5, none.az(), 0.01);
        assertEquals(0.5, none.bx(), 0.01);
        assertEquals(-39.5, none.bz(), 0.01, "the mirror image across the line joining the cells");
        assertEquals(LocatorFix.Ambiguous.NO_PREFERENCE, none.likely(), "no previous fix: no preference");

        LocatorFix nearA = new LocatorFix.Fix(4.0, 70.0, 33.0, 1.2, 9.0, 3);
        LocatorFix nearB = new LocatorFix.Fix(-6.0, 70.0, -30.0, 1.2, 9.0, 3);
        assertEquals(0, ambiguous(ranges, nearA).likely());
        assertEquals(1, ambiguous(ranges, nearB).likely());

        // A previous Ambiguous counts through its likely candidate; one with no preference does not.
        assertEquals(1, ambiguous(ranges, new LocatorFix.Ambiguous(0, 40, 0, -40, 1)).likely());
        assertEquals(0, ambiguous(ranges, new LocatorFix.Ambiguous(0, 40, 0, -40, 0)).likely());
        assertEquals(LocatorFix.Ambiguous.NO_PREFERENCE, ambiguous(ranges,
                new LocatorFix.Ambiguous(0, 40, 0, -40, LocatorFix.Ambiguous.NO_PREFERENCE)).likely());
        assertEquals(LocatorFix.Ambiguous.NO_PREFERENCE, ambiguous(ranges, new LocatorFix.NoSignal()).likely());
        assertEquals(LocatorFix.Ambiguous.NO_PREFERENCE,
                ambiguous(ranges, new LocatorFix.RangeOnly(0, 0, 50)).likely(), "a ring is not a position");
        assertEquals(LocatorFix.Ambiguous.NO_PREFERENCE,
                ambiguous(ranges, new LocatorFix.Fix(100.0, 70.0, 0.5, 1, 1, 3)).likely(),
                "equally far from both: no preference");
    }

    private static LocatorFix.Ambiguous ambiguous(List<RangeMeasurement> ranges, LocatorFix previous) {
        return assertInstanceOf(LocatorFix.Ambiguous.class,
                LocatorSolver.solve(ranges, FLAT, previous, LocatorParams.DEFAULTS));
    }

    @Test
    @DisplayName("Two cells on uneven ground: each candidate is refined with the altitude under it")
    void twoCellsRefineAltitudePerCandidate() {
        // High ground to the south (+z), low to the north; the receiver stands on the high side.
        SurfaceProbe terraced = (x, z) -> z >= 0 ? GROUND + 16 : GROUND;
        Rx rx = Rx.standingOn(terraced, 0.5, 40.5);
        List<RangeMeasurement> ranges = measure(List.of(
                heard(1L, -50, 100, 0, PERFECT, rx),
                heard(2L, 50, 100, 0, PERFECT, rx)));

        LocatorFix.Ambiguous result = assertInstanceOf(LocatorFix.Ambiguous.class,
                LocatorSolver.solve(ranges, terraced, null, LocatorParams.DEFAULTS));
        assertEquals(rx.x(), result.ax(), 0.01);
        assertEquals(rx.z(), result.az(), 0.01, "the true candidate is exact once refined on its own ground");
        assertTrue(result.bz() < 0.0, "the mirror candidate stays on the other side");
    }

    @Test
    @DisplayName("Two cells whose circles do not meet give RANGE ONLY on the nearer cell")
    void twoCellsThatDoNotMeet() {
        RangeMeasurement near = new RangeMeasurement(1L, 0.5, 70.5, 0.5, 20.0, 8.66, "band_900", 0.0);
        RangeMeasurement far = new RangeMeasurement(2L, 200.5, 70.5, 0.5, 60.0, 8.66, "band_900", 0.0);
        // Listed strongest first, but the nearer one is second: "nearer" means the shorter range.
        assertEquals(new LocatorFix.RangeOnly(0.5, 0.5, 20.0),
                LocatorSolver.solve(List.of(far, near), FLAT, null, LocatorParams.DEFAULTS));

        // Nested: one circle wholly inside the other.
        RangeMeasurement big = new RangeMeasurement(3L, 0.5, 70.5, 0.5, 300.0, 8.66, "band_900", 0.0);
        RangeMeasurement small = new RangeMeasurement(4L, 20.5, 70.5, 0.5, 30.0, 8.66, "band_900", 0.0);
        assertEquals(new LocatorFix.RangeOnly(20.5, 0.5, 30.0),
                LocatorSolver.solve(List.of(big, small), FLAT, null, LocatorParams.DEFAULTS));

        // Co-sited (two sectors on one mast): concentric circles pin nothing down.
        RangeMeasurement sectorA = new RangeMeasurement(5L, 0.5, 90.5, 0.5, 60.0, 8.66, "band_900", 0.0);
        RangeMeasurement sectorB = new RangeMeasurement(6L, 0.5, 90.5, 0.5, 90.0, 8.66, "band_900", 0.0);
        assertEquals(new LocatorFix.RangeOnly(0.5, 0.5, 60.0),
                LocatorSolver.solve(List.of(sectorB, sectorA), FLAT, null, LocatorParams.DEFAULTS));
    }

    // ---- Test 9: one cell ----------------------------------------------------------------------

    @Test
    @DisplayName("Test 9: one cell gives RANGE ONLY with the measured radius; none gives NO SIGNAL")
    void oneCellIsARing() {
        Rx rx = Rx.standingOn(FLAT, 37.2, -12.9);
        List<RangeMeasurement> ranges = measure(List.of(heard(7L, -20, 85, 30, BAND_900, 12.0, rx)));
        RangeMeasurement only = ranges.get(0);

        LocatorFix.RangeOnly ring = assertInstanceOf(LocatorFix.RangeOnly.class,
                LocatorSolver.solve(ranges, FLAT, null, LocatorParams.DEFAULTS));
        assertEquals(-19.5, ring.cx(), 0.0);
        assertEquals(30.5, ring.cz(), 0.0);
        assertEquals(only.rangeBlocks(), ring.radius(), 0.0, "the radius is the measured range, as measured");

        assertEquals(new LocatorFix.NoSignal(), LocatorSolver.solve(List.of(), FLAT, null, LocatorParams.DEFAULTS));
    }

    @Test
    @DisplayName("At most locatorMaxCells cells are used, the first ones given (strongest first)")
    void maxCells() {
        Rx rx = Rx.standingOn(FLAT, 0.5, 0.5);
        List<CellSample> cells = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            double bearing = Math.toRadians(36.0 * i);
            cells.add(heard(i + 1L, (int) Math.round(120 * Math.cos(bearing)), 80,
                    (int) Math.round(120 * Math.sin(bearing)), PERFECT, rx));
        }
        assertEquals(8, fix(solve(cells, FLAT, LocatorParams.DEFAULTS)).cellsUsed());
        LocatorParams three = new LocatorParams(-100.0, 3, 6.0, 0.25);
        assertEquals(3, fix(solve(cells, FLAT, three)).cellsUsed());
        LocatorParams two = new LocatorParams(-100.0, 2, 6.0, 0.25);
        assertInstanceOf(LocatorFix.Ambiguous.class, solve(cells, FLAT, two));
    }

    @Test
    @DisplayName("cellsUsed (slice 5, the Locator's rings) is exactly the selection solve() fits: same cells, same order")
    void cellsUsedMatchesSolve() {
        Rx rx = Rx.standingOn(FLAT, 0.5, 0.5);
        List<RangeMeasurement> ranges = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            double bearing = Math.toRadians(36.0 * i);
            ranges.addAll(measure(List.of(heard(i + 1L, (int) Math.round(120 * Math.cos(bearing)), 80,
                    (int) Math.round(120 * Math.sin(bearing)), BAND_1800, rx))));
        }
        // Invalid measurements are skipped by both, and do not use up a slot.
        ranges.add(1, new RangeMeasurement(99L, Double.NaN, 80, 0, 40, 4, "band_900", 0));
        ranges.add(3, new RangeMeasurement(98L, 0, 80, 0, -1, 4, "band_900", 0));

        List<RangeMeasurement> used = LocatorSolver.cellsUsed(ranges, LocatorParams.DEFAULTS);
        assertEquals(List.of(1L, 2L, 3L, 4L, 5L, 6L, 7L, 8L), used.stream().map(RangeMeasurement::cellId).toList());
        assertEquals(used.size(), fix(LocatorSolver.solve(ranges, FLAT, null, LocatorParams.DEFAULTS)).cellsUsed());

        LocatorParams three = new LocatorParams(-100.0, 3, 6.0, 0.25);
        assertEquals(3, LocatorSolver.cellsUsed(ranges, three).size());
        assertEquals(List.of(), LocatorSolver.cellsUsed(List.of(), LocatorParams.DEFAULTS));
    }

    // ---- Sites, not cells (Phase 3A review, round 1) -------------------------------------------

    /**
     * Where the sectors of a three-sector site sit, round a mast column at (mx, mz): each sector is
     * its own block and its own cell (NOTES.md, Phase 2 "Sector antenna"), one block from the column.
     */
    private static final int[][] SECTOR_OFFSETS = {{0, -1}, {1, 0}, {-1, 0}};

    /** The first {@code sectors} sectors of a site round the column (mx, mz), ids from {@code firstId}. */
    private static List<CellSample> site(long firstId, int mx, int mz, int sectors, Band band, Rx rx) {
        List<CellSample> cells = new ArrayList<>();
        for (int i = 0; i < sectors; i++) {
            cells.add(heard(firstId + i, mx + SECTOR_OFFSETS[i][0], 80, mz + SECTOR_OFFSETS[i][1], band, rx));
        }
        return cells;
    }

    /** Defaults, except that no two antennas count as one site unless stacked: the pre-review rule. */
    private static final LocatorParams PER_CELL = new LocatorParams(-100.0, 8, 6.0, 0.25, 0.0);

    @Test
    @DisplayName("Sites: two sectors of one site are one range: RANGE ONLY round one of them, not AMBIGUOUS on a ring")
    void oneSiteIsOneRange() {
        for (int k = 0; k < 24; k++) {
            double bearing = Math.toRadians(15.0 * k + 7.0);
            double distance = 60.0 + 90.0 * k / 23.0;
            Rx rx = Rx.standingOn(FLAT, 0.5 + distance * Math.cos(bearing), 0.5 + distance * Math.sin(bearing));
            List<RangeMeasurement> ranges = measure(site(1L, 0, 0, 2, BAND_900, rx));
            assertEquals(2, ranges.size(), "fixture: both sectors are heard");

            LocatorFix.RangeOnly ring = assertInstanceOf(LocatorFix.RangeOnly.class,
                    LocatorSolver.solve(ranges, FLAT, null, LocatorParams.DEFAULTS), "receiver " + rx);
            // Same band, same sigma: the shorter range represents the site (the first on a tie).
            RangeMeasurement expected = ranges.get(1).rangeBlocks() < ranges.get(0).rangeBlocks()
                    ? ranges.get(1) : ranges.get(0);
            assertEquals(expected.x(), ring.cx(), 0.0);
            assertEquals(expected.z(), ring.cz(), 0.0);
            assertEquals(expected.rangeBlocks(), ring.radius(), 0.0);
            assertEquals(List.of(expected), LocatorSolver.cellsUsed(ranges, LocatorParams.DEFAULTS), "one ring");
        }
    }

    /**
     * The reviewer's example. Before the fix the six sectors were six towers: FIX at z = -64.3, the
     * mirror image, 145 blocks off with "± 7.8". Two sites are two towers: AMBIGUOUS, exactly what
     * one cell per site gives. The true-side candidate is 16.5 blocks off here, not closer: both
     * sites' ranges round down by band_900's 30-block step (that is quantisation, the same with one
     * cell per site), so it is asserted within one ranging step.
     */
    @Test
    @DisplayName("Sites: two three-sector sites are AMBIGUOUS (two towers), not a confident FIX on the mirror image")
    void twoThreeSectorSitesAreAmbiguous() {
        Rx rx = Rx.standingOn(FLAT, 100.5, 80.5);
        List<CellSample> cells = new ArrayList<>(site(1L, 0, 0, 3, BAND_900, rx));
        cells.addAll(site(11L, 200, 0, 3, BAND_900, rx));
        List<RangeMeasurement> ranges = measure(cells);

        LocatorFix.Ambiguous result = assertInstanceOf(LocatorFix.Ambiguous.class,
                LocatorSolver.solve(ranges, FLAT, null, LocatorParams.DEFAULTS));
        double step = Ranging.resolutionBlocks(BAND_900.bandwidthMhz(), 1.0);
        double toA = Math.hypot(result.ax() - rx.x(), result.az() - rx.z());
        double toB = Math.hypot(result.bx() - rx.x(), result.bz() - rx.z());
        assertTrue(Math.min(toA, toB) <= step, "a candidate within one ranging step of the truth: " + result);
        // The other is its mirror image across the line joining the sites (z = -0.5 here).
        double nearX = toA <= toB ? result.ax() : result.bx();
        double nearZ = toA <= toB ? result.az() : result.bz();
        double farX = toA <= toB ? result.bx() : result.ax();
        double farZ = toA <= toB ? result.bz() : result.az();
        assertEquals(nearX, farX, 1e-9);
        assertEquals(-0.5, (nearZ + farZ) / 2.0, 1e-9);
        assertTrue(nearZ > 0.0 && farZ < 0.0);

        List<RangeMeasurement> sites = LocatorSolver.cellsUsed(ranges, LocatorParams.DEFAULTS);
        assertEquals(2, sites.size(), "one range per site");
        assertEquals(result, LocatorSolver.solve(sites, FLAT, null, LocatorParams.DEFAULTS),
                "exactly what one cell per site gives");
    }

    @Test
    @DisplayName("Sites: a triangle of three-sector sites is a 3-site FIX with the one-cell-per-site ±, not a 9-cell one")
    void triangleOfSectorSitesCountsSites() {
        Rx rx = Rx.standingOn(FLAT, 12.3, -7.8);
        List<CellSample> cells = new ArrayList<>();
        cells.addAll(site(1L, 0, 150, 3, BAND_900, rx));
        cells.addAll(site(11L, -130, -75, 3, BAND_900, rx));
        cells.addAll(site(21L, 130, -75, 3, BAND_900, rx));
        List<RangeMeasurement> ranges = measure(cells);

        LocatorFix.Fix fix = fix(LocatorSolver.solve(ranges, FLAT, null, LocatorParams.DEFAULTS));
        assertEquals(3, fix.cellsUsed(), "three sites");
        List<RangeMeasurement> sites = LocatorSolver.cellsUsed(ranges, LocatorParams.DEFAULTS);
        assertEquals(3, sites.size());
        LocatorFix.Fix perSite = fix(LocatorSolver.solve(sites, FLAT, null, LocatorParams.DEFAULTS));
        assertEquals(perSite.errorBlocks(), fix.errorBlocks(), 1e-9, "the ± of one cell per site");
        assertEquals(perSite.x(), fix.x(), 1e-9);
        assertEquals(perSite.z(), fix.z(), 1e-9);

        // Counted per cell, three rows per direction would shrink the ± by about sqrt(3): the
        // over-confidence the grouping removes.
        LocatorParams perCellAllNine = new LocatorParams(-100.0, 9, 6.0, 0.25, 0.0);
        LocatorFix.Fix perCell = fix(LocatorSolver.solve(ranges, FLAT, null, perCellAllNine));
        assertEquals(9, perCell.cellsUsed());
        assertTrue(perCell.errorBlocks() < 0.7 * fix.errorBlocks(), perCell.errorBlocks() + " vs " + fix.errorBlocks());
    }

    @Test
    @DisplayName("Sites: with siteMergeBlocks = 0 every sector counts again (the pre-review behaviour)")
    void noMergeIsPerCell() {
        Rx rx = Rx.standingOn(FLAT, 100.5, 80.5);
        List<CellSample> cells = new ArrayList<>(site(1L, 0, 0, 3, BAND_900, rx));
        cells.addAll(site(11L, 200, 0, 3, BAND_900, rx));
        List<RangeMeasurement> ranges = measure(cells);
        assertEquals(ranges, LocatorSolver.cellsUsed(ranges, PER_CELL));
        // With the mirror check off (the solver before row 16d): the old answer, a FIX on the mirror image.
        LocatorFix.Fix mirror = fix(LocatorSolver.solve(ranges, FLAT, null, PER_CELL, -1.0));
        assertEquals(6, mirror.cellsUsed());
        assertTrue(mirror.z() < 0.0, "the old answer: a FIX on the mirror image, " + mirror);
        // Row 16d: the mirror check sees that both sides fit, so per-cell counting is now AMBIGUOUS.
        LocatorFix.Ambiguous both = assertInstanceOf(LocatorFix.Ambiguous.class,
                LocatorSolver.solve(ranges, FLAT, null, PER_CELL), "the mirror check, per cell");
        assertTrue(both.az() * both.bz() < 0.0, "one candidate each side of the line: " + both);

        // One site, two sectors: two circles one block apart, crossing on their bisector.
        Rx near = Rx.standingOn(FLAT, 70.5, 40.5);
        List<RangeMeasurement> twoSectors = measure(site(1L, 0, 0, 2, BAND_900, near));
        assertEquals(2, LocatorSolver.cellsUsed(twoSectors, PER_CELL).size());
        assertInstanceOf(LocatorFix.RangeOnly.class, LocatorSolver.solve(twoSectors, FLAT, null, LocatorParams.DEFAULTS));
        assertInstanceOf(LocatorFix.Ambiguous.class, LocatorSolver.solve(twoSectors, FLAT, null, PER_CELL));
    }

    @Test
    @DisplayName("Sites: grouping is transitive and order-free; the finest band, then the shortest range, represents a site")
    void siteRepresentativeRule() {
        RangeMeasurement a = new RangeMeasurement(1L, 0.5, 80.5, 0.5, 100.0, 8.66, "band_900", 0.0);
        RangeMeasurement b = new RangeMeasurement(2L, 3.0, 80.5, 0.5, 90.0, 8.66, "band_900", 0.0);
        RangeMeasurement c = new RangeMeasurement(3L, 5.5, 80.5, 0.5, 95.0, 8.66, "band_900", 0.0);
        // a-b and b-c are 2.5 apart, a-c 5: one site through b, whatever the order.
        for (List<RangeMeasurement> order : List.of(List.of(a, b, c), List.of(c, a, b), List.of(a, c, b), List.of(c, b, a))) {
            assertEquals(List.of(b), LocatorSolver.siteRepresentatives(order, LocatorParams.DEFAULTS), order.toString());
        }
        // Without b, a and c are two sites, in the order given.
        assertEquals(List.of(c, a), LocatorSolver.siteRepresentatives(List.of(c, a), LocatorParams.DEFAULTS));

        // The finest band wins over a shorter range; equal sigma and range keep the first given.
        RangeMeasurement wide = new RangeMeasurement(4L, 1.5, 80.5, 0.5, 120.0, 0.87, "band_3500", 0.0);
        assertEquals(List.of(wide), LocatorSolver.siteRepresentatives(List.of(a, wide, b), LocatorParams.DEFAULTS));
        RangeMeasurement twin = new RangeMeasurement(5L, 1.5, 80.5, 0.5, 100.0, 8.66, "band_900", 0.0);
        assertEquals(List.of(a), LocatorSolver.siteRepresentatives(List.of(a, twin), LocatorParams.DEFAULTS));
        assertEquals(List.of(twin), LocatorSolver.siteRepresentatives(List.of(twin, a), LocatorParams.DEFAULTS));

        // Sites are listed by their first (strongest) member, and maxCells counts sites.
        RangeMeasurement far1 = new RangeMeasurement(6L, 100.5, 80.5, 0.5, 40.0, 8.66, "band_900", 0.0);
        RangeMeasurement far2 = new RangeMeasurement(7L, 100.5, 80.5, 200.5, 40.0, 8.66, "band_900", 0.0);
        RangeMeasurement far3 = new RangeMeasurement(8L, -100.5, 80.5, 0.5, 40.0, 8.66, "band_900", 0.0);
        List<RangeMeasurement> mixed = List.of(a, far1, b, far2, far3);
        assertEquals(List.of(b, far1, far2, far3), LocatorSolver.siteRepresentatives(mixed, LocatorParams.DEFAULTS));
        LocatorParams three = new LocatorParams(-100.0, 3, 6.0, 0.25);
        assertEquals(List.of(b, far1, far2), LocatorSolver.siteRepresentatives(mixed, three));

        // Stacked in one column: one site even with no merge distance; a negative or NaN distance merges nothing.
        RangeMeasurement above = new RangeMeasurement(9L, 0.5, 95.5, 0.5, 105.0, 8.66, "band_900", 0.0);
        assertEquals(List.of(a), LocatorSolver.siteRepresentatives(List.of(a, above), PER_CELL));
        for (double off : new double[] {-1.0, Double.NaN}) {
            LocatorParams none = new LocatorParams(-100.0, 8, 6.0, 0.25, off);
            assertEquals(List.of(a, above), LocatorSolver.siteRepresentatives(List.of(a, above), none));
        }
    }

    // ---- Test 10: a wideband cell --------------------------------------------------------------

    /**
     * Test 10 as listed, with the owner-approved weighted "±" ({@code sqrt(trace((H^T W H)^-1))};
     * NOTES.md, Phase 3 "Owner decisions on the slice 3 follow-ups"). The 3x assertion keeps the
     * edge-of-network geometry: three band_900 sites clustered to the west (a 20 degree wedge, HDOP
     * about 4.1) and a band_3500 site added to the north. 35.49 → 5.12 (6.9x). Part of that is
     * geometry: the same site on band_900 gives 9.81 (3.6x). The weighting credits the rest, which
     * {@code HDOP x rms(sigma)} (8.50, 4.2x) did not. Inside a good triangle one wideband cell still
     * gives less than 3x, which the owner accepts: {@link #oneWidebandCellInAGoodTriangle()}.
     */
    @Test
    @DisplayName("Test 10: adding one band_3500 cell to three band_900 cells cuts errorBlocks at least 3x")
    void widebandCellShrinksTheError() {
        Rx rx = Rx.standingOn(FLAT, 0.5, 0.5);
        List<CellSample> west = List.of(
                heard(1L, (int) Math.round(150 * Math.cos(Math.toRadians(170))), 80,
                        (int) Math.round(150 * Math.sin(Math.toRadians(170))), BAND_900, rx),
                heard(2L, -150, 80, 0, BAND_900, rx),
                heard(3L, (int) Math.round(150 * Math.cos(Math.toRadians(190))), 80,
                        (int) Math.round(150 * Math.sin(Math.toRadians(190))), BAND_900, rx));

        List<CellSample> plus3500 = new ArrayList<>(west);
        plus3500.add(heard(9L, 0, 80, 60, BAND_3500, rx));
        List<CellSample> plus900 = new ArrayList<>(west);
        plus900.add(heard(9L, 0, 80, 60, BAND_900, rx));

        // The three west sites are nearly in a north-south line, 150 blocks from the receiver, so the
        // point 150 blocks beyond them fits every range to about 2 blocks: since row 16d the mirror
        // check reports that as AMBIGUOUS, the truth one of the two. The "before" numbers below are the
        // fit itself, read with the check off.
        LocatorFix.Ambiguous westOnly = assertInstanceOf(LocatorFix.Ambiguous.class, solve(west, FLAT, LocatorParams.DEFAULTS));
        assertTrue(Math.hypot(westOnly.ax() - rx.x(), westOnly.az() - rx.z()) < 40.0
                || Math.hypot(westOnly.bx() - rx.x(), westOnly.bz() - rx.z()) < 40.0, "the truth is a candidate: " + westOnly);
        LocatorFix.Fix before = fix(LocatorSolver.solve(measure(west), FLAT, null, LocatorParams.DEFAULTS, -1.0));
        LocatorFix.Fix after = fix(solve(plus3500, FLAT, LocatorParams.DEFAULTS));
        LocatorFix.Fix control = fix(solve(plus900, FLAT, LocatorParams.DEFAULTS));

        assertTrue(before.errorBlocks() >= 3.0 * after.errorBlocks(),
                "errorBlocks " + before.errorBlocks() + " -> " + after.errorBlocks());
        assertTrue(after.errorBlocks() < control.errorBlocks(),
                "the band_3500 site must beat the same site on band_900: "
                        + after.errorBlocks() + " vs " + control.errorBlocks());

        // The numbers NOTES.md quotes, pinned so they are checked, not asserted.
        assertEquals(35.49, before.errorBlocks(), 0.005, "three band_900 sites in a 20 degree wedge");
        assertEquals(5.12, after.errorBlocks(), 0.005, "plus band_3500 to the north");
        assertEquals(9.81, control.errorBlocks(), 0.005, "plus band_900 in the same spot");
        assertEquals(6.93, before.errorBlocks() / after.errorBlocks(), 0.005);
        assertEquals(3.62, before.errorBlocks() / control.errorBlocks(), 0.005);

        // Single-band fixes are still HDOP x sigma, exactly as §3A.5 writes it.
        double sigma900 = Ranging.sigmaBlocks(Ranging.resolutionBlocks(10.0, 1.0));
        double sigma3500 = Ranging.sigmaBlocks(Ranging.resolutionBlocks(100.0, 1.0));
        assertEquals(before.hdop() * sigma900, before.errorBlocks(), 1e-9);
        assertEquals(control.hdop() * sigma900, control.errorBlocks(), 1e-9);

        // Same geometry to 0.001 in HDOP, so the gap between the two four-cell fixes is the weighting.
        // The spec's HDOP x rms(sigma) credited only the rms (1.15x); the weighted fit credits 1.91x.
        assertEquals(control.hdop(), after.hdop(), 0.001);
        double rms = Math.sqrt((3 * sigma900 * sigma900 + sigma3500 * sigma3500) / 4.0);
        assertEquals(8.50, after.hdop() * rms, 0.005, "what HDOP x rms(sigma) would have reported");
        assertEquals(1.91, control.errorBlocks() / after.errorBlocks(), 0.005);
    }

    /**
     * Pins the limit of one wideband cell at fixed good geometry, so NOTES.md's numbers are checked,
     * not asserted: in a good 120 degree triangle one band_3500 cell improves the weighted "±" by
     * 1.40x (9.99 → 7.15; the same site on band_900 1.12x). A single range constrains only its own
     * direction, so the band_900 error across it remains. The project owner accepts that this stays
     * below 3x; test 10's 3x is asserted at the edge of a network instead.
     */
    @Test
    @DisplayName("Limit: in a good 120 degree triangle one band_3500 cell improves errorBlocks 1.40x (below 3x, accepted)")
    void oneWidebandCellInAGoodTriangle() {
        Rx rx = Rx.standingOn(FLAT, 0.5, 0.5);
        List<CellSample> triangle = List.of(
                heard(1L, 0, 80, 150, BAND_900, rx),
                heard(2L, -130, 80, -75, BAND_900, rx),
                heard(3L, 130, 80, -75, BAND_900, rx));
        List<CellSample> plus3500 = new ArrayList<>(triangle);
        plus3500.add(heard(9L, 52, 80, 30, BAND_3500, rx));
        List<CellSample> plus900 = new ArrayList<>(triangle);
        plus900.add(heard(9L, 52, 80, 30, BAND_900, rx));

        LocatorFix.Fix before = fix(solve(triangle, FLAT, LocatorParams.DEFAULTS));
        LocatorFix.Fix after = fix(solve(plus3500, FLAT, LocatorParams.DEFAULTS));
        LocatorFix.Fix control = fix(solve(plus900, FLAT, LocatorParams.DEFAULTS));

        assertEquals(9.99, before.errorBlocks(), 0.005);
        assertEquals(7.15, after.errorBlocks(), 0.005);
        double ratio = before.errorBlocks() / after.errorBlocks();
        assertEquals(1.40, ratio, 0.005);
        assertEquals(1.12, before.errorBlocks() / control.errorBlocks(), 0.005);
        assertTrue(ratio < 3.0, "one wideband cell in a good triangle stays below 3x: " + ratio);
    }

    // ---- The weighted "±" (owner-approved deviation from §3A.5's errorBlocks formula) ----------

    @Test
    @DisplayName("With every sigma equal, the weighted ± is exactly HDOP x sigma (single-band fixes are unchanged)")
    void equalSigmasGiveHdopTimesSigma() {
        SplittableRandom random = new SplittableRandom(0xE0_A1_5EL);
        Band[] bands = {BAND_900, BAND_1800, BAND_3500};
        int fixes = 0;
        for (int scene = 0; scene < 2_000; scene++) {
            Rx rx = Rx.standingOn(FLAT, random.nextDouble(-150, 150), random.nextDouble(-150, 150));
            int count = 3 + random.nextInt(6);
            List<RangeMeasurement> ranges = new ArrayList<>();
            double sigma;
            if (random.nextBoolean()) {
                // Engine cells of one band, quantised as in the game.
                Band band = bands[random.nextInt(bands.length)];
                sigma = Ranging.sigmaBlocks(Ranging.resolutionBlocks(band.bandwidthMhz(), 1.0));
                List<CellSample> cells = new ArrayList<>();
                for (int i = 0; i < count; i++) {
                    cells.add(heard(i + 1L, random.nextInt(-300, 301), random.nextInt(66, 140),
                            random.nextInt(-300, 301), band, rx));
                }
                ranges.addAll(measure(cells));
            } else {
                // Exact ranges, any common sigma.
                sigma = random.nextDouble(0.1, 20.0);
                for (int i = 0; i < count; i++) {
                    ranges.add(exact(i + 1L, random.nextDouble(-300, 300), random.nextDouble(66, 140),
                            random.nextDouble(-300, 300), rx, sigma));
                }
            }
            LocatorFix result = LocatorSolver.solve(ranges, FLAT, null, LocatorParams.DEFAULTS);
            if (result instanceof LocatorFix.Fix fix) {
                fixes++;
                assertEquals(fix.hdop() * sigma, fix.errorBlocks(), 1e-9 * Math.max(1.0, fix.errorBlocks()),
                        "scene " + scene + ": " + fix);
            }
        }
        assertTrue(fixes > 1_000, "the sweep should mostly give FIX results, got " + fixes);
    }

    @Test
    @DisplayName("Mixed bands: ± is sqrt(trace((HᵀWH)⁻¹)) and HDOP sqrt(trace((HᵀH)⁻¹)) over the same rows at the fix")
    void errorIsTheWeightedCovarianceAndHdopStaysUnweighted() {
        SplittableRandom random = new SplittableRandom(0xC0_7A_12L);
        Band[] bands = {BAND_900, BAND_1800, BAND_3500};
        int mixedFixes = 0;
        for (int scene = 0; scene < 2_000; scene++) {
            Rx rx = Rx.standingOn(FLAT, random.nextDouble(-150, 150), random.nextDouble(-150, 150));
            int count = 3 + random.nextInt(6);
            List<CellSample> cells = new ArrayList<>();
            for (int i = 0; i < count; i++) {
                cells.add(heard(i + 1L, random.nextInt(-300, 301), random.nextInt(66, 140),
                        random.nextInt(-300, 301), bands[random.nextInt(bands.length)], rx));
            }
            List<RangeMeasurement> ranges = measure(cells);
            if (!(LocatorSolver.solve(ranges, FLAT, null, LocatorParams.DEFAULTS) instanceof LocatorFix.Fix fix)) {
                continue;
            }
            // Written out independently: the 2x2 normal matrices at the estimate, inverted directly.
            double a = 0.0;
            double b = 0.0;
            double c = 0.0;
            double wa = 0.0;
            double wb = 0.0;
            double wc = 0.0;
            boolean mixed = false;
            for (RangeMeasurement m : LocatorSolver.cellsUsed(ranges, LocatorParams.DEFAULTS)) {
                mixed |= m.sigmaBlocks() != ranges.get(0).sigmaBlocks();
                double dx = m.x() - fix.x();
                double dz = m.z() - fix.z();
                double d = Math.hypot(dx, dz);
                if (d < 1e-6) {
                    continue;
                }
                double ex = dx / d;
                double ez = dz / d;
                double w = 1.0 / (m.sigmaBlocks() * m.sigmaBlocks());
                a += ex * ex;
                b += ex * ez;
                c += ez * ez;
                wa += w * ex * ex;
                wb += w * ex * ez;
                wc += w * ez * ez;
            }
            double hdop = Math.sqrt((a + c) / (a * c - b * b));
            double weighted = Math.sqrt((wa + wc) / (wa * wc - wb * wb));
            assertEquals(hdop, fix.hdop(), 1e-7 * hdop, "scene " + scene + ": HDOP is unweighted");
            assertEquals(weighted, fix.errorBlocks(), 1e-7 * weighted, "scene " + scene + ": ± is the weighted covariance");
            if (mixed) {
                mixedFixes++;
            }
        }
        assertTrue(mixedFixes > 500, "the sweep should reach many mixed-band fixes, got " + mixedFixes);
    }

    // ---- Test 11: NLOS bias --------------------------------------------------------------------

    @Test
    @DisplayName("Test 11: a positive NLOS bias on one cell pulls the estimate away from that cell")
    void nlosBiasPushesAway() {
        Rx rx = Rx.standingOn(FLAT, 3.5, -4.5);
        CellSample behindHill = heard(1L, 120, 80, 10, PERFECT, 40.0, rx);        // +10 blocks of bias
        CellSample clearOfHill = heard(1L, 120, 80, 10, PERFECT, 0.0, rx);
        List<CellSample> others = List.of(
                heard(2L, -100, 80, 90, PERFECT, rx),
                heard(3L, -90, 80, -110, PERFECT, rx),
                heard(4L, 20, 80, -130, PERFECT, rx));

        List<CellSample> clear = new ArrayList<>(others);
        clear.add(0, clearOfHill);
        List<CellSample> biased = new ArrayList<>(others);
        biased.add(0, behindHill);

        LocatorFix.Fix truth = fix(solve(clear, FLAT, LocatorParams.DEFAULTS));
        LocatorFix.Fix pushed = fix(solve(biased, FLAT, LocatorParams.DEFAULTS));
        assertTrue(horizontalError(truth, rx) < 0.01, "fixture: unbiased ranges recover the truth");

        double cellX = 120.5;
        double cellZ = 10.5;
        double before = Math.hypot(truth.x() - cellX, truth.z() - cellZ);
        double after = Math.hypot(pushed.x() - cellX, pushed.z() - cellZ);
        assertTrue(after > before + 1.0, "estimate should move away from the cell: " + before + " -> " + after);
        // And the move is mostly along the line from the cell through the receiver.
        double awayX = (truth.x() - cellX) / before;
        double awayZ = (truth.z() - cellZ) / before;
        double shift = Math.hypot(pushed.x() - truth.x(), pushed.z() - truth.z());
        double along = (pushed.x() - truth.x()) * awayX + (pushed.z() - truth.z()) * awayZ;
        assertTrue(along > 0.7 * shift, "shift " + shift + ", of which away from the cell " + along);
        assertEquals(truth.errorBlocks(), pushed.errorBlocks(), 1e-3,
                "the bias is systematic: the reported ± does not see it");
    }

    // ---- Test 12: never NaN or Infinity --------------------------------------------------------

    @Test
    @DisplayName("Test 12: the receiver exactly under a tower gets a finite fix")
    void exactlyUnderATower() {
        Rx rx = Rx.standingOn(FLAT, 20.5, 30.5);                    // the column of cell 1
        for (Band band : List.of(PERFECT, BAND_900, BAND_1800, BAND_3500)) {
            List<CellSample> cells = List.of(
                    heard(1L, 20, 95, 30, band, rx),
                    heard(2L, 140, 80, 60, band, rx),
                    heard(3L, -60, 85, 130, band, rx),
                    heard(4L, -30, 75, -90, band, rx));
            LocatorFix result = solve(cells, FLAT, ANY_HDOP);
            assertFinite(result);
            if (band == PERFECT) {
                assertTrue(horizontalError(fix(result), rx) < 0.01, "perfect ranges still find it: " + result);
            }
            // Three cells, one overhead.
            assertFinite(solve(cells.subList(0, 3), FLAT, LocatorParams.DEFAULTS));
            // Two cells, one overhead; and the overhead one alone.
            assertFinite(solve(cells.subList(0, 2), FLAT, LocatorParams.DEFAULTS));
            assertFinite(solve(cells.subList(0, 1), FLAT, LocatorParams.DEFAULTS));
        }
    }

    /**
     * The estimate lands bit-exactly on a tower: by symmetry the first Gauss-Newton step is exactly
     * zero, so the fit stops at the centroid, which is the centre tower. That tower's line of sight
     * then has no horizontal direction at all (0 / 0), and must add no row rather than a NaN.
     */
    @Test
    @DisplayName("Test 12: an estimate exactly on a tower's column still has a finite HDOP")
    void estimateExactlyOnATower() {
        Rx rx = Rx.standingOn(FLAT, 0.5, 0.5);
        List<CellSample> cross = List.of(
                heard(1L, 0, 95, 0, PERFECT, rx),
                heard(2L, 100, 80, 0, PERFECT, rx),
                heard(3L, -100, 80, 0, PERFECT, rx),
                heard(4L, 0, 80, 100, PERFECT, rx),
                heard(5L, 0, 80, -100, PERFECT, rx));

        LocatorFix.Fix fix = fix(solve(cross, FLAT, LocatorParams.DEFAULTS));
        assertEquals(0.5, fix.x(), 0.0, "exactly on the centre tower");
        assertEquals(0.5, fix.z(), 0.0);
        assertEquals(1.0, fix.hdop(), 1e-12, "four rows at 90 degrees; the tower overhead adds none");
        assertFinite(fix);
    }

    @Test
    @DisplayName("Test 12: degenerate inputs never produce NaN or Infinity")
    void degenerateInputs() {
        RangeMeasurement a = new RangeMeasurement(1L, 0.5, 80.5, 0.5, 50.0, 8.66, "band_900", 0.0);
        RangeMeasurement sameSpot = new RangeMeasurement(2L, 0.5, 80.5, 0.5, 70.0, 8.66, "band_900", 0.0);
        RangeMeasurement zeroRange = new RangeMeasurement(3L, 30.5, 80.5, 0.5, 0.0, 8.66, "band_900", 0.0);
        RangeMeasurement zeroSigma = new RangeMeasurement(4L, -20.5, 80.5, 40.5, 45.0, 0.0, "band_900", 0.0);
        RangeMeasurement nanRange = new RangeMeasurement(5L, 60.5, 80.5, 0.5, Double.NaN, 8.66, "band_900", 0.0);
        RangeMeasurement infX = new RangeMeasurement(6L, Double.POSITIVE_INFINITY, 80.5, 0.5, 40.0, 8.66, "x", 0.0);
        RangeMeasurement tooHigh = new RangeMeasurement(7L, 0.5, 5000.5, 0.5, 10.0, 8.66, "band_900", 0.0);

        List<List<RangeMeasurement>> inputs = List.of(
                List.of(a, sameSpot, sameSpot),                // all co-sited
                List.of(a, sameSpot, zeroRange),
                List.of(a, zeroSigma, zeroRange, sameSpot),
                List.of(nanRange, infX, a),                    // invalid ones are ignored: one left
                List.of(nanRange, infX),                       // none left
                List.of(tooHigh, a, zeroRange),                // a cell far above its range
                List.of(zeroRange, zeroRange, zeroRange),
                List.of(a, zeroSigma));
        List<LocatorFix> previous = new ArrayList<>();
        previous.add(null);
        previous.add(new LocatorFix.NoSignal());
        previous.add(new LocatorFix.Fix(10, 70, 10, 1, 1, 3));
        previous.add(new LocatorFix.Ambiguous(5, 5, -5, -5, 0));
        for (List<RangeMeasurement> input : inputs) {
            for (LocatorFix prior : previous) {
                for (SurfaceProbe ground : List.of(FLAT, (SurfaceProbe) (x, z) -> SurfaceProbe.UNLOADED)) {
                    assertFinite(LocatorSolver.solve(input, ground, prior, LocatorParams.DEFAULTS));
                    assertFinite(LocatorSolver.solve(input, ground, prior, ANY_HDOP));
                }
            }
        }
        assertEquals(new LocatorFix.NoSignal(), LocatorSolver.solve(List.of(nanRange, infX), FLAT, null, ANY_HDOP));
    }

    @Test
    @DisplayName("Test 12: 5,000 seeded random scenes, many with the receiver under a tower, are all finite")
    void randomScenesAreFinite() {
        SplittableRandom random = new SplittableRandom(0x5EED_3A5L);
        Band[] bands = {BAND_900, BAND_1800, BAND_3500, PERFECT};
        SurfaceProbe hills = (x, z) -> GROUND + (int) Math.round(6.0 * Math.sin(x / 13.0) * Math.cos(z / 17.0));
        SurfaceProbe holes = (x, z) -> Math.floorMod(x * 7 + z * 3, 11) == 0 ? SurfaceProbe.UNLOADED : GROUND;
        SurfaceProbe[] grounds = {FLAT, hills, holes};
        int fixes = 0;
        LocatorFix previous = null;

        for (int scene = 0; scene < 5_000; scene++) {
            SurfaceProbe ground = grounds[random.nextInt(grounds.length)];
            int count = 1 + random.nextInt(8);
            int[][] voxels = new int[count][];
            for (int i = 0; i < count; i++) {
                // Small coordinates on purpose: coincident and collinear cells come up often.
                voxels[i] = new int[] {random.nextInt(-8, 9) * 20, random.nextInt(66, 140), random.nextInt(-8, 9) * 20};
            }
            double rxX;
            double rxZ;
            if (random.nextInt(3) == 0) {
                int[] tower = voxels[random.nextInt(count)];
                rxX = tower[0] + 0.5;                               // exactly under it
                rxZ = tower[2] + 0.5;
            } else {
                rxX = random.nextDouble(-200, 200);
                rxZ = random.nextDouble(-200, 200);
            }
            int feet = ground.surfaceY((int) Math.floor(rxX), (int) Math.floor(rxZ));
            Rx rx = new Rx(rxX, (feet == SurfaceProbe.UNLOADED ? GROUND : feet) + EYE, rxZ);

            List<CellSample> cells = new ArrayList<>();
            for (int i = 0; i < count; i++) {
                double obstruction = random.nextInt(4) == 0 ? random.nextDouble(0, 60) : 0.0;
                cells.add(heard(i + 1L, voxels[i][0], voxels[i][1], voxels[i][2],
                        bands[random.nextInt(bands.length)], obstruction, rx));
            }
            LocatorParams params = random.nextBoolean() ? LocatorParams.DEFAULTS : ANY_HDOP;
            LocatorFix result = LocatorSolver.solve(measure(cells), ground, previous, params);
            assertFinite(result);
            if (result instanceof LocatorFix.Fix) {
                fixes++;
            }
            previous = result;
        }
        assertTrue(fixes > 500, "the sweep should reach the least-squares path often, got " + fixes);
    }

    private static void assertFinite(LocatorFix result) {
        double[] values = switch (result) {
            case LocatorFix.NoSignal ignored -> new double[0];
            case LocatorFix.RangeOnly r -> new double[] {r.cx(), r.cz(), r.radius()};
            case LocatorFix.Ambiguous a -> new double[] {a.ax(), a.az(), a.bx(), a.bz()};
            case LocatorFix.PoorGeometry p -> new double[] {p.hdop()};
            case LocatorFix.Fix f -> new double[] {f.x(), f.y(), f.z(), f.hdop(), f.errorBlocks()};
        };
        for (double value : values) {
            if (!Double.isFinite(value)) {
                fail("non-finite value in " + result);
            }
        }
        if (result instanceof LocatorFix.Ambiguous a) {
            assertTrue(a.likely() >= LocatorFix.Ambiguous.NO_PREFERENCE && a.likely() <= 1, result.toString());
        }
    }

    // ---- the mirror check (row 16d) -------------------------------------------------------------

    /** Outcomes over seeded scenes: FIX, FIX on the wrong side of z = 0, AMBIGUOUS, AMBIGUOUS holding the truth. */
    private record MirrorTally(int fix, int wrongSide, int ambiguous, int ambiguousHoldsTruth) {
    }

    /**
     * band_900 scenes on flat ground: masts radiating at y 80 from {@code masts} ({x, z} each), the
     * receiver uniform in x [x0, x1], z [z0, z1]. "Wrong side" is a FIX on the other side of z = 0
     * from the receiver; "holds the truth" is an AMBIGUOUS with a candidate within 40 blocks of it.
     */
    private static MirrorTally mirrorScenes(int[][] masts, double x0, double x1, double z0, double z1,
                                            double margin, long seed) {
        java.util.Random random = new java.util.Random(seed);
        int fix = 0;
        int wrong = 0;
        int ambiguous = 0;
        int holds = 0;
        for (int scene = 0; scene < 4_000; scene++) {
            Rx rx = Rx.standingOn(FLAT, x0 + random.nextDouble() * (x1 - x0), z0 + random.nextDouble() * (z1 - z0));
            List<CellSample> cells = new java.util.ArrayList<>();
            for (int i = 0; i < masts.length; i++) {
                cells.add(heard(i + 1L, masts[i][0], 80, masts[i][1], BAND_900, rx));
            }
            LocatorFix result = LocatorSolver.solve(measure(cells), FLAT, null, LocatorParams.DEFAULTS, margin);
            if (result instanceof LocatorFix.Fix f) {
                fix++;
                if (f.z() * rx.z() < 0.0) {
                    wrong++;
                }
            } else if (result instanceof LocatorFix.Ambiguous a) {
                ambiguous++;
                if (Math.min(Math.hypot(a.ax() - rx.x(), a.az() - rx.z()), Math.hypot(a.bx() - rx.x(), a.bz() - rx.z())) < 40.0) {
                    holds++;
                }
            }
        }
        return new MirrorTally(fix, wrong, ambiguous, holds);
    }

    /**
     * Row 16d, the slices 2-3 gate's follow-up: three band_900 masts 300 blocks end to end with the
     * middle one 10 blocks off the line, the receiver 20-120 blocks from it. Without the mirror check
     * about a third of all scenes were a FIX on the wrong side, about 140 blocks off with a "±" of
     * about 12. With it (margin {@link LocatorSolver#MIRROR_COST_MARGIN}) under 5 % are, the rest of
     * those being AMBIGUOUS with the truth among the two candidates (NOTES.md, row 16d: the full
     * table, measured 1.8 % over both offsets).
     */
    @Test
    @DisplayName("Nearly collinear sites: AMBIGUOUS (both sides) instead of a confident FIX on the wrong side")
    void mirrorCheckNearlyCollinear() {
        for (int offset : new int[] {10, -10}) {
            int[][] masts = {{-150, 0}, {0, offset}, {150, 0}};
            MirrorTally before = mirrorScenes(masts, -150, 150, 20, 120, -1.0, 42L + offset);
            MirrorTally after = mirrorScenes(masts, -150, 150, 20, 120, LocatorSolver.MIRROR_COST_MARGIN, 42L + offset);
            assertTrue(before.wrongSide() > 800 && before.ambiguous() == 0,
                    "the old solver's confident wrong-side FIX (offset " + offset + "): " + before);
            assertTrue(after.wrongSide() < 200, "now under 5 % of scenes (offset " + offset + "): " + after);
            assertTrue(after.ambiguous() > 2_500, "most become AMBIGUOUS (offset " + offset + "): " + after);
            assertEquals(after.ambiguous(), after.ambiguousHoldsTruth(),
                    "every AMBIGUOUS has the truth among its candidates (offset " + offset + "): " + after);
        }
    }

    @Test
    @DisplayName("The mirror check leaves well-spread sites alone: no AMBIGUOUS from three or more good sites")
    void mirrorCheckLeavesGoodGeometryAlone() {
        int[][] triangle = {{100, 0}, {-50, 87}, {-50, -87}};
        int[][] square = {{-100, -100}, {100, -100}, {100, 100}, {-100, 100}};
        double margin = LocatorSolver.MIRROR_COST_MARGIN;
        assertEquals(0, mirrorScenes(triangle, -50, 50, -50, 50, margin, 7L).ambiguous(), "triangle, inside");
        assertEquals(0, mirrorScenes(triangle, 120, 260, -100, 100, margin, 8L).ambiguous(), "triangle, outside");
        assertEquals(0, mirrorScenes(square, -90, 90, -90, 90, margin, 9L).ambiguous(), "square, inside");
    }

    @Test
    @DisplayName("The sites' line and the mirror image across it")
    void siteLineAndMirror() {
        List<RangeMeasurement> sites = List.of(
                new RangeMeasurement(1L, -100.0, 80.0, -100.0, 10.0, 1.0, "band_900", 0.0),
                new RangeMeasurement(2L, 0.0, 80.0, 0.0, 10.0, 1.0, "band_900", 0.0),
                new RangeMeasurement(3L, 100.0, 80.0, 100.0, 10.0, 1.0, "band_900", 0.0));
        double[] line = LocatorSolver.siteLine(sites);
        assertEquals(0.0, line[0], 1e-9);
        assertEquals(0.0, line[1], 1e-9);
        assertEquals(1.0, Math.abs(line[2] * line[3]) * 2.0, 1e-9, "along the diagonal x = z");
        double[] image = LocatorSolver.mirrored(line, 30.0, -10.0);
        assertEquals(-10.0, image[0], 1e-9);
        assertEquals(30.0, image[1], 1e-9);
    }
}
