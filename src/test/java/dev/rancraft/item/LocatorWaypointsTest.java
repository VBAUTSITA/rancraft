package dev.rancraft.item;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import dev.rancraft.item.LocatorWaypoints.Waypoint;
import io.netty.buffer.Unpooled;
import io.netty.handler.codec.DecoderException;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The Network Locator's waypoint list (Phase 3 slice 5, §3A.6): at most 8 entries, sneak + use
 * saves, use cycles, and the component survives disk, the network and hand-edited data.
 */
class LocatorWaypointsTest {

    private static final String OVERWORLD = "minecraft:overworld";

    private static Waypoint at(double x, double z) {
        return new Waypoint(OVERWORLD, x, 71.62, z, 4.5);
    }

    private static LocatorWaypoints filled(int n) {
        LocatorWaypoints list = LocatorWaypoints.EMPTY;
        for (int i = 0; i < n; i++) {
            list = list.save(at(i * 10, -i * 10)).waypoints();
        }
        return list;
    }

    @Test
    @DisplayName("empty: nothing selected, cycling does nothing")
    void empty() {
        LocatorWaypoints empty = LocatorWaypoints.EMPTY;
        assertTrue(empty.isEmpty());
        assertEquals(0, empty.selected());
        assertTrue(empty.selectedWaypoint().isEmpty());
        assertSame(empty, empty.cycle());
    }

    @Test
    @DisplayName("save appends the estimate and selects it")
    void saveAppendsAndSelects() {
        LocatorWaypoints.Saved first = LocatorWaypoints.EMPTY.save(at(1, 2));
        assertEquals(0, first.index());
        assertFalse(first.replaced());
        LocatorWaypoints.Saved second = first.waypoints().save(at(3, 4));
        assertEquals(1, second.index());
        assertEquals(2, second.waypoints().size());
        assertEquals(1, second.waypoints().selected());
        assertEquals(at(3, 4), second.waypoints().selectedWaypoint().orElseThrow());
        assertEquals(at(1, 2), second.waypoints().entries().get(0), "oldest first");
    }

    @Test
    @DisplayName("at most 8: a ninth save overwrites the selected entry, never drops one silently")
    void fullListOverwritesTheSelected() {
        LocatorWaypoints full = filled(LocatorWaypoints.MAX_ENTRIES);
        assertEquals(8, full.size());
        assertTrue(full.isFull());
        assertEquals(7, full.selected());

        // Select entry 2 (cycle wraps from 7 to 0, then 1, then 2), then save over it.
        LocatorWaypoints atTwo = full.cycle().cycle().cycle();
        assertEquals(2, atTwo.selected());
        LocatorWaypoints.Saved saved = atTwo.save(at(999, 999));
        assertTrue(saved.replaced());
        assertEquals(2, saved.index());
        assertEquals(8, saved.waypoints().size());
        assertEquals(at(999, 999), saved.waypoints().entries().get(2));
        assertEquals(atTwo.entries().get(3), saved.waypoints().entries().get(3), "the rest untouched");
        assertEquals(2, saved.waypoints().selected());
    }

    @Test
    @DisplayName("use cycles the selection and wraps")
    void cycleWraps() {
        LocatorWaypoints three = filled(3);
        assertEquals(2, three.selected());
        assertEquals(0, three.cycle().selected());
        assertEquals(1, three.cycle().cycle().selected());
        assertEquals(2, three.cycle().cycle().cycle().selected());
        assertEquals(three.entries(), three.cycle().entries());
    }

    @Test
    @DisplayName("a waypoint with a non-finite number or a negative error is never saved")
    void invalidWaypointRejected() {
        assertThrows(IllegalArgumentException.class, () -> LocatorWaypoints.EMPTY.save(null));
        assertThrows(IllegalArgumentException.class,
                () -> LocatorWaypoints.EMPTY.save(new Waypoint(OVERWORLD, Double.NaN, 70, 0, 1)));
        assertThrows(IllegalArgumentException.class,
                () -> LocatorWaypoints.EMPTY.save(new Waypoint(OVERWORLD, 0, 70, Double.POSITIVE_INFINITY, 1)));
        assertThrows(IllegalArgumentException.class,
                () -> LocatorWaypoints.EMPTY.save(new Waypoint(OVERWORLD, 0, 70, 0, -1)));
    }

