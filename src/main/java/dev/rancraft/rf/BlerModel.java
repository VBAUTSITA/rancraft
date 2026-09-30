package dev.rancraft.rf;

/**
 * Block error rate from SINR: how often one transport block fails to decode (Phase 3 slice 9, §3B.4).
 * The Radio Link's messages are lost with this probability at each end.
 *
 * <pre>
 * BLER(sinr)            = 1 / (1 + 10^((sinr - sinr50) / slope))       sinr50 = 0 dB, slope = 2 dB
 * P(deliver)            = (1 - BLER(sinr_tx)) x (1 - BLER(sinr_rx))
 * </pre>
 *
 * <table>
 *   <caption>BLER at the default parameters</caption>
 *   <tr><th>SINR (dB)</th><td>-2</td><td>0</td><td>2</td><td>5</td><td>10</td></tr>
 *   <tr><th>BLER</th><td>0.91</td><td>0.50</td><td>0.091</td><td>0.0032</td><td>0.00001</td></tr>
 * </table>
 *
 * So POOR service by SINR (0 to 5 dB, {@link ServiceLevel#SINR_POOR_DB}) is flaky and FAIR (5 dB and
 * up) is solid, a distinction the HUD's bars cannot show on their own. The curve reads SINR, not
 * RSRP, as a real link's does: a link that is POOR only because its signal is weak but clean (RSRP in
 * the one-bar band, little interference, SINR above 5 dB) loses almost nothing, and one that is POOR
 * because of interference (SINR 0 to 5 dB) loses a lot.
 *
 * <p><b>Honest label (NOTES.md, slice 9).</b> The shape is right: real link-level BLER curves are
 * steep sigmoids in SINR, falling about a decade per dB or two around an operating point. The numbers
 * are not a real system's: this is one generic curve, not an MCS table. A real link picks a modulation
 * and coding scheme from its channel quality reports, so each MCS has its own curve, shifted by
 * several dB, and link adaptation keeps the BLER near a target (typically 10 %) instead of letting it
 * run up the curve. There is <b>no HARQ and no retransmission</b> here either: a block that fails is
 * lost, where a real link would retransmit it a few milliseconds later and deliver it almost always,
 * only later. Delivery is one draw per message against {@link #deliveryProbability}; the two ends'
 * errors are independent.
 *
 * <p>Pure: no Minecraft imports ({@code PackagePurityTest}). The draws come from {@link SplitMix64}.
 *
 * @param sinr50Db the SINR at which half the blocks fail ({@code blerSinr50Db}, default 0 dB).
 * @param slopeDb  the dB for a tenfold change in the odds of success ({@code blerSlopeDb}, default 2 dB):
 *                 smaller is steeper. Must be positive.
 */
public record BlerModel(double sinr50Db, double slopeDb) {

    public static final double DEFAULT_SINR50_DB = 0.0;
    public static final double DEFAULT_SLOPE_DB = 2.0;
    public static final BlerModel DEFAULT = new BlerModel(DEFAULT_SINR50_DB, DEFAULT_SLOPE_DB);

    public BlerModel {
        if (!Double.isFinite(sinr50Db)) {
            throw new IllegalArgumentException("sinr50Db must be finite: " + sinr50Db);
        }
        if (!(slopeDb > 0.0) || !Double.isFinite(slopeDb)) {
            throw new IllegalArgumentException("slopeDb must be positive and finite: " + slopeDb);
        }
    }

    /**
     * The block error rate at {@code sinrDb}, in {@code [0, 1]}, strictly decreasing in SINR where it
     * is not saturated. {@code -Infinity} (no signal) is 1; NaN is treated as no link (1).
     */
    public double bler(double sinrDb) {
        if (Double.isNaN(sinrDb)) {
            return 1.0;
        }
        return 1.0 / (1.0 + Math.pow(10.0, (sinrDb - sinr50Db) / slopeDb));
    }

    /**
     * {@code 1 - bler(sinrDb)}, computed directly as {@code 1 / (1 + 10^((sinr50 - sinr) / slope))} so
     * that it stays exact where the BLER is tiny (and never becomes NaN at the infinities).
     */
    public double success(double sinrDb) {
        if (Double.isNaN(sinrDb)) {
            return 0.0;
        }
        return 1.0 / (1.0 + Math.pow(10.0, (sinr50Db - sinrDb) / slopeDb));
    }

    /**
     * The chance that one message crosses the network: the sender's uplink block and the receiver's
     * downlink block must both decode, independently. {@code (1 - BLER(tx)) x (1 - BLER(rx))}.
     */
    public double deliveryProbability(double txSinrDb, double rxSinrDb) {
        return success(txSinrDb) * success(rxSinrDb);
    }

    /**
     * One delivery decision: {@code uniform < probability}. With {@code uniform} from
     * {@link SplitMix64#nextDouble()}, in {@code [0, 1)}, a probability of 1 always delivers and 0 never.
     */
    public static boolean delivered(double probability, double uniform) {
        return uniform < probability;
    }
}
