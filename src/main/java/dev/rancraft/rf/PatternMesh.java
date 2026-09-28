package dev.rancraft.rf;

import java.util.ArrayList;
import java.util.List;

/**
 * Turns an {@link AntennaPattern} into 3D wireframe geometry.
 *
 * <p>Pure maths, no Minecraft. This is what lets the RF Lens draw the <em>same</em> pattern object
 * the server evaluates against: the lobe you see and the dBm you measure cannot drift apart,
 * because there is one implementation of the maths.
 *
 * <h2>What a shell is</h2>
 * A shell is the surface where the antenna's gain has fallen a fixed number of dB below its peak.
 * The −3 dB shell is the classic half-power beamwidth surface. Drawing several nested shells
 * instead of one filled blob is deliberate: <b>RF does not stop anywhere</b>, and a solid surface
 * would teach a player that a beam has an edge.
 *
 * <h2>Normalisation, and what it does not mean</h2>
 * Radii come back in the range 0..1 and are scaled to world size by the caller.
 * <b>The lobe has no absolute scale.</b> A 22 dBi pencil beam and a 6 dBi omni are drawn at the
 * same nominal radius: this shows <em>shape</em>, never <em>reach</em>.
 */
public final class PatternMesh {

    private PatternMesh() {
    }

    /** Default contour depths, in dB below peak gain. */
    public static final double[] DEFAULT_SHELLS_DB = {3.0, 10.0, 20.0};

    /** Angular step for sampling, in degrees. 6 is smooth enough to read and cheap to build. */
    public static final int DEFAULT_STEP_DEG = 6;

    /**
     * How far below peak the geometry is allowed to collapse to zero radius. Matches the dynamic
     * range the GUI polar plot uses, so the flat and 3D views agree.
     */
    public static final double PLOT_RANGE_DB = 40.0;

    /** A point on a shell, in antenna-local space with +X east, +Y up, +Z south. Unit radius max. */
    public record Vertex(double x, double y, double z) {
    }

    /** One contour surface, as line segments. {@code depthDb} is dB below peak. */
    public record Shell(double depthDb, List<Vertex> lines) {
    }

    /**
     * Builds nested wireframe shells for a cell.
     *
     * <p>{@code lines} is a flat list of point pairs: vertices 0-1 are a segment, 2-3 are the next,
     * and so on. That maps directly onto a {@code LINES} draw with no index buffer.
     *
     * @param stepDeg angular resolution; smaller is smoother and more vertices.
     */
    public static List<Shell> build(
            CellParams cell, AntennaPattern pattern, double[] shellsDb, int stepDeg) {

        List<Shell> shells = new ArrayList<>(shellsDb.length);
        for (double depthDb : shellsDb) {
            shells.add(new Shell(depthDb, buildShell(cell, pattern, depthDb, Math.max(1, stepDeg))));
        }
        return shells;
    }

    public static List<Shell> build(CellParams cell, AntennaPattern pattern) {
        return build(cell, pattern, DEFAULT_SHELLS_DB, DEFAULT_STEP_DEG);
    }

    /**
     * One shell as a lat/long wireframe: rings of constant elevation, plus meridians of constant
     * bearing. Both directions are drawn because a single family of curves reads as a flat disc.
     */
    private static List<Vertex> buildShell(
            CellParams cell, AntennaPattern pattern, double depthDb, int stepDeg) {

        List<Vertex> lines = new ArrayList<>();
        double peak = cell.gainDbi();

        // Rings: walk bearing all the way round at each elevation.
        for (int elevation = -90 + stepDeg; elevation <= 90 - stepDeg; elevation += stepDeg) {
            Vertex previous = null;
            Vertex first = null;
            for (int bearing = 0; bearing <= 360; bearing += stepDeg) {
                Vertex point = sample(cell, pattern, bearing, elevation, depthDb, peak);
                if (previous != null) {
                    lines.add(previous);
                    lines.add(point);
                } else {
                    first = point;
                }
                previous = point;
            }
            if (previous != null && first != null) {
                lines.add(previous);
                lines.add(first);
            }
        }

        // Meridians: walk elevation pole to pole at each bearing.
        for (int bearing = 0; bearing < 360; bearing += stepDeg) {
            Vertex previous = null;
            for (int elevation = -90; elevation <= 90; elevation += stepDeg) {
                Vertex point = sample(cell, pattern, bearing, elevation, depthDb, peak);
                if (previous != null) {
                    lines.add(previous);
                    lines.add(point);
                }
                previous = point;
            }
        }
        return lines;
    }

    /**
     * Radius at one direction: 1.0 on boresight, shrinking as gain falls away, 0 once the pattern
     * is {@link #PLOT_RANGE_DB} down.
     *
     * <p>{@code depthDb} offsets the whole surface inward, which is what makes the −10 dB shell sit
     * inside the −3 dB one.
     */
    static double radius(double gainDbi, double peakDbi, double depthDb) {
        double belowPeak = (peakDbi - gainDbi) + depthDb;
        return Math.clamp(1.0 - belowPeak / PLOT_RANGE_DB, 0.0, 1.0);
    }

    private static Vertex sample(
            CellParams cell, AntennaPattern pattern,
            double bearingDeg, double elevationDeg, double depthDb, double peakDbi) {

        double gain = pattern.gainDbi(bearingDeg, elevationDeg, cell);
        double r = radius(gain, peakDbi, depthDb);
        return toCartesian(bearingDeg, elevationDeg, r);
    }

    /**
     * Compass bearing plus elevation to Minecraft-local Cartesian.
     *
     * <p>Matches {@link AntennaGeometry}: bearing 0 is north, which is <b>−Z</b>, and bearing 90 is
     * east, which is +X. Getting this wrong would rotate every lobe 90° and it would still look
     * plausible, which is exactly why {@code PatternMeshTest} pins the cardinals.
     */
    static Vertex toCartesian(double bearingDeg, double elevationDeg, double radius) {
        double bearing = Math.toRadians(bearingDeg);
        double elevation = Math.toRadians(elevationDeg);
        double horizontal = radius * Math.cos(elevation);

        double x = horizontal * Math.sin(bearing);
        double z = -horizontal * Math.cos(bearing);
        double y = radius * Math.sin(elevation);
        return new Vertex(x, y, z);
    }
}
