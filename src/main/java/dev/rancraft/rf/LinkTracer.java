package dev.rancraft.rf;

import java.util.ArrayList;
import java.util.List;

/**
 * Traces one antenna-to-receiver link and records <em>where</em> along it the attenuation accrued.
 *
 * <p>This exists for the RF Lens link rays (Step 2a). {@link RayMarcher} answers "how many dB did
 * the terrain take"; the lens wants to draw a line that visibly reddens at the hill that took them.
 * The client is not allowed to work that out for itself -- obstruction is a measurement, and a client
 * that marched rays could see through terrain it has not loaded -- so the server traces the link here
 * and ships the result as a short list of {@link Breakpoint}s. The client only colours a line between
 * two known points by server-supplied numbers.
 *
 * <h2>Why a decorator and not a second marcher</h2>
 * The trace wraps the caller's {@link WorldProbe} in a recorder and runs the <b>unmodified</b>
 * {@link RayMarcher#march}. There is exactly one voxel traversal in the mod, so the total a link ray
 * shows and the obstruction the meter reports cannot drift apart: the recorder sums the same
 * {@code attenuation * penetrationFactor} terms in the same order as the marcher, and the final
 * breakpoint equals {@link Trace#obstructionDb()} bit for bit.
 *
 * <h2>What t means, and its abstraction</h2>
 * {@code t} is the voxel centre projected onto the straight antenna-to-receiver segment, clamped to
 * [0, 1]. <b>Abstraction:</b> a voxel's loss is drawn as a step at its centre, although the ray really
 * accrues it across the whole voxel; at link-ray scale that is less than one block of error.
 * Voxel centres visited by the traversal project in non-decreasing order (each step moves one block
 * along an axis the ray is travelling in), so {@code t} never runs backwards.
 */
public final class LinkTracer {

    private LinkTracer() {
    }

    /**
     * Breakpoint budget for one link. The payload carries it per link per sample, and a line split
     * into two dozen coloured pieces is already finer than the eye resolves at link-ray distance.
     */
    public static final int DEFAULT_MAX_BREAKPOINTS = 24;

    /**
     * One place where loss accrued.
     *
     * @param t            position along the link: 0 = antenna radiating centre, 1 = receiver.
     * @param cumulativeDb total loss from {@code t = 0} up to and including this voxel, in dB,
     *                     already multiplied by the band's penetration factor.
     */
    public record Breakpoint(double t, double cumulativeDb) {
    }

    /**
     * A traced link.
     *
     * @param obstructionDb exactly what {@link RayMarcher#march} reported for the same arguments.
     * @param earlyExit     the march stopped because the link was already past
     *                      {@code maxObstructionDb}; the breakpoints end at that voxel.
     * @param cappedOut     the step budget ran out before the receiver; the breakpoints cover only
     *                      the marched part.
     * @param breakpoints   non-decreasing in both {@code t} and {@code cumulativeDb}; empty for a
     *                      clear link. When non-empty, the last {@code cumulativeDb} equals
     *                      {@code obstructionDb} exactly. (Both guarantees assume the probe keeps
     *                      {@link WorldProbe}'s contract that attenuation is a loss, never negative.)
     */
    public record Trace(double obstructionDb, boolean earlyExit, boolean cappedOut, List<Breakpoint> breakpoints) {

        public Trace {
            breakpoints = List.copyOf(breakpoints);
        }
    }

    /**
     * Marches the link and records every lossy voxel on it.
     *
     * <p>The march arguments mean exactly what they mean to {@link RayMarcher#march}, including
     * the rule that the antenna's and the receiver's own voxels are skipped.
     *
     * @param x0             antenna radiating centre ({@link CellParams#centerX()} etc.)
     * @param x1             the receiver (a player's eye)
     * @param maxBreakpoints cap on the returned list; values below 1 are treated as 1. When more
     *                       lossy voxels than this were crossed, they are merged (see
     *                       {@link #compress}), never truncated, so the total survives.
     */
    public static Trace trace(WorldProbe probe,
                              double x0, double y0, double z0,
                              double x1, double y1, double z1,
                              double penetrationFactor, double maxObstructionDb, int maxSteps,
                              int maxBreakpoints) {

        Recorder recorder = new Recorder(probe, x0, y0, z0, x1, y1, z1, penetrationFactor);
        RayMarcher.MarchResult result = RayMarcher.march(
                recorder, x0, y0, z0, x1, y1, z1, penetrationFactor, maxObstructionDb, maxSteps);

        return new Trace(
                result.obstructionDb(),
                result.earlyExit(),
                result.cappedOut(),
                compress(recorder.breakpoints, Math.max(1, maxBreakpoints)));
    }

