package dev.rancraft.rf;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.rancraft.rf.MicrowaveLink.Budget;
import dev.rancraft.rf.MicrowaveLink.LinkState;
import dev.rancraft.rf.MicrowaveLink.Weather;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Phase 3 slice 11 (§3C.2, 3C tests): the microwave link budget, the Fresnel check and rain fade.
 *
 * <p>The reference hop runs along x from dish A at block (0, 64, 0) to dish B at (1000, 64, 0), centre
 * to centre, so it is exactly 1000 blocks = 1000 m at the default scale. At the midpoint the 60 %
 * Fresnel offset is 0.6 × 2.04 = 1.22 blocks: the "up" path passes y 65.72 (voxel row 65), "down"
 * 63.28 (row 63), the side paths z 1.72 and −0.72 (columns 1 and −1).
 */
class MicrowaveLinkTest {

    private static final MicrowaveLink LINK = MicrowaveLink.DEFAULT;
    private static final int MAX_STEPS = 8192;

    /** Stone: {@code minecraft:base_stone_overworld} is 12 dB in the shipped material table. */
    private static final double STONE_DB = 12.0;
    /** Leaves: {@code minecraft:leaves} is 1 dB. */
    private static final double LEAVES_DB = 1.0;

    private static Budget hop(WorldProbe probe, Weather weather) {
        return LINK.evaluate(probe, 0.5, 64.5, 0.5, 1000.5, 64.5, 0.5, 1.0, weather, MAX_STEPS);
    }

    private static Budget hop(WorldProbe probe) {
        return hop(probe, Weather.CLEAR);
    }

    @Test
    @DisplayName("FSPL at 18 GHz over 1000 m is 117.55 dB within 0.01")
    void fspl() {
        assertEquals(117.55, LINK.fsplDb(1000.0), 0.01);
        // The formula itself: 20 log10(18000) - 27.56 + 20 log10(1000).
        assertEquals(20.0 * Math.log10(18000.0) - 27.56 + 60.0, LINK.fsplDb(1000.0), 1e-12);
        // Free space, n = 2: doubling the distance costs 6.02 dB.
        assertEquals(20.0 * Math.log10(2.0), LINK.fsplDb(2000.0) - LINK.fsplDb(1000.0), 1e-12);
        // Floored at 1 m, as RfMath does, so adjacent dishes stay finite.
        assertEquals(LINK.fsplDb(1.0), LINK.fsplDb(0.0), 0.0);
    }

    @Test
    @DisplayName("a clear 1000 m hop: RSL -33.55 dBm, 16.45 dB of margin, UP (the §3C.2 sanity check)")
    void clearHop() {
        Budget budget = hop(WorldProbe.AIR);
        assertEquals(1000.0, budget.distanceMeters(), 1e-9);
        assertEquals(LINK.fsplDb(1000.0), budget.fsplDb(), 0.0);
        assertEquals(0.0, budget.obstructionDb());
        assertTrue(budget.fresnelClear());
        assertEquals(0.0, budget.fresnelLossDb());
        assertEquals(0.0, budget.rainLossDb());
        assertEquals(20.0 + 2 * 32.0 - LINK.fsplDb(1000.0), budget.rslDbm(), 1e-12);
        assertEquals(-33.6, budget.rslDbm(), 0.06);
        assertEquals(16.45, budget.marginDb(), 0.01);
        assertEquals(budget.rslDbm() + 50.0, budget.marginDb(), 1e-12);
        assertSame(LinkState.UP, budget.state());
        assertFalse(budget.outOfRange());
    }

    @Test
    @DisplayName("one stone block on the path is DEGRADED: 12 dB x 3.0 = 36 dB")
    void oneStoneIsDegraded() {
        Budget budget = hop(new TestProbes.Sparse(STONE_DB).add(500, 64, 0));
        assertEquals(36.0, budget.obstructionDb(), 1e-9);
        assertTrue(budget.fresnelClear(), "a single block on the line in mid-path leaves the offset paths clear");
        assertEquals(-69.55, budget.rslDbm(), 0.01);
        assertSame(LinkState.DEGRADED, budget.state());
    }

    @Test
    @DisplayName("two stone blocks on the path are DOWN: 72 dB")
    void twoStonesAreDown() {
        Budget budget = hop(new TestProbes.Sparse(STONE_DB).add(500, 64, 0).add(501, 64, 0));
        assertEquals(72.0, budget.obstructionDb(), 1e-9);
        assertEquals(-105.55, budget.rslDbm(), 0.01);
        assertSame(LinkState.DOWN, budget.state());
    }

