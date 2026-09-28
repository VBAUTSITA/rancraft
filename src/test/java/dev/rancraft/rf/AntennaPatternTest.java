package dev.rancraft.rf;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Phase 2 tests 1-6: the 3GPP parabolic pattern. */
class AntennaPatternTest {

    private static final double A_M = ParabolicPattern.DEFAULT_FRONT_TO_BACK_DB;
    private static final double SLA_V = ParabolicPattern.DEFAULT_SIDELOBE_FLOOR_DB;
    private static final double H_BEAMWIDTH = 65.0;
    private static final double V_BEAMWIDTH = 10.0;

    private final ParabolicPattern pattern = ParabolicPattern.defaults();

    // ---- Test 1 ----------------------------------------------------------------------------

    @Test
    @DisplayName("1. A_H(0) is exactly 0 dB on boresight")
    void horizontalBoresightIsZero() {
        assertEquals(0.0, pattern.horizontalAttenuationDb(0.0, H_BEAMWIDTH), 0.0);
    }

    // ---- Test 2 ----------------------------------------------------------------------------

    @Test
    @DisplayName("2. A_H(+-hBeamwidth/2) is exactly -3.0 dB -- the definition of 3 dB beamwidth")
    void halfBeamwidthIsThreeDbDown() {
        assertEquals(-3.0, pattern.horizontalAttenuationDb(H_BEAMWIDTH / 2.0, H_BEAMWIDTH), 1e-9);
        assertEquals(-3.0, pattern.horizontalAttenuationDb(-H_BEAMWIDTH / 2.0, H_BEAMWIDTH), 1e-9);

        // Holds for any beamwidth: it falls out of the shape constant 12, not out of 65 degrees.
        for (double bw : new double[] {30.0, 45.0, 90.0, 120.0}) {
            assertEquals(-3.0, pattern.horizontalAttenuationDb(bw / 2.0, bw), 1e-9,
                    "3 dB point for beamwidth " + bw);
        }
    }

    @Test
    @DisplayName("2b. A_V(+-vBeamwidth/2) is exactly -3.0 dB too")
    void verticalHalfBeamwidthIsThreeDbDown() {
        assertEquals(-3.0, pattern.verticalAttenuationDb(V_BEAMWIDTH / 2.0, V_BEAMWIDTH), 1e-9);
        assertEquals(-3.0, pattern.verticalAttenuationDb(-V_BEAMWIDTH / 2.0, V_BEAMWIDTH), 1e-9);
    }

    // ---- Test 3 ----------------------------------------------------------------------------

    @Test
    @DisplayName("3. A_H saturates at exactly -A_m and is symmetric about boresight")
    void horizontalSaturatesAndIsSymmetric() {
        assertEquals(-A_M, pattern.horizontalAttenuationDb(180.0, H_BEAMWIDTH), 1e-9);
        assertEquals(-A_M, pattern.horizontalAttenuationDb(-180.0, H_BEAMWIDTH), 1e-9);
        assertEquals(-A_M, pattern.horizontalAttenuationDb(120.0, H_BEAMWIDTH), 1e-9);

        for (double phi = 0.0; phi <= 180.0; phi += 1.0) {
            assertEquals(
                    pattern.horizontalAttenuationDb(phi, H_BEAMWIDTH),
                    pattern.horizontalAttenuationDb(-phi, H_BEAMWIDTH),
                    1e-12,
                    "symmetry at phi=" + phi);
        }
    }

    @Test
    @DisplayName("3b. A_V saturates at exactly -SLA_v")
    void verticalSaturatesAtSidelobeFloor() {
        assertEquals(-SLA_V, pattern.verticalAttenuationDb(90.0, V_BEAMWIDTH), 1e-9);
        assertEquals(-SLA_V, pattern.verticalAttenuationDb(-90.0, V_BEAMWIDTH), 1e-9);
    }

    // ---- Test 4 ----------------------------------------------------------------------------

