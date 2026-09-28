package dev.rancraft.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.SplittableRandom;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Distance and bearing between the Locator's estimate and a waypoint (Phase 3 slice 5). Minecraft's
 * compass: north is -z, east is +x.
 */
class NavigationTest {

    private static final double EPS = 1e-9;

    @Test
    @DisplayName("bearing: north (-z) 0, east (+x) 90, south (+z) 180, west (-x) 270")
    void cardinalBearings() {
        assertEquals(0.0, Navigation.bearingDegrees(10, 10, 10, -90), EPS);
        assertEquals(90.0, Navigation.bearingDegrees(10, 10, 110, 10), EPS);
        assertEquals(180.0, Navigation.bearingDegrees(10, 10, 10, 110), EPS);
        assertEquals(270.0, Navigation.bearingDegrees(10, 10, -90, 10), EPS);
    }

    @Test
    @DisplayName("bearing: the diagonals are 45, 135, 225 and 315")
    void diagonalBearings() {
        assertEquals(45.0, Navigation.bearingDegrees(0, 0, 5, -5), EPS);
        assertEquals(135.0, Navigation.bearingDegrees(0, 0, 5, 5), EPS);
        assertEquals(225.0, Navigation.bearingDegrees(0, 0, -5, 5), EPS);
        assertEquals(315.0, Navigation.bearingDegrees(0, 0, -5, -5), EPS);
    }

    @Test
    @DisplayName("bearing: the HUD mock-up's 047 degrees is north-east of the estimate")
    void mockUpBearing() {
        // 312 blocks at 47 degrees clockwise from north.
        double east = 312 * Math.sin(Math.toRadians(47));
        double north = 312 * Math.cos(Math.toRadians(47));
        double bearing = Navigation.bearingDegrees(1204, -3391, 1204 + east, -3391 - north);
        assertEquals(47, Navigation.wholeDegrees(bearing));
        assertEquals(312.0, Navigation.horizontalDistance(1204, -3391, 1204 + east, -3391 - north), 1e-9);
    }

    @Test
    @DisplayName("bearing: always on [0, 360), never NaN, 0 when the points coincide or are not finite")
    void bearingRange() {
        SplittableRandom random = new SplittableRandom(5);
        for (int i = 0; i < 100_000; i++) {
            double fx = random.nextDouble(-3e7, 3e7);
            double fz = random.nextDouble(-3e7, 3e7);
            double tx = fx + random.nextDouble(-1e-9, 1e-9) * (i % 2 == 0 ? 1 : 1e12);
            double tz = fz + random.nextDouble(-1e-9, 1e-9) * (i % 3 == 0 ? 1 : 1e12);
            double bearing = Navigation.bearingDegrees(fx, fz, tx, tz);
            assertTrue(bearing >= 0.0 && bearing < 360.0, "bearing " + bearing);
        }
        assertEquals(0.0, Navigation.bearingDegrees(3, 4, 3, 4));
        assertEquals(0.0, Navigation.bearingDegrees(Double.NaN, 0, 1, 1));
        assertEquals(0.0, Navigation.bearingDegrees(0, 0, Double.POSITIVE_INFINITY, 1));
    }

    @Test
    @DisplayName("a tiny westward offset just west of north reads 360 - epsilon, never 360")
    void wrapsBelowNorth() {
        double bearing = Navigation.bearingDegrees(0, 0, -1e-12, -1);
        assertTrue(bearing < 360.0 && bearing > 359.0, "bearing " + bearing);
        assertEquals(0, Navigation.wholeDegrees(bearing));
    }

    @Test
    @DisplayName("whole degrees: rounded, and 359.6 is 0, not 360")
    void wholeDegrees() {
        assertEquals(47, Navigation.wholeDegrees(47.4));
        assertEquals(48, Navigation.wholeDegrees(47.5));
        assertEquals(0, Navigation.wholeDegrees(359.6));
        assertEquals(359, Navigation.wholeDegrees(359.4));
        assertEquals(0, Navigation.wholeDegrees(0.2));
        assertEquals(359, Navigation.wholeDegrees(-0.6));
    }

    @Test
    @DisplayName("distance is horizontal only (height is an assumption, so it is ignored)")
    void horizontalDistance() {
        assertEquals(5.0, Navigation.horizontalDistance(0, 0, 3, 4), EPS);
        assertEquals(5.0, Navigation.horizontalDistance(-3, -4, 0, 0), EPS);
        assertEquals(0.0, Navigation.horizontalDistance(7, 7, 7, 7), EPS);
    }
}
