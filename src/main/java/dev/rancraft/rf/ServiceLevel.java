package dev.rancraft.rf;

/**
 * The five-step quality scale the HUD renders as bars.
 *
 * <p>A link is classified independently on RSRP (is there enough signal?) and on SINR (is the
 * signal clean enough to decode?), and the reported level is the <em>worse</em> of the two. That
 * asymmetry is the core lesson of Phase 2: a receiver can sit in a strong field and still get
 * nothing, because four co-channel sites are shouting over each other.
 *
 * <p>Phase 3 gates devices on a minimum {@code ServiceLevel} plus a minimum
 * {@link Band#capacityTier()}, which is why {@link #atLeast(ServiceLevel)} exists now.
 */
public enum ServiceLevel {

    NONE(0, "NO SERVICE"),
    POOR(1, "POOR"),
    FAIR(2, "FAIR"),
    GOOD(3, "GOOD"),
    EXCELLENT(4, "EXCELLENT");

    /** Lower SINR bound, in dB, for each level. */
    public static final double SINR_EXCELLENT_DB = 20.0;
    public static final double SINR_GOOD_DB = 13.0;
    public static final double SINR_FAIR_DB = 5.0;
    public static final double SINR_POOR_DB = 0.0;

    private final int bars;
    private final String label;

    ServiceLevel(int bars, String label) {
        this.bars = bars;
        this.label = label;
    }

    /** 0..4, the same scale the Phase 1 HUD already drew. */
    public int bars() {
        return bars;
    }

    public String label() {
        return label;
    }

    public boolean atLeast(ServiceLevel minimum) {
        return bars >= minimum.bars;
    }

    public static ServiceLevel ofBars(int bars) {
        return values()[Math.clamp(bars, 0, 4)];
    }

    /**
     * Classifies received power.
     *
     * <p>Delegates to {@link RfMath#bars(double)} rather than repeating the threshold table, so the
     * Phase 1 bar boundaries and the Phase 2 service levels can never drift apart.
     */
    public static ServiceLevel fromRsrp(double rsrpDbm) {
        return ofBars(RfMath.bars(rsrpDbm));
    }

    /** Classifies signal quality. Below 0 dB the interference exceeds the wanted signal. */
    public static ServiceLevel fromSinr(double sinrDb) {
        if (sinrDb >= SINR_EXCELLENT_DB) {
            return EXCELLENT;
        }
        if (sinrDb >= SINR_GOOD_DB) {
            return GOOD;
        }
        if (sinrDb >= SINR_FAIR_DB) {
            return FAIR;
        }
        if (sinrDb >= SINR_POOR_DB) {
            return POOR;
        }
        return NONE;
    }

    /** The reported level: whichever of the two classifications is worse. */
    public static ServiceLevel worstOf(ServiceLevel a, ServiceLevel b) {
        return a.bars <= b.bars ? a : b;
    }

    /** Convenience for the common case of classifying a link from both figures at once. */
    public static ServiceLevel of(double rsrpDbm, double sinrDb) {
        return worstOf(fromRsrp(rsrpDbm), fromSinr(sinrDb));
    }
}