    @Test
    @DisplayName("about six leaves (18 dB) is DEGRADED")
    void sixLeavesAreDegraded() {
        TestProbes.Sparse probe = new TestProbes.Sparse(LEAVES_DB);
        for (int x = 498; x < 504; x++) {
            probe.add(x, 64, 0);
        }
        Budget budget = hop(probe);
        assertEquals(18.0, budget.obstructionDb(), 1e-9);
        assertTrue(budget.fresnelClear());
        assertSame(LinkState.DEGRADED, budget.state());
    }

    @Test
    @DisplayName("the first Fresnel radius at the midpoint of 1000 m at 18 GHz is 2.04 m within 0.01")
    void fresnelRadius() {
        assertEquals(2.04, LINK.fresnelRadiusMeters(1000.0, 0.5), 0.01);
        // lambda = c / f = 16.66 mm; r(t) = sqrt(lambda d t(1-t)).
        assertEquals(299_792_458.0 / 18.0e9, LINK.wavelengthMeters(), 1e-15);
        assertEquals(Math.sqrt(LINK.wavelengthMeters() * 1000.0 * 0.25), LINK.fresnelRadiusMeters(1000.0, 0.5), 1e-12);
        assertEquals(0.0, LINK.fresnelRadiusMeters(1000.0, 0.0), 0.0);
        assertEquals(0.0, LINK.fresnelRadiusMeters(1000.0, 1.0), 0.0);
        assertEquals(LINK.fresnelRadiusMeters(1000.0, 0.2), LINK.fresnelRadiusMeters(1000.0, 0.8), 1e-12);
    }

    @Test
    @DisplayName("a block inside the 60% zone beside a clear line costs the 6 dB knife-edge penalty, in each of the four directions")
    void fresnelPenaltyEachDirection() {
        int[][] inside = {{500, 65, 0}, {500, 63, 0}, {500, 64, 1}, {500, 64, -1}};
        for (int[] block : inside) {
            Budget budget = hop(new TestProbes.Sparse(STONE_DB).add(block[0], block[1], block[2]));
            String where = block[0] + "," + block[1] + "," + block[2];
            assertEquals(0.0, budget.obstructionDb(), "the line of sight itself is clear: " + where);
            assertFalse(budget.fresnelClear(), "offset path should hit " + where);
            assertEquals(6.0, budget.fresnelLossDb(), 0.0, where);
            assertEquals(-39.55, budget.rslDbm(), 0.01, where);
            assertSame(LinkState.UP, budget.state(), where);
        }
    }

    @Test
    @DisplayName("a block beyond 60% of the Fresnel radius costs nothing")
    void blockOutsideTheZone() {
        // 1.5 blocks above the line at the midpoint: the up path passes y 65.72, under row 66.
        int[][] outside = {{500, 66, 0}, {500, 62, 0}, {500, 64, 2}, {500, 64, -2}};
        for (int[] block : outside) {
            Budget budget = hop(new TestProbes.Sparse(STONE_DB).add(block[0], block[1], block[2]));
            assertTrue(budget.fresnelClear(), block[0] + "," + block[1] + "," + block[2]);
            assertSame(LinkState.UP, budget.state());
        }
    }

    @Test
    @DisplayName("near a dish the zone is narrow: one block on the line fills it and costs penetration and the penalty")
    void blockNearDishFillsTheZone() {
        // At x = 50 the offset paths are only 0.12 blocks off the line, inside the same voxel.
        Budget budget = hop(new TestProbes.Sparse(STONE_DB).add(50, 64, 0));
        assertEquals(36.0, budget.obstructionDb(), 1e-9);
        assertFalse(budget.fresnelClear());
        assertEquals(-75.55, budget.rslDbm(), 0.01);
        assertSame(LinkState.DOWN, budget.state());
    }

    @Test
    @DisplayName("ground under a hop between dishes set right on the ground breaks the zone: 6 dB")
    void groundClearance() {
        // A flat floor at y = 63, one row under the dishes' row: the down path dips to 63.28 mid-path.
        WorldProbe floor = (x, y, z) -> y <= 63 ? 6.0 : 0.0;
        Budget budget = hop(floor);
        assertEquals(0.0, budget.obstructionDb());
        assertFalse(budget.fresnelClear());
        // Raised two blocks, the dishes clear it.
        Budget raised = LINK.evaluate(floor, 0.5, 66.5, 0.5, 1000.5, 66.5, 0.5, 1.0, Weather.CLEAR, MAX_STEPS);
        assertTrue(raised.fresnelClear());
    }

