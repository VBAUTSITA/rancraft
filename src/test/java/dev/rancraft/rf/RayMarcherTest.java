package dev.rancraft.rf;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class RayMarcherTest {

    private static final double MAX_OBSTRUCTION_DB = 120.0;

    private static RayMarcher.MarchResult march(WorldProbe probe,
                                                double x0, double y0, double z0,
                                                double x1, double y1, double z1) {
        return RayMarcher.march(probe, x0, y0, z0, x1, y1, z1,
                1.0, MAX_OBSTRUCTION_DB, RayMarcher.DEFAULT_MAX_STEPS);
    }

    @Test
    @DisplayName("Obstruction additivity: three 12 dB voxels on the path add exactly 36 dB")
    void obstructionAdditivity() {
        RayMarcher.MarchResult clear = march(WorldProbe.AIR, 0.5, 0.5, 0.5, 10.5, 0.5, 0.5);
        assertEquals(0.0, clear.obstructionDb(), 1e-9);

        TestProbes.Sparse walls = new TestProbes.Sparse(12.0).add(3, 0, 0).add(4, 0, 0).add(5, 0, 0);
        RayMarcher.MarchResult blocked = march(walls, 0.5, 0.5, 0.5, 10.5, 0.5, 0.5);

        assertEquals(36.0, blocked.obstructionDb() - clear.obstructionDb(), 1e-9);
    }

    @Test
    @DisplayName("Source and destination voxels are both skipped")
    void endpointsSkipped() {
        // Attenuating blocks sitting exactly on the transmitter and the receiver must not count.
        TestProbes.Sparse endpoints = new TestProbes.Sparse(60.0).add(0, 0, 0).add(10, 0, 0);
        RayMarcher.MarchResult result = march(endpoints, 0.5, 0.5, 0.5, 10.5, 0.5, 0.5);
        assertEquals(0.0, result.obstructionDb(), 1e-9);
    }

    @Test
    @DisplayName("Early exit: a 200 dB first voxel terminates the march in <= 3 probe calls")
    void earlyExit() {
        TestProbes.Counting probe = new TestProbes.Counting(200.0);
        RayMarcher.MarchResult result = march(probe, 0.5, 0.5, 0.5, 400.5, 0.5, 0.5);

        assertTrue(result.earlyExit(), "march should report an early exit");
        assertTrue(probe.calls <= 3, "expected <= 3 probe calls, got " + probe.calls);
        assertTrue(result.obstructionDb() > MAX_OBSTRUCTION_DB);
    }

    @Test
    @DisplayName("Ray symmetry: A->B and B->A agree within 0.5 dB")
    void raySymmetry() {
        TestProbes.Scatter probe = new TestProbes.Scatter(3.0);

        double ax = 0.5, ay = 0.5, az = 0.5;
        double bx = 20.3, by = 7.7, bz = 13.1;

        RayMarcher.MarchResult forward = march(probe, ax, ay, az, bx, by, bz);
        RayMarcher.MarchResult backward = march(probe, bx, by, bz, ax, ay, az);

        assertEquals(forward.obstructionDb(), backward.obstructionDb(), 0.5,
                "forward=" + forward.obstructionDb() + " backward=" + backward.obstructionDb());
    }

    @Test
    @DisplayName("Step cap: an over-long ray reports cappedOut instead of grinding on")
    void stepCap() {
        RayMarcher.MarchResult result = RayMarcher.march(
                WorldProbe.AIR, 0.5, 0.5, 0.5, 5000.5, 0.5, 0.5,
                1.0, MAX_OBSTRUCTION_DB, RayMarcher.DEFAULT_MAX_STEPS);

        assertTrue(result.cappedOut(), "expected the step cap to trip");
        assertEquals(RayMarcher.DEFAULT_MAX_STEPS, result.steps());
        assertFalse(result.earlyExit());
    }

    @Test
    @DisplayName("Receiver inside the antenna voxel marches nothing")
    void sameVoxel() {
        TestProbes.Counting probe = new TestProbes.Counting(60.0);
        RayMarcher.MarchResult result = march(probe, 0.5, 0.5, 0.5, 0.7, 0.2, 0.9);

        assertEquals(0.0, result.obstructionDb(), 1e-9);
        assertEquals(0, probe.calls);
    }
}
