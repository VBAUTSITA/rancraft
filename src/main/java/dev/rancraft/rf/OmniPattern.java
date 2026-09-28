package dev.rancraft.rf;

/**
 * A flat omnidirectional radiator: the same gain in every direction.
 *
 * <p>This is the Phase 1 Signal Mast, and keeping it exactly flat is deliberate. Phase 2's
 * acceptance criteria require an omni mast to produce the <em>same</em> RSRP as the Phase 1
 * implementation within 0.01 dB, and Phase 1 applied a constant gain with no angular dependence at
 * all. Running the mast through {@link ParabolicPattern} instead would apply its vertical rolloff
 * and change old readings by up to 3 dB close to the tower.
 *
 * <p><b>Fidelity note:</b> a real omni panel is only omnidirectional in azimuth — it still has a
 * vertical pattern, and a receiver at the foot of the mast really is in its null. Phase 2 does not
 * model that, in exchange for the mast behaving identically to Phase 1. {@link ParabolicPattern}
 * does apply a vertical pattern to 360° cells if one is constructed directly.
 */
public final class OmniPattern implements AntennaPattern {

    public static final OmniPattern INSTANCE = new OmniPattern();

    private OmniPattern() {
    }

    @Override
    public double gainDbi(double bearingDeg, double elevationDeg, CellParams cell) {
        return cell.gainDbi();
    }
}
