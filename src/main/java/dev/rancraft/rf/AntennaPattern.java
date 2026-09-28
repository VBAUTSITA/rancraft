package dev.rancraft.rf;

/**
 * Effective antenna gain in a given direction.
 *
 * <p>This replaces the flat {@code gainDbi} the Phase 1 link budget used:
 * {@code RSRP = Tx + G(phi, dTheta) + Gr - PL - Obstruction}.
 *
 * <p>Deliberately an interface. Phase 4 loads measured patterns from JSON and must be able to do
 * so without touching {@link RfEngine}.
 */
@FunctionalInterface
public interface AntennaPattern {

    /**
     * @param bearingDeg   compass bearing to the receiver, 0 = north. See {@link AntennaGeometry}.
     * @param elevationDeg elevation to the receiver; positive means the receiver is above.
     * @param cell         supplies azimuth, tilt, beamwidths and max gain.
     * @return effective gain in dBi.
     */
    double gainDbi(double bearingDeg, double elevationDeg, CellParams cell);

    /**
     * Picks the pattern a cell should use.
     *
     * <p>Both the server engine and the GUI preview call this, so the plot a player sees is
     * produced by the same object that computed the RSRP they are standing in.
     *
     * <p>A cell whose horizontal beamwidth is 360° is omnidirectional and short-circuits to
     * {@link OmniPattern}; anything narrower uses the 3GPP {@link ParabolicPattern}.
     */
    static AntennaPattern forCell(CellParams cell, double frontToBackDb, double sidelobeFloorDb) {
        if (isOmni(cell)) {
            return OmniPattern.INSTANCE;
        }
        return new ParabolicPattern(frontToBackDb, sidelobeFloorDb);
    }

    static boolean isOmni(CellParams cell) {
        return cell.hBeamwidthDeg() >= CellParams.OMNI_H_BEAMWIDTH_DEG;
    }
}