    @Test
    @DisplayName("hand-edited data: bad entries dropped, the list cut to 8, the selection clamped")
    void robustConstruction() {
        List<Waypoint> twelve = new ArrayList<>();
        twelve.add(new Waypoint(OVERWORLD, Double.NaN, 0, 0, 0));
        twelve.add(null);
        for (int i = 0; i < 10; i++) {
            twelve.add(at(i, i));
        }
        LocatorWaypoints list = new LocatorWaypoints(twelve, 50);
        assertEquals(8, list.size());
        assertEquals(at(0, 0), list.entries().get(0));
        assertEquals(7, list.selected());
        assertEquals(0, new LocatorWaypoints(List.of(at(1, 1)), -3).selected());
        assertEquals(0, new LocatorWaypoints(null, 5).selected());
    }

    @Test
    @DisplayName("a dimension id longer than the wire cap is clamped rather than disconnecting the holder")
    void dimensionClamped() {
        Waypoint waypoint = new Waypoint("x".repeat(1000), 0, 0, 0, 0);
        assertEquals(LocatorWaypoints.MAX_DIMENSION_LENGTH, waypoint.dimension().length());
        assertEquals("", new Waypoint(null, 0, 0, 0, 0).dimension());
    }

    @Test
    @DisplayName("disk: the codec round-trips, and missing fields read as empty")
    void codecRoundTrip() {
        LocatorWaypoints list = filled(3).cycle();
        JsonElement json = LocatorWaypoints.CODEC.encodeStart(JsonOps.INSTANCE, list).getOrThrow();
        assertEquals(list, LocatorWaypoints.CODEC.parse(JsonOps.INSTANCE, json).getOrThrow());
        assertEquals(LocatorWaypoints.EMPTY,
                LocatorWaypoints.CODEC.parse(JsonOps.INSTANCE, JsonParser.parseString("{}")).getOrThrow());
        LocatorWaypoints noError = LocatorWaypoints.CODEC.parse(JsonOps.INSTANCE, JsonParser.parseString(
                "{\"entries\":[{\"dimension\":\"minecraft:overworld\",\"x\":1,\"y\":2,\"z\":3}]}")).getOrThrow();
        assertEquals(0.0, noError.entries().get(0).errorBlocks());
    }

    @Test
    @DisplayName("network: the stream codec round-trips")
    void streamRoundTrip() {
        LocatorWaypoints list = filled(8).cycle().cycle();
        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
        LocatorWaypoints.STREAM_CODEC.encode(buf, list);
        assertEquals(list, LocatorWaypoints.STREAM_CODEC.decode(buf));
        assertEquals(0, buf.readableBytes());
    }

    @Test
    @DisplayName("network: more than 8 entries is rejected before they are read")
    void streamRejectsTooMany() {
        List<Waypoint> nine = new ArrayList<>();
        for (int i = 0; i < 9; i++) {
            nine.add(at(i, i));
        }
        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
        Waypoint.STREAM_CODEC.apply(ByteBufCodecs.list(16)).encode(buf, nine);
        ByteBufCodecs.VAR_INT.encode(buf, 0);
        assertThrows(DecoderException.class, () -> LocatorWaypoints.STREAM_CODEC.decode(buf));

        FriendlyByteBuf huge = new FriendlyByteBuf(Unpooled.buffer());
        huge.writeVarInt(Integer.MAX_VALUE);
        assertThrows(DecoderException.class, () -> LocatorWaypoints.STREAM_CODEC.decode(huge));
    }
}
