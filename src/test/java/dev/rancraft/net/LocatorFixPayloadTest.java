package dev.rancraft.net;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.rancraft.net.LocatorFixPayload.Emergency;
import dev.rancraft.net.LocatorFixPayload.Kind;
import dev.rancraft.net.LocatorFixPayload.Ring;
import dev.rancraft.rf.CellParams;
import dev.rancraft.rf.LocatorFix;
import dev.rancraft.rf.LocatorParams;
import dev.rancraft.rf.RangeMeasurement;
import io.netty.buffer.Unpooled;
import io.netty.handler.codec.DecoderException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;
import net.minecraft.network.FriendlyByteBuf;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The Locator's fix on the wire (Phase 3 slice 5, §3A.6): version first, every fix type round-trips,
 * counts are checked before anything is allocated, and malformed input is rejected rather than drawn.
 * Only a Netty buffer is touched, so this runs headless.
 */
class LocatorFixPayloadTest {

    private static final Emergency EMERGENCY =
            new Emergency("minecraft:overworld", 120.5, 71.62, -33.25, 9.0, 1_000L, 1_600L);

    private static RangeMeasurement range(long id, double range, String bandId) {
        return new RangeMeasurement(id, 10.5 * id, 90.5, -4.5 * id, range, 4.33, bandId, 0.0);
    }

    private static List<RangeMeasurement> ranges(int n) {
        List<RangeMeasurement> ranges = new ArrayList<>();
        for (int i = 1; i <= n; i++) {
            ranges.add(range(i, 40.0 + i, i % 2 == 0 ? "band_1800" : "band_900"));
        }
        return ranges;
    }

    private static LocatorFixPayload payload(LocatorFix fix, int rings, Optional<Emergency> emergency) {
        return LocatorFixPayload.of(12_345L, fix, ranges(rings), 14.99, "band_1800", 1.0, LocatorParams.DEFAULTS, emergency);
    }

    private static FriendlyByteBuf encode(LocatorFixPayload payload) {
        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
        LocatorFixPayload.STREAM_CODEC.encode(buf, payload);
        return buf;
    }

    private static LocatorFixPayload roundTrip(LocatorFixPayload payload) {
        FriendlyByteBuf buf = encode(payload);
        LocatorFixPayload decoded = LocatorFixPayload.STREAM_CODEC.decode(buf);
        assertEquals(0, buf.readableBytes(), "bytes left unread: the reader and writer disagree");
        return decoded;
    }

    /** A buffer holding exactly what {@code writer} puts in it. */
    private static FriendlyByteBuf raw(Consumer<FriendlyByteBuf> writer) {
        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
        writer.accept(buf);
        return buf;
    }

    private static void rejects(FriendlyByteBuf buf) {
        assertThrows(DecoderException.class, () -> LocatorFixPayload.STREAM_CODEC.decode(buf));
    }

    @Test
    @DisplayName("every fix type round-trips with its rings, and the HUD's state names are the spec's")
    void everyKindRoundTrips() {
        List<LocatorFix> fixes = List.of(
                new LocatorFix.NoSignal(),
                new LocatorFix.RangeOnly(10.5, -4.5, 312.0),
                new LocatorFix.Ambiguous(1.0, 2.0, 3.0, 4.0, LocatorFix.Ambiguous.NO_PREFERENCE),
                new LocatorFix.Ambiguous(1.0, 2.0, 3.0, 4.0, 0),
                new LocatorFix.Ambiguous(1.0, 2.0, 3.0, 4.0, 1),
                new LocatorFix.PoorGeometry(11.2, 3),
                new LocatorFix.PoorGeometry(99.9, 5),
                new LocatorFix.Fix(1204.3, 87.62, -3391.8, 1.4, 9.2, 4));
        int[] rings = {0, 1, 2, 2, 2, 3, 5, 4};
        for (int i = 0; i < fixes.size(); i++) {
            LocatorFixPayload payload = payload(fixes.get(i), rings[i], i % 2 == 0 ? Optional.of(EMERGENCY) : Optional.empty());
            LocatorFixPayload decoded = roundTrip(payload);
            assertEquals(payload, decoded, "round trip of " + fixes.get(i));
            assertEquals(rings[i], decoded.rings().size());
        }
        assertEquals(List.of("NO SIGNAL", "RANGE ONLY", "AMBIGUOUS", "POOR GEOMETRY", "FIX"),
                java.util.Arrays.stream(Kind.values()).map(Kind::label).toList());
    }

