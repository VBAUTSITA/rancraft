package dev.rancraft.rf;

/**
 * Closed-form RF arithmetic. No state, no world access.
 *
 * <p>Phase 1 is deterministic by design: no fast fading, no shadowing randomness. The same geometry
 * must always produce the same RSRP so that results are reproducible and unit-testable.
 */
public final class RfMath {

    private RfMath() {
    }

    /**
     * Phase 1 receiver sensitivity floor. A cell computing below this is not reported at all,
     * and is also the boundary between 1 bar and 0 bars.
     */
    public static final double NO_SERVICE_DBM = -105.0;

    /** Receiver antenna gain. Phase 1 models the field meter as a 0 dBi whip. */
    public static final double RX_GAIN_DBI = 0.0;

    /** Lower RSRP bound, in dBm, for each bar level. */
    private static final double BARS_4_DBM = -75.0;
    private static final double BARS_3_DBM = -85.0;
    private static final double BARS_2_DBM = -95.0;
    private static final double BARS_1_DBM = NO_SERVICE_DBM;

    /**
     * Free-space path loss at exactly 1 metre, in dB.
     *
     * <p>{@code 32.44 + 20*log10(f_MHz) + 20*log10(d_km)} evaluated at {@code d_km = 0.001}
     * collapses to {@code 20*log10(f_MHz) - 27.56}. At 900 MHz this is 31.52 dB.
     */
    public static double fsplAt1mDb(double frequencyMhz) {
        return 20.0 * Math.log10(frequencyMhz) - 27.56;
    }

    /**
     * Log-distance path loss: {@code PL_1m + 10*n*log10(d)}.
     *
     * <p>{@code distanceMeters} is clamped to a 1.0 m floor so that a receiver standing inside the
     * antenna voxel yields a finite value rather than -Infinity.
     */
    public static double pathLossDb(double frequencyMhz, double pathLossExponent, double distanceMeters) {
        double d = Math.max(distanceMeters, 1.0);
        return fsplAt1mDb(frequencyMhz) + 10.0 * pathLossExponent * Math.log10(d);
    }

    /** {@code RSRP = Tx + Gt + Gr - PL - Obstruction}. */
    public static double rsrpDbm(double txPowerDbm, double txGainDbi, double pathLossDb, double obstructionDb) {
        return txPowerDbm + txGainDbi + RX_GAIN_DBI - pathLossDb - obstructionDb;
    }

    /** Maps RSRP onto the 0..4 bar scale used by the HUD. */
    public static int bars(double rsrpDbm) {
        if (rsrpDbm >= BARS_4_DBM) {
            return 4;
        }
        if (rsrpDbm >= BARS_3_DBM) {
            return 3;
        }
        if (rsrpDbm >= BARS_2_DBM) {
            return 2;
        }
        if (rsrpDbm >= BARS_1_DBM) {
            return 1;
        }
        return 0;
    }

    /** True when the cell is above the Phase 1 sensitivity floor and worth reporting. */
    public static boolean inService(double rsrpDbm) {
        return rsrpDbm >= NO_SERVICE_DBM;
    }
}
