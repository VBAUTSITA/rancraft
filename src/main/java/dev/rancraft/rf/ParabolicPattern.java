package dev.rancraft.rf;

/**
 * The 3GPP parabolic antenna pattern approximation (TR 36.814 / TR 38.901 style).
 *
 * <p>Horizontal and vertical cuts are each modelled as a parabola in dB that saturates at a floor:
 *
 * <pre>
 *   A_H(phi)    = -min( 12 * (phi / hBeamwidth)^2, A_m )
 *   A_V(dTheta) = -min( 12 * (dTheta / vBeamwidth)^2, SLA_v )
 *   G           = maxGain - min( -(A_H + A_V), A_m )
 * </pre>
 *
 * <p>The factor 12 is not arbitrary: at {@code phi = hBeamwidth/2} it gives
 * {@code 12 * 0.25 = 3} dB, which <em>is</em> the definition of the 3 dB beamwidth. If that
 * identity ever fails, the formula is wrong rather than the test.
 *
 * <p>This is a genuine industry model, not a game abstraction — real sector planning uses it.
 */
public final class ParabolicPattern implements AntennaPattern {

    /** 3GPP's shape constant: chosen so that half a beamwidth off boresight costs exactly 3 dB. */
    private static final double THREE_DB_SHAPE = 12.0;

    public static final double DEFAULT_FRONT_TO_BACK_DB = 30.0;
    public static final double DEFAULT_SIDELOBE_FLOOR_DB = 30.0;

    /** Aperture efficiency loss folded into the gain-from-beamwidth estimate, in dB. */
    public static final double APERTURE_LOSS_DB = 2.0;

    public static final double MIN_DERIVED_GAIN_DBI = 0.0;
    public static final double MAX_DERIVED_GAIN_DBI = 22.0;

    private final double frontToBackDb;
    private final double sidelobeFloorDb;

    public ParabolicPattern(double frontToBackDb, double sidelobeFloorDb) {
        this.frontToBackDb = frontToBackDb;
        this.sidelobeFloorDb = sidelobeFloorDb;
    }

    public static ParabolicPattern defaults() {
        return new ParabolicPattern(DEFAULT_FRONT_TO_BACK_DB, DEFAULT_SIDELOBE_FLOOR_DB);
    }

    public double frontToBackDb() {
        return frontToBackDb;
    }

    public double sidelobeFloorDb() {
        return sidelobeFloorDb;
    }

    @Override
    public double gainDbi(double bearingDeg, double elevationDeg, CellParams cell) {
        double aH = horizontalAttenuationDb(azimuthOffsetDeg(bearingDeg, cell.azimuthDeg()), cell.hBeamwidthDeg());
        double aV = verticalAttenuationDb(elevationOffsetDeg(elevationDeg, cell.tiltDeg()), cell.vBeamwidthDeg());

        // A_H and A_V are <= 0, so -(A_H + A_V) is the combined loss. Cap it at the front-to-back
        // ratio: an antenna is never worse behind than its A_m, even when both cuts saturate.
        double combinedLossDb = Math.min(-(aH + aV), frontToBackDb);
        return cell.gainDbi() - combinedLossDb;
    }

    /**
     * Horizontal offset from boresight, signed, −180..+180.
     *
     * @param azimuthDeg where the antenna points, 0 = north.
     */
    public static double azimuthOffsetDeg(double bearingDeg, double azimuthDeg) {
        return AntennaGeometry.wrapTo180(bearingDeg - azimuthDeg);
    }

    /**
     * Vertical offset from boresight.
     *
     * <p>Convention: {@code tiltDeg > 0} is <b>downtilt</b>, so the boresight sits at elevation
     * {@code -tiltDeg} and {@code dTheta = elevationDeg - (-tiltDeg) = elevationDeg + tiltDeg}.
     *
     * <p>Sanity: a receiver 10° below the antenna ({@code elevationDeg = -10}) with 10° of downtilt
     * gives {@code dTheta = 0}, i.e. full gain. A result of −12 dB there means the sign is flipped.
     */
    public static double elevationOffsetDeg(double elevationDeg, double tiltDeg) {
        return elevationDeg + tiltDeg;
    }

    /**
     * Horizontal cut, in dB (always <= 0).
     *
     * <p>A 360° beamwidth is omnidirectional and returns 0 for every angle. That is branched on
     * explicitly rather than left to the formula, which would otherwise return a small but non-zero
     * attenuation and quietly make omni cells slightly directional.
     */
    public double horizontalAttenuationDb(double phiDeg, double hBeamwidthDeg) {
        if (hBeamwidthDeg >= CellParams.OMNI_H_BEAMWIDTH_DEG) {
            return 0.0;
        }
        return parabola(phiDeg, hBeamwidthDeg, frontToBackDb);
    }

    /** Vertical cut, in dB (always <= 0), saturating at the sidelobe floor SLA_v. */
    public double verticalAttenuationDb(double deltaThetaDeg, double vBeamwidthDeg) {
        return parabola(deltaThetaDeg, vBeamwidthDeg, sidelobeFloorDb);
    }

    private static double parabola(double offsetDeg, double beamwidthDeg, double floorDb) {
        if (beamwidthDeg <= 0.0) {
            return 0.0;
        }
        double ratio = offsetDeg / beamwidthDeg;
        return -Math.min(THREE_DB_SHAPE * ratio * ratio, floorDb);
    }

    /**
     * Gain implied by a pair of beamwidths, in dBi.
     *
     * <pre>
     *   G ~= 10*log10( 32400 / (hBeamwidth * vBeamwidth) ) - apertureLoss
     * </pre>
     *
     * <p>Gain–beamwidth reciprocity is real physics: concentrating power into a narrower beam is
     * the <em>only</em> way to raise gain. Deriving gain rather than letting a player set gain and
     * beamwidth independently is what keeps "360° wide and 20 dBi" from being free, and a
     * 65° x 10° panel lands at ~15 dBi, which is what a real sector antenna specifies.
     */
    public static double gainFromBeamwidthDbi(double hBeamwidthDeg, double vBeamwidthDeg) {
        if (hBeamwidthDeg <= 0.0 || vBeamwidthDeg <= 0.0) {
            return MIN_DERIVED_GAIN_DBI;
        }
        double raw = 10.0 * Math.log10(32400.0 / (hBeamwidthDeg * vBeamwidthDeg)) - APERTURE_LOSS_DB;
        return Math.clamp(raw, MIN_DERIVED_GAIN_DBI, MAX_DERIVED_GAIN_DBI);
    }
}
