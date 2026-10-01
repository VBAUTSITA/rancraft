package dev.rancraft.net;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.netty.buffer.Unpooled;
import io.netty.handler.codec.DecoderException;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The configuration screen's payload since Phase 3 slice 10 (§3C.1): the appended radio tier and
 * per-band capacity tiers round-trip, and the screen's lock rule is the server's. Only a Netty
 * buffer is touched, so this runs headless.
 */
class OpenAntennaConfigPayloadTest {

    private static final List<String> BANDS = List.of("band_900", "band_1800", "band_3500", "band_700");
    private static final List<Integer> TIERS = List.of(1, 2, 3, 1);

    private static OpenAntennaConfigPayload payload(int radioTier, List<String> bands, List<Integer> tiers) {
        return new OpenAntennaConfigPayload(new BlockPos(12, 70, -340), "band_1800", 20.0, 15.2,
                120.0, 3.0, 65.0, 10.0, 211, bands, List.of("mod-3 clash with PCI 214 at 80 m"),
                radioTier, tiers);
    }

    private static OpenAntennaConfigPayload roundTrip(OpenAntennaConfigPayload payload) {
        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
        OpenAntennaConfigPayload.STREAM_CODEC.encode(buf, payload);
        OpenAntennaConfigPayload decoded = OpenAntennaConfigPayload.STREAM_CODEC.decode(buf);
        assertEquals(0, buf.readableBytes(), "bytes left unread: the reader and writer disagree");
        return decoded;
    }

    @Test
    @DisplayName("Every field round-trips, the slice 10 radio tier and band tiers included")
    void roundTrips() {
        OpenAntennaConfigPayload sent = payload(2, BANDS, TIERS);
        assertEquals(sent, roundTrip(sent));
        OpenAntennaConfigPayload wideband = payload(3, BANDS, TIERS);
        assertEquals(wideband, roundTrip(wideband));
    }

    @Test
    @DisplayName("On a tier-2 sector the screen locks band_3500 only; with a Wideband Radio Unit nothing")
    void lockRule() {
        OpenAntennaConfigPayload sector = roundTrip(payload(2, BANDS, TIERS));
        assertTrue(sector.bandLocked("band_3500"));
        assertFalse(sector.bandLocked("band_1800"));
        assertFalse(sector.bandLocked("band_900"));
        assertFalse(sector.bandLocked("band_700"));
        assertEquals(3, sector.capacityTierOf("band_3500"));

        OpenAntennaConfigPayload wideband = roundTrip(payload(3, BANDS, TIERS));
        for (String id : BANDS) {
            assertFalse(wideband.bandLocked(id), id);
        }
    }

    @Test
    @DisplayName("A band the server did not list reads as tier 1 (the server checks the real tier on apply)")
    void unlistedBand() {
        OpenAntennaConfigPayload sector = payload(2, BANDS, TIERS);
        assertEquals(1, sector.capacityTierOf("band_2600"));
        assertFalse(sector.bandLocked("band_2600"));
        // A short tier list (never sent by the server) does not throw either.
        OpenAntennaConfigPayload shortList = payload(2, BANDS, List.of(1));
        assertEquals(1, shortList.capacityTierOf("band_3500"));
    }

    @Test
    @DisplayName("At most 64 bands and 64 tiers are written, and a larger tier count is rejected on read")
    void caps() {
        List<String> bands = new ArrayList<>();
        List<Integer> tiers = new ArrayList<>();
        for (int i = 0; i < 70; i++) {
            bands.add("band_" + i);
            tiers.add(1 + i % 3);
        }
        OpenAntennaConfigPayload decoded = roundTrip(payload(2, bands, tiers));
        assertEquals(64, decoded.availableBandIds().size());
        assertEquals(64, decoded.bandCapacityTiers().size());
        assertEquals(tiers.subList(0, 64), decoded.bandCapacityTiers());

        // Hand-written: everything up to the tier list, then a count of 65.
        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
        OpenAntennaConfigPayload.STREAM_CODEC.encode(buf, payload(2, List.of(), List.of()));
        buf.writerIndex(buf.writerIndex() - 1);   // drop the empty tier list's count (one byte)
        buf.writeVarInt(65);
        assertThrows(DecoderException.class, () -> OpenAntennaConfigPayload.STREAM_CODEC.decode(buf));
    }
}
