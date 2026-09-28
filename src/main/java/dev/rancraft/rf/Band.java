package dev.rancraft.rf;

/**
 * A radio band. Loaded from {@code data/rancraft/rf/bands/*.json}.
 *
 * <p>Phase 2 appends {@code noiseFloorDbm} and {@code capacityTier}. Components are appended rather
 * than reordered so that Phase 1 band JSON keeps loading unchanged.
 *
 * @param penetrationFactor multiplies every per-block material attenuation on this band, so a
 *                          high band can be made to punch through walls worse than a low one
 *                          without duplicating the material table.
 * @param noiseFloorDbm     the thermal floor this band's SINR is measured against.
 *                          <b>Abstraction:</b> a real floor is bandwidth x noise figure x kT;
 *                          this is one flat number per band instead.
 * @param capacityTier      1 low, 2 mid, 3 high. The engine never reads it -- Phase 3 devices gate
 *                          on it, so a player has a reason to deploy a band that is harder to
 *                          propagate.
 */
public record Band(
        String id,
        double frequencyMhz,
        double pathLossExponent,
        double penetrationFactor,
        double noiseFloorDbm,
        int capacityTier
) {
    public static final double DEFAULT_NOISE_FLOOR_DBM = -110.0;
    public static final int DEFAULT_CAPACITY_TIER = 1;

    /** Built-in fallback, matching the shipped {@code band_900.json}. Unchanged since Phase 1. */
    public static final Band DEFAULT_900 =
            new Band("band_900", 900.0, 3.5, 1.0, DEFAULT_NOISE_FLOOR_DBM, DEFAULT_CAPACITY_TIER);
}