    /**
     * Merges breakpoints down to at most {@code max} by cutting [0, 1] into {@code max} equal
     * buckets of {@code t} and keeping each bucket's <b>last</b> breakpoint.
     *
     * <p>Bucketing by {@code t} rather than by list index bounds the error in <em>space</em>: after
     * compression, the loss drawn at any point of the line is late by at most {@code 1 / max} of the
     * link length, wherever the lossy voxels happen to cluster. Keeping the last element (not the
     * first, not an average) is what keeps the final value equal to the march total, and keeps both
     * sequences monotonic because the kept elements are a subsequence of the originals.
     */
    static List<Breakpoint> compress(List<Breakpoint> all, int max) {
        int count = all.size();
        if (count <= max) {
            return all;
        }

        List<Breakpoint> kept = new ArrayList<>(max);
        for (int i = 0; i < count; i++) {
            Breakpoint current = all.get(i);
            boolean lastInBucket = i == count - 1 || bucketOf(all.get(i + 1).t(), max) != bucketOf(current.t(), max);
            if (lastInBucket) {
                kept.add(current);
            }
        }
        return kept;
    }

    private static int bucketOf(double t, int buckets) {
        return Math.min(buckets - 1, (int) Math.floor(t * buckets));
    }

    /**
     * Passes every query straight through to the real probe and notes the lossy ones.
     *
     * <p>The running total repeats the marcher's own arithmetic ({@code total += a * factor} for
     * every voxel it asks about, in the order it asks) rather than approximating it, which is what
     * makes the final breakpoint exact. Air adds {@code 0.0}, which leaves a double unchanged, so it
     * is safe to fold into the same sum.
     */
    private static final class Recorder implements WorldProbe {

        private final WorldProbe delegate;
        private final double originX;
        private final double originY;
        private final double originZ;
        private final double dx;
        private final double dy;
        private final double dz;
        private final double lengthSquared;
        private final double penetrationFactor;

        final List<Breakpoint> breakpoints = new ArrayList<>();
        private double total;
        private double lastT;

        Recorder(WorldProbe delegate,
                 double x0, double y0, double z0,
                 double x1, double y1, double z1,
                 double penetrationFactor) {
            this.delegate = delegate;
            this.originX = x0;
            this.originY = y0;
            this.originZ = z0;
            this.dx = x1 - x0;
            this.dy = y1 - y0;
            this.dz = z1 - z0;
            this.lengthSquared = dx * dx + dy * dy + dz * dz;
            this.penetrationFactor = penetrationFactor;
        }

        @Override
        public double attenuationDbAt(int x, int y, int z) {
            double attenuation = delegate.attenuationDbAt(x, y, z);
            double contribution = attenuation * penetrationFactor;
            total += contribution;

            // "Lossy" means this voxel actually added loss on this band. For any positive
            // penetration factor that is the same as attenuation > 0; for a factor of zero the
            // band ignores walls, and a breakpoint that adds nothing would only be noise.
            if (contribution > 0.0) {
                // Floating-point projection cannot run backwards by the geometry above, but a
                // max() costs nothing and guarantees the documented ordering outright.
                lastT = Math.max(lastT, projectVoxelCentre(x, y, z));
                breakpoints.add(new Breakpoint(lastT, total));
            }
            return attenuation;
        }

        private double projectVoxelCentre(int x, int y, int z) {
            if (lengthSquared == 0.0) {
                // Unreachable in practice: RayMarcher never queries a zero-length link.
                return 0.0;
            }
            double along = (x + 0.5 - originX) * dx + (y + 0.5 - originY) * dy + (z + 0.5 - originZ) * dz;
            return Math.clamp(along / lengthSquared, 0.0, 1.0);
        }
    }
}
