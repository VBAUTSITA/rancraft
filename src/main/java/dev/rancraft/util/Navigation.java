package dev.rancraft.util;

/**
 * Plain map arithmetic for the Network Locator's waypoints: how far, and which way. Phase 3 slice 5.
 *
 * <p>Not RF, and not positioning: both points are handed in (the Locator's estimate and a saved
 * waypoint, itself an earlier estimate). This only measures the straight line between them, on the
 * horizontal plane, the way a map and a protractor would. A waypoint saved under a bad fix is
 * therefore pointed at faithfully and wrongly; that is the lesson, not a bug.
 *
 * <p>Minecraft's compass: north is -z, east is +x, south is +z, west is -x. Bearings are clockwise
 * from north in degrees, as on a real compass.
 *
 * <p>Pure (no Minecraft types), so it lives in {@code util} and is unit-tested headless. It is the
 * first class in {@code util}; §2 of the Phase 3 spec created the package for {@code ColumnScan}
 * (§3B.1), and it holds pure code that is not RF, which this is. {@code PackagePurityTest} checks it.
 */
public final class Navigation {

    private Navigation() {
    }

    /** Horizontal (x, z) distance in blocks. Height is ignored: a waypoint's y is an assumption. */
    public static double horizontalDistance(double fromX, double fromZ, double toX, double toZ) {
        return Math.hypot(toX - fromX, toZ - fromZ);
    }

    /**
     * Compass bearing from one point to another, in degrees on [0, 360): 0 north (-z), 90 east (+x),
     * 180 south (+z), 270 west (-x).
     *
     * @return 0 when the two points coincide horizontally (no direction to point in), and 0 for
     *         non-finite input rather than NaN, so a HUD never prints "NaN°".
     */
    public static double bearingDegrees(double fromX, double fromZ, double toX, double toZ) {
        double east = toX - fromX;
        double north = fromZ - toZ;
        if (!Double.isFinite(east) || !Double.isFinite(north) || (east == 0.0 && north == 0.0)) {
            return 0.0;
        }
        double degrees = Math.toDegrees(Math.atan2(east, north));
        double wrapped = degrees % 360.0;
        if (wrapped < 0.0) {
            wrapped += 360.0;
        }
        // -1e-15 % 360 + 360 rounds to exactly 360.0 in double arithmetic.
        return wrapped >= 360.0 ? 0.0 : wrapped;
    }

    /** A bearing rounded to a whole degree on [0, 359]: 359.6 is 0, not 360. */
    public static int wholeDegrees(double bearingDegrees) {
        long rounded = Math.round(bearingDegrees);
        return (int) Math.floorMod(rounded, 360L);
    }
}