    @Test
    @DisplayName("the version is the first field on the wire")
    void versionFirst() {
        FriendlyByteBuf buf = encode(payload(new LocatorFix.NoSignal(), 0, Optional.empty()));
        assertEquals(LocatorFixPayload.VERSION, buf.readVarInt());
    }

    @Test
    @DisplayName("cells used: the fix's own count for FIX and POOR GEOMETRY, the rings otherwise")
    void cellsUsed() {
        assertEquals(4, payload(new LocatorFix.Fix(0, 0, 0, 1, 1, 4), 4, Optional.empty()).cellsUsed());
        assertEquals(3, payload(new LocatorFix.PoorGeometry(20, 3), 3, Optional.empty()).cellsUsed());
        assertEquals(2, payload(new LocatorFix.Ambiguous(0, 0, 1, 1, -1), 2, Optional.empty()).cellsUsed());
        assertEquals(0, payload(new LocatorFix.NoSignal(), 0, Optional.empty()).cellsUsed());
    }

    @Test
    @DisplayName("building it: at most 8 rings, a non-finite ring left out, a non-finite fix sent as NO SIGNAL")
    void buildingIsDefensive() {
        LocatorFixPayload many = payload(new LocatorFix.PoorGeometry(8.0, 10), 10, Optional.empty());
        assertEquals(LocatorFixPayload.MAX_RINGS, many.rings().size());

        List<RangeMeasurement> withNaN = new ArrayList<>(ranges(3));
        withNaN.add(1, new RangeMeasurement(99, Double.NaN, 90, 0, 40, 4, "band_900", 0));
        withNaN.add(new RangeMeasurement(98, 0, 90, 0, -1, 4, "band_900", 0));
        LocatorFixPayload filtered = LocatorFixPayload.of(1L, new LocatorFix.NoSignal(), withNaN, 0, "", 1.0,
                LocatorParams.DEFAULTS, Optional.empty());
        assertEquals(3, filtered.rings().size());

        LocatorFixPayload badFix = payload(new LocatorFix.Fix(Double.NaN, 0, 0, 1, 1, 3), 3, Optional.empty());
        assertInstanceOf(LocatorFix.NoSignal.class, badFix.fix());
        LocatorFixPayload badLikely = payload(new LocatorFix.Ambiguous(0, 0, 1, 1, 7), 2, Optional.empty());
        assertInstanceOf(LocatorFix.NoSignal.class, badLikely.fix());

        LocatorFixPayload badScale = LocatorFixPayload.of(1L, new LocatorFix.NoSignal(), List.of(), Double.NaN, null,
                0.0, LocatorParams.DEFAULTS, Optional.of(new Emergency("d", Double.NaN, 0, 0, 1, 0, 0)));
        assertEquals(1.0, badScale.metersPerBlock());
        assertEquals(0.0, badScale.bestResolutionMeters());
        assertEquals("", badScale.bestResolutionBandId());
        assertTrue(badScale.emergency().isEmpty());
        roundTrip(badScale);

        assertThrows(IllegalArgumentException.class, () -> new LocatorFixPayload(LocatorFixPayload.VERSION, 0,
                new LocatorFix.NoSignal(), java.util.Collections.nCopies(9, new Ring(0, 0, 0, 1, "b")), 1, 0, "", 6, -100,
                Optional.empty()));
    }

    @Test
    @DisplayName("band ids are clamped to CellParams.MAX_BAND_ID_LENGTH, so a long datapack id cannot disconnect the player")
    void bandIdsClamped() {
        String longBand = "band_" + "9".repeat(200);
        LocatorFixPayload payload = LocatorFixPayload.of(1L, new LocatorFix.RangeOnly(0, 0, 10),
                List.of(range(1, 10, longBand)), 3.0, longBand, 1.0, LocatorParams.DEFAULTS, Optional.empty());
        assertEquals(CellParams.MAX_BAND_ID_LENGTH, payload.rings().get(0).bandId().length());
        assertEquals(CellParams.MAX_BAND_ID_LENGTH, payload.bestResolutionBandId().length());
        assertEquals(payload, roundTrip(payload));

        Emergency longDimension = new Emergency("x".repeat(1_000), 0, 0, 0, 0, 0, 0);
        assertEquals(LocatorFixPayload.MAX_DIMENSION_LENGTH, longDimension.dimension().length());
    }

