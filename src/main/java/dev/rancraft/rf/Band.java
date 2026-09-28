package dev.rancraft.rf;

/**
 * A radio band. Loaded from {@code data/rancraft/rf/bands/*.json}.
 *
 * <p>Phase 2 appends {@code noiseFloorDbm} and {@code capacityTier}; Phase 3 appends
 * {@code bandwidthMhz}. Components are appended rather than reordered so that older band JSON keeps
 * loading unchanged, and the pre-Phase-3 six-argument constructor is kept (it defaults the
 * bandwidth) so every existing call site still compiles and means what it meant.
 *
 * @param penetrationFactor multiplies every per-block material attenuation on this band, so a
 *                          high band can be made to punch through walls worse than a low one
 *                          without duplicating the material table.
 * @param noiseFloorDbm     the thermal floor this band's SINR is measured against.
 *                          <b>Abstraction:</b> a real floor is bandwidth x noise figure x kT;
 *                          this is one flat number per band instead.
 * @param capacityTier      1 low, 2 mid, 3 high. The engine never reads it -- Phase 3 devices gate
 *                          on it ({@link DeviceRequirement}), so a player has a reason to deploy a
 *                          band that is harder to propagate.
 * @param bandwidthMhz      the carrier bandwidth. Phase 3 reads it for positioning only: timing
 *                          resolution is c / bandwidth ({@link Ranging}), which is what makes a
 *                          wideband carrier worth deploying for the Network Locator. The engine's
 *                          propagation and the noise floor do not read it (see noiseFloorDbm).
 */
public record Band(
        String id,
        double frequencyMhz,
        double pathLossExponent,
        double penetrationFactor,
        double noiseFloorDbm,
        int capacityTier,
        // ---- Phase 3 ----
        double bandwidthMhz
) {
    public static final double DEFAULT_NOISE_FLOOR_DBM = -110.0;
    public static final int DEFAULT_CAPACITY_TIER = 1;
    /** Used when a band JSON has no (or an invalid) {@code bandwidth_mhz}. Matches band_700/900. */
    public static final double DEFAULT_BANDWIDTH_MHZ = 10.0;

    /** Built-in fallback, matching the shipped {@code band_900.json} (10 MHz since Phase 3). */
    public static final Band DEFAULT_900 = new Band(
            "band_900", 900.0, 3.5, 1.0, DEFAULT_NOISE_FLOOR_DBM, DEFAULT_CAPACITY_TIER, DEFAULT_BANDWIDTH_MHZ);

    /** Pre-Phase-3 shape: the bandwidth defaults to {@link #DEFAULT_BANDWIDTH_MHZ}. */
    public Band(String id, double frequencyMhz, double pathLossExponent, double penetrationFactor,
                double noiseFloorDbm, int capacityTier) {
        this(id, frequencyMhz, pathLossExponent, penetrationFactor, noiseFloorDbm, capacityTier,
                DEFAULT_BANDWIDTH_MHZ);
    }
}
