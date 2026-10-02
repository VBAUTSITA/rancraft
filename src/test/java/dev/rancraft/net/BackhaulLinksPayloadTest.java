package dev.rancraft.net;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.rancraft.rf.MicrowaveLink.LinkState;
import io.netty.buffer.Unpooled;
import io.netty.handler.codec.DecoderException;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.network.FriendlyByteBuf;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The microwave hops on the wire (Phase 3 slice 12, §3C.2 "Visibility"): versioned, capped, every
 * field survives, and a malformed count or state is rejected rather than clamped. Headless.
 */
class BackhaulLinksPayloadTest {

    private static BackhaulLinksPayload.Link link(int i, LinkState state) {
        return new BackhaulLinksPayload.Link(i, 64 + i, -30_000_000 + i, -i, 200, 29_999_999 - i,
                state, -33.55f - i, 16.45f - i);
    }

    private static BackhaulLinksPayload roundTrip(BackhaulLinksPayload payload) {
        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
        BackhaulLinksPayload.STREAM_CODEC.encode(buf, payload);
        BackhaulLinksPayload decoded = BackhaulLinksPayload.STREAM_CODEC.decode(buf);
        assertEquals(0, buf.readableBytes(), "bytes left unread: the reader and writer disagree");
        return decoded;
    }

    @Test
    @DisplayName("every field round-trips, negative and world-edge coordinates included; version 1")
    void roundTrips() {
        List<BackhaulLinksPayload.Link> links = List.of(
                link(1, LinkState.UP), link(2, LinkState.DEGRADED), link(3, LinkState.DOWN),
                new BackhaulLinksPayload.Link(0, 0, 0, 1, 1, 1, LinkState.DOWN, -Float.MAX_VALUE, -Float.MAX_VALUE));
        BackhaulLinksPayload payload = new BackhaulLinksPayload(links);
        assertEquals(1, BackhaulLinksPayload.VERSION);
        BackhaulLinksPayload decoded = roundTrip(payload);
        assertEquals(payload, decoded);
        assertEquals(BackhaulLinksPayload.VERSION, decoded.version());
        assertEquals(LinkState.DEGRADED, decoded.links().get(1).state());
        assertEquals(-35.55f, decoded.links().get(1).rslDbm());
    }

    @Test
    @DisplayName("empty clears the client's lines and round-trips")
    void empty() {
        BackhaulLinksPayload decoded = roundTrip(BackhaulLinksPayload.empty());
        assertTrue(decoded.links().isEmpty());
    }

    @Test
    @DisplayName("the cap: 64 links pass, 65 are refused when built and rejected when read")
    void cap() {
        List<BackhaulLinksPayload.Link> full = new ArrayList<>();
        for (int i = 0; i < BackhaulLinksPayload.MAX_LINKS; i++) {
            full.add(link(i, LinkState.UP));
        }
        assertEquals(BackhaulLinksPayload.MAX_LINKS, roundTrip(new BackhaulLinksPayload(full)).links().size());

        List<BackhaulLinksPayload.Link> over = new ArrayList<>(full);
        over.add(link(99, LinkState.UP));
        assertThrows(IllegalArgumentException.class, () -> new BackhaulLinksPayload(over));

        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
        buf.writeVarInt(BackhaulLinksPayload.VERSION);
        buf.writeVarInt(BackhaulLinksPayload.MAX_LINKS + 1);
        assertThrows(DecoderException.class, () -> BackhaulLinksPayload.STREAM_CODEC.decode(buf));
    }

    @Test
    @DisplayName("an unknown state byte is rejected, not read as some state")
    void unknownState() {
        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
        buf.writeVarInt(BackhaulLinksPayload.VERSION);
        buf.writeVarInt(1);
        for (int i = 0; i < 6; i++) {
            buf.writeVarInt(i);
        }
        buf.writeByte(LinkState.values().length);
        buf.writeFloat(0.0f);
        buf.writeFloat(0.0f);
        assertThrows(DecoderException.class, () -> BackhaulLinksPayload.STREAM_CODEC.decode(buf));
        assertThrows(IllegalArgumentException.class,
                () -> new BackhaulLinksPayload.Link(0, 0, 0, 1, 1, 1, null, 0.0f, 0.0f));
    }
}
