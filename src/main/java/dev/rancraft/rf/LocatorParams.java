package dev.rancraft.rf;

/**
 * Tunables of the Network Locator's measurement model ({@link Ranging}) and solver
 * ({@link LocatorSolver}). Phase 3, §3A.4-§3A.5.
 *
 * <p>Built from {@link RfConfig#locatorParams()}, which mirrors the {@code locator*} entries of the
 * mod config, so the Locator never reads a config API. {@code locatorEmergencyMaxAgeTicks} is not
 * here: it is gameplay for the game-side emergency record, and no {@code rf} code reads it.
 *
 * <p>Components are appended, never reordered. {@code siteMergeBlocks} was appended by the Phase 3A
 * review (round 1); the four-argument constructor is kept and defaults it, so every older call site
 * compiles and means what it did, now with sites grouped.
 *
 * @param minRsrpDbm          a cell is used for ranging only at or above this RSRP. Weak cells are
 *                            the ones most likely to be heard through obstruction, so their ranges
 *                            are the most NLOS-biased.
 * @param maxCells            at most this many usable sites (one cell each, see
 *                            {@code siteMergeBlocks}) go into a fix, strongest first.
 * @param maxHdop             above this horizontal dilution of precision the fix is reported as
 *                            {@link LocatorFix.PoorGeometry} instead of a position.
 * @param nlosBiasBlocksPerDb how many blocks each dB of obstruction adds to a measured range: the
 *                            flat stand-in for non-line-of-sight bias (see {@link Ranging}).
 * @param siteMergeBlocks     antennas whose radiating points are at most this far apart horizontally
 *                            are one <em>site</em> for positioning, and the site contributes one
 *                            range ({@link LocatorSolver#siteRepresentatives}). <b>Game
 *                            abstraction:</b> a real network knows each transmission point's
 *                            location, and the sectors of one site add no independent geometry; the
 *                            mod has no site container (a three-sector site is three blocks, each its
 *                            own cell), so horizontal proximity stands in for that identity. 0 groups
 *                            only antennas stacked in one column.
 */
public record LocatorParams(
        double minRsrpDbm,
        int maxCells,
        double maxHdop,
        double nlosBiasBlocksPerDb,
        // ---- Phase 3A review, round 1 ----
        double siteMergeBlocks
) {
    public static final double DEFAULT_MIN_RSRP_DBM = -100.0;
    public static final int DEFAULT_MAX_CELLS = 8;
    public static final double DEFAULT_MAX_HDOP = 6.0;
    public static final double DEFAULT_NLOS_BIAS_BLOCKS_PER_DB = 0.25;

    /**
     * The widest three-sector site the mod builds spans 2 blocks horizontally (sectors on the blocks
     * either side of a mast, (1, 0) and (-1, 0)); 3 keeps a margin for a sector one block further
     * out, and is far below any sensible spacing between two real sites.
     */
    public static final double DEFAULT_SITE_MERGE_BLOCKS = 3.0;

    public static final LocatorParams DEFAULTS = new LocatorParams(
            DEFAULT_MIN_RSRP_DBM, DEFAULT_MAX_CELLS, DEFAULT_MAX_HDOP, DEFAULT_NLOS_BIAS_BLOCKS_PER_DB,
            DEFAULT_SITE_MERGE_BLOCKS);

    /** The pre-review shape, with {@link #DEFAULT_SITE_MERGE_BLOCKS}. */
    public LocatorParams(double minRsrpDbm, int maxCells, double maxHdop, double nlosBiasBlocksPerDb) {
        this(minRsrpDbm, maxCells, maxHdop, nlosBiasBlocksPerDb, DEFAULT_SITE_MERGE_BLOCKS);
    }
}
