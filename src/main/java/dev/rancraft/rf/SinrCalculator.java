package dev.rancraft.rf;

import java.util.List;

/**
 * Signal-to-interference-plus-noise ratio for one serving link.
 *
 * <pre>
 *   I = sum over co-channel cells   p(rsrp)
 *     + sum over other-band cells   p(rsrp - adjacentChannelRejection)
 *   N = p(band noise floor)
 *   SINR = 10*log10( p(servingRsrp) / (I + N) )
 * </pre>
 *
 * <p>All summation happens in linear milliwatts; dB is a logarithmic scale and adding two dBm
 * figures directly is meaningless. This is why the calculator converts in and out rather than
 * working in dB throughout.
 *
 * <p><b>Real:</b> SINR as wanted-over-unwanted-plus-noise, and adjacent-channel rejection making
 * other-band neighbours matter slightly but not much. <b>Abstracted:</b> a single flat noise floor
 * per band instead of bandwidth x noise figure, and the mod-3 penalty below.
 */
public final class SinrCalculator {

    private SinrCalculator() {
    }

    /**
     * Interferers weaker than {@code NO_SERVICE_DBM - this} contribute nothing worth summing.
     * Without the cut, a dense network sums forty terms that together move the answer by less than
     * 0.01 dB.
     */
    public static final double INTERFERER_MARGIN_DB = 20.0;

    public static final double INTERFERER_FLOOR_DBM = RfMath.NO_SERVICE_DBM - INTERFERER_MARGIN_DB;

    /** Display clamp. The raw value is always kept on the result. */
    public static final double DISPLAY_MIN_DB = -20.0;
    public static final double DISPLAY_MAX_DB = 40.0;

    /** One received signal, reduced to what the SINR sum actually needs. */
    public record RxSignal(long cellId, String bandId, int pci, double rsrpDbm) {
    }

    /**
     * Tunables, mirrored out of the mod config so this class never reads a config API.
     *
     * @param pciMod3PenaltyFactor linear power multiplier applied to a co-channel interferer whose
     *                             {@code pci % 3} matches the serving cell. 2.0 is +3 dB.
     */
    public record SinrParams(
            double adjacentChannelRejectionDb,
            double pciMod3PenaltyFactor,
            boolean enablePciMod3Penalty
    ) {
        public static final SinrParams DEFAULTS = new SinrParams(30.0, 2.0, true);
    }

    /**
     * @param sinrDb           raw, unclamped. Used for classification.
     * @param interferenceDbm  total interference power, or {@code -Infinity} when there is none.
     * @param coChannelCount   how many same-band interferers actually contributed.
     * @param mod3Count        how many of those also collided on {@code pci % 3}.
     */
    public record SinrResult(
            double sinrDb,
            double interferenceDbm,
            double noiseDbm,
            int coChannelCount,
            int otherBandCount,
            int mod3Count
    ) {
        /** Clamped to a sane display range; the raw figure stays on {@link #sinrDb()}. */
        public double displaySinrDb() {
            return Math.clamp(sinrDb, DISPLAY_MIN_DB, DISPLAY_MAX_DB);
        }

        public ServiceLevel serviceLevel(double servingRsrpDbm) {
            return ServiceLevel.of(servingRsrpDbm, sinrDb);
        }
    }

    public static double dbmToMilliwatts(double dbm) {
        return Math.pow(10.0, dbm / 10.0);
    }

    public static double milliwattsToDbm(double mw) {
        return mw <= 0.0 ? Double.NEGATIVE_INFINITY : 10.0 * Math.log10(mw);
    }

    /**
     * @param serving       the cell currently being served, excluded from its own interference.
     * @param allCells      every evaluated cell, serving included; the serving entry is skipped by
     *                      {@code cellId}.
     * @param noiseFloorDbm the serving band's noise floor.
     */
    public static SinrResult compute(
            RxSignal serving,
            List<RxSignal> allCells,
            double noiseFloorDbm,
            SinrParams params) {

        double interferenceMw = 0.0;
        int coChannel = 0;
        int otherBand = 0;
        int mod3 = 0;

        for (RxSignal other : allCells) {
            if (other.cellId() == serving.cellId()) {
                continue;
            }
            if (other.rsrpDbm() <= INTERFERER_FLOOR_DBM) {
                continue;
            }

            if (other.bandId().equals(serving.bandId())) {
                double mw = dbmToMilliwatts(other.rsrpDbm());
                if (params.enablePciMod3Penalty() && collidesOnMod3(serving.pci(), other.pci())) {
                    // GAME ABSTRACTION, deliberately not physically exact.
                    //
                    // A real mod-3 conflict collides the cell-specific reference signals, which
                    // wrecks channel estimation and reference-signal decoding. It does not raise
                    // the interferer's transmit power at all. Modelling it as a flat linear power
                    // multiplier is a stand-in that costs one multiply and makes mod-3 planning a
                    // decision the player can actually feel in the meter. Anyone reading this to
                    // learn how LTE works should read the sentence above, not the line below.
                    mw *= params.pciMod3PenaltyFactor();
                    mod3++;
                }
                interferenceMw += mw;
                coChannel++;
            } else {
                // Adjacent-channel leakage: real, and correctly small.
                interferenceMw += dbmToMilliwatts(other.rsrpDbm() - params.adjacentChannelRejectionDb());
                otherBand++;
            }
        }

        double noiseMw = dbmToMilliwatts(noiseFloorDbm);
        double servingMw = dbmToMilliwatts(serving.rsrpDbm());
        double sinrDb = 10.0 * Math.log10(servingMw / (interferenceMw + noiseMw));

        return new SinrResult(
                sinrDb,
                milliwattsToDbm(interferenceMw),
                noiseFloorDbm,
                coChannel,
                otherBand,
                mod3);
    }

    /** True when two PCIs share a residue mod 3 -- see the abstraction note in {@link #compute}. */
    public static boolean collidesOnMod3(int pciA, int pciB) {
        return Math.floorMod(pciA, 3) == Math.floorMod(pciB, 3);
    }
}