    @Test
    @DisplayName("the dishes' own voxels never count, on the line or on the offset paths")
    void dishVoxelsSkipped() {
        TestProbes.Sparse dishes = new TestProbes.Sparse(60.0).add(0, 64, 0).add(1000, 64, 0);
        Budget budget = hop(dishes);
        assertEquals(0.0, budget.obstructionDb());
        assertTrue(budget.fresnelClear());
        // Adjacent dishes: every offset midpoint falls in dish B's voxel, and it still does not count.
        Budget adjacent = LINK.evaluate(new TestProbes.Sparse(60.0).add(0, 64, 0).add(1, 64, 0),
                0.5, 64.5, 0.5, 1.5, 64.5, 0.5, 1.0, Weather.CLEAR, MAX_STEPS);
        assertEquals(0.0, adjacent.obstructionDb());
        assertTrue(adjacent.fresnelClear());
        assertSame(LinkState.UP, adjacent.state());
        // Two blocks apart: the block between them counts on the line and as the offset midpoint's voxel.
        Budget between = LINK.evaluate(new TestProbes.Sparse(12.0).add(1, 64, 0),
                0.5, 64.5, 0.5, 2.5, 64.5, 0.5, 1.0, Weather.CLEAR, MAX_STEPS);
        assertEquals(36.0, between.obstructionDb(), 1e-9);
        assertFalse(between.fresnelClear());
    }

    @Test
    @DisplayName("rain adds exactly rain_db_per_km x d_km, thunder the higher figure, snow nothing")
    void rainFade() {
        for (double meters : new double[] {1000.0, 2400.0, 333.0}) {
            double km = meters / 1000.0;
            assertEquals(2.5 * km, LINK.rainLossDb(Weather.RAIN, meters), 0.0, "rain over " + meters);
            assertEquals(6.0 * km, LINK.rainLossDb(Weather.THUNDER, meters), 0.0, "thunder over " + meters);
            assertEquals(0.0, LINK.rainLossDb(Weather.SNOW, meters), 0.0);
            assertEquals(0.0, LINK.rainLossDb(Weather.CLEAR, meters), 0.0);
        }
        Budget clear = hop(WorldProbe.AIR, Weather.CLEAR);
        Budget rain = hop(WorldProbe.AIR, Weather.RAIN);
        Budget snow = hop(WorldProbe.AIR, Weather.SNOW);
        assertEquals(2.5, rain.rainLossDb(), 0.0);
        assertEquals(clear.rslDbm() - 2.5, rain.rslDbm(), 1e-12);
        assertEquals(0.0, snow.rainLossDb(), 0.0);
        assertEquals(clear.rslDbm(), snow.rslDbm(), 0.0);
        // Thunder never gets less than rain, even if a datapack sets it lower.
        MicrowaveLink odd = new MicrowaveLink(18000.0, 20.0, 32.0, 3.0, -50.0, -70.0, 6.0, 4.0, 1.0);
        assertEquals(4.0, odd.rainLossDb(Weather.THUNDER, 1000.0), 0.0);
    }

    @Test
    @DisplayName("a marginal hop drops in a thunderstorm and recovers after")
    void marginalHopInAThunderstorm() {
        // One stone mid-path: DEGRADED at -69.55, 0.45 dB above DOWN.
        WorldProbe probe = new TestProbes.Sparse(STONE_DB).add(500, 64, 0);
        assertSame(LinkState.DEGRADED, hop(probe, Weather.CLEAR).state());
        assertSame(LinkState.DOWN, hop(probe, Weather.THUNDER).state());
        assertEquals(-75.55, hop(probe, Weather.THUNDER).rslDbm(), 0.01);
        assertSame(LinkState.DEGRADED, hop(probe, Weather.CLEAR).state());
        // A clear 3 km hop is UP and steps down in a thunderstorm (18 dB).
        Budget longClear = LINK.evaluate(WorldProbe.AIR, 0.5, 64.5, 0.5, 3000.5, 64.5, 0.5, 1.0, Weather.CLEAR, MAX_STEPS);
        Budget longStorm = LINK.evaluate(WorldProbe.AIR, 0.5, 64.5, 0.5, 3000.5, 64.5, 0.5, 1.0, Weather.THUNDER, MAX_STEPS);
        assertSame(LinkState.UP, longClear.state());
        assertEquals(18.0, longStorm.rainLossDb(), 1e-12);
        assertSame(LinkState.DEGRADED, longStorm.state());
    }

