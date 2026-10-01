package dev.rancraft.rf;

import java.util.OptionalInt;

/**
 * Which bands an antenna's radio may transmit on: a radio of tier {@code n} may use any band whose
 * {@link Band#capacityTier()} is at most {@code n}. Phase 3 slice 10, §3C.1.
 *
 * <table>
 *   <caption>Radio tiers</caption>
 *   <tr><th>Antenna</th><th>Radio tier</th><th>Bands (shipped JSON)</th></tr>
 *   <tr><td>Signal Mast</td><td>1</td><td>band_900 (it has no GUI; band_700 is tier 1 too)</td></tr>
 *   <tr><td>Sector Antenna</td><td>2</td><td>band_700, band_900, band_1800</td></tr>
 *   <tr><td>Sector Antenna + Wideband Radio Unit</td><td>{@link #WIDEBAND} (3)</td><td>adds band_3500</td></tr>
 * </table>
 *
 * <p><b>Game abstraction, labelled:</b> a real base station's radio unit is built for a set of
 * bands (its filters, power amplifier and the instantaneous bandwidth it can handle), so a site
 * needs new hardware to add a band such as n78 (3.5 GHz, 100 MHz carriers). RANCraft folds that into
 * one integer per antenna compared against the band's {@code capacityTier}: no per-band filter, no
 * multi-band radio, no carrier aggregation, and the Wideband Radio Unit unlocks every band up to its
 * tier at once.
 *
 * <p>Pure: the server's check ({@code UpdateCellParamsPayload.applyOn}), the v2 to v3 save
 * migration ({@code AntennaBlockEntity}) and the configuration screen's greyed-out bands all call
 * this one rule, so the screen can never show a band as open that the server would refuse.
 */
public final class RadioTier {

    /** The lowest radio tier. Every band's {@code capacityTier} of 1 or less is open to it. */
    public static final int MIN = 1;

    /** A Sector Antenna with a Wideband Radio Unit installed: the highest tier any radio reaches. */
    public static final int WIDEBAND = 3;

    private RadioTier() {
    }

    /** Whether a radio of {@code radioTier} may use a band of {@code capacityTier}. */
    public static boolean allows(int radioTier, int capacityTier) {
        return capacityTier <= radioTier;
    }

    /**
     * Whether a radio of {@code radioTier} may use the band {@code bandId}. An id the table does not
     * hold is never allowed: the server rejects it anyway, as an unknown band.
     */
    public static boolean allows(int radioTier, BandTable bands, String bandId) {
        return bands.get(bandId).map(band -> allows(radioTier, band.capacityTier())).orElse(false);
    }

    /**
     * Whether the band the radio cannot use would open with a Wideband Radio Unit: its tier is above
     * the radio's but not above {@link #WIDEBAND}. False for a band no radio reaches (a datapack band
     * of tier 4 or more), so the screen does not promise what the unit cannot give.
     */
    public static boolean unlockedByWideband(int radioTier, int capacityTier) {
        return !allows(radioTier, capacityTier) && capacityTier <= WIDEBAND;
    }

    /**
     * The radio tier of an antenna saved before radio tiers existed (save format v1 or v2, §3C.1):
     * {@code max(blockDefault, tierOf(currentBand))}. A sector already on band_3500 is grandfathered
     * to tier 3, so nothing that worked before stops working. A band the table does not hold
     * (removed by a datapack) grants nothing: the block default.
     */
    public static int migrated(int blockDefault, OptionalInt currentBandTier) {
        return currentBandTier.isPresent() ? Math.max(blockDefault, currentBandTier.getAsInt()) : blockDefault;
    }

    /** {@link #migrated(int, OptionalInt)}, looking the band's tier up in {@code bands}. */
    public static int migrated(int blockDefault, BandTable bands, String currentBandId) {
        return migrated(blockDefault, bands.get(currentBandId)
                .map(band -> OptionalInt.of(band.capacityTier()))
                .orElse(OptionalInt.empty()));
    }

    /**
     * A radio tier read from saved data. Saved data is not trusted (a placed item's
     * {@code block_entity_data} reaches the load unchecked), but a value below the block's default
     * would only take away what the block always has, so it is raised to the default. A value above
     * {@link #WIDEBAND} is kept: migration can grandfather one (an antenna saved on a datapack band of
     * tier 4), and it would only open bands, which a creative player editing NBT can do anyway.
     */
    public static int loaded(int blockDefault, int saved) {
        return Math.max(blockDefault, saved);
    }
}
