package dev.rancraft.item;

import java.util.Locale;

/**
 * The layer presets the RF Lens cycles through with its layers key.
 *
 * <p>{@link LensSettings} stores the layer flags independently, because that is the honest shape of
 * the data and it lets a future GUI toggle them one by one. A key binding, though, wants a short
 * fixed cycle rather than sixteen combinations, so this enum names the five that are worth a key
 * press: everything, or one layer on its own. Isolating one layer is the point -- coverage painting
 * under a forest of link rays is hard to read, and vice versa.
 *
 * <p>Deliberately free of Minecraft types, like the rest of the lens state, so it can be reasoned
 * about (and tested) without a game.
 */
public enum LensLayers {

    /** Lobes, link rays, coverage painting and the drive-test trail together. The default. */
    ALL(true, true, true, true),
    /** Antenna radiation patterns only: Step 1's view. */
    ANTENNAS(true, false, false, false),
    /** Rays to the cells hitting the wearer only. */
    LINKS(false, true, false, false),
    /** Best-server painting on the terrain only. */
    COVERAGE(false, false, true, false),
    /** The drive-test trail only: where you walked and what the server measured there. Step 3a. */
    TRAIL(false, false, false, true);

    private static final LensLayers[] VALUES = values();

    private final boolean showLobes;
    private final boolean showLinks;
    private final boolean showCoverage;
    private final boolean showTrail;

    LensLayers(boolean showLobes, boolean showLinks, boolean showCoverage, boolean showTrail) {
        this.showLobes = showLobes;
        this.showLinks = showLinks;
        this.showCoverage = showCoverage;
        this.showTrail = showTrail;
    }

    public boolean showLobes() {
        return showLobes;
    }

    public boolean showLinks() {
        return showLinks;
    }

    public boolean showCoverage() {
        return showCoverage;
    }

    public boolean showTrail() {
        return showTrail;
    }

    /** The preset after this one, wrapping from {@link #TRAIL} back to {@link #ALL}. */
    public LensLayers next() {
        return VALUES[(ordinal() + 1) % VALUES.length];
    }

    /** {@code rancraft.lens.layers.all}, {@code .antennas}, {@code .links}, {@code .coverage} or {@code .trail}. */
    public String translationKey() {
        return "rancraft.lens.layers." + name().toLowerCase(Locale.ROOT);
    }

    /**
     * The preset with exactly these flags.
     *
     * <p>A combination no preset names (say lobes and links without coverage, which only a
     * hand-edited item can carry) reads as {@link #ALL}, so the next key press lands on
     * {@link #ANTENNAS} and the cycle is back on its rails.
     */
    public static LensLayers of(boolean lobes, boolean links, boolean coverage, boolean trail) {
        for (LensLayers layers : VALUES) {
            if (layers.showLobes == lobes && layers.showLinks == links
                    && layers.showCoverage == coverage && layers.showTrail == trail) {
                return layers;
            }
        }
        return ALL;
    }
}
