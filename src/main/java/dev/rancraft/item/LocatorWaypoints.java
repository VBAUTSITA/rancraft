package dev.rancraft.item;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import io.netty.buffer.ByteBuf;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;

/**
 * The Network Locator's waypoints: a data component on the stack, at most {@value #MAX_ENTRIES}
 * entries and one selected. Phase 3 slice 5, §3A.6.
 *
 * <p><b>A waypoint is an estimate, never the true position.</b> Sneak + use saves the Locator's
 * current FIX, read on the server from the Locator's own last fix
 * ({@code device.NetworkLocator}), together with its "±". A waypoint saved under a bad fix (behind
 * a hill, towers in a near line) points to the wrong place, and the HUD then guides you there
 * faithfully. Navigation error inherits fix error: that is the lesson.
 *
 * <p>Waypoints replace the roadmap's "live map" (§8 of the Phase 3 spec). They carry no name:
 * naming needs a text input this slice does not add (recorded in NOTES.md as a deviation from the
 * HUD mock-up's {@code "base"}).
 *
 * <p>Robust to hand-edited or hostile data (the component is also decoded from a creative-mode
 * client): entries with non-finite numbers are dropped, the list is cut to {@value #MAX_ENTRIES},
 * dimension ids are clamped to {@value #MAX_DIMENSION_LENGTH} chars (the network codec throws on a
 * longer one, which would disconnect the holder), and the selection is clamped into the list.
 *
 * <p>No Minecraft game state here, so the list operations are unit-tested headless.
 *
 * @param entries  oldest first, at most {@value #MAX_ENTRIES}.
 * @param selected index of the selected entry; 0 when the list is empty.
 */
public record LocatorWaypoints(List<Waypoint> entries, int selected) {

    /** Most waypoints one Locator holds (§3A.6: "up to 8 entries"). */
    public static final int MAX_ENTRIES = 8;

    /** Longest dimension id kept, in chars. Real ids are far shorter. */
    public static final int MAX_DIMENSION_LENGTH = 256;

    public static final LocatorWaypoints EMPTY = new LocatorWaypoints(List.of(), 0);

    /**
     * One saved estimate.
     *
     * @param dimension   the dimension it was saved in, e.g. {@code minecraft:overworld}.
     * @param y           the fix's assumed eye height (altitude aiding), not a measurement.
     * @param errorBlocks the fix's reported "±" when it was saved.
     */
    public record Waypoint(String dimension, double x, double y, double z, double errorBlocks) {

        public static final Codec<Waypoint> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                Codec.STRING.fieldOf("dimension").forGetter(Waypoint::dimension),
                Codec.DOUBLE.fieldOf("x").forGetter(Waypoint::x),
                Codec.DOUBLE.fieldOf("y").forGetter(Waypoint::y),
                Codec.DOUBLE.fieldOf("z").forGetter(Waypoint::z),
                Codec.DOUBLE.optionalFieldOf("error_blocks", 0.0).forGetter(Waypoint::errorBlocks)
        ).apply(instance, Waypoint::new));

        public static final StreamCodec<ByteBuf, Waypoint> STREAM_CODEC = StreamCodec.composite(
                ByteBufCodecs.stringUtf8(MAX_DIMENSION_LENGTH), Waypoint::dimension,
                ByteBufCodecs.DOUBLE, Waypoint::x,
                ByteBufCodecs.DOUBLE, Waypoint::y,
                ByteBufCodecs.DOUBLE, Waypoint::z,
                ByteBufCodecs.DOUBLE, Waypoint::errorBlocks,
                Waypoint::new);

        public Waypoint {
            dimension = clamp(dimension);
        }

        /** Finite coordinates and a finite, non-negative "±". */
        public boolean isValid() {
            return Double.isFinite(x) && Double.isFinite(y) && Double.isFinite(z)
                    && Double.isFinite(errorBlocks) && errorBlocks >= 0.0;
        }
    }

    public static final Codec<LocatorWaypoints> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            Waypoint.CODEC.listOf().optionalFieldOf("entries", List.of()).forGetter(LocatorWaypoints::entries),
            Codec.INT.optionalFieldOf("selected", 0).forGetter(LocatorWaypoints::selected)
    ).apply(instance, LocatorWaypoints::new));

    /** Decoding rejects more than {@value #MAX_ENTRIES} entries before allocating them. */
    public static final StreamCodec<ByteBuf, LocatorWaypoints> STREAM_CODEC = StreamCodec.composite(
            Waypoint.STREAM_CODEC.apply(ByteBufCodecs.list(MAX_ENTRIES)), LocatorWaypoints::entries,
            ByteBufCodecs.VAR_INT, LocatorWaypoints::selected,
            LocatorWaypoints::new);

    public LocatorWaypoints {
        List<Waypoint> kept = new ArrayList<>(Math.min(entries == null ? 0 : entries.size(), MAX_ENTRIES));
        if (entries != null) {
            for (Waypoint waypoint : entries) {
                if (kept.size() >= MAX_ENTRIES) {
                    break;
                }
                if (waypoint != null && waypoint.isValid()) {
                    kept.add(waypoint);
                }
            }
        }
        entries = List.copyOf(kept);
        selected = entries.isEmpty() ? 0 : Math.clamp(selected, 0, entries.size() - 1);
    }

    public int size() {
        return entries.size();
    }

    public boolean isEmpty() {
        return entries.isEmpty();
    }

    public boolean isFull() {
        return entries.size() >= MAX_ENTRIES;
    }

    /** The selected waypoint, or empty when there are none. */
    public Optional<Waypoint> selectedWaypoint() {
        return entries.isEmpty() ? Optional.empty() : Optional.of(entries.get(selected));
    }

    /**
     * The result of {@link #save}.
     *
     * @param index    where the waypoint went (0-based); it is now the selected one.
     * @param replaced true when the list was full and the selected entry was overwritten.
     */
    public record Saved(LocatorWaypoints waypoints, int index, boolean replaced) {
    }

    /**
     * Saves a waypoint and selects it. While there is room it is appended; once the list holds
     * {@value #MAX_ENTRIES}, it overwrites the <em>selected</em> entry, so the player chooses what
     * to lose by selecting it first (use cycles the selection). Nothing is ever dropped silently.
     *
     * @throws IllegalArgumentException for a waypoint with a non-finite number (never saved).
     */
    public Saved save(Waypoint waypoint) {
        if (waypoint == null || !waypoint.isValid()) {
            throw new IllegalArgumentException("not a valid waypoint: " + waypoint);
        }
        List<Waypoint> next = new ArrayList<>(entries);
        if (isFull()) {
            next.set(selected, waypoint);
            return new Saved(new LocatorWaypoints(next, selected), selected, true);
        }
        next.add(waypoint);
        int index = next.size() - 1;
        return new Saved(new LocatorWaypoints(next, index), index, false);
    }

    /** The next waypoint selected, wrapping to the first; unchanged when there are none. */
    public LocatorWaypoints cycle() {
        if (entries.isEmpty()) {
            return this;
        }
        return new LocatorWaypoints(entries, (selected + 1) % entries.size());
    }

    private static String clamp(String text) {
        if (text == null) {
            return "";
        }
        if (text.length() <= MAX_DIMENSION_LENGTH) {
            return text;
        }
        int end = MAX_DIMENSION_LENGTH;
        if (Character.isHighSurrogate(text.charAt(end - 1))) {
            end--;
        }
        return text.substring(0, end);
    }
}