    @Test
    @DisplayName("enableRainFade off ignores the weather; the RfConfig overload uses metersPerBlock")
    void configOverload() {
        RfConfig on = RfConfig.DEFAULTS;
        RfConfig off = withRainFadeAndScale(on, false, 1.0);
        assertTrue(on.enableRainFade());
        Budget withFade = LINK.evaluate(WorldProbe.AIR, 0.5, 64.5, 0.5, 1000.5, 64.5, 0.5, on, Weather.THUNDER, MAX_STEPS);
        Budget withoutFade = LINK.evaluate(WorldProbe.AIR, 0.5, 64.5, 0.5, 1000.5, 64.5, 0.5, off, Weather.THUNDER, MAX_STEPS);
        assertEquals(6.0, withFade.rainLossDb(), 1e-12);
        assertEquals(0.0, withoutFade.rainLossDb(), 0.0);
        // 500 blocks at 2 m per block is the 1000 m hop.
        Budget scaled = LINK.evaluate(WorldProbe.AIR, 0.5, 64.5, 0.5, 500.5, 64.5, 0.5,
                withRainFadeAndScale(on, true, 2.0), Weather.RAIN, MAX_STEPS);
        assertEquals(1000.0, scaled.distanceMeters(), 1e-9);
        assertEquals(117.55, scaled.fsplDb(), 0.01);
        assertEquals(2.5, scaled.rainLossDb(), 1e-12);
    }

    @Test
    @DisplayName("the Fresnel offset scales with metersPerBlock: 1.22 m is 0.61 blocks at 2 m per block")
    void fresnelOffsetInBlocks() {
        // 500 blocks at 2 m/block = 1000 m; the offset is 0.61 blocks, so the up path passes y 65.11.
        Budget hit = LINK.evaluate(new TestProbes.Sparse(STONE_DB).add(250, 65, 0),
                0.5, 64.5, 0.5, 500.5, 64.5, 0.5, 2.0, Weather.CLEAR, MAX_STEPS);
        assertFalse(hit.fresnelClear());
        Budget clear = LINK.evaluate(new TestProbes.Sparse(STONE_DB).add(250, 66, 0),
                0.5, 64.5, 0.5, 500.5, 64.5, 0.5, 2.0, Weather.CLEAR, MAX_STEPS);
        assertTrue(clear.fresnelClear());
    }

    @Test
    @DisplayName("the thresholds: UP at -50 exactly, DEGRADED at -70 exactly, DOWN below")
    void thresholds() {
        assertSame(LinkState.UP, LINK.stateOf(-50.0));
        assertSame(LinkState.DEGRADED, LINK.stateOf(Math.nextDown(-50.0)));
        assertSame(LinkState.DEGRADED, LINK.stateOf(-70.0));
        assertSame(LinkState.DOWN, LINK.stateOf(Math.nextDown(-70.0)));
        assertSame(LinkState.DOWN, LINK.stateOf(Double.NEGATIVE_INFINITY));
        assertSame(LinkState.DOWN, LINK.stateOf(Double.NaN));
        assertEquals(-33.0, LINK.rslDbm(100.0, 10.0, 6.0, 1.0), 1e-12);
    }

    @Test
    @DisplayName("a sloping hop: up/down are perpendicular to the line, left/right horizontal")
    void slopingHop() {
        // Dish B 300 blocks higher: 1044 m, so 0.6 r(0.5) = 1.25 blocks, at right angles to the climbing line.
        double ax = 0.5, ay = 64.5, az = 0.5, bx = 1000.5, by = 364.5, bz = 0.5;
        Budget clear = LINK.evaluate(WorldProbe.AIR, ax, ay, az, bx, by, bz, 1.0, Weather.CLEAR, MAX_STEPS);
        assertTrue(clear.fresnelClear());
        assertSame(LinkState.UP, clear.state());
        // Midpoint (500.5, 214.5, 0.5); the up offset moves it -0.36 in x and +1.20 in y, into (500, 215, 0).
        Budget above = LINK.evaluate(new TestProbes.Sparse(STONE_DB).add(500, 215, 0),
                ax, ay, az, bx, by, bz, 1.0, Weather.CLEAR, MAX_STEPS);
        assertFalse(above.fresnelClear());
        assertEquals(0.0, above.obstructionDb());
        // Side offsets stay horizontal: z 1.72 at the midpoint.
        Budget side = LINK.evaluate(new TestProbes.Sparse(STONE_DB).add(500, 214, 1),
                ax, ay, az, bx, by, bz, 1.0, Weather.CLEAR, MAX_STEPS);
        assertFalse(side.fresnelClear());
        // A vertical hop has no horizontal direction of its own and still evaluates.
        Budget vertical = LINK.evaluate(WorldProbe.AIR, 0.5, 64.5, 0.5, 0.5, 164.5, 0.5, 1.0, Weather.CLEAR, MAX_STEPS);
        assertTrue(vertical.fresnelClear());
        assertEquals(100.0, vertical.distanceMeters(), 1e-9);
    }

