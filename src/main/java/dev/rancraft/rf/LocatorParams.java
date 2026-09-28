package dev.rancraft.rf;

/**
 * Tunables of the Network Locator's measurement model ({@link Ranging}) and solver
 * ({@link LocatorSolver}). Phase 3, §3A.4-§3A.5.
 *
 * <p>Built from {@link RfConfig#locatorParams()}, which mirrors the {@code locator*} entries of the
 * mod config, so the Locator never reads a config API. {@code locatorEmergencyMaxAgeTicks} is not
 * here: it is gameplay for the game-side emergency record, and no {@code rf} code reads it.
 *
 * @param minRsrpDbm          a cell is used for ranging only at or above this RSRP. Weak cells are
 *                            the ones most likely to be heard through obstruction, so their ranges
 *                            are the most NLOS-biased.
 * @param maxCells            at most this many usable cells go into a fix, strongest first.
 * @param maxHdop             above this horizontal dilution of precision the fix is reported as
 *                            {@link LocatorFix.PoorGeometry} instead of a position.
 * @param nlosBiasBlocksPerDb how many blocks each dB of obstruction adds to a measured range: the
 *                            flat stand-in for non-line-of-sight bias (see {@link Ranging}).
 */
public record LocatorParams(
        double minRsrpDbm,
        int maxCells,
        double maxHdop,
        double nlosBiasBlocksPerDb
) {
    public static final double DEFAULT_MIN_RSRP_DBM = -100.0;
    public static final int DEFAULT_MAX_CELLS = 8;
    public static final double DEFAULT_MAX_HDOP = 6.0;
    public static final double DEFAULT_NLOS_BIAS_BLOCKS_PER_DB = 0.25;

    public static final LocatorParams DEFAULTS = new LocatorParams(
            DEFAULT_MIN_RSRP_DBM, DEFAULT_MAX_CELLS, DEFAULT_MAX_HDOP, DEFAULT_NLOS_BIAS_BLOCKS_PER_DB);
}
