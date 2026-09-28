package dev.rancraft.rf;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.rancraft.rf.PatternMesh.Shell;
import dev.rancraft.rf.PatternMesh.Vertex;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** RF Lens Step 1: the pattern geometry feeding the 3D lobe. */
class PatternMeshTest {

    private static final ParabolicPattern PARABOLIC = ParabolicPattern.defaults();

    private static double length(Vertex v) {
        return Math.sqrt(v.x() * v.x() + v.y() * v.y() + v.z() * v.z());
    }

    // ---- orientation: the 90-degree-rotation guard ------------------------------------------

    @Test
    @DisplayName("Cartesian mapping matches AntennaGeometry: 0=north(-Z), 90=east(+X)")
    void cardinalsMatchEngineConvention() {
        Vertex north = PatternMesh.toCartesian(0.0, 0.0, 1.0);
        assertEquals(0.0, north.x(), 1e-9);
        assertEquals(-1.0, north.z(), 1e-9, "bearing 0 must point at -Z");

        Vertex east = PatternMesh.toCartesian(90.0, 0.0, 1.0);
        assertEquals(1.0, east.x(), 1e-9, "bearing 90 must point at +X");
        assertEquals(0.0, east.z(), 1e-9);

        Vertex south = PatternMesh.toCartesian(180.0, 0.0, 1.0);
        assertEquals(1.0, south.z(), 1e-9);

        Vertex west = PatternMesh.toCartesian(270.0, 0.0, 1.0);
        assertEquals(-1.0, west.x(), 1e-9);
    }

    @Test
    @DisplayName("Positive elevation is up (+Y), and straight up has no horizontal component")
    void elevationMapsToY() {
        Vertex up = PatternMesh.toCartesian(0.0, 90.0, 1.0);
        assertEquals(1.0, up.y(), 1e-9);
        assertEquals(0.0, up.x(), 1e-9);
        assertEquals(0.0, up.z(), 1e-9);

        assertTrue(PatternMesh.toCartesian(0.0, 45.0, 1.0).y() > 0.0);
        assertTrue(PatternMesh.toCartesian(0.0, -45.0, 1.0).y() < 0.0);
    }

    // ---- radius normalisation ----------------------------------------------------------------

    @Test
    @DisplayName("Boresight on the 0 dB shell is unit radius; deeper shells sit strictly inside")
    void shellsNest() {
        double peak = 15.0;
        assertEquals(1.0, PatternMesh.radius(peak, peak, 0.0), 1e-9);

        double outer = PatternMesh.radius(peak, peak, 3.0);
        double middle = PatternMesh.radius(peak, peak, 10.0);
        double inner = PatternMesh.radius(peak, peak, 20.0);

        assertTrue(outer > middle, "the -3 dB shell must enclose the -10 dB shell");
        assertTrue(middle > inner, "the -10 dB shell must enclose the -20 dB shell");
        assertTrue(inner > 0.0);
    }

    @Test
    @DisplayName("Radius clamps to zero once the pattern is past the plot range, never negative")
    void radiusClamps() {
        assertEquals(0.0, PatternMesh.radius(-100.0, 15.0, 0.0), 1e-9);
        assertEquals(0.0, PatternMesh.radius(15.0, 15.0, PatternMesh.PLOT_RANGE_DB + 10.0), 1e-9);
        assertTrue(PatternMesh.radius(-1000.0, 15.0, 20.0) >= 0.0);
    }

    // ---- shape: the thing a player actually sees ---------------------------------------------

    @Test
    @DisplayName("An omni mast renders as a sphere: every vertex the same distance from centre")
    void omniIsASphere() {
        CellParams omni = TestCells.omni(1L, 0, 64, 0);
        List<Shell> shells = PatternMesh.build(omni, OmniPattern.INSTANCE);

        assertEquals(PatternMesh.DEFAULT_SHELLS_DB.length, shells.size());

        for (Shell shell : shells) {
            assertFalse(shell.lines().isEmpty());
            double expected = PatternMesh.radius(omni.gainDbi(), omni.gainDbi(), shell.depthDb());
            for (Vertex v : shell.lines()) {
                assertEquals(expected, length(v), 1e-9,
                        "omni must be flat in every direction at depth " + shell.depthDb());
            }
        }
    }

