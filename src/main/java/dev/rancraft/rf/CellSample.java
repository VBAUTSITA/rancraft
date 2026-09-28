package dev.rancraft.rf;

/**
 * One evaluated cell as seen from one receiver position at one tick.
 *
 * <p>Phase 2 appends the band and PCI identity, and the three figures that explain the antenna's
 * contribution: what gain it actually delivered in this direction, and how far off boresight the
 * receiver was in each plane. Those three exist so the HUD can show <em>why</em> a reading is what
 * it is -- "−82 dBm" teaches nothing; "−82 dBm, 18° off boresight, 13.1 dBi" teaches the player to
 * turn the antenna.
 */
public record CellSample(
        long cellId,
        int x, int y, int z,
        double rsrpDbm,
        double distanceBlocks,
        double pathLossDb,
        double obstructionDb,
        // ---- Phase 2 ----
        String bandId,
        int pci,
        double effectiveGainDbi,
        double azimuthOffsetDeg,
        double elevationOffsetDeg
) {
    public int bars() {
        return RfMath.bars(rsrpDbm);
    }

    public ServiceLevel rsrpLevel() {
        return ServiceLevel.fromRsrp(rsrpDbm);
    }

    /** Reduced to what the SINR sum needs. */
    public SinrCalculator.RxSignal asRxSignal() {
        return new SinrCalculator.RxSignal(cellId, bandId, pci, rsrpDbm);
    }
}