    @Test
    @DisplayName("adding a block anywhere never raises the RSL")
    void monotonicInBlocks() {
        Random random = new Random(11L);
        for (int trial = 0; trial < 200; trial++) {
            double ax = random.nextInt(40) + 0.5;
            double ay = 60 + random.nextInt(8) + 0.5;
            double az = random.nextInt(40) + 0.5;
            double bx = 200 + random.nextInt(200) + 0.5;
            double by = 60 + random.nextInt(8) + 0.5;
            double bz = random.nextInt(120) + 0.5;
            TestProbes.Sparse probe = new TestProbes.Sparse(3.0);
            double previous = LINK.evaluate(probe, ax, ay, az, bx, by, bz, 1.0, Weather.CLEAR, MAX_STEPS).rslDbm();
            for (int added = 0; added < 12; added++) {
                // Near the line, so most additions matter.
                double t = random.nextDouble();
                probe.add((int) Math.floor(ax + (bx - ax) * t) + random.nextInt(5) - 2,
                        (int) Math.floor(ay + (by - ay) * t) + random.nextInt(5) - 2,
                        (int) Math.floor(az + (bz - az) * t) + random.nextInt(5) - 2);
                double rsl = LINK.evaluate(probe, ax, ay, az, bx, by, bz, 1.0, Weather.CLEAR, MAX_STEPS).rslDbm();
                assertTrue(rsl <= previous, "RSL rose from " + previous + " to " + rsl + " in trial " + trial);
                previous = rsl;
            }
        }
    }

    @Test
    @DisplayName("a hop longer than the march cap is DOWN and out of range")
    void outOfRange() {
        Budget budget = LINK.evaluate(WorldProbe.AIR, 0.5, 64.5, 0.5, 1000.5, 64.5, 0.5, 1.0, Weather.CLEAR, 100);
        assertTrue(budget.outOfRange());
        assertSame(LinkState.DOWN, budget.state());
        assertEquals(Double.NEGATIVE_INFINITY, budget.rslDbm());
        // Exactly enough steps: in range.
        assertFalse(LINK.evaluate(WorldProbe.AIR, 0.5, 64.5, 0.5, 1000.5, 64.5, 0.5, 1.0, Weather.CLEAR, 1000)
                .outOfRange());
    }

    @Test
    @DisplayName("Weather.at: dry is clear, a snowy midpoint is snow, a desert is clear, thunder only where it rains")
    void weatherAt() {
        assertSame(Weather.CLEAR, Weather.at(false, false, Weather.RAIN));
        assertSame(Weather.RAIN, Weather.at(true, false, Weather.RAIN));
        assertSame(Weather.THUNDER, Weather.at(true, true, Weather.RAIN));
        assertSame(Weather.SNOW, Weather.at(true, true, Weather.SNOW));
        assertSame(Weather.SNOW, Weather.at(true, false, Weather.SNOW));
        assertSame(Weather.CLEAR, Weather.at(true, true, Weather.CLEAR));
        assertSame(Weather.RAIN, Weather.at(true, false, Weather.THUNDER));
    }

