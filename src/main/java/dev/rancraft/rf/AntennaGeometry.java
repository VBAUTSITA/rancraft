package dev.rancraft.rf;

/**
 * Pure geometry between a radiating point and a receiver.
 *
 * <p>Shared by the engine and by the GUI pattern preview so that the two can never disagree about
 * which way an antenna is pointing. No state, no world access.
 *
 * <h2>Bearing convention</h2>
 * Minecraft's axes put <b>north at −Z</b> and <b>east at +X</b>, and the vanilla yaw shown by F3
 * runs clockwise from south. This class uses the compass convention instead, because that is what
 * every real antenna datasheet uses and what the GUI polar plot draws:
 *
 * <pre>
 *   0   = north (−Z)
 *   90  = east  (+X)
 *   180 = south (+Z)
 *   270 = west  (−X)
 * </pre>
 *
 * {@code atan2(dx, -dz)} produces exactly that. A 90° azimuth error is the easiest bug to ship
 * here, so {@code AntennaPatternTest} pins all four cardinals.
 */
public final class AntennaGeometry {

    private AntennaGeometry() {
    }

    /**
     * Guards {@code atan2} when the receiver is directly above or below the antenna. Without it a
     * co-located receiver produces {@code atan2(0, 0) == 0}, reporting level when it is actually
     * straight up.
     */
    private static final double MIN_HORIZONTAL = 1e-6;

    /** Compass bearing from the antenna to the receiver, 0..360, 0 = north. */
    public static double bearingDeg(double ax, double az, double rx, double rz) {
        double dx = rx - ax;
        double dz = rz - az;
        return normalize360(Math.toDegrees(Math.atan2(dx, -dz)));
    }

    /**
     * Elevation angle from the antenna to the receiver, in degrees.
     *
     * <p>Positive means the <em>receiver is above the antenna</em>. A receiver on the ground below
     * a tall mast therefore has a negative elevation, which is the case downtilt is designed for.
     */
    public static double elevationDeg(double ax, double ay, double az, double rx, double ry, double rz) {
        double dx = rx - ax;
        double dy = ry - ay;
        double dz = rz - az;
        double horizontal = Math.sqrt(dx * dx + dz * dz);
        return Math.toDegrees(Math.atan2(dy, Math.max(horizontal, MIN_HORIZONTAL)));
    }

    /** Wraps any angle into 0..360. */
    public static double normalize360(double deg) {
        double wrapped = deg % 360.0;
        return wrapped < 0.0 ? wrapped + 360.0 : wrapped;
    }

    /**
     * Wraps any angle into −180..+180.
     *
     * <p>This is what makes the horizontal pattern symmetric: a receiver 10° clockwise of boresight
     * and one 10° anticlockwise must see the same attenuation, and only a signed wrap gives that.
     */
    public static double wrapTo180(double deg) {
        double wrapped = normalize360(deg);
        return wrapped > 180.0 ? wrapped - 360.0 : wrapped;
    }
}
