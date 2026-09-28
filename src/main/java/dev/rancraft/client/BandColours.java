package dev.rancraft.client;

import java.util.Map;

/**
 * Colour per band, for anything the RF Lens draws.
 *
 * <p>This table is the reason multi-band vision is a lookup rather than a redesign. Nothing in the
 * renderer knows what a band <em>is</em>: it reads {@code bandId} off a {@code CellParams} and asks
 * here. A fifth band dropped into {@code data/rancraft/rf/bands/} renders immediately, in the
 * fallback colour, with no code change. See VISION.md.
 *
 * <p>Hues follow the usual planning convention of low bands warm and high bands cool, so a glance
 * at a cluster tells you the frequency layering.
 */
public final class BandColours {

    private BandColours() {
    }

    /** Unknown bands still draw -- they just get a neutral colour rather than being invisible. */
    public static final int FALLBACK = 0xFFFFFF;

    private static final Map<String, Integer> BY_BAND = Map.of(
            "band_700", 0xFF5555,
            "band_900", 0xFFAA00,
            "band_1800", 0x55FF55,
            "band_3500", 0x55AAFF);

    public static int of(String bandId) {
        return BY_BAND.getOrDefault(bandId, FALLBACK);
    }
}
