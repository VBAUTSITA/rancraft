package dev.rancraft.net;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.rancraft.net.ScannerPayload.Contact;
import dev.rancraft.net.ScannerPayload.Status;
import dev.rancraft.rf.CellParams;
import dev.rancraft.rf.DeviceRequirement;
import dev.rancraft.rf.ServiceLevel;
import dev.rancraft.util.ProximityScan;
import io.netty.buffer.Unpooled;
import io.netty.handler.codec.DecoderException;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import net.minecraft.network.FriendlyByteBuf;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The Proximity Scanner on the wire (Phase 3 slice 14, §3C.4): versioned, at most 16 entries (type,
 * distance, bearing), every field survives, a refusal carries its reason and no list, and malformed input
 * is rejected rather than read on. Headless.
 */
class ScannerPayloadTest {

    private static final DeviceRequirement GOOD_TIER_3 = new DeviceRequirement(ServiceLevel.GOOD, 3);

    private static ScannerPayload roundTrip(ScannerPayload payload) {
        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
        ScannerPayload.STREAM_CODEC.encode(buf, payload);
        ScannerPayload decoded = ScannerPayload.STREAM_CODEC.decode(buf);
        assertEquals(0, buf.readableBytes(), "bytes left unread: the reader and writer disagree");
        return decoded;
    }

