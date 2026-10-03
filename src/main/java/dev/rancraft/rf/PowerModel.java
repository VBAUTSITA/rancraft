package dev.rancraft.rf;

/**
 * What a cell's radio costs to run, in game energy (FE) per tick. Phase 3 slice 15 (§3C.5). Pure.
 *
 * <pre>
 * P_rf_W   = 10^((txDbm - 30) / 10)
 * FE/tick  = baseFe + paFePerWatt x P_rf_W / paEfficiency
 *            baseFe: mast 2, sector 4, +4 for a wideband (tier 3) radio
 *            paFePerWatt = 20, paEfficiency = 0.3
 * </pre>
 *
 * <p>The lesson is the power amplifier: radiated power is logarithmic in dBm and linear in watts, and
 * the amplifier turns electrical power into radio power at an efficiency well below one, so 10 dB more
 * Tx power costs exactly ten times the amplifier's energy. A sector at 20 dBm (0.1 W) draws about 10.7
 * FE/t, at 30 dBm (1 W) about 70.7 FE/t: about seven times the fuel bill. Most players never feel dBm;
 * they feel a fuel bill.
 *
 * <p><b>Real, modelled faithfully:</b> the dBm to watt conversion, and the PA's DC input being its RF
 * output divided by its efficiency (0.3 is in the range of a real macro-cell PA's drain efficiency,
 * 25-50 %).
 *
 * <p><b>Abstracted, labelled (NOTES.md, slice 15):</b>
 * <ul>
 *   <li>The base load is a flat figure per kind of radio (baseband, cooling, the wideband unit's extra
 *       processing), not a function of the bandwidth or the traffic carried.</li>
 *   <li>The PA draws its full figure whenever the cell is on the air, whatever it carries: no load
 *       dependence, no sleep (DTX) or cell switch-off. Phase 4 can build those on the served-receivers
 *       seam ({@code util.ServedReceivers}).</li>
 *   <li>FE is the game's energy unit and has no fixed exchange rate to joules: {@code paFePerWatt}
 *       sets the scale.</li>
 * </ul>
 *
 * <p>The figures come from {@code RanCraftConfig} (COMMON); {@link #DEFAULT} holds the spec's.
 *
 * @param baseFeMast     the base load of a Signal Mast's radio (a mast column's cell), FE/t.
 * @param baseFeSector   the base load of a Sector Antenna's radio, FE/t.
 * @param baseFeWideband added for a radio of tier {@link RadioTier#WIDEBAND} or above, FE/t.
 * @param paFePerWatt    FE/t per watt the amplifier draws from the supply.
 * @param paEfficiency   the amplifier's efficiency: RF watts out per watt in, in (0, 1].
 */
public record PowerModel(double baseFeMast, double baseFeSector, double baseFeWideband,
                         double paFePerWatt, double paEfficiency) {

    /** The spec's figures (§3C.5). */
    public static final double DEFAULT_BASE_FE_MAST = 2.0;
    public static final double DEFAULT_BASE_FE_SECTOR = 4.0;
    public static final double DEFAULT_BASE_FE_WIDEBAND = 4.0;
    public static final double DEFAULT_PA_FE_PER_WATT = 20.0;
    public static final double DEFAULT_PA_EFFICIENCY = 0.3;

    public static final PowerModel DEFAULT = new PowerModel(
            DEFAULT_BASE_FE_MAST, DEFAULT_BASE_FE_SECTOR, DEFAULT_BASE_FE_WIDEBAND,
            DEFAULT_PA_FE_PER_WATT, DEFAULT_PA_EFFICIENCY);

    public PowerModel {
        requireNonNegative("baseFeMast", baseFeMast);
        requireNonNegative("baseFeSector", baseFeSector);
        requireNonNegative("baseFeWideband", baseFeWideband);
        requireNonNegative("paFePerWatt", paFePerWatt);
        if (!(paEfficiency > 0.0 && paEfficiency <= 1.0)) {
            throw new IllegalArgumentException("paEfficiency must be in (0, 1]: " + paEfficiency);
        }
    }

    private static void requireNonNegative(String name, double value) {
        if (!(value >= 0.0) || Double.isInfinite(value)) {
            throw new IllegalArgumentException(name + " must be finite and not negative: " + value);
        }
    }

    /** Radiated power in watts: {@code 10^((txDbm - 30) / 10)}. 30 dBm is 1 W, 20 dBm 0.1 W. */
    public static double rfWatts(double txDbm) {
        return Math.pow(10.0, (txDbm - 30.0) / 10.0);
    }

    /**
     * The radio's base load: {@link #baseFeSector} for a sector, {@link #baseFeMast} for a mast, plus
     * {@link #baseFeWideband} when the radio is tier {@link RadioTier#WIDEBAND} or above.
     */
    public double baseFe(boolean sector, int radioTier) {
        double base = sector ? baseFeSector : baseFeMast;
        return radioTier >= RadioTier.WIDEBAND ? base + baseFeWideband : base;
    }

    /** The amplifier's draw: {@code paFePerWatt x P_rf_W / paEfficiency}. Exactly 10x per 10 dB. */
    public double paFePerTick(double txDbm) {
        return paFePerWatt * rfWatts(txDbm) / paEfficiency;
    }

    /**
     * FE per tick of a cell on the air: {@code baseFe + paFePerTick(txDbm)}. Not finite (infinite, or
     * NaN) for a non-finite Tx power; a buffer cannot pay that and goes out ({@code util.EnergyBuffer}).
     */
    public double fePerTick(double txDbm, double baseFe) {
        return baseFe + paFePerTick(txDbm);
    }

    /** {@link #fePerTick(double, double)} for a radio of this kind and tier. */
    public double fePerTick(boolean sector, int radioTier, double txDbm) {
        return fePerTick(txDbm, baseFe(sector, radioTier));
    }
}