    @Test
    @DisplayName("A sector points where its azimuth says: the longest vertex lies on boresight")
    void sectorPointsAtItsAzimuth() {
        for (double azimuth : new double[] {0.0, 90.0, 180.0, 270.0, 120.0}) {
            CellParams sector = TestCells.sector(1L, 0, 64, 0, azimuth, 0.0);
            List<Shell> shells = PatternMesh.build(sector, PARABOLIC);

            Vertex longest = null;
            for (Vertex v : shells.get(0).lines()) {
                if (longest == null || length(v) > length(longest)) {
                    longest = v;
                }
            }

            Vertex expected = PatternMesh.toCartesian(azimuth, 0.0, length(longest));
            assertEquals(expected.x(), longest.x(), 0.05, "azimuth " + azimuth + " x");
            assertEquals(expected.z(), longest.z(), 0.05, "azimuth " + azimuth + " z");
        }
    }

    @Test
    @DisplayName("A sector is not a sphere: the backlobe is far shorter than boresight")
    void sectorHasAFrontAndABack() {
        CellParams sector = TestCells.sector(1L, 0, 64, 0, 0.0, 0.0);
        List<Shell> shells = PatternMesh.build(sector, PARABOLIC);

        double peak = sector.gainDbi();
        double front = PatternMesh.radius(PARABOLIC.gainDbi(0.0, 0.0, sector), peak, 3.0);
        double back = PatternMesh.radius(PARABOLIC.gainDbi(180.0, 0.0, sector), peak, 3.0);

        assertTrue(front > back, "boresight must reach further than the backlobe");
        assertTrue(front - back > 0.5, "a 30 dB front-to-back should be obvious, not subtle");
        assertFalse(shells.get(0).lines().isEmpty());
    }

    @Test
    @DisplayName("Downtilt tips the beam below the horizon")
    void downtiltTipsTheLobeDown() {
        CellParams level = TestCells.sector(1L, 0, 64, 0, 0.0, 0.0);
        CellParams tilted = TestCells.withTilt(level, 10.0);

        // On boresight bearing, compare the radius above and below the horizon.
        double levelUp = PatternMesh.radius(PARABOLIC.gainDbi(0.0, 10.0, level), level.gainDbi(), 3.0);
        double levelDown = PatternMesh.radius(PARABOLIC.gainDbi(0.0, -10.0, level), level.gainDbi(), 3.0);
        assertEquals(levelUp, levelDown, 1e-9, "an untilted beam is symmetric about the horizon");

        double tiltedDown = PatternMesh.radius(
                PARABOLIC.gainDbi(0.0, -10.0, tilted), tilted.gainDbi(), 3.0);
        double tiltedUp = PatternMesh.radius(
                PARABOLIC.gainDbi(0.0, 10.0, tilted), tilted.gainDbi(), 3.0);

        assertTrue(tiltedDown > tiltedUp, "10 degrees of downtilt must favour below the horizon");
        assertTrue(tiltedDown > levelDown, "and must reach further down than an untilted beam");
    }

    // ---- structure ---------------------------------------------------------------------------

    @Test
    @DisplayName("Lines come back as point pairs, ready for a LINES draw with no index buffer")
    void linesArePaired() {
        CellParams sector = TestCells.sector(1L, 0, 64, 0, 0.0, 3.0);
        for (Shell shell : PatternMesh.build(sector, PARABOLIC)) {
            assertEquals(0, shell.lines().size() % 2,
                    "an odd vertex count would leave a dangling segment");
        }
    }

    @Test
    @DisplayName("A coarser step yields fewer vertices, so the renderer can pick its cost")
    void stepControlsDetail() {
        CellParams sector = TestCells.sector(1L, 0, 64, 0, 0.0, 3.0);
        int fine = PatternMesh.build(sector, PARABOLIC, new double[] {3.0}, 6).get(0).lines().size();
        int coarse = PatternMesh.build(sector, PARABOLIC, new double[] {3.0}, 15).get(0).lines().size();

        assertTrue(coarse < fine);
        assertTrue(coarse > 0);
    }

    @Test
    @DisplayName("Every vertex stays inside the unit sphere, so world scaling is predictable")
    void staysNormalised() {
        CellParams sector = TestCells.sector(1L, 0, 64, 0, 37.0, 5.0);
        for (Shell shell : PatternMesh.build(sector, PARABOLIC)) {
            for (Vertex v : shell.lines()) {
                assertTrue(length(v) <= 1.0 + 1e-9, "vertex escaped the unit sphere: " + length(v));
            }
        }
    }
}
