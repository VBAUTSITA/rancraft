package dev.rancraft.rf;

/**
 * A transmitting cell.
 *
 * <p>{@code x}/{@code y}/{@code z} are the <em>radiating point</em> in block coordinates, not the
 * block the player placed. For a Signal Mast that is {@code pos.above()} -- the top face of the
 * mast. Phase 3 stacks masts for height, so the radiating point must stay decoupled from the
 * block position.
 *
 * <p>{@code azimuthDeg}, {@code tiltDeg}, {@code hBeamwidthDeg}, {@code vBeamwidthDeg} and
 * {@code pci} are Phase 2 seams: they are persisted and round-tripped through save/load, but the
 * Phase 1 engine ignores them entirely. Phase 1 is omnidirectional.
 */
public record CellParams(
        long cellId,
        int x, int y, int z,
        String bandId,
        double txPowerDbm,
        double gainDbi,
        double azimuthDeg,
        double tiltDeg,
        double hBeamwidthDeg,
        double vBeamwidthDeg,
        int pci
) {
    public static final String DEFAULT_BAND_ID = "band_900";

    /**
     * Longest band id the mod accepts, in chars. Every payload that carries a band id writes it
     * with this cap, and the encoder throws on anything longer -- which disconnects the receiving
     * player -- so band ids are held to it where they enter (the data loader, antenna NBT) and
     * clamped to it again at the wire.
     */
    public static final int MAX_BAND_ID_LENGTH = 64;
    public static final double DEFAULT_TX_DBM = 20.0;
    public static final double DEFAULT_GAIN_DBI = 6.0;
    public static final double OMNI_H_BEAMWIDTH_DEG = 360.0;
    public static final double DEFAULT_V_BEAMWIDTH_DEG = 90.0;

    /** A Phase 1 omni cell: Phase 2 seams present but neutral. */
    public static CellParams omniDefaults(long cellId, int x, int y, int z) {
        return new CellParams(
                cellId, x, y, z, DEFAULT_BAND_ID,
                DEFAULT_TX_DBM, DEFAULT_GAIN_DBI,
                0.0, 0.0, OMNI_H_BEAMWIDTH_DEG, DEFAULT_V_BEAMWIDTH_DEG, 0);
    }

    /** Centre of the radiating voxel, which is where the ray march starts. */
    public double centerX() {
        return x + 0.5;
    }

    public double centerY() {
        return y + 0.5;
    }

    public double centerZ() {
        return z + 0.5;
    }
}