    @Test
    @DisplayName("parameters that make no sense are refused")
    void validation() {
        assertThrows(IllegalArgumentException.class,
                () -> new MicrowaveLink(0.0, 20.0, 32.0, 3.0, -50.0, -70.0, 6.0, 2.5, 6.0));
        assertThrows(IllegalArgumentException.class,
                () -> new MicrowaveLink(18000.0, 20.0, 32.0, 3.0, -70.0, -50.0, 6.0, 2.5, 6.0));
        assertThrows(IllegalArgumentException.class,
                () -> new MicrowaveLink(18000.0, 20.0, 32.0, -1.0, -50.0, -70.0, 6.0, 2.5, 6.0));
        assertThrows(IllegalArgumentException.class,
                () -> new MicrowaveLink(18000.0, 20.0, 32.0, 3.0, -50.0, -70.0, -6.0, 2.5, 6.0));
        assertThrows(IllegalArgumentException.class,
                () -> new MicrowaveLink(18000.0, 20.0, 32.0, 3.0, -50.0, -70.0, 6.0, Double.NaN, 6.0));
        assertThrows(IllegalArgumentException.class,
                () -> new MicrowaveLink(18000.0, Double.POSITIVE_INFINITY, 32.0, 3.0, -50.0, -70.0, 6.0, 2.5, 6.0));
        // Equal thresholds are allowed: no DEGRADED band.
        MicrowaveLink noDegraded = new MicrowaveLink(18000.0, 20.0, 32.0, 3.0, -60.0, -60.0, 6.0, 2.5, 6.0);
        assertSame(LinkState.DOWN, noDegraded.stateOf(-60.5));
    }

    @Test
    @DisplayName("withWeather re-budgets a measured hop without marching and equals a fresh evaluation")
    void withWeatherEqualsFreshEvaluation() {
        WorldProbe stone = new TestProbes.Sparse(STONE_DB).add(500, 64, 0).add(500, 65, 0);
        for (WorldProbe probe : new WorldProbe[] {WorldProbe.AIR, stone}) {
            Budget clear = hop(probe, Weather.CLEAR);
            for (Weather weather : Weather.values()) {
                assertEquals(hop(probe, weather), LINK.withWeather(clear, weather), weather.name());
                // And back again.
                assertEquals(clear, LINK.withWeather(LINK.withWeather(clear, weather), Weather.CLEAR));
            }
        }
        // It reads no block at all.
        TestProbes.Counting counting = new TestProbes.Counting(0.0);
        Budget measured = hop(counting, Weather.CLEAR);
        int calls = counting.calls;
        LINK.withWeather(measured, Weather.THUNDER);
        assertEquals(calls, counting.calls);
        // The RfConfig overload ignores the weather when rain fade is off.
        assertEquals(measured, LINK.withWeather(measured, withRainFadeAndScale(RfConfig.DEFAULTS, false, 1.0), Weather.THUNDER));
        assertEquals(hop(WorldProbe.AIR, Weather.THUNDER), LINK.withWeather(measured, RfConfig.DEFAULTS, Weather.THUNDER));
        // An out-of-range hop stays DOWN whatever the weather.
        Budget far = LINK.evaluate(WorldProbe.AIR, 0.5, 64.5, 0.5, 1000.5, 64.5, 0.5, 1.0, Weather.CLEAR, 100);
        Budget farRain = LINK.withWeather(far, Weather.RAIN);
        assertTrue(farRain.outOfRange());
        assertSame(LinkState.DOWN, farRain.state());
        assertEquals(LINK.evaluate(WorldProbe.AIR, 0.5, 64.5, 0.5, 1000.5, 64.5, 0.5, 1.0, Weather.RAIN, 100), farRain);
    }

    @Test
    @DisplayName("the offset midpoints are 0.6 r(0.5) from the midpoint, perpendicular to the line, up first")
    void offsetMidpoints() {
        double[][] points = LINK.fresnelOffsetMidpoints(0.5, 64.5, 0.5, 1000.5, 64.5, 0.5, 1.0);
        double offset = 0.6 * LINK.fresnelRadiusMeters(1000.0, 0.5);
        double[][] expected = {{500.5, 64.5 + offset, 0.5}, {500.5, 64.5 - offset, 0.5},
                {500.5, 64.5, 0.5 + offset}, {500.5, 64.5, 0.5 - offset}};
        assertEquals(4, points.length);
        for (int i = 0; i < 4; i++) {
            for (int axis = 0; axis < 3; axis++) {
                assertEquals(expected[i][axis], points[i][axis], 1e-9, "point " + i + " axis " + axis);
            }
        }
        // A sloping hop: every offset is perpendicular to the line and 0.6 r(0.5) long.
        double ax = 3.5, ay = 70.5, az = -40.5, bx = 640.5, by = 190.5, bz = 255.5;
        double dx = bx - ax, dy = by - ay, dz = bz - az;
        double length = Math.sqrt(dx * dx + dy * dy + dz * dz);
        double expectedOffset = 0.6 * LINK.fresnelRadiusMeters(length * 2.0, 0.5) / 2.0;
        for (double[] p : LINK.fresnelOffsetMidpoints(ax, ay, az, bx, by, bz, 2.0)) {
            double ox = p[0] - (ax + bx) / 2, oy = p[1] - (ay + by) / 2, oz = p[2] - (az + bz) / 2;
            assertEquals(expectedOffset, Math.sqrt(ox * ox + oy * oy + oz * oz), 1e-9);
            assertEquals(0.0, (ox * dx + oy * dy + oz * dz) / length, 1e-9);
        }
        assertEquals(0, LINK.fresnelOffsetMidpoints(1.5, 2.5, 3.5, 1.5, 2.5, 3.5, 1.0).length);
    }

