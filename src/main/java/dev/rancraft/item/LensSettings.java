package dev.rancraft.item;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;

/**
 * What the RF Lens is currently showing.
 *
 * <p>This record is the scalability seam for the whole vision feature. Step 1 declared every flag
 * here but only honoured {@link #showLobes()}; Step 2 switches the other two on without changing
 * the record's shape, so worlds that already carry a lens keep loading unchanged. It is the same
 * technique Phase 1 used for the azimuth/tilt/PCI seams that Phase 2 then switched on.
 *
 * <p>The lens's two key bindings edit this record on the server (see
 * {@code net.LensControlPayload}): one cycles {@link #bandFilter()}, the other cycles the three
 * flags through the {@link LensLayers} presets.
 *
 * @param bandFilter   band id to show exclusively, or {@code ""} for all bands. Compared as a plain
 *                     string against {@code CellParams.bandId()}, so a new band dropped into
 *                     {@code data/rancraft/rf/bands/} needs no code change to become visible.
 * @param showLobes    draw antenna radiation patterns. <b>Step 1.</b>
 * @param showLinks    draw rays to the cells currently serving and interfering. <b>Step 2a.</b>
 *                     Costs the server one extra traced ray per drawn link per sample.
 * @param showCoverage paint best-server colours onto the terrain. <b>Step 2b.</b> Costs the server
 *                     a time-sliced survey around the wearer.
 */
public record LensSettings(
        String bandFilter,
        boolean showLobes,
        boolean showLinks,
        boolean showCoverage
) {
    /** Sentinel meaning "do not filter". */
    public static final String ALL_BANDS = "";

    /**
     * Every layer on, across every band. Step 1 shipped lobes only because nothing else was built;
     * with Step 2 live, a fresh lens shows everything it can and the wearer narrows it with the keys.
     */
    public static final LensSettings DEFAULT = new LensSettings(ALL_BANDS, true, true, true);

    /**
     * Optional-field defaults are read off {@link #DEFAULT} rather than repeated, so an item saved
     * with some fields missing decodes to exactly what an item with no component at all shows.
     */
    public static final Codec<LensSettings> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            Codec.STRING.optionalFieldOf("band_filter", DEFAULT.bandFilter()).forGetter(LensSettings::bandFilter),
            Codec.BOOL.optionalFieldOf("show_lobes", DEFAULT.showLobes()).forGetter(LensSettings::showLobes),
            Codec.BOOL.optionalFieldOf("show_links", DEFAULT.showLinks()).forGetter(LensSettings::showLinks),
            Codec.BOOL.optionalFieldOf("show_coverage", DEFAULT.showCoverage()).forGetter(LensSettings::showCoverage)
    ).apply(instance, LensSettings::new));

    public static final StreamCodec<RegistryFriendlyByteBuf, LensSettings> STREAM_CODEC =
            StreamCodec.composite(
                    ByteBufCodecs.STRING_UTF8, LensSettings::bandFilter,
                    ByteBufCodecs.BOOL, LensSettings::showLobes,
                    ByteBufCodecs.BOOL, LensSettings::showLinks,
                    ByteBufCodecs.BOOL, LensSettings::showCoverage,
                    LensSettings::new);

    public LensSettings {
        bandFilter = bandFilter == null ? ALL_BANDS : bandFilter;
    }

    /** True when this band should be drawn under the current filter. */
    public boolean showsBand(String bandId) {
        return bandFilter.isEmpty() || bandFilter.equals(bandId);
    }

    public boolean showsAllBands() {
        return bandFilter.isEmpty();
    }

    /**
     * The layer preset these flags match. A combination no preset names reads as
     * {@link LensLayers#ALL}; see {@link LensLayers#of}.
     */
    public LensLayers layers() {
        return LensLayers.of(showLobes, showLinks, showCoverage);
    }

    public LensSettings withLayers(LensLayers layers) {
        return new LensSettings(bandFilter, layers.showLobes(), layers.showLinks(), layers.showCoverage());
    }

    public LensSettings withBandFilter(String bandId) {
        return new LensSettings(bandId == null ? ALL_BANDS : bandId, showLobes, showLinks, showCoverage);
    }

    public LensSettings withShowLobes(boolean value) {
        return new LensSettings(bandFilter, value, showLinks, showCoverage);
    }
}
