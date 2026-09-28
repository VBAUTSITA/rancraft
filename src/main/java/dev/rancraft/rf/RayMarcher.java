package dev.rancraft.rf;

/**
 * Amanatides-Woo 3D DDA voxel traversal between the radiating point and the receiver.
 *
 * <p>Both the source voxel and the destination voxel are skipped: the antenna is not considered to
 * be obstructing itself, and the player is not standing inside a wall of their own making.
 */
public final class RayMarcher {

    private RayMarcher() {
    }

    /** Hard ceiling on voxels visited in one march. */
    public static final int DEFAULT_MAX_STEPS = 1200;

    /**
     * @param obstructionDb accumulated attenuation in dB
     * @param steps         voxels entered
     * @param earlyExit     true when the march stopped because the link was already dead
     * @param cappedOut     true when {@code maxSteps} was exhausted before reaching the receiver;
     *                      the caller should treat the cell as out of range
     */
    public record MarchResult(double obstructionDb, int steps, boolean earlyExit, boolean cappedOut) {
    }

    private static final MarchResult CLEAR = new MarchResult(0.0, 0, false, false);

    public static MarchResult march(
            WorldProbe probe,
            double x0, double y0, double z0,
            double x1, double y1, double z1,
            double penetrationFactor,
            double maxObstructionDb,
            int maxSteps) {

        int x = floorInt(x0);
        int y = floorInt(y0);
        int z = floorInt(z0);

        final int xEnd = floorInt(x1);
        final int yEnd = floorInt(y1);
        final int zEnd = floorInt(z1);

        // Source and destination share a voxel: nothing in between to attenuate.
        if (x == xEnd && y == yEnd && z == zEnd) {
            return CLEAR;
        }

        final double dx = x1 - x0;
        final double dy = y1 - y0;
        final double dz = z1 - z0;

        final int stepX = stepOf(dx);
        final int stepY = stepOf(dy);
        final int stepZ = stepOf(dz);

        // t is parameterised over the whole ray, so t == 1.0 is exactly the receiver.
        double tMaxX = firstCrossing(x0, dx);
        double tMaxY = firstCrossing(y0, dy);
        double tMaxZ = firstCrossing(z0, dz);

        final double tDeltaX = dx == 0.0 ? Double.POSITIVE_INFINITY : Math.abs(1.0 / dx);
        final double tDeltaY = dy == 0.0 ? Double.POSITIVE_INFINITY : Math.abs(1.0 / dy);
        final double tDeltaZ = dz == 0.0 ? Double.POSITIVE_INFINITY : Math.abs(1.0 / dz);

        double total = 0.0;
        int steps = 0;
        boolean reachedEnd = false;

        while (steps < maxSteps) {
            if (Math.min(tMaxX, Math.min(tMaxY, tMaxZ)) > 1.0) {
                // The next boundary crossing lies past the receiver.
                reachedEnd = true;
                break;
            }

            if (tMaxX <= tMaxY && tMaxX <= tMaxZ) {
                x += stepX;
                tMaxX += tDeltaX;
            } else if (tMaxY <= tMaxZ) {
                y += stepY;
                tMaxY += tDeltaY;
            } else {
                z += stepZ;
                tMaxZ += tDeltaZ;
            }
            steps++;

            if (x == xEnd && y == yEnd && z == zEnd) {
                reachedEnd = true;
                break;
            }

            total += probe.attenuationDbAt(x, y, z) * penetrationFactor;

            if (total > maxObstructionDb) {
                return new MarchResult(total, steps, true, false);
            }
        }

        return new MarchResult(total, steps, false, !reachedEnd);
    }

    /** Distance along the ray, in units of t, to the first voxel boundary in this axis. */
    private static double firstCrossing(double origin, double delta) {
        if (delta == 0.0) {
            return Double.POSITIVE_INFINITY;
        }
        double voxel = Math.floor(origin);
        double boundary = delta > 0.0 ? voxel + 1.0 : voxel;
        return (boundary - origin) / delta;
    }

    private static int stepOf(double delta) {
        return delta > 0.0 ? 1 : (delta < 0.0 ? -1 : 0);
    }

    private static int floorInt(double v) {
        return (int) Math.floor(v);
    }
}