    @Test
    @DisplayName("dependencyBins holds every voxel the evaluation reads, including where only an offset path crosses into a bin")
    void dependencyBinsContainEveryProbedVoxel() {
        int bin = 128;
        // The line runs at z 127.5, half a block inside bin z 0; the left/right offsets (1.22 blocks)
        // reach z 128.72, inside bin z 1, which the line itself never enters.
        double[] hop = {0.5, 64.5, 127.5, 1000.5, 64.5, 127.5};
        long[] bins = LINK.dependencyBins(hop[0], hop[1], hop[2], hop[3], hop[4], hop[5], 1.0, bin);
        long[] lineOnly = BinTraversal.binsAlong(hop[0], hop[2], hop[3], hop[5], bin);
        assertTrue(bins.length > lineOnly.length, "the offset path adds bins the line does not cross");
        assertTrue(contains(bins, BinTraversal.keyOfBlock(500, 128, bin)));
        assertFalse(contains(lineOnly, BinTraversal.keyOfBlock(500, 128, bin)));

        Random random = new Random(1128L);
        for (int trial = 0; trial < 300; trial++) {
            double ax = random.nextInt(600) - 300 + random.nextDouble();
            double ay = 60 + random.nextInt(40) + random.nextDouble();
            double az = random.nextInt(600) - 300 + random.nextDouble();
            double bx = random.nextInt(600) - 300 + random.nextDouble();
            double by = 60 + random.nextInt(40) + random.nextDouble();
            double bz = random.nextInt(600) - 300 + random.nextDouble();
            if (trial % 3 == 0) {
                // Hug a bin edge, where the offset paths most often cross a bin the line does not.
                az = bin * (random.nextInt(4) - 2) - 0.5;
                bz = az + random.nextInt(3) - 1;
            }
            double metersPerBlock = trial % 5 == 0 ? 0.5 : 1.0;
            long[] dependency = LINK.dependencyBins(ax, ay, az, bx, by, bz, metersPerBlock, bin);
            // A probe that records every (x, z) it is asked about and is air everywhere, so every march
            // runs to the end and nothing exits early.
            List<long[]> probed = new ArrayList<>();
            WorldProbe recording = (x, y, z) -> {
                probed.add(new long[] {x, z});
                return 0.0;
            };
            LINK.evaluate(recording, ax, ay, az, bx, by, bz, metersPerBlock, Weather.CLEAR, MAX_STEPS);
            for (long[] xz : probed) {
                assertTrue(contains(dependency, BinTraversal.keyOfBlock((int) xz[0], (int) xz[1], bin)),
                        "trial " + trial + ": voxel column " + xz[0] + "," + xz[1] + " outside the dependency bins");
            }
        }
    }

    private static boolean contains(long[] sortedKeys, long key) {
        return java.util.Arrays.binarySearch(sortedKeys, key) >= 0;
    }

    // ---- slice 12 -------------------------------------------------------------------------------

