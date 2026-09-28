package dev.rancraft.rf;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.rancraft.rf.LinkTracer.Breakpoint;
import dev.rancraft.rf.LinkTracer.Trace;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class LinkTracerTest {

    private static final double MAX_OBSTRUCTION_DB = 120.0;
    private static final int UNCAPPED = Integer.MAX_VALUE;

    /** A spread of links: axis-aligned, diagonal in every octant, short and long. */
    private static final double[][] LINKS = {
            {0.5, 64.5, 0.5, 40.5, 64.5, 0.5},
            {0.5, 70.5, 0.5, 30.3, 64.2, 20.9},
            {12.5, 80.5, -7.5, -41.7, 65.6, 33.2},
            {-3.5, 66.5, 9.5, 55.1, 71.9, -48.4},
            {100.5, 90.5, 100.5, 23.8, 65.62, 61.1},
    };

    private static Trace trace(WorldProbe probe, double[] link, double penetrationFactor, int maxBreakpoints) {
        return LinkTracer.trace(probe, link[0], link[1], link[2], link[3], link[4], link[5],
                penetrationFactor, MAX_OBSTRUCTION_DB, RayMarcher.DEFAULT_MAX_STEPS, maxBreakpoints);
    }

    private static RayMarcher.MarchResult march(WorldProbe probe, double[] link, double penetrationFactor) {
        return RayMarcher.march(probe, link[0], link[1], link[2], link[3], link[4], link[5],
                penetrationFactor, MAX_OBSTRUCTION_DB, RayMarcher.DEFAULT_MAX_STEPS);
    }

    private static double length(double[] link) {
        double dx = link[3] - link[0];
        double dy = link[4] - link[1];
        double dz = link[5] - link[2];
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    private static void assertMonotonic(List<Breakpoint> breakpoints) {
        for (int i = 0; i < breakpoints.size(); i++) {
            Breakpoint b = breakpoints.get(i);
            assertTrue(b.t() >= 0.0 && b.t() <= 1.0, "t out of range: " + b);
            if (i > 0) {
                Breakpoint previous = breakpoints.get(i - 1);
                assertTrue(b.t() >= previous.t(), "t ran backwards at " + i + ": " + previous + " -> " + b);
                assertTrue(b.cumulativeDb() >= previous.cumulativeDb(),
                        "loss ran backwards at " + i + ": " + previous + " -> " + b);
            }
        }
    }

    @Test
    @DisplayName("A clear link has no breakpoints and no loss")
    void allAirIsEmpty() {
        for (double[] link : LINKS) {
            Trace trace = trace(WorldProbe.AIR, link, 1.0, LinkTracer.DEFAULT_MAX_BREAKPOINTS);
            assertEquals(0.0, trace.obstructionDb());
            assertTrue(trace.breakpoints().isEmpty());
            assertFalse(trace.earlyExit());
            assertFalse(trace.cappedOut());
        }
    }

    @Test
    @DisplayName("The traced total is exactly RayMarcher's, for sparse walls and scattered terrain")
    void totalMatchesRayMarcherExactly() {
        TestProbes.Sparse walls = new TestProbes.Sparse(12.0);
        for (int y = 60; y <= 90; y++) {
            for (int z = -50; z <= 50; z++) {
                walls.add(10, y, z).add(-20, y, z);
            }
        }
        WorldProbe[] probes = {walls, new TestProbes.Scatter(3.0), new TestProbes.Scatter(0.7)};

        for (WorldProbe probe : probes) {
            for (double[] link : LINKS) {
                // A non-integer factor so the equality is of real floating-point sums, not of
                // small integers that would agree whatever order they were added in.
                for (double factor : new double[] {1.0, 1.3}) {
                    RayMarcher.MarchResult expected = march(probe, link, factor);
                    for (int cap : new int[] {1, 5, LinkTracer.DEFAULT_MAX_BREAKPOINTS, UNCAPPED}) {
                        Trace trace = trace(probe, link, factor, cap);
                        assertEquals(expected.obstructionDb(), trace.obstructionDb(), 0.0);
                        assertEquals(expected.earlyExit(), trace.earlyExit());
                        assertEquals(expected.cappedOut(), trace.cappedOut());
                    }
                }
            }
        }
    }

    @Test
    @DisplayName("Breakpoints never run backwards in t or in cumulative loss")
    void monotonic() {
        for (double[] link : LINKS) {
            Trace trace = trace(new TestProbes.Scatter(3.0), link, 1.0, UNCAPPED);
            assertFalse(trace.breakpoints().isEmpty(), "scatter should obstruct every test link");
            assertMonotonic(trace.breakpoints());
            assertTrue(trace.breakpoints().get(0).cumulativeDb() > 0.0);
        }
    }

    @Test
    @DisplayName("The last breakpoint's cumulative loss equals the obstruction exactly")
    void lastEqualsObstruction() {
        for (double[] link : LINKS) {
            for (int cap : new int[] {1, 3, LinkTracer.DEFAULT_MAX_BREAKPOINTS, UNCAPPED}) {
                Trace trace = trace(new TestProbes.Scatter(1.7), link, 1.1, cap);
                List<Breakpoint> breakpoints = trace.breakpoints();
                assertEquals(trace.obstructionDb(), breakpoints.get(breakpoints.size() - 1).cumulativeDb(), 0.0);
            }
        }
    }

    @Test
    @DisplayName("A single wall on an axis-aligned link lands at the expected t")
    void singleWallAxisAligned() {
        double[] link = {0.5, 64.5, 0.5, 40.5, 64.5, 0.5};
        TestProbes.Sparse wall = new TestProbes.Sparse(12.0).add(10, 64, 0);

        Trace trace = trace(wall, link, 1.0, UNCAPPED);

        assertEquals(1, trace.breakpoints().size());
        Breakpoint hit = trace.breakpoints().get(0);
        // Voxel 10's centre is 10 blocks along a 40 block link.
        assertEquals(0.25, hit.t(), 1.0 / length(link));
        assertEquals(12.0, hit.cumulativeDb(), 0.0);
    }

    @Test
    @DisplayName("A one-block-thick slab crossed diagonally lands within one voxel of the crossing")
    void singleWallDiagonal() {
        double[] link = {0.5, 64.5, 0.5, 30.5, 79.5, 20.5};
        WorldProbe slab = (x, y, z) -> x == 15 ? 10.0 : 0.0;

        Trace trace = trace(slab, link, 1.0, UNCAPPED);

        assertFalse(trace.breakpoints().isEmpty());
        // The ray is at x = 15.5, the slab's middle, exactly halfway along.
        double crossing = 0.5;
        double oneVoxel = Math.sqrt(3.0) / length(link);
        for (Breakpoint b : trace.breakpoints()) {
            assertEquals(crossing, b.t(), oneVoxel, "breakpoint " + b + " is not at the slab");
        }
    }

    @Test
    @DisplayName("Compression respects the cap, keeps the exact total, and is never more than a bucket late")
    void compression() {
        double[] link = {0.5, 64.5, 0.5, 150.5, 90.5, 70.5};
        WorldProbe probe = new TestProbes.Scatter(0.9);
        Trace full = trace(probe, link, 1.0, UNCAPPED);
        assertFalse(full.earlyExit(), "premise: the full link must not early-exit");
        assertTrue(full.breakpoints().size() > LinkTracer.DEFAULT_MAX_BREAKPOINTS,
                "premise: needs more lossy voxels than the cap, had " + full.breakpoints().size());

        for (int cap : new int[] {1, 2, 5, LinkTracer.DEFAULT_MAX_BREAKPOINTS}) {
            Trace compressed = trace(probe, link, 1.0, cap);
            List<Breakpoint> kept = compressed.breakpoints();

            assertTrue(kept.size() <= cap, "cap " + cap + " exceeded: " + kept.size());
            assertFalse(kept.isEmpty());
            assertMonotonic(kept);
            assertEquals(full.obstructionDb(), kept.get(kept.size() - 1).cumulativeDb(), 0.0);
            assertTrue(full.breakpoints().containsAll(kept), "compression must only drop, never invent");

            // Every original loss still shows up, at most one bucket (1/cap of the link) later.
            for (Breakpoint original : full.breakpoints()) {
                Breakpoint shownBy = kept.stream()
                        .filter(k -> k.t() >= original.t())
                        .findFirst()
                        .orElseThrow(() -> new AssertionError("loss at " + original + " was dropped"));
                assertTrue(shownBy.t() - original.t() < 1.0 / cap + 1e-12,
                        "cap " + cap + ": " + original + " shown late at " + shownBy);
                assertTrue(shownBy.cumulativeDb() >= original.cumulativeDb());
            }
        }

        Trace one = trace(probe, link, 1.0, 1);
        assertEquals(1, one.breakpoints().size());
        assertEquals(full.breakpoints().get(full.breakpoints().size() - 1), one.breakpoints().get(0));

        Trace zero = trace(probe, link, 1.0, 0);
        assertEquals(one.breakpoints(), zero.breakpoints(), "a cap below 1 is treated as 1");
    }

    @Test
    @DisplayName("Early exit propagates, and the breakpoints stop at the voxel that killed the link")
    void earlyExitPropagates() {
        double[] link = {0.5, 0.5, 0.5, 400.5, 0.5, 0.5};
        TestProbes.Counting probe = new TestProbes.Counting(200.0);

        Trace trace = trace(probe, link, 1.0, LinkTracer.DEFAULT_MAX_BREAKPOINTS);

        assertTrue(trace.earlyExit());
        assertTrue(trace.obstructionDb() > MAX_OBSTRUCTION_DB);
        assertEquals(1, trace.breakpoints().size());
        assertEquals(trace.obstructionDb(), trace.breakpoints().get(0).cumulativeDb(), 0.0);
        assertTrue(probe.calls <= 3, "the recorder must not keep the march going: " + probe.calls);
    }

    @Test
    @DisplayName("Capping out propagates, and the breakpoints cover only the marched part")
    void cappedOutPropagates() {
        double[] link = {0.5, 64.5, 0.5, 200.5, 64.5, 0.5};
        WorldProbe probe = new TestProbes.Scatter(0.5);
        int maxSteps = 50;

        RayMarcher.MarchResult expected = RayMarcher.march(probe, link[0], link[1], link[2],
                link[3], link[4], link[5], 1.0, MAX_OBSTRUCTION_DB, maxSteps);
        Trace trace = LinkTracer.trace(probe, link[0], link[1], link[2], link[3], link[4], link[5],
                1.0, MAX_OBSTRUCTION_DB, maxSteps, UNCAPPED);

        assertTrue(trace.cappedOut());
        assertFalse(trace.earlyExit());
        assertEquals(expected.obstructionDb(), trace.obstructionDb(), 0.0);
        List<Breakpoint> breakpoints = trace.breakpoints();
        assertEquals(trace.obstructionDb(), breakpoints.get(breakpoints.size() - 1).cumulativeDb(), 0.0);
        // 50 one-block steps along a 200 block link reach t = 0.25 and no further.
        for (Breakpoint b : breakpoints) {
            assertTrue(b.t() <= 50.0 / 200.0 + 1e-9, "breakpoint past the cap: " + b);
        }
    }

    @Test
    @DisplayName("The penetration factor scales every cumulative value and moves no t")
    void penetrationFactorScales() {
        double[] link = {0.5, 70.5, 0.5, 30.3, 64.2, 20.9};
        WorldProbe probe = new TestProbes.Scatter(3.0);

        Trace base = trace(probe, link, 1.0, UNCAPPED);
        Trace scaled = trace(probe, link, 2.5, UNCAPPED);
        assertFalse(scaled.earlyExit(), "premise: the scaled link must not early-exit");

        assertEquals(base.breakpoints().size(), scaled.breakpoints().size());
        assertEquals(base.obstructionDb() * 2.5, scaled.obstructionDb(), 1e-9);
        for (int i = 0; i < base.breakpoints().size(); i++) {
            Breakpoint b = base.breakpoints().get(i);
            Breakpoint s = scaled.breakpoints().get(i);
            assertEquals(b.t(), s.t(), 0.0);
            assertEquals(b.cumulativeDb() * 2.5, s.cumulativeDb(), 1e-9);
        }

        Trace deaf = trace(probe, link, 0.0, UNCAPPED);
        assertEquals(0.0, deaf.obstructionDb());
        assertTrue(deaf.breakpoints().isEmpty(), "a band that ignores walls accrues no breakpoints");
    }

    @Test
    @DisplayName("A receiver inside the antenna voxel traces nothing and never touches the world")
    void sameVoxel() {
        TestProbes.Counting probe = new TestProbes.Counting(60.0);
        Trace trace = LinkTracer.trace(probe, 0.5, 0.5, 0.5, 0.7, 0.2, 0.9,
                1.0, MAX_OBSTRUCTION_DB, RayMarcher.DEFAULT_MAX_STEPS, LinkTracer.DEFAULT_MAX_BREAKPOINTS);

        assertEquals(0.0, trace.obstructionDb());
        assertTrue(trace.breakpoints().isEmpty());
        assertEquals(0, probe.calls);
    }
}