    @Test
    @DisplayName("4. A 360-degree cell has identical gain at bearing 0/90/180/270")
    void omniIsFlatInAzimuth() {
        CellParams omni = TestCells.omni(1L, 0, 64, 0);
        double reference = pattern.gainDbi(0.0, 0.0, omni);
        for (double bearing : new double[] {0.0, 90.0, 180.0, 270.0, 37.4}) {
            assertEquals(reference, pattern.gainDbi(bearing, 0.0, omni), 1e-12,
                    "bearing " + bearing);
        }
        assertEquals(0.0, pattern.horizontalAttenuationDb(180.0, 360.0), 0.0);
    }

    @Test
    @DisplayName("4b. The factory short-circuits a 360-degree cell to the flat OmniPattern")
    void factoryPicksOmni() {
        CellParams omni = TestCells.omni(1L, 0, 64, 0);
        AntennaPattern picked = AntennaPattern.forCell(omni, A_M, SLA_V);
        assertSame(OmniPattern.INSTANCE, picked);

        // Flat in elevation as well -- this is what keeps the Phase 1 regression exact.
        assertEquals(omni.gainDbi(), picked.gainDbi(0.0, -45.0, omni), 0.0);
        assertEquals(omni.gainDbi(), picked.gainDbi(123.0, 80.0, omni), 0.0);

        CellParams sector = TestCells.sector(2L, 0, 64, 0, 0.0, 0.0);
        assertTrue(AntennaPattern.forCell(sector, A_M, SLA_V) instanceof ParabolicPattern);
    }

    // ---- Test 5 ----------------------------------------------------------------------------

    @Test
    @DisplayName("5. Downtilt 10 aims the beam at elevation -10: A_V is 0 there, negative without it")
    void downtiltAimsBelowHorizon() {
        double withTilt = ParabolicPattern.elevationOffsetDeg(-10.0, 10.0);
        assertEquals(0.0, withTilt, 1e-12);
        assertEquals(0.0, pattern.verticalAttenuationDb(withTilt, V_BEAMWIDTH), 1e-12);

        double withoutTilt = ParabolicPattern.elevationOffsetDeg(-10.0, 0.0);
        assertTrue(pattern.verticalAttenuationDb(withoutTilt, V_BEAMWIDTH) < 0.0,
                "an untilted antenna must not give full gain 10 degrees below the horizon");
    }

    // ---- Test 6 ----------------------------------------------------------------------------

    @Test
    @DisplayName("6. Uptilt (negative tilt) raises the beam: more gain above the horizon")
    void uptiltRaisesTheBeam() {
        CellParams level = TestCells.sector(1L, 0, 64, 0, 0.0, 0.0);
        CellParams uptilted = TestCells.withTilt(level, -10.0);

        double gainLevel = pattern.gainDbi(0.0, 10.0, level);
        double gainUptilted = pattern.gainDbi(0.0, 10.0, uptilted);

        assertTrue(gainUptilted > gainLevel,
                "uptilt should help a receiver above the antenna: " + gainUptilted + " vs " + gainLevel);
        assertEquals(uptilted.gainDbi(), gainUptilted, 1e-12, "10 degrees up is exactly boresight");
    }

    // ---- Geometry: the 90-degree-azimuth-error guard ----------------------------------------

    @Test
    @DisplayName("Bearing convention: 0=north(-Z), 90=east(+X), 180=south(+Z), 270=west(-X)")
    void bearingCardinals() {
        assertEquals(0.0, AntennaGeometry.bearingDeg(0, 0, 0, -10), 1e-9, "north");
        assertEquals(90.0, AntennaGeometry.bearingDeg(0, 0, 10, 0), 1e-9, "east");
        assertEquals(180.0, AntennaGeometry.bearingDeg(0, 0, 0, 10), 1e-9, "south");
        assertEquals(270.0, AntennaGeometry.bearingDeg(0, 0, -10, 0), 1e-9, "west");
        assertEquals(45.0, AntennaGeometry.bearingDeg(0, 0, 10, -10), 1e-9, "north-east");
    }