    @Test
    @DisplayName("an unknown version or fix type is rejected")
    void rejectsUnknownVersionAndKind() {
        rejects(raw(buf -> buf.writeVarInt(LocatorFixPayload.VERSION + 1)));
        rejects(raw(buf -> {
            buf.writeVarInt(LocatorFixPayload.VERSION);
            buf.writeVarLong(1L);
            buf.writeByte(Kind.values().length);
        }));
        rejects(raw(buf -> {
            buf.writeVarInt(LocatorFixPayload.VERSION);
            buf.writeVarLong(1L);
            buf.writeByte(-1);
        }));
    }

    @Test
    @DisplayName("a ring count over the cap is rejected before any ring is read or allocated")
    void rejectsTooManyRings() {
        for (int count : new int[] {LocatorFixPayload.MAX_RINGS + 1, Integer.MAX_VALUE, -1}) {
            rejects(raw(buf -> {
                buf.writeVarInt(LocatorFixPayload.VERSION);
                buf.writeVarLong(1L);
                buf.writeByte(Kind.NO_SIGNAL.ordinal());
                buf.writeVarInt(count);
                // Deliberately nothing after the count: rejected without reading a single ring.
            }));
        }
    }

    @Test
    @DisplayName("non-finite numbers, a negative range, HDOP or error, a bad likely index or cell count are rejected")
    void rejectsMalformedValues() {
        rejects(raw(buf -> {
            header(buf, Kind.FIX);
            buf.writeDouble(Double.NaN);
        }));
        rejects(raw(buf -> {
            header(buf, Kind.RANGE_ONLY);
            buf.writeDouble(0);
            buf.writeDouble(0);
            buf.writeDouble(-5);
        }));
        rejects(raw(buf -> {
            header(buf, Kind.AMBIGUOUS);
            for (int i = 0; i < 4; i++) {
                buf.writeDouble(1);
            }
            buf.writeByte(2);
        }));
        rejects(raw(buf -> {
            header(buf, Kind.POOR_GEOMETRY);
            buf.writeDouble(Double.POSITIVE_INFINITY);
        }));
        rejects(raw(buf -> {
            header(buf, Kind.POOR_GEOMETRY);
            buf.writeDouble(10);
            buf.writeVarInt(LocatorFixPayload.MAX_CELLS_USED + 1);
        }));
        rejects(raw(buf -> {
            header(buf, Kind.FIX);
            buf.writeDouble(1);
            buf.writeDouble(2);
            buf.writeDouble(3);
            buf.writeDouble(1.2);
            buf.writeDouble(-0.5);
        }));
        // A ring with a negative radius.
        rejects(raw(buf -> {
            header(buf, Kind.NO_SIGNAL);
            buf.writeVarInt(1);
            buf.writeDouble(0);
            buf.writeDouble(0);
            buf.writeDouble(0);
            buf.writeDouble(-1);
        }));
    }

    @Test
    @DisplayName("a non-positive scale is rejected")
    void rejectsBadScale() {
        FriendlyByteBuf good = encode(payload(new LocatorFix.NoSignal(), 0, Optional.empty()));
        byte[] bytes = new byte[good.readableBytes()];
        good.readBytes(bytes);
        // version (1) + tick varint (2 bytes for 12345) + kind (1) + ring count (1) = offset 5.
        FriendlyByteBuf patched = new FriendlyByteBuf(Unpooled.wrappedBuffer(bytes));
        patched.setDouble(5, 0.0);
        rejects(patched);
    }

    private static void header(FriendlyByteBuf buf, Kind kind) {
        buf.writeVarInt(LocatorFixPayload.VERSION);
        buf.writeVarLong(1L);
        buf.writeByte(kind.ordinal());
    }
}