    @Test
    @DisplayName("slice 12: stepsToReach never leaves a hop out of range, and caps nothing a larger cap would read")
    void stepsToReachIsEnough() {
        // The slice 11 note: a 1000-block diagonal hop enters about 1414 voxels, past maxRaySteps 1200.
        double[] diagonal = {0.5, 64.5, 0.5, 707.5, 64.5, 707.5};
        assertTrue(LINK.evaluate(WorldProbe.AIR, diagonal[0], diagonal[1], diagonal[2], diagonal[3], diagonal[4],
                diagonal[5], 1.0, Weather.CLEAR, 1200).outOfRange(), "fixture: the default cap is too short");
        int steps = LINK.stepsToReach(diagonal[0], diagonal[1], diagonal[2], diagonal[3], diagonal[4], diagonal[5], 1.0);
        assertFalse(LINK.evaluate(WorldProbe.AIR, diagonal[0], diagonal[1], diagonal[2], diagonal[3], diagonal[4],
                diagonal[5], 1.0, Weather.CLEAR, steps).outOfRange());

        Random random = new Random(1212L);
        for (int trial = 0; trial < 400; trial++) {
            double ax = random.nextInt(2000) - 1000 + random.nextDouble();
            double ay = random.nextInt(300) - 60 + random.nextDouble();
            double az = random.nextInt(2000) - 1000 + random.nextDouble();
            double bx = ax + random.nextInt(1400) - 700 + random.nextDouble();
            double by = random.nextInt(300) - 60 + random.nextDouble();
            double bz = az + random.nextInt(1400) - 700 + random.nextDouble();
            double metersPerBlock = new double[] {0.25, 0.5, 1.0, 2.0, 4.0}[trial % 5];
            int cap = LINK.stepsToReach(ax, ay, az, bx, by, bz, metersPerBlock);
            // A scatter world, so the Fresnel paths run to their first hit or to the end.
            WorldProbe world = new TestProbes.Scatter(trial % 2 == 0 ? 0.0 : 1.0);
            Budget capped = LINK.evaluate(world, ax, ay, az, bx, by, bz, metersPerBlock, Weather.CLEAR, cap);
            Budget unbounded = LINK.evaluate(world, ax, ay, az, bx, by, bz, metersPerBlock, Weather.CLEAR,
                    Integer.MAX_VALUE);
            assertFalse(capped.outOfRange(), "trial " + trial);
            assertEquals(unbounded, capped, "trial " + trial + ": the cap cut a march short");
        }
    }

    @Test
    @DisplayName("slice 12: a cap of 0 is out of range and DOWN without reading a block (a hop past the range)")
    void zeroStepsReadsNothing() {
        TestProbes.Counting counting = new TestProbes.Counting(12.0);
        Budget budget = LINK.evaluate(counting, 0.5, 64.5, 0.5, 2000.5, 64.5, 0.5, 1.0, Weather.CLEAR, 0);
        assertTrue(budget.outOfRange());
        assertSame(LinkState.DOWN, budget.state());
        assertEquals(0, counting.calls);
        assertEquals("DOWN, out of range (2000 m)", budget.describe());
    }

    @Test
    @DisplayName("slice 12: describe() gives the state, RSL, margin, Fresnel state and rain loss, dot decimals")
    void describe() {
        java.util.Locale previous = java.util.Locale.getDefault();
        try {
            java.util.Locale.setDefault(java.util.Locale.GERMANY);
            assertEquals("UP, RSL -33.5 dBm, margin +16.5 dB, Fresnel clear, rain 0.0 dB (1000 m, obstruction 0.0 dB)",
                    hop(WorldProbe.AIR).describe());
            Budget oneStone = hop(new TestProbes.Sparse(STONE_DB).add(500, 64, 0), Weather.THUNDER);
            assertEquals("DOWN, RSL -75.5 dBm, margin -25.5 dB, Fresnel clear, rain 6.0 dB (1000 m, obstruction 36.0 dB)",
                    oneStone.describe());
            Budget grazing = hop(new TestProbes.Sparse(STONE_DB).add(500, 65, 0));
            assertTrue(grazing.describe().contains("Fresnel obstructed (+6.0 dB)"), grazing.describe());
        } finally {
            java.util.Locale.setDefault(previous);
        }
    }

    private static RfConfig withRainFadeAndScale(RfConfig base, boolean enableRainFade, double metersPerBlock) {
        return new RfConfig(
                metersPerBlock, base.maxEvaluationRangeBlocks(), base.maxCellsEvaluated(),
                base.maxObstructionDb(), base.maxRaySteps(), base.evaluationIntervalTicks(),
                base.requireRedstone(), base.enableSampleCaching(),
                base.antennaFrontToBackDb(), base.antennaSidelobeFloorDb(),
                base.adjacentChannelRejectionDb(), base.pciMod3PenaltyFactor(),
                base.enablePciMod3Penalty(), base.handoverHysteresisDb(), base.timeToTriggerTicks(),
                base.pciPlanningRadius(), base.pciMod3Radius(),
                base.locatorMinRsrpDbm(), base.locatorMaxCells(), base.locatorMaxHdop(),
                base.nlosBiasBlocksPerDb(), base.locatorSiteMergeBlocks(),
                base.blerSinr50Db(), base.blerSlopeDb(),
                base.fiberRadiusBlocks(), base.siteRadiusBlocks(), enableRainFade);
    }
}