    @Test
    @DisplayName("Elevation is positive when the receiver is above the antenna")
    void elevationSign() {
        assertTrue(AntennaGeometry.elevationDeg(0, 64, 0, 10, 74, 0) > 0.0);
        assertTrue(AntennaGeometry.elevationDeg(0, 64, 0, 10, 54, 0) < 0.0);
        assertEquals(0.0, AntennaGeometry.elevationDeg(0, 64, 0, 10, 64, 0), 1e-9);
        assertEquals(-45.0, AntennaGeometry.elevationDeg(0, 64, 0, 10, 54, 0), 1e-9);
        // Not exactly 90: the 1e-6 horizontal floor that stops atan2(0,0) reporting level also
        // stops the vertical case reaching a clean 90. 1e-4 degrees of error is irrelevant to gain.
        assertEquals(90.0, AntennaGeometry.elevationDeg(0, 64, 0, 0, 74, 0), 1e-4,
                "straight up must not read as level");
    }

    @Test
    @DisplayName("wrapTo180 keeps the offset signed and symmetric across the 0/360 seam")
    void azimuthOffsetWraps() {
        assertEquals(-10.0, ParabolicPattern.azimuthOffsetDeg(350.0, 0.0), 1e-9);
        assertEquals(10.0, ParabolicPattern.azimuthOffsetDeg(10.0, 0.0), 1e-9);
        assertEquals(-20.0, ParabolicPattern.azimuthOffsetDeg(10.0, 30.0), 1e-9);
        assertEquals(180.0, ParabolicPattern.azimuthOffsetDeg(180.0, 0.0), 1e-9);
    }

    // ---- Combined gain and gain-beamwidth reciprocity ---------------------------------------

    @Test
    @DisplayName("Combined loss is capped at A_m, so the back lobe is never worse than front-to-back")
    void combinedLossCapped() {
        CellParams sector = TestCells.sector(1L, 0, 64, 0, 0.0, 0.0);
        // Pointed 180 degrees away and far off in elevation: both cuts saturate at 30 dB each.
        double gain = pattern.gainDbi(180.0, 60.0, sector);
        assertEquals(sector.gainDbi() - A_M, gain, 1e-9);
    }

    @Test
    @DisplayName("Boresight gain is the full antenna gain")
    void boresightIsFullGain() {
        CellParams sector = TestCells.sector(1L, 0, 64, 0, 120.0, 0.0);
        assertEquals(sector.gainDbi(), pattern.gainDbi(120.0, 0.0, sector), 1e-12);
    }

    @Test
    @DisplayName("Gain-beamwidth reciprocity: 65x10 gives ~15.0 dBi, matching a real sector panel")
    void gainFromBeamwidth() {
        assertEquals(15.0, ParabolicPattern.gainFromBeamwidthDbi(65.0, 10.0), 0.05);

        // Wider is always weaker, so a 360-degree omni cannot buy gain by being wide.
        assertTrue(ParabolicPattern.gainFromBeamwidthDbi(120.0, 10.0)
                < ParabolicPattern.gainFromBeamwidthDbi(65.0, 10.0));
        assertTrue(ParabolicPattern.gainFromBeamwidthDbi(360.0, 30.0)
                < ParabolicPattern.gainFromBeamwidthDbi(65.0, 10.0));

        assertEquals(ParabolicPattern.MAX_DERIVED_GAIN_DBI,
                ParabolicPattern.gainFromBeamwidthDbi(5.0, 5.0), 1e-9, "clamped at 22 dBi");
        assertTrue(ParabolicPattern.gainFromBeamwidthDbi(360.0, 90.0) >= ParabolicPattern.MIN_DERIVED_GAIN_DBI);
    }
}
