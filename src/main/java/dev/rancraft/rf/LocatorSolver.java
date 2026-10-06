package dev.rancraft.rf;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * Turns measured ranges into a position estimate. Phase 3, §3A.5.
 *
 * <h2>Sites, not cells (Phase 3A review, round 1)</h2>
 * §3A.5 counts "usable cells". In this mod a sector is its own cell on its own block, so a
 * three-sector site is three cells one block apart. Their ranges are near-identical, and on one band
 * they round the same way, so their errors are perfectly correlated: they add no independent
 * geometry. Counted as separate towers they made one site AMBIGUOUS on a ring, two sites a confident
 * FIX on the wrong mirror image, and the "±" of a three-sector network about 1.6x too small. So the
 * solver first groups the measurements into <b>sites</b> ({@link #siteRepresentatives}: antennas
 * within {@link LocatorParams#siteMergeBlocks()} of each other horizontally) and each site
 * contributes exactly one range. Every "cell" below means one such site. <b>Game abstraction:</b> a
 * real network knows each site's (TRP's) location, and co-sited sectors are one position to range
 * from; the mod has no site container, so horizontal proximity stands in for that identity.
 *
 * <h2>Three or more usable sites: weighted least squares with altitude aiding</h2>
 * <ol>
 *   <li>Take up to {@link LocatorParams#maxCells()} sites, in the order of each site's strongest
 *       cell (strongest first, as {@link Ranging} emits them), one representative range each.
 *   <li>Start at the centroid of those sites' (x, z).
 *   <li><b>Altitude aiding.</b> Assume the receiver stands on the ground:
 *       {@code yRx = ground.surfaceY(x, z) + 1.62} at the current estimate, re-derived every
 *       iteration.
 *   <li>Horizontal range per cell: {@code rho_i = sqrt(max(0, r_i^2 - (y_i - yRx)^2))}.
 *   <li>Gauss-Newton on (x, z), weights {@code 1 / sigma_i^2}, at most {@value #MAX_ITERATIONS}
 *       iterations, stopping once a step is under {@value #CONVERGENCE_BLOCKS} blocks.
 *   <li><b>Deliberate deviation from §3A.5's centroid-only start, kept by the project owner's
 *       decision</b> (NOTES.md, Phase 3 "Owner decisions on the slice 3 follow-ups"): the same fit is
 *       also started from the circle crossings of the strongest cells, and a run that ends in a
 *       different place with a smaller weighted residual wins. Why: from the centroid alone, 10.5 to
 *       16.7 % of fixes with the receiver outside the cells' footprint settle in a wrong local minimum
 *       and are reported as a FIX with a small "±", even with perfect ranges. A confident wrong
 *       answer teaches the wrong thing about the "±". When the centroid's run is the best fit, the
 *       answer is the spec's, unchanged.
 *   <li>{@code HDOP = sqrt(trace((H^T H)^-1))}, the rows of H being the horizontal unit vectors from
 *       the estimate to each cell. <b>Unweighted</b>, as HDOP is conventionally defined: it describes
 *       the geometry alone.
 *   <li>HDOP above {@link LocatorParams#maxHdop()}, or a singular H^T H, gives
 *       {@link LocatorFix.PoorGeometry}. Towers in a straight line are exactly that: from the centroid
 *       (which is on the line) every line of sight is parallel, the matrix is singular, and the two
 *       mirror-image positions either side of the line cannot be told apart. That is the lesson, not
 *       a bug to smooth over.
 *   <li>{@code errorBlocks = sqrt(trace((H^T W H)^-1))} with {@code W = diag(1 / sigma_i^2)} and the
 *       same H as HDOP: the reported "±", the 1-sigma horizontal uncertainty of the weighted fit
 *       (the root of the two axis variances summed). <b>An owner-approved deviation from §3A.5</b>,
 *       which writes {@code HDOP * rms(sigma_i)}: the two are identical when every cell has the same
 *       sigma (any single-band fix), and for mixed bands this is the covariance of the estimate the
 *       solver actually computes, so it credits a wideband cell for the weight it carries in the
 *       fit. See {@link #weightedErrorOf}.
 * </ol>
 *
 * <p>Mixed bands just work: a band_3500 cell has a 10x smaller sigma, so its weight is 100x larger
 * and it dominates the fit along its own line of sight, and the "±" says so. (One range still
 * constrains only one direction, so a single wideband cell inside a good triangle helps less than
 * at the edge of a network; see NOTES.md, Phase 3.)
 *
 * <h2>Two sites: circle intersection</h2>
 * The two horizontal circles (radius {@code rho_i}, altitude-aided at the midpoint of the two sites)
 * cross in 0 or 2 points. None → {@link LocatorFix.RangeOnly} on the nearer site (smaller measured
 * range). Two → {@link LocatorFix.Ambiguous}; each candidate is then refined with the altitude
 * re-derived under it, and {@code likely} names the one nearer the previous estimate, if any.
 *
 * <h2>One site</h2>
 * {@link LocatorFix.RangeOnly}: a ring, not a point, however many of the site's sectors are heard.
 *
 * <h2>Honest limits (also in NOTES.md)</h2>
 * <ul>
 *   <li><b>Altitude aiding assumes the top surface.</b> {@link SurfaceProbe} knows one height per
 *       column, so in a cave or under an overhang the assumed height is wrong, and so are the
 *       horizontal ranges. (Underground you would rarely hear enough cells anyway.) The reported
 *       {@code y} is that assumption, never a measurement.
 *   <li><b>Absolute ranging, not time differences</b> (see {@link Ranging}): like multi-RTT, with no
 *       clock error and no dedicated positioning reference signals.
 *   <li><b>The "±" is a reported uncertainty, not a guarantee.</b> It is the 1-sigma horizontal
 *       uncertainty of the weighted fit, from the random quantisation error only; the NLOS bias is
 *       systematic and is not in it, and neither is a mirror ambiguity.
 *   <li><b>Nearly collinear towers: the mirror check</b> (row 16d). Only an exact line is singular at
 *       the centroid. The HDOP gate is read at the estimate, and seen from off a nearly straight line
 *       the lines of sight fan out, so HDOP looks good while the mirror image fits the coarse ranges
 *       about as well. Before row 16d that gave a confident FIX on the wrong side: band_900, three
 *       masts 300 blocks end to end with the middle one 10 blocks off the line, the receiver 20-120
 *       blocks from it, 32 % of FIX results on the wrong side, about 139 blocks off with a "±" of
 *       about 12. Now the fit is also run from the estimate mirrored across the sites' line, and a
 *       mirror about as good ({@link #MIRROR_COST_MARGIN}) makes the answer
 *       {@link LocatorFix.Ambiguous}: both candidates, as for two sites. A deviation from §3A.5,
 *       which defines AMBIGUOUS only for two cells (NOTES.md, row 16d, with the measurement).
 * </ul>
 *
 * <h2>Robustness</h2>
 * Every division and square root is guarded: a result never contains NaN or an infinity, including
 * when the receiver stands exactly under a tower (that cell's line of sight has no horizontal
 * direction, so it adds no row to the geometry at that point). Measurements with non-finite or
 * absurd values are ignored.
 */
public final class LocatorSolver {

    /** Gauss-Newton iteration cap (§3A.5). */
    public static final int MAX_ITERATIONS = 15;

    /** Gauss-Newton stops once a step is shorter than this (§3A.5). */
    public static final double CONVERGENCE_BLOCKS = 0.01;

    /** {@link LocatorFix.PoorGeometry#hdop()} is capped here; a singular geometry reports it. */
    public static final double HDOP_CEILING = 99.9;

    /** The altitude-aiding assumption: a standing player's eye, as the live meter measures from. */
    public static final double RECEIVER_EYE_HEIGHT = CoverageSurvey.RECEIVER_EYE_HEIGHT;

    /** A sigma of zero would be an infinite weight; this floor stands in for "exact". */
    static final double MIN_SIGMA_BLOCKS = 1e-6;

    /** Closer than this horizontally, the direction to a cell is undefined: no geometry row. */
    static final double MIN_DIRECTION_BLOCKS = 1e-6;

    /** H^T H counts as singular when det <= this * trace^2 (scale-free; the ratio is at most 1/4). */
    static final double SINGULAR_RELATIVE = 1e-12;

    /**
     * Coordinates, ranges and sigmas beyond this are rejected, and an estimate that runs this far is
     * poor geometry. Far outside any Minecraft world (30 million), and small enough that no square
     * of it can overflow.
     */
    static final double MAX_ABS_BLOCKS = 1e9;

    /**
     * Extra Gauss-Newton starts come from the circle crossings of each pair among this many of the
     * strongest cells: 3 pairs, at most 6 extra runs. A deliberate deviation from §3A.5, kept by the
     * project owner; see {@link #leastSquares}.
     */
    static final int ALTERNATIVE_START_CELLS = 3;

    /** Two runs ending closer than this found the same minimum; the centroid's run is kept. */
    static final double SAME_BASIN_BLOCKS = 1.0;

    /**
     * The mirror check (row 16d): the fit is run once more from the estimate mirrored across the sites'
     * best-fit line, and a mirror minimum whose weighted residual is within this of the best one is as
     * well supported by the ranges, so the answer is AMBIGUOUS. In weighted-cost units (squared
     * residuals over sigma squared, summed). Chosen from the measurement in NOTES.md, row 16d.
     */
    static final double MIRROR_COST_MARGIN = 4.0;

    /**
     * A mirror candidate closer to the fix than this many reported "±" changes nothing: the "±"
     * already covers it, so the FIX is not a confident wrong answer.
     */
    static final double MIRROR_MIN_SEPARATION_ERRORS = 3.0;

    private LocatorSolver() {
    }

    /**
     * @param ranges   usable ranges, strongest first ({@link Ranging#measure}); grouped into sites
     *                 here ({@link #siteRepresentatives}).
     * @param ground   where the ground is, for altitude aiding. An {@link SurfaceProbe#UNLOADED}
     *                 column keeps the last known height (see {@link #initialEyeY}).
     * @param previous the last fix for this receiver, or null. Only picks the likely candidate of an
     *                 {@link LocatorFix.Ambiguous} and seeds the height if the first column is unknown.
     */
    public static LocatorFix solve(List<RangeMeasurement> ranges, SurfaceProbe ground,
                                   LocatorFix previous, LocatorParams params) {
        return solve(ranges, ground, previous, params, MIRROR_COST_MARGIN);
    }

    /**
     * {@link #solve} with the mirror check's margin given ({@link #MIRROR_COST_MARGIN} in the game); a
     * negative margin turns the check off, which is the solver as it was before row 16d. For measuring
     * the check.
     */
    static LocatorFix solve(List<RangeMeasurement> ranges, SurfaceProbe ground,
                            LocatorFix previous, LocatorParams params, double mirrorCostMargin) {
        Objects.requireNonNull(ranges, "ranges");
        Objects.requireNonNull(ground, "ground");
        Objects.requireNonNull(params, "params");

        // One representative range per site (see the class comment): the branch is chosen by the
        // number of sites, so the centroid, the singular-at-start test, HDOP, the "±" and the extra
        // starts all see one row per site.
        List<RangeMeasurement> used = siteRepresentatives(ranges, params);
        return switch (used.size()) {
            case 0 -> new LocatorFix.NoSignal();
            case 1 -> rangeOnly(used.get(0));
            case 2 -> twoCells(used.get(0), used.get(1), ground, previous);
            default -> leastSquares(used, ground, previous, params.maxHdop(), mirrorCostMargin);
        };
    }

    /**
     * Exactly the measurements {@link #solve} takes from {@code ranges}: one representative per site,
     * at most {@link LocatorParams#maxCells()} ({@link #siteRepresentatives}). Phase 3 slice 5 added
     * this so the Locator can draw a ring for every range that went into a fix, and only those,
     * without repeating the selection rule; since the Phase 3A review that is one ring per site (the
     * rings of co-sited sectors were near-identical anyway). {@code solve(...).cellsUsed()} equals its
     * size for a {@link LocatorFix.Fix} and a {@link LocatorFix.PoorGeometry}.
     */
    public static List<RangeMeasurement> cellsUsed(List<RangeMeasurement> ranges, LocatorParams params) {
        Objects.requireNonNull(ranges, "ranges");
        Objects.requireNonNull(params, "params");
        return List.copyOf(siteRepresentatives(ranges, params));
    }

    /**
     * Groups the valid measurements into sites and keeps one range per site. Phase 3A review, round 1.
     *
     * <ol>
     *   <li>Every valid measurement is kept (non-finite or absurd values are dropped, as always); the
     *       {@code maxCells} cap comes last, so it counts sites.
     *   <li>Two measurements whose radiating points are at most
     *       {@link LocatorParams#siteMergeBlocks()} apart horizontally are the same site, and so is
     *       anything linked to either (union-find over all pairs: transitive, and independent of the
     *       input order).
     *   <li>Each site is represented by its measurement with the smallest sigma (the finest band),
     *       then the smallest range (the least NLOS-biased), then the first given.
     *   <li>Sites are listed in the order of each one's first (strongest) measurement, and at most
     *       {@code maxCells} are kept.
     * </ol>
     *
     * <p><b>Game abstraction, labelled:</b> a real network knows where each transmission point is,
     * and the sectors of one site are one position to range from; their ranges share the same
     * geometry and, rounded on the same band, the same error, so counting them separately invents
     * geometry that is not there. The mod has no site container (a three-sector site is three
     * blocks, each its own cell), so horizontal proximity stands in for that identity. A negative
     * or NaN distance groups nothing; 0 groups only antennas stacked in one column.
     */
    static List<RangeMeasurement> siteRepresentatives(List<RangeMeasurement> ranges, LocatorParams params) {
        List<RangeMeasurement> valid = new ArrayList<>(ranges.size());
        for (RangeMeasurement m : ranges) {
            if (isValid(m)) {
                valid.add(m);
            }
        }
        int n = valid.size();
        int limit = Math.max(0, params.maxCells());
        if (n == 0 || limit == 0) {
            return new ArrayList<>(0);
        }

        int[] parent = new int[n];
        for (int i = 0; i < n; i++) {
            parent[i] = i;
        }
        double merge = params.siteMergeBlocks();
        if (merge >= 0.0 && withinBounds(merge)) {
            // Squared distances: the same test as hypot(dx, dz) <= merge without a square root per
            // pair (coordinates are within MAX_ABS_BLOCKS, so no square overflows).
            double mergeSquared = merge * merge;
            for (int i = 0; i < n; i++) {
                RangeMeasurement a = valid.get(i);
                for (int j = i + 1; j < n; j++) {
                    RangeMeasurement b = valid.get(j);
                    double dx = a.x() - b.x();
                    double dz = a.z() - b.z();
                    if (dx * dx + dz * dz <= mergeSquared) {
                        union(parent, i, j);
                    }
                }
            }
        }

        // The representative of each site, indexed by its root; sites in order of first member.
        int[] representative = new int[n];
        Arrays.fill(representative, -1);
        int[] siteOrder = new int[n];
        int sites = 0;
        for (int i = 0; i < n; i++) {
            int root = find(parent, i);
            int current = representative[root];
            if (current < 0) {
                representative[root] = i;
                siteOrder[sites++] = root;
            } else if (representsBetter(valid.get(i), valid.get(current))) {
                representative[root] = i;
            }
        }

        List<RangeMeasurement> used = new ArrayList<>(Math.min(limit, sites));
        for (int s = 0; s < sites && used.size() < limit; s++) {
            used.add(valid.get(representative[siteOrder[s]]));
        }
        return used;
    }

    /** Smaller sigma, then a shorter range; a tie keeps the one given first. */
    private static boolean representsBetter(RangeMeasurement candidate, RangeMeasurement current) {
        double candidateSigma = sigma(candidate);
        double currentSigma = sigma(current);
        if (candidateSigma != currentSigma) {
            return candidateSigma < currentSigma;
        }
        return candidate.rangeBlocks() < current.rangeBlocks();
    }

    private static int find(int[] parent, int i) {
        int root = i;
        while (parent[root] != root) {
            root = parent[root];
        }
        while (parent[i] != root) {
            int next = parent[i];
            parent[i] = root;
            i = next;
        }
        return root;
    }

    private static void union(int[] parent, int i, int j) {
        int a = find(parent, i);
        int b = find(parent, j);
        if (a != b) {
            // The smaller index stays the root: deterministic, and the root is not what orders sites.
            if (a < b) {
                parent[b] = a;
            } else {
                parent[a] = b;
            }
        }
    }

    // ---- 3+ cells ------------------------------------------------------------------------------

    private static LocatorFix leastSquares(List<RangeMeasurement> used, SurfaceProbe ground,
                                           LocatorFix previous, double maxHdop, double mirrorCostMargin) {
        int n = used.size();

        double centroidX = 0.0;
        double centroidZ = 0.0;
        for (RangeMeasurement m : used) {
            centroidX += m.x();
            centroidZ += m.z();
        }
        centroidX /= n;
        centroidZ /= n;

        double yStart = initialEyeY(used, previous);
        Fit best = gaussNewton(used, ground, centroidX, centroidZ, yStart);
        if (best.status() == Status.SINGULAR_AT_START) {
            // Every line of sight from the centroid is parallel: the cells are in a straight line, and
            // the two mirror images either side of it fit equally well. Poor geometry, full stop;
            // trying other starts would only pick one of the mirrors at random.
            return new LocatorFix.PoorGeometry(HDOP_CEILING, n);
        }

        // DELIBERATE DEVIATION from §3A.5 ("initial guess: centroid"), kept by the project owner's
        // decision (PHASE_3.md follow-ups; NOTES.md, Phase 3 slice 3 and "Owner decisions"). The
        // centroid is still the first start, but Gauss-Newton from the centroid alone settles in a
        // wrong local minimum for 10.5-16.7 % of fixes once the receiver is outside the cells'
        // footprint, even with perfect ranges, and reports that wrong fix with a small "±" (measured
        // over 20,000 seeded scenes per case). So the fit is also started from the circle crossings
        // of the strongest cells (with exact ranges one of them IS the answer), and a start that
        // ends in a clearly different place with a lower weighted residual replaces the centroid's
        // result. When the centroid's fit is the best one, the result is exactly the spec's.
        double yAtCentroid = eyeY(ground, centroidX, centroidZ, yStart);
        int starters = Math.min(n, ALTERNATIVE_START_CELLS);
        for (int i = 0; i < starters; i++) {
            for (int j = i + 1; j < starters; j++) {
                double[] crossing = intersect(used.get(i), used.get(j), yAtCentroid);
                if (crossing == null) {
                    continue;
                }
                for (int k = 0; k < 2; k++) {
                    Fit alternative = gaussNewton(used, ground, crossing[2 * k], crossing[2 * k + 1], yAtCentroid);
                    if (alternative.isBetterThan(best)) {
                        best = alternative;
                    }
                }
            }
        }
        if (best.status() != Status.CONVERGED_OR_CAPPED) {
            // Every start ran away or met a singular matrix on the way: the geometry cannot hold a fit.
            return new LocatorFix.PoorGeometry(HDOP_CEILING, n);
        }

        // Row 16d, the mirror check (owner decision on the slices 2-3 gate's collinear follow-up). Seen
        // from off a nearly straight line of sites the lines of sight fan out, so HDOP looks good, yet
        // the mirror image across the line fits coarse ranges about as well, and quantisation alone
        // picked the side: up to 46 % of such FIX results were on the wrong side, about 140 blocks off
        // with a "±" of about 12. So the fit is run once more from the estimate mirrored across the
        // sites' best-fit line. A clearly better fit there replaces the estimate, as an extra start's
        // would; one about as good (within the margin) makes the answer AMBIGUOUS, both sides shown.
        Fit mirror = null;
        if (mirrorCostMargin >= 0.0) {
            double[] start = mirrored(siteLine(used), best.x(), best.z());
            Fit run = gaussNewton(used, ground, start[0], start[1], best.yRx());
            if (run.status() == Status.CONVERGED_OR_CAPPED
                    && Math.hypot(run.x() - best.x(), run.z() - best.z()) > SAME_BASIN_BLOCKS) {
                if (run.cost() < best.cost() - mirrorCostMargin) {
                    best = run;
                } else if (run.cost() <= best.cost() + mirrorCostMargin) {
                    mirror = run;
                }
            }
        }

        double[] ux = new double[n];
        double[] uz = new double[n];
        unitVectors(used, best.x(), best.z(), ux, uz);
        double hdop = hdopOf(ux, uz);
        if (!(hdop <= maxHdop)) {
            return new LocatorFix.PoorGeometry(Math.min(hdop, HDOP_CEILING), n);
        }

        // Owner-approved deviation from §3A.5's "errorBlocks = HDOP x rms(sigma)": the "±" is the
        // covariance of the weighted fit, over the same rows as HDOP. Identical for single-band
        // fixes; see weightedErrorOf for why it is the right estimate when the bands are mixed.
        double errorBlocks = weightedErrorOf(used, ux, uz);
        if (!Double.isFinite(errorBlocks)) {
            // Unreachable once HDOP passed: every weight is positive, so H^T W H is singular only
            // where H^T H is. Kept so that no infinity can ever leave the solver (test 12).
            return new LocatorFix.PoorGeometry(HDOP_CEILING, n);
        }
        if (mirror != null && Math.hypot(mirror.x() - best.x(), mirror.z() - best.z())
                > MIRROR_MIN_SEPARATION_ERRORS * errorBlocks) {
            // Candidate A is the better fit, B its mirror; "likely" is the one nearer the last estimate.
            boolean bestFirst = best.cost() <= mirror.cost();
            Fit a = bestFirst ? best : mirror;
            Fit b = bestFirst ? mirror : best;
            double[] pa = {a.x(), a.z()};
            double[] pb = {b.x(), b.z()};
            return new LocatorFix.Ambiguous(pa[0], pa[1], pb[0], pb[1], likely(previous, pa, pb));
        }
        return new LocatorFix.Fix(best.x(), best.yRx(), best.z(), hdop, errorBlocks, n);
    }

    /**
     * The sites' best-fit line, {cx, cz, ux, uz}: through their centroid along the major axis of their
     * spread (total least squares). For sites nearly in a line it is that line; for a well-spread set
     * it is some axis through the middle, and the mirror run from across it finds the same minimum or
     * a clearly worse one.
     */
    static double[] siteLine(List<RangeMeasurement> used) {
        double cx = 0.0;
        double cz = 0.0;
        for (RangeMeasurement m : used) {
            cx += m.x();
            cz += m.z();
        }
        cx /= used.size();
        cz /= used.size();
        double sxx = 0.0;
        double sxz = 0.0;
        double szz = 0.0;
        for (RangeMeasurement m : used) {
            double dx = m.x() - cx;
            double dz = m.z() - cz;
            sxx += dx * dx;
            sxz += dx * dz;
            szz += dz * dz;
        }
        double angle = 0.5 * Math.atan2(2.0 * sxz, sxx - szz);
        return new double[] {cx, cz, Math.cos(angle), Math.sin(angle)};
    }

    /** (px, pz) mirrored across {@code line} ({@link #siteLine}). */
    static double[] mirrored(double[] line, double px, double pz) {
        double vx = px - line[0];
        double vz = pz - line[1];
        double along = vx * line[2] + vz * line[3];
        return new double[] {line[0] + 2.0 * along * line[2] - vx, line[1] + 2.0 * along * line[3] - vz};
    }

    private enum Status { CONVERGED_OR_CAPPED, SINGULAR_AT_START, FAILED }

    /**
     * One Gauss-Newton run. {@code cost} is the weighted sum of squared horizontal residuals at the
     * end point, with the altitude re-derived there: what least squares minimises.
     */
    private record Fit(double x, double z, double yRx, double cost, Status status) {

        /** A usable fit that ends clearly elsewhere (another basin) and fits the ranges better. */
        boolean isBetterThan(Fit other) {
            if (status != Status.CONVERGED_OR_CAPPED) {
                return false;
            }
            if (other.status != Status.CONVERGED_OR_CAPPED) {
                return true;
            }
            return cost < other.cost && Math.hypot(x - other.x, z - other.z) > SAME_BASIN_BLOCKS;
        }
    }

    /**
     * Weighted Gauss-Newton on (x, z) from one start: at most {@link #MAX_ITERATIONS} iterations,
     * stopping once a step is under {@link #CONVERGENCE_BLOCKS}, altitude re-derived every iteration.
     * A run that hits the iteration cap keeps its last estimate, as §3A.5 says.
     */
    private static Fit gaussNewton(List<RangeMeasurement> used, SurfaceProbe ground,
                                   double startX, double startZ, double yStart) {
        int n = used.size();
        double[] ux = new double[n];
        double[] uz = new double[n];
        double px = startX;
        double pz = startZ;
        double yRx = yStart;

        for (int iteration = 0; iteration < MAX_ITERATIONS; iteration++) {
            yRx = eyeY(ground, px, pz, yRx);

            // Rows: d(distance)/d(estimate) = unit vector from the cell to the estimate.
            double n11 = 0.0;
            double n12 = 0.0;
            double n22 = 0.0;
            double g1 = 0.0;
            double g2 = 0.0;
            for (int i = 0; i < n; i++) {
                RangeMeasurement m = used.get(i);
                double dx = px - m.x();
                double dz = pz - m.z();
                double d = Math.hypot(dx, dz);
                if (!(d >= MIN_DIRECTION_BLOCKS)) {
                    ux[i] = 0.0;
                    uz[i] = 0.0;
                    continue;
                }
                ux[i] = dx / d;
                uz[i] = dz / d;
                double w = weight(m);
                double residual = horizontalRange(m, yRx) - d;
                n11 += w * ux[i] * ux[i];
                n12 += w * ux[i] * uz[i];
                n22 += w * uz[i] * uz[i];
                g1 += w * ux[i] * residual;
                g2 += w * uz[i] * residual;
            }

            // Singular in the unweighted, scale-free sense: the geometry, not the weights, decides.
            if (Double.isInfinite(hdopOf(ux, uz))) {
                return new Fit(px, pz, yRx, Double.POSITIVE_INFINITY,
                        iteration == 0 ? Status.SINGULAR_AT_START : Status.FAILED);
            }
            // Cauchy-Binet: det = sum over pairs of w_i w_j (u_i x u_j)^2. Exact to rounding even
            // when one weight is orders of magnitude above the rest, where n11*n22 - n12^2 cancels.
            double det = weightedDet(used, ux, uz);
            if (!(det > 0.0) || !Double.isFinite(det)) {
                return new Fit(px, pz, yRx, Double.POSITIVE_INFINITY, Status.FAILED);
            }
            double stepX = (n22 * g1 - n12 * g2) / det;
            double stepZ = (n11 * g2 - n12 * g1) / det;
            px += stepX;
            pz += stepZ;
            if (!withinBounds(px) || !withinBounds(pz)) {
                // The fit ran away: the geometry could not hold it. Not a position.
                return new Fit(startX, startZ, yStart, Double.POSITIVE_INFINITY, Status.FAILED);
            }
            if (Math.hypot(stepX, stepZ) < CONVERGENCE_BLOCKS) {
                break;
            }
        }

        yRx = eyeY(ground, px, pz, yRx);
        double cost = 0.0;
        for (RangeMeasurement m : used) {
            double residual = horizontalRange(m, yRx) - Math.hypot(px - m.x(), pz - m.z());
            cost += weight(m) * residual * residual;
        }
        return new Fit(px, pz, yRx, Double.isFinite(cost) ? cost : Double.MAX_VALUE, Status.CONVERGED_OR_CAPPED);
    }

    /** Horizontal unit vectors from (px, pz) to each cell; a zero row where the direction is undefined. */
    private static void unitVectors(List<RangeMeasurement> used, double px, double pz, double[] ux, double[] uz) {
        for (int i = 0; i < ux.length; i++) {
            RangeMeasurement m = used.get(i);
            double dx = m.x() - px;
            double dz = m.z() - pz;
            double d = Math.hypot(dx, dz);
            boolean hasDirection = d >= MIN_DIRECTION_BLOCKS;
            ux[i] = hasDirection ? dx / d : 0.0;
            uz[i] = hasDirection ? dz / d : 0.0;
        }
    }

    /**
     * {@code sqrt(trace((H^T H)^-1))} for rows (ux[i], uz[i]); zero rows add nothing.
     *
     * @return {@link Double#POSITIVE_INFINITY} when H^T H is singular (relative to its trace).
     */
    static double hdopOf(double[] ux, double[] uz) {
        double a = 0.0;
        double c = 0.0;
        double det = 0.0;
        for (int i = 0; i < ux.length; i++) {
            a += ux[i] * ux[i];
            c += uz[i] * uz[i];
            for (int j = i + 1; j < ux.length; j++) {
                double cross = ux[i] * uz[j] - uz[i] * ux[j];
                det += cross * cross;
            }
        }
        double trace = a + c;
        if (!(det > SINGULAR_RELATIVE * trace * trace)) {
            return Double.POSITIVE_INFINITY;
        }
        return Math.sqrt(trace / det);
    }

    /**
     * The reported "±": {@code sqrt(trace((H^T W H)^-1))} with {@code W = diag(1 / sigma_i^2)}, for
     * the same rows (ux[i], uz[i]) as {@link #hdopOf}; zero rows add nothing. It is the 1-sigma
     * horizontal uncertainty of the weighted fit: the root of the x and z variances summed (a
     * distance-RMS figure, the same convention as {@code HDOP * sigma}).
     *
     * <p><b>An owner-approved deviation from §3A.5</b>, which writes {@code HDOP * rms(sigma_i)}.
     * Why this is the right estimate for this solver: Gauss-Newton with weights {@code 1 / sigma_i^2}
     * is weighted least squares with W the inverse of the range-error covariance, and the
     * covariance of that estimate, linearised at the fit, is exactly {@code (H^T W H)^-1} (the
     * Gauss-Markov result; it is also the Cramer-Rao bound for Gaussian errors). {@code HDOP * rms}
     * describes a fit that treats every cell alike, which is not the fit being made: it ignores that
     * a band_3500 range carries 100x the weight of a band_900 one, so it under-reports what the
     * wideband cell buys. When every sigma is equal, {@code W = I / sigma^2} and the two are
     * identical: {@code sqrt(sigma^2 trace((H^T H)^-1)) = HDOP * sigma}.
     *
     * <p>2x2 closed form: {@code trace(M^-1) = trace(M) / det(M)}, with the determinant summed
     * pairwise (Cauchy-Binet, as {@link #weightedDet}), exact to rounding when one weight is orders
     * of magnitude above the rest. The sigmas are the quantisation spread {@code res / sqrt(12)}
     * (uniform, not Gaussian, errors: the covariance is still exact for a linear fit; only the
     * "68 %" reading of 1 sigma is approximate). The NLOS bias is systematic and not in it.
     *
     * <p><b>Simplification, labelled (NOTES.md, "Owner decisions on the slice 3 follow-ups"):</b>
     * the fit works on horizontal ranges {@code rho = sqrt(r^2 - dy^2)}, whose error is
     * {@code sigma * r / rho}, larger than the slant-range sigma close under a high tower. Both the
     * weights and this "±" use the slant-range sigma, as §3A.5 does. Small at the usual distances; it
     * under-states the "±" only when the receiver stands almost under a mast.
     *
     * @return {@link Double#POSITIVE_INFINITY} when the weighted matrix is singular or not finite.
     */
    static double weightedErrorOf(List<RangeMeasurement> used, double[] ux, double[] uz) {
        double trace = 0.0;
        for (int i = 0; i < ux.length; i++) {
            trace += weight(used.get(i)) * (ux[i] * ux[i] + uz[i] * uz[i]);
        }
        double det = weightedDet(used, ux, uz);
        if (!(det > 0.0) || !Double.isFinite(det) || !Double.isFinite(trace)) {
            return Double.POSITIVE_INFINITY;
        }
        double error = Math.sqrt(trace / det);
        return Double.isFinite(error) ? error : Double.POSITIVE_INFINITY;
    }

    private static double weightedDet(List<RangeMeasurement> used, double[] ux, double[] uz) {
        double det = 0.0;
        for (int i = 0; i < ux.length; i++) {
            double wi = weight(used.get(i));
            for (int j = i + 1; j < ux.length; j++) {
                double cross = ux[i] * uz[j] - uz[i] * ux[j];
                det += wi * weight(used.get(j)) * cross * cross;
            }
        }
        return det;
    }

    // ---- 2 cells -------------------------------------------------------------------------------

    private static LocatorFix twoCells(RangeMeasurement first, RangeMeasurement second,
                                       SurfaceProbe ground, LocatorFix previous) {
        double midX = (first.x() + second.x()) / 2.0;
        double midZ = (first.z() + second.z()) / 2.0;
        double yMid = eyeY(ground, midX, midZ, initialEyeY(List.of(first, second), previous));

        double[] crossing = intersect(first, second, yMid);
        if (crossing == null) {
            return rangeOnly(first.rangeBlocks() <= second.rangeBlocks() ? first : second);
        }
        double[] a = refine(first, second, ground, crossing[0], crossing[1], yMid);
        double[] b = refine(first, second, ground, crossing[2], crossing[3], yMid);
        return new LocatorFix.Ambiguous(a[0], a[1], b[0], b[1], likely(previous, a, b));
    }

    /**
     * Follows one candidate while the altitude under it is re-derived: the same "re-derive yRx each
     * iteration" rule as the 3-cell fit, per candidate, because the two sit on different ground.
     */
    private static double[] refine(RangeMeasurement first, RangeMeasurement second, SurfaceProbe ground,
                                   double x, double z, double yStart) {
        double px = x;
        double pz = z;
        double y = yStart;
        for (int iteration = 0; iteration < MAX_ITERATIONS; iteration++) {
            y = eyeY(ground, px, pz, y);
            double[] crossing = intersect(first, second, y);
            if (crossing == null) {
                break;
            }
            boolean firstIsNearer = Math.hypot(crossing[0] - px, crossing[1] - pz)
                    <= Math.hypot(crossing[2] - px, crossing[3] - pz);
            double qx = firstIsNearer ? crossing[0] : crossing[2];
            double qz = firstIsNearer ? crossing[1] : crossing[3];
            double moved = Math.hypot(qx - px, qz - pz);
            px = qx;
            pz = qz;
            if (moved < CONVERGENCE_BLOCKS) {
                break;
            }
        }
        return new double[] {px, pz};
    }

    /**
     * The two crossings of the horizontal circles at receiver height {@code yRx}, as
     * {ax, az, bx, bz} with a on the (-dz, dx) side of first → second, or null if they do not cross
     * (apart, nested, or concentric). Tangent circles give two equal points.
     */
    private static double[] intersect(RangeMeasurement first, RangeMeasurement second, double yRx) {
        double r1 = horizontalRange(first, yRx);
        double r2 = horizontalRange(second, yRx);
        double dx = second.x() - first.x();
        double dz = second.z() - first.z();
        double d = Math.hypot(dx, dz);
        if (!(d >= MIN_DIRECTION_BLOCKS) || d > r1 + r2 || d < Math.abs(r1 - r2)) {
            return null;
        }
        double along = (r1 * r1 - r2 * r2 + d * d) / (2.0 * d);
        double half = Math.sqrt(Math.max(0.0, r1 * r1 - along * along));
        double ex = dx / d;
        double ez = dz / d;
        double mx = first.x() + along * ex;
        double mz = first.z() + along * ez;
        double ox = -ez * half;
        double oz = ex * half;
        return new double[] {mx + ox, mz + oz, mx - ox, mz - oz};
    }

    /** 0 or 1 for the candidate nearer the previous estimate; NO_PREFERENCE without one, or on a tie. */
    private static int likely(LocatorFix previous, double[] a, double[] b) {
        double[] reference = positionOf(previous);
        if (reference == null) {
            return LocatorFix.Ambiguous.NO_PREFERENCE;
        }
        double toA = Math.hypot(a[0] - reference[0], a[1] - reference[1]);
        double toB = Math.hypot(b[0] - reference[0], b[1] - reference[1]);
        if (toA < toB) {
            return 0;
        }
        return toB < toA ? 1 : LocatorFix.Ambiguous.NO_PREFERENCE;
    }

    /** The previous estimate's (x, z): a Fix, or an Ambiguous's likely candidate. Null otherwise. */
    private static double[] positionOf(LocatorFix previous) {
        double[] position = null;
        if (previous instanceof LocatorFix.Fix fix) {
            position = new double[] {fix.x(), fix.z()};
        } else if (previous instanceof LocatorFix.Ambiguous ambiguous) {
            if (ambiguous.likely() == 0) {
                position = new double[] {ambiguous.ax(), ambiguous.az()};
            } else if (ambiguous.likely() == 1) {
                position = new double[] {ambiguous.bx(), ambiguous.bz()};
            }
        }
        return position != null && withinBounds(position[0]) && withinBounds(position[1]) ? position : null;
    }

    // ---- shared --------------------------------------------------------------------------------

    private static LocatorFix rangeOnly(RangeMeasurement cell) {
        return new LocatorFix.RangeOnly(cell.x(), cell.z(), cell.rangeBlocks());
    }

    /** A measurement the solver can use: present, finite and not absurd. */
    private static boolean isValid(RangeMeasurement m) {
        return m != null
                && withinBounds(m.x()) && withinBounds(m.y()) && withinBounds(m.z())
                && m.rangeBlocks() >= 0.0 && withinBounds(m.rangeBlocks())
                && !Double.isNaN(m.sigmaBlocks()) && withinBounds(m.sigmaBlocks());
    }

    /**
     * The horizontal part of a slant range, given the receiver's height:
     * {@code sqrt(max(0, r^2 - (y_cell - yRx)^2))}. Zero when the height difference alone is longer
     * than the range (quantisation can round a range below it).
     */
    static double horizontalRange(RangeMeasurement m, double yRx) {
        double dy = Math.abs(m.y() - yRx);
        double r = m.rangeBlocks();
        return r > dy ? Math.sqrt((r - dy) * (r + dy)) : 0.0;
    }

    /**
     * Altitude aiding: the ground under (x, z), plus a standing eye height.
     *
     * <p><b>Deviation from the spec text, recorded in NOTES.md:</b> §3A.5 writes
     * {@code surfaceY(round(x), round(z))}. The column a point is in is {@code floor}, as everywhere
     * else in the tree (a player at x = 10.7 stands in column 10); {@code round} would read the next
     * column for half of all positions, and on a slope that is a wrong height. So: floor.
     *
     * @param fallback returned when the column is {@link SurfaceProbe#UNLOADED} (unloaded, or no
     *                 ground): the last height the solver knew. It never loads a chunk to find out.
     */
    private static double eyeY(SurfaceProbe ground, double x, double z, double fallback) {
        int feet = ground.surfaceY((int) Math.floor(x), (int) Math.floor(z));
        return feet == SurfaceProbe.UNLOADED ? fallback : feet + RECEIVER_EYE_HEIGHT;
    }

    /**
     * The height assumed before any ground has been read, used only if the first column the solver
     * looks at is unloaded (the centroid of distant cells can be). The previous fix's height if there
     * is one; otherwise the lowest radiating point among the cells, which is the nearest thing to
     * ground level the Locator knows. After the first step the estimate is normally on loaded
     * ground and the real surface takes over.
     */
    private static double initialEyeY(List<RangeMeasurement> used, LocatorFix previous) {
        if (previous instanceof LocatorFix.Fix fix && withinBounds(fix.y())) {
            return fix.y();
        }
        double lowest = Double.POSITIVE_INFINITY;
        for (RangeMeasurement m : used) {
            lowest = Math.min(lowest, m.y());
        }
        return Double.isFinite(lowest) ? lowest : 0.0;
    }

    private static double sigma(RangeMeasurement m) {
        return Math.max(m.sigmaBlocks(), MIN_SIGMA_BLOCKS);
    }

    private static double weight(RangeMeasurement m) {
        double sigma = sigma(m);
        return 1.0 / (sigma * sigma);
    }

    /** Finite and not absurd. */
    private static boolean withinBounds(double value) {
        return Math.abs(value) <= MAX_ABS_BLOCKS;
    }
}
