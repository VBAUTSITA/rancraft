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
     * Pins the one deviation from §3A.5's algorithm (NOTES.md, Phase 3 slice 3). From the centroid
     * alone, Gauss-Newton settles at (234, -7) here, a wrong local minimum with HDOP 4.3, so the
     * spec's solver would report FIX "± 0" nearly 400 blocks from the truth, with perfect ranges.
     * The extra starts from the strongest cells' circle crossings find the real minimum.
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

    // ---- Test 10: a wideband cell --------------------------------------------------------------

    /**
     * Test 10 as listed. With {@code errorBlocks = HDOP x rms(sigma)} (§3A.5, followed to the letter)
     * one wideband cell cannot cut the "±" 3x on bandwidth alone: see
     * {@link #oneWidebandCellInAGoodTriangle()}. It does here, at the edge of a network: three band_900
     * sites clustered to the west (a 20 degree wedge, HDOP about 4.1) and a band_3500 site added to
     * the north. Most of that gain is geometry (the same site on band_900 also helps about 3.6x); the
     * control below pins the part that is bandwidth. NOTES.md, Phase 3 slice 3, has the numbers and
     * the open question for the spec.
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

        LocatorFix.Fix before = fix(solve(west, FLAT, LocatorParams.DEFAULTS));
        LocatorFix.Fix after = fix(solve(plus3500, FLAT, LocatorParams.DEFAULTS));
        LocatorFix.Fix control = fix(solve(plus900, FLAT, LocatorParams.DEFAULTS));

        assertTrue(before.errorBlocks() >= 3.0 * after.errorBlocks(),
                "errorBlocks " + before.errorBlocks() + " -> " + after.errorBlocks());
        assertTrue(after.errorBlocks() < control.errorBlocks(),
                "the band_3500 site must beat the same site on band_900: "
                        + after.errorBlocks() + " vs " + control.errorBlocks());
        // Same geometry, so the difference is exactly the rms of the sigmas.
        double sigma900 = Ranging.sigmaBlocks(Ranging.resolutionBlocks(10.0, 1.0));
        double sigma3500 = Ranging.sigmaBlocks(Ranging.resolutionBlocks(100.0, 1.0));
        double rmsRatio = sigma900 / Math.sqrt((3 * sigma900 * sigma900 + sigma3500 * sigma3500) / 4.0);
        assertEquals(rmsRatio, (control.errorBlocks() / control.hdop()) / (after.errorBlocks() / after.hdop()), 1e-9);
    }

    /**
     * Pins the limit the spec's test 10 runs into, so NOTES.md's numbers are checked, not asserted:
     * in an already good triangle one band_3500 cell improves the "±" by about 1.3x, not 3x. A single
     * range constrains one direction; the rms of the sigmas is still dominated by the coarse cells.
     */
    @Test
    @DisplayName("Limit: in a good 120 degree triangle one band_3500 cell improves errorBlocks only ~1.3x")
    void oneWidebandCellInAGoodTriangle() {
        Rx rx = Rx.standingOn(FLAT, 0.5, 0.5);
        List<CellSample> triangle = List.of(
                heard(1L, 0, 80, 150, BAND_900, rx),
                heard(2L, -130, 80, -75, BAND_900, rx),
                heard(3L, 130, 80, -75, BAND_900, rx));
        List<CellSample> plus3500 = new ArrayList<>(triangle);
        plus3500.add(heard(9L, 52, 80, 30, BAND_3500, rx));

        double ratio = fix(solve(triangle, FLAT, LocatorParams.DEFAULTS)).errorBlocks()
                / fix(solve(plus3500, FLAT, LocatorParams.DEFAULTS)).errorBlocks();
        assertTrue(ratio > 1.2 && ratio < 1.4, "ratio " + ratio);
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
}
