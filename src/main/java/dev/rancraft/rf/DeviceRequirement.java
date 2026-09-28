package dev.rancraft.rf;

import java.util.Objects;

/**
 * What a device needs from the network before it will work: a minimum {@link ServiceLevel} and a
 * minimum {@link Band#capacityTier()} on the serving cell.
 *
 * <p>Phase 3, §3A.1. This is the first reader of {@code capacityTier}. Devices never compute RF:
 * {@link #check} only reads a {@link SignalSample} the server already produced, so asking it costs
 * nothing and it gives the same answer for a cached replay as for the evaluation it replays.
 *
 * <h2>Why the verdict carries a reason</h2>
 * The HUD must be able to say "needs band tier 3 -- you're on band_900", not just "unavailable".
 * {@link Verdict#LOW_QUALITY} and {@link Verdict#LOW_TIER} call for different fixes: the first for
 * better signal (retilt, move, clear the path, fewer co-channel neighbours), the second for a
 * different band on the serving cell. A player who cannot tell them apart fixes the wrong thing.
 *
 * <h2>Serving cell, never index 0</h2>
 * The tier is read off the cell that is actually serving, {@link SignalSample#serving()}. Since
 * Phase 2, handover hysteresis can hold a receiver on a weaker cell, so the strongest cell may be on
 * a different band from the one carrying the device's traffic.
 *
 * @param minServiceLevel the worst service level the device still works at.
 * @param minCapacityTier the lowest {@link Band#capacityTier()} the serving cell's band may have.
 *                        0 accepts every band.
 */
public record DeviceRequirement(ServiceLevel minServiceLevel, int minCapacityTier) {

    /** Asks nothing. The verdict still reports NO_SERVICE when there is none; see {@link #check}. */
    public static final DeviceRequirement NONE = new DeviceRequirement(ServiceLevel.NONE, 0);

    public DeviceRequirement {
        Objects.requireNonNull(minServiceLevel, "minServiceLevel");
    }

    /**
     * Checks one sample, first failing reason wins, in this order:
     *
     * <ol>
     *   <li>{@link Verdict#NO_SERVICE} if {@link SignalSample#isNoService()}. This is checked
     *       unconditionally, so even {@link #NONE} reports it: the verdict describes the link, and a
     *       device with no requirement (the meter, the Network Locator) simply keeps working and
     *       shows its own degraded state.
     *   <li>{@link Verdict#LOW_QUALITY} if the sample's service level is below
     *       {@link #minServiceLevel()}.
     *   <li>{@link Verdict#LOW_TIER} if the serving cell's band has a capacity tier below
     *       {@link #minCapacityTier()}.
     * </ol>
     *
     * <p>A serving cell whose band id is not in {@code bands} is judged by the table's fallback band,
     * exactly as the engine already propagated it ({@link BandTable#getOrFallback}).
     */
    public Verdict check(SignalSample sample, BandTable bands) {
        if (sample.isNoService()) {
            return Verdict.NO_SERVICE;
        }
        if (!sample.serviceLevel().atLeast(minServiceLevel)) {
            return Verdict.LOW_QUALITY;
        }
        // isNoService() is false, so a serving cell is present.
        CellSample serving = sample.serving().orElseThrow();
        if (bands.getOrFallback(serving.bandId()).capacityTier() < minCapacityTier) {
            return Verdict.LOW_TIER;
        }
        return Verdict.OK;
    }

    public enum Verdict {
        /** Served, good enough, on a band of high enough tier. */
        OK,
        /** No serving cell at all. */
        NO_SERVICE,
        /** Served, but the service level is below the device's minimum: fix the signal. */
        LOW_QUALITY,
        /** Good enough signal, but the serving cell's band tier is too low: change the band. */
        LOW_TIER
    }
}
