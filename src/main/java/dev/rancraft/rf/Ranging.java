package dev.rancraft.rf;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * The Network Locator's measurement model: how far away each heard cell <em>appears</em> to be.
 * Phase 3, §3A.4.
 *
 * <h2>Named honestly</h2>
 * This is not GPS. GPS is satellite navigation; this locates a receiver from cell towers, the
 * family of network positioning methods that includes E-CID, OTDOA and NR multi-RTT.
 *
 * <h2>What is modelled</h2>
 * <ul>
 *   <li><b>Timing resolution is set by bandwidth</b> (real): a receiver can time an arrival to about
 *       one sample, and the sample period is 1 / bandwidth, so a range can be resolved to about
 *       {@code c / bandwidth}. 10 MHz resolves 30 m; 100 MHz resolves 3 m. That is why operators
 *       want wideband carriers for positioning, and what makes band_3500 worth deploying here.
 *   <li><b>Quantisation</b>: the true slant distance is rounded to the nearest multiple of that
 *       resolution, and its 1-sigma is reported as {@code resolution / sqrt(12)}, the standard
 *       deviation of a uniform rounding error. <b>Simplified, labelled (NOTES.md, Phase 3 slice
 *       3):</b> real receivers interpolate a correlation peak to well below one sample and average
 *       over time; this model does neither. The rounding is deterministic: the same true distance
 *       always rounds the same way, so a player standing still sees a fixed error, not noise that
 *       averages out, and the sigma describes the spread over positions, not over time.
 *   <li><b>NLOS bias</b> (the direction is real, the size is a stand-in; see
 *       {@link #nlosBiasBlocks}).
 * </ul>
 *
 * <p><b>Abstraction: absolute ranging, not time differences.</b> Real OTDOA measures time
 * <em>differences</em> between cells' reference signals, because the receiver's clock is not synced
 * to the network; multi-RTT gets absolute ranges from round-trip times. This models absolute ranges,
 * like multi-RTT, with no clock error and no dedicated positioning reference signals.
 *
 * <p>Input is the engine's own full cell list for one evaluation (up to {@code maxCellsEvaluated},
 * not the 4 cells a payload carries). {@code distanceBlocks} there is the true 3D distance from the
 * receiver's eye to the radiating centre; everything after this class sees only the quantised,
 * biased range.
 */
public final class Ranging {

    /**
     * The speed of light in metres per microsecond, so that {@code c / bandwidthMhz} is metres
     * (299,792,458 m/s divided by 10^6 Hz per MHz).
     */
    public static final double SPEED_OF_LIGHT_M_PER_US = 299.792458;

    private static final double SQRT_12 = Math.sqrt(12.0);

    /**
     * Beyond 2^52 resolution steps a double has no fractional part left to round, so quantising
     * changes nothing; checked so that a resolution tending to zero cannot overflow the rounding.
     */
    private static final double MAX_EXACT_STEPS = 0x1p52;

    private Ranging() {
    }

    /** {@code c / bandwidth}, in metres. 10 MHz → 29.98 m, 20 → 14.99, 100 → 3.00. */
    public static double resolutionMeters(double bandwidthMhz) {
        if (!isPositiveFinite(bandwidthMhz)) {
            throw new IllegalArgumentException("bandwidthMhz must be positive and finite: " + bandwidthMhz);
        }
        return SPEED_OF_LIGHT_M_PER_US / bandwidthMhz;
    }

    /** {@link #resolutionMeters} in blocks at the configured {@code metersPerBlock}. */
    public static double resolutionBlocks(double bandwidthMhz, double metersPerBlock) {
        if (!isPositiveFinite(metersPerBlock)) {
            throw new IllegalArgumentException("metersPerBlock must be positive and finite: " + metersPerBlock);
        }
        return resolutionMeters(bandwidthMhz) / metersPerBlock;
    }

    /**
     * {@code round(trueRange / resolution) * resolution}, rounding halves up as {@link Math#round}
     * does. With 30-block resolution, 44 reports 30 and 46 reports 60.
     */
    public static double quantise(double trueRangeBlocks, double resolutionBlocks) {
        if (!isPositiveFinite(resolutionBlocks)) {
            throw new IllegalArgumentException("resolutionBlocks must be positive and finite: " + resolutionBlocks);
        }
        double steps = trueRangeBlocks / resolutionBlocks;
        if (!(Math.abs(steps) < MAX_EXACT_STEPS)) {
            return trueRangeBlocks;
        }
        return Math.floor(steps + 0.5) * resolutionBlocks;
    }

    /**
     * The non-line-of-sight bias: {@code nlosBiasBlocksPerDb * obstructionDb}, never negative.
     *
     * <p><b>Abstraction: a flat stand-in.</b> In the real world, when the direct path goes through a
     * hill, the first thing to arrive is a longer <em>reflected</em> path, so the measured range is
     * biased long. RANCraft has no reflections, so a flat number of blocks per dB of obstruction
     * stands in for it. It is still directionally right: ranges behind terrain read long, the
     * Locator gets worse there, and high towers with clear sight lines make it better. The bias is
     * systematic, so it is <b>not</b> in {@link #sigmaBlocks}, nor in the reported "±".
     */
    public static double nlosBiasBlocks(double obstructionDb, double nlosBiasBlocksPerDb) {
        double bias = nlosBiasBlocksPerDb * obstructionDb;
        return bias > 0.0 && Double.isFinite(bias) ? bias : 0.0;
    }

    /** 1-sigma of a uniform rounding error over one resolution step: {@code resolution / sqrt(12)}. */
    public static double sigmaBlocks(double resolutionBlocks) {
        return resolutionBlocks / SQRT_12;
    }

    /** A cell is used for ranging at or above {@code minRsrpDbm}. */
    public static boolean usable(CellSample cell, double minRsrpDbm) {
        return cell.rsrpDbm() >= minRsrpDbm;
    }

    /**
     * One cell's measured range, whether or not it is {@link #usable}.
     *
     * @throws IllegalArgumentException if the band's bandwidth or {@code metersPerBlock} is not a
     *                                  positive finite number (the data loader never lets one in).
     */
    public static RangeMeasurement measure(
            CellSample cell, Band band, double metersPerBlock, double nlosBiasBlocksPerDb) {

        double resolution = resolutionBlocks(band.bandwidthMhz(), metersPerBlock);
        double range = quantise(cell.distanceBlocks(), resolution)
                + nlosBiasBlocks(cell.obstructionDb(), nlosBiasBlocksPerDb);
        return new RangeMeasurement(
                cell.cellId(),
                cell.x() + 0.5, cell.y() + 0.5, cell.z() + 0.5,
                Math.max(0.0, range),
                sigmaBlocks(resolution),
                cell.bandId(),
                cell.obstructionDb());
    }

    /**
     * Every usable cell's range, in the order given. Pass {@code sample.cells()}: the engine's full
     * list, strongest RSRP first, so {@link LocatorSolver} keeps the strongest when it caps the
     * count.
     *
     * <p>A cell whose band has an unusable bandwidth, or whose distance or obstruction is not a
     * finite number, is left out rather than allowed to put a NaN into the fit.
     */
    public static List<RangeMeasurement> measure(
            List<CellSample> cells, BandTable bands, double metersPerBlock, LocatorParams params) {

        Objects.requireNonNull(params, "params");
        List<RangeMeasurement> ranges = new ArrayList<>(cells.size());
        if (!isPositiveFinite(metersPerBlock)) {
            return ranges;
        }
        for (CellSample cell : cells) {
            if (!usable(cell, params.minRsrpDbm())
                    || !Double.isFinite(cell.distanceBlocks())
                    || !Double.isFinite(cell.obstructionDb())) {
                continue;
            }
            Band band = bands.getOrFallback(cell.bandId());
            if (!isPositiveFinite(band.bandwidthMhz())) {
                continue;
            }
            ranges.add(measure(cell, band, metersPerBlock, params.nlosBiasBlocksPerDb()));
        }
        return ranges;
    }

    /** {@link #measure(List, BandTable, double, LocatorParams)} with the config's scale and tunables. */
    public static List<RangeMeasurement> measure(List<CellSample> cells, BandTable bands, RfConfig config) {
        return measure(cells, bands, config.metersPerBlock(), config.locatorParams());
    }

    private static boolean isPositiveFinite(double value) {
        return value > 0.0 && Double.isFinite(value);
    }
}
