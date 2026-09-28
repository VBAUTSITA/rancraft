package dev.rancraft.item;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.Optional;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;

/**
 * What the RF Lens is currently showing.
 *
 * <p>This record is the scalability seam for the whole vision feature. Step 1 declared every flag
 * here but only honoured {@link #showLobes()}; Step 2 switches the other two on without changing
 * the record's shape, so worlds that already carry a lens keep loading unchanged. It is the same
 * technique Phase 1 used for the azimuth/tilt/PCI seams that Phase 2 then switched on. Step 3a
 * appends {@link #showTrail()}.
 *
 * <p>The lens's two key bindings edit this record on the server (see
 * {@code net.LensControlPayload}): one cycles {@link #bandFilter()}, the other cycles the layer
 * flags through the {@link LensLayers} presets.
 *
 * <p><b>Field ceiling.</b> {@link #STREAM_CODEC} is a {@code StreamCodec.composite}, which in
 * 1.21.1 takes at most <b>6</b> fields. This record has 5; VISION_STEP3.md's Step 3b metric makes 6.
 * A seventh field has to switch the codec to a hand-written {@code StreamCodec.of(...)}, as
 * {@code SignalSamplePayload} already is -- not hard, but it must not be discovered by a compile
 * error.
 *
 * @param bandFilter   band id to show exclusively, or {@code ""} for all bands. Compared as a plain
 *                     string against {@code CellParams.bandId()}, so a new band dropped into
 *                     {@code data/rancraft/rf/bands/} needs no code change to become visible.
 *                     The drive-test trail ignores it: the trail records what the receiver got, and
 *                     the serving band is part of that result rather than a view choice.
 * @param showLobes    draw antenna radiation patterns. <b>Step 1.</b>
 * @param showLinks    draw rays to the cells currently serving and interfering. <b>Step 2a.</b>
 *                     Costs the server one extra traced ray per drawn link per sample.
 * @param showCoverage paint best-server colours onto the terrain. <b>Step 2b.</b> Costs the server
 *                     a time-sliced survey around the wearer.
 * @param showTrail    draw the drive-test trail. <b>Step 3a.</b> Makes the server evaluate the
 *                     wearer once per interval (the same single evaluation the meter and link rays
 *                     share) and send the sample, so the client can log it.
 */
public record LensSettings(
        String bandFilter,
        boolean showLobes,
        boolean showLinks,
        boolean showCoverage,
        // ---- RF Vision Step 3a ----
        boolean showTrail
) {
    /** Sentinel meaning "do not filter". */
    public static final String ALL_BANDS = "";

    /**
     * Every layer on, across every band. Step 1 shipped lobes only because nothing else was built;
     * with Step 2 live, a fresh lens shows everything it can and the wearer narrows it with the keys.
     */
    public static final LensSettings DEFAULT = new LensSettings(ALL_BANDS, true, true, true, true);

    /**
     * Optional-field defaults are read off {@link #DEFAULT} rather than repeated, so an item saved
     * with some fields missing decodes to exactly what an item with no component at all shows.
     *
     * <p>{@code show_trail} is the exception, because a lens saved before Step 3a has no such field
     * and a flat default would misread it. A lens left on the ALL preset should gain the trail, as ALL
     * now includes it; a lens deliberately narrowed to one layer (say LINKS) should stay narrowed, and
     * not wake up with the trail on and read as an unnamed combination. So a missing
     * {@code show_trail} is derived: on exactly when the three older layers are all on. The field is
     * always written, so the derivation only ever applies to pre-3a data.
     */
    public static final Codec<LensSettings> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            Codec.STRING.optionalFieldOf("band_filter", DEFAULT.bandFilter()).forGetter(LensSettings::bandFilter),
            Codec.BOOL.optionalFieldOf("show_lobes", DEFAULT.showLobes()).forGetter(LensSettings::showLobes),
            Codec.BOOL.optionalFieldOf("show_links", DEFAULT.showLinks()).forGetter(LensSettings::showLinks),
            Codec.BOOL.optionalFieldOf("show_coverage", DEFAULT.showCoverage()).forGetter(LensSettings::showCoverage),
            Codec.BOOL.optionalFieldOf("show_trail").forGetter(settings -> Optional.of(settings.showTrail()))
    ).apply(instance, (bandFilter, lobes, links, coverage, trail) -> new LensSettings(
            bandFilter, lobes, links, coverage, trail.orElseGet(() -> legacyTrail(lobes, links, coverage)))));

    /** Five of the six fields {@code StreamCodec.composite} allows; see the class comment. */
    public static final StreamCodec<RegistryFriendlyByteBuf, LensSettings> STREAM_CODEC =
            StreamCodec.composite(
                    ByteBufCodecs.STRING_UTF8, LensSettings::bandFilter,
                    ByteBufCodecs.BOOL, LensSettings::showLobes,
                    ByteBufCodecs.BOOL, LensSettings::showLinks,
                    ByteBufCodecs.BOOL, LensSettings::showCoverage,
                    ByteBufCodecs.BOOL, LensSettings::showTrail,
                    LensSettings::new);

    public LensSettings {
        bandFilter = bandFilter == null ? ALL_BANDS : bandFilter;
    }

    /** What a lens saved before Step 3a had for the trail: on only if it was on the ALL preset. */
    static boolean legacyTrail(boolean lobes, boolean links, boolean coverage) {
        return lobes && links && coverage;
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
        return LensLayers.of(showLobes, showLinks, showCoverage, showTrail);
    }

    public LensSettings withLayers(LensLayers layers) {
        return new LensSettings(bandFilter,
                layers.showLobes(), layers.showLinks(), layers.showCoverage(), layers.showTrail());
    }

    public LensSettings withBandFilter(String bandId) {
        return new LensSettings(bandId == null ? ALL_BANDS : bandId, showLobes, showLinks, showCoverage, showTrail);
    }

    public LensSettings withShowLobes(boolean value) {
        return new LensSettings(bandFilter, value, showLinks, showCoverage, showTrail);
    }
}
