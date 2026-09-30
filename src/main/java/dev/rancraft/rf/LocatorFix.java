package dev.rancraft.rf;

/**
 * What the Network Locator can say about where the receiver is. Phase 3, §3A.5.
 *
 * <p>Every variant is an <em>estimate</em> built from quantised, biased ranges
 * ({@link RangeMeasurement}); none of them is the true position. {@link LocatorSolver} never puts
 * NaN or an infinity into any field.
 *
 * <p>Coordinates are world blocks. {@code y} values are the receiver's assumed <em>eye</em> height
 * (surface + 1.62), the same convention as the drive-test log and the sample payload, because that
 * is where the engine measured the ranges from.
 */
public sealed interface LocatorFix {

    /** No usable cell. */
    record NoSignal() implements LocatorFix {
    }

    /**
     * One usable cell (or two whose circles do not meet): the receiver is somewhere on a ring, not
     * at a point.
     *
     * @param cx     the cell's radiating centre, x.
     * @param cz     the cell's radiating centre, z.
     * @param radius the cell's measured slant (3D) range, exactly as measured. Drawn as a horizontal
     *               ring it is the widest circle of the range sphere; a receiver below the radiating
     *               point is horizontally a little closer than this.
     */
    record RangeOnly(double cx, double cz, double radius) implements LocatorFix {
    }

    /**
     * Two usable cells whose horizontal circles cross: two candidate points, mirror images across
     * the line joining the cells. With {@code (dx, dz)} the direction from the first cell to the
     * second, {@code a} lies on the {@code (-dz, dx)} side and {@code b} on the other: standing on the
     * first cell facing the second, {@code a} is on your right-hand side (+x east, +z south).
     *
     * @param likely {@code 0} for {@code a}, {@code 1} for {@code b}: whichever is nearer the previous
     *               estimate (a {@link Fix}, or the likely candidate of a previous Ambiguous).
     *               {@link #NO_PREFERENCE} when there was no previous estimate to compare with, or it
     *               was exactly as far from both: the two are then equally likely, and the Locator
     *               must not pretend otherwise.
     */
    record Ambiguous(double ax, double az, double bx, double bz, int likely) implements LocatorFix {
        public static final int NO_PREFERENCE = -1;
    }

    /**
     * Three or more usable cells, but the geometry cannot pin the receiver down: HDOP above the limit,
     * or a singular geometry matrix (towers in a straight line). That is the lesson, not a failure.
     *
     * @param hdop      the horizontal dilution of precision, capped at
     *                  {@link LocatorSolver#HDOP_CEILING}; a singular geometry reports the ceiling.
     * @param cellsUsed sites that went into the attempt, one range each (co-sited sectors count once;
     *                  {@link LocatorSolver#siteRepresentatives}).
     */
    record PoorGeometry(double hdop, int cellsUsed) implements LocatorFix {
    }

    /**
     * A position.
     *
     * @param y           the assumed eye height: the ground under the estimate + 1.62 (altitude aiding,
     *                    see {@link LocatorSolver}). Not measured.
     * @param hdop        horizontal dilution of precision at the estimate (unweighted).
     * @param errorBlocks shown as "±": the reported 1-sigma horizontal uncertainty of the weighted
     *                    fit, {@code sqrt(trace((H^T W H)^-1))} with {@code W = diag(1 / sigma_i^2)}
     *                    (see {@link LocatorSolver}; an owner-approved deviation from §3A.5's
     *                    {@code hdop * rms(sigma)}, and identical to {@code hdop * sigma} when every
     *                    cell has the same sigma). <b>A reported uncertainty, not a guarantee</b>: it
     *                    covers the random quantisation error only. The NLOS bias is systematic and is
     *                    not in it, so behind terrain the true error can be larger.
     * @param cellsUsed   sites that went into the fit, one range each (co-sited sectors count once;
     *                    {@link LocatorSolver#siteRepresentatives}).
     */
    record Fix(double x, double y, double z, double hdop, double errorBlocks, int cellsUsed)
            implements LocatorFix {
    }
}
