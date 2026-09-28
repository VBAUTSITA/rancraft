package dev.rancraft.rf;

/**
 * One cell's range as the Network Locator measured it. Phase 3, §3A.4.
 *
 * <p>This is everything the solver is allowed to know about a cell: where the cell radiates from
 * (public, it is the antenna's own position) and the quantised, biased range. It deliberately does
 * <em>not</em> carry the true distance or the receiver's position: the Locator never sees where the
 * receiver really is, so it can be wrong in the ways real positioning is wrong.
 *
 * @param cellId        the measured cell.
 * @param x             radiating centre, x (the centre of the radiating voxel: block x + 0.5).
 * @param y             radiating centre, y.
 * @param z             radiating centre, z.
 * @param rangeBlocks   the measured slant (3D) range: quantised to the band's timing resolution,
 *                      plus the NLOS bias. Never negative.
 * @param sigmaBlocks   the reported 1-sigma of the quantisation error, resolution / sqrt(12). It
 *                      does <b>not</b> include the NLOS bias, which is systematic, not random.
 * @param bandId        the cell's band, for colouring its ring.
 * @param obstructionDb what the engine's ray march accumulated to this cell; the source of the bias.
 */
public record RangeMeasurement(
        long cellId,
        double x, double y, double z,
        double rangeBlocks,
        double sigmaBlocks,
        String bandId,
        double obstructionDb
) {
}