    private static List<ProximityScan.Contact> contacts(int count) {
        List<ProximityScan.Contact> contacts = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            contacts.add(new ProximityScan.Contact("minecraft:creeper", 1.0 + i, (i * 37.0) % 360.0));
        }
        return contacts;
    }

    @Test
    @DisplayName("an OK payload round-trips with its list; version 1")
    void okRoundTrips() {
        ScannerPayload payload = ScannerPayload.of(Status.OK, GOOD_TIER_3, ServiceLevel.GOOD, ServiceLevel.EXCELLENT,
                "band_3500", 3, 24.0, List.of(
                        new ProximityScan.Contact("minecraft:creeper", 7.25, 47.5),
                        new ProximityScan.Contact("minecraft:slime", 12.0, 359.75)));
        assertEquals(1, ScannerPayload.VERSION);
        ScannerPayload decoded = roundTrip(payload);
        assertEquals(payload, decoded);
        assertEquals(Status.OK, decoded.status());
        assertEquals(ServiceLevel.GOOD, decoded.neededLevel());
        assertEquals(3, decoded.neededTier());
        assertEquals("band_3500", decoded.servingBandId());
        assertEquals(24.0f, decoded.rangeBlocks());
        assertEquals(new Contact("minecraft:creeper", 7.25f, 47.5f), decoded.contacts().get(0));
        assertEquals(359.75f, decoded.contacts().get(1).bearingDegrees());
    }

    @Test
    @DisplayName("every refusal round-trips with its reason's values and no list, even when one is offered")
    void refusalsRoundTrip() {
        for (Status status : Status.values()) {
            if (status == Status.OK) {
                continue;
            }
            ScannerPayload payload = ScannerPayload.of(status, GOOD_TIER_3, ServiceLevel.FAIR, ServiceLevel.FAIR,
                    "band_1800", 2, 24.0, contacts(3));
            assertTrue(payload.contacts().isEmpty(), status + ": a refusal carries no list");
            ScannerPayload decoded = roundTrip(payload);
            assertEquals(payload, decoded);
            assertEquals(status, decoded.status());
            assertEquals(ServiceLevel.FAIR, decoded.radioLevel());
            assertEquals(ServiceLevel.FAIR, decoded.serviceCap());
            assertEquals(2, decoded.servingBandTier());
        }
        assertThrows(IllegalArgumentException.class, () -> new ScannerPayload(ScannerPayload.VERSION, Status.LOW_TIER,
                ServiceLevel.GOOD, 3, ServiceLevel.GOOD, ServiceLevel.EXCELLENT, "band_1800", 2, 24.0f,
                List.of(new Contact("minecraft:creeper", 1.0f, 0.0f))));
    }

    @Test
    @DisplayName("the cap: of() keeps the first 16; 17 are refused when built and rejected when read")
    void cap() {
        ScannerPayload full = ScannerPayload.of(Status.OK, GOOD_TIER_3, ServiceLevel.GOOD, ServiceLevel.EXCELLENT,
                "band_3500", 3, 24.0, contacts(20));
        assertEquals(ScannerPayload.MAX_CONTACTS, full.contacts().size());
        assertEquals(1.0f, full.contacts().get(0).distanceBlocks(), "the first ones, in order");
        assertEquals(16, roundTrip(full).contacts().size());

        List<Contact> over = new ArrayList<>(full.contacts());
        over.add(new Contact("minecraft:creeper", 20.0f, 0.0f));
        assertThrows(IllegalArgumentException.class, () -> new ScannerPayload(ScannerPayload.VERSION, Status.OK,
                ServiceLevel.GOOD, 3, ServiceLevel.GOOD, ServiceLevel.EXCELLENT, "band_3500", 3, 24.0f, over));

        assertRejected(buf -> {
            header(buf, Status.OK);
            buf.writeVarInt(ScannerPayload.MAX_CONTACTS + 1);
        });
    }

    @Test
    @DisplayName("of() drops a contact it could not send and folds a bearing that rounds to 360 back to 0")
    void defensiveBuild() {
        ScannerPayload payload = ScannerPayload.of(Status.OK, GOOD_TIER_3, ServiceLevel.GOOD, ServiceLevel.EXCELLENT,
                "band_3500", 3, Double.NaN, List.of(
                        new ProximityScan.Contact("minecraft:creeper", Double.NaN, 10.0),
                        new ProximityScan.Contact("minecraft:spider", 3.0, Math.nextDown(360.0)),
                        new ProximityScan.Contact("minecraft:witch", -1.0, 10.0)));
        assertEquals(1, payload.contacts().size());
        assertEquals(new Contact("minecraft:spider", 3.0f, 0.0f), payload.contacts().get(0));
        assertEquals(0.0f, payload.rangeBlocks(), "a non-finite range reads 0");
        assertEquals(payload, roundTrip(payload));
    }

    @Test
    @DisplayName("strings are clamped to their wire caps, so a long id never drops the player")
    void clamped() {
        String longType = "minecraft:" + "x".repeat(400);
        String longBand = "band_" + "9".repeat(200);
        ScannerPayload payload = ScannerPayload.of(Status.OK, GOOD_TIER_3, ServiceLevel.GOOD, ServiceLevel.EXCELLENT,
                longBand, 3, 24.0, List.of(new ProximityScan.Contact(longType, 2.0, 90.0)));
        assertEquals(CellParams.MAX_BAND_ID_LENGTH, payload.servingBandId().length());
        assertEquals(ScannerPayload.MAX_TYPE_ID_LENGTH, payload.contacts().get(0).typeId().length());
        assertEquals(payload, roundTrip(payload));
    }

    @Test
    @DisplayName("malformed input is rejected: version, status, level, tier, range, a list on a refusal, a bad contact")
    void malformed() {
        assertRejected(buf -> buf.writeVarInt(ScannerPayload.VERSION + 1));
        assertRejected(buf -> {
            buf.writeVarInt(ScannerPayload.VERSION);
            buf.writeByte(Status.values().length);
        });
        assertRejected(buf -> {
            buf.writeVarInt(ScannerPayload.VERSION);
            buf.writeByte(Status.OK.ordinal());
            buf.writeByte(ServiceLevel.values().length);
        });
        assertRejected(buf -> {
            buf.writeVarInt(ScannerPayload.VERSION);
            buf.writeByte(Status.OK.ordinal());
            buf.writeByte(ServiceLevel.GOOD.ordinal());
            buf.writeVarInt(-1);
        });
        assertRejected(buf -> {
            buf.writeVarInt(ScannerPayload.VERSION);
            buf.writeByte(Status.OK.ordinal());
            buf.writeByte(ServiceLevel.GOOD.ordinal());
            buf.writeVarInt(3);
            buf.writeByte(ServiceLevel.GOOD.ordinal());
            buf.writeByte(ServiceLevel.EXCELLENT.ordinal());
            buf.writeUtf("band_3500");
            buf.writeVarInt(3);
            buf.writeFloat(Float.NaN);
        });
        assertRejected(buf -> {
            header(buf, Status.LOW_TIER);
            buf.writeVarInt(1);
            buf.writeUtf("minecraft:creeper");
            buf.writeFloat(1.0f);
            buf.writeFloat(0.0f);
        });
        for (float[] bad : new float[][] {{-1.0f, 0.0f}, {Float.NaN, 0.0f}, {1.0f, 360.0f}, {1.0f, -0.5f},
                {1.0f, Float.POSITIVE_INFINITY}}) {
            assertRejected(buf -> {
                header(buf, Status.OK);
                buf.writeVarInt(1);
                buf.writeUtf("minecraft:creeper");
                buf.writeFloat(bad[0]);
                buf.writeFloat(bad[1]);
            });
        }
    }

    /** Everything before the contact count, valid. */
    private static void header(FriendlyByteBuf buf, Status status) {
        buf.writeVarInt(ScannerPayload.VERSION);
        buf.writeByte(status.ordinal());
        buf.writeByte(ServiceLevel.GOOD.ordinal());
        buf.writeVarInt(3);
        buf.writeByte(ServiceLevel.GOOD.ordinal());
        buf.writeByte(ServiceLevel.EXCELLENT.ordinal());
        buf.writeUtf("band_3500");
        buf.writeVarInt(3);
        buf.writeFloat(24.0f);
    }

    private static void assertRejected(Consumer<FriendlyByteBuf> writer) {
        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
        writer.accept(buf);
        assertThrows(DecoderException.class, () -> ScannerPayload.STREAM_CODEC.decode(buf));
    }
}
