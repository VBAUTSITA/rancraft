package dev.rancraft.net;

import dev.rancraft.RanCraft;
import dev.rancraft.rf.MicrowaveLink;
import io.netty.handler.codec.DecoderException;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * Server to client: the microwave backhaul hops near an RF Lens wearer, with the state the server
 * measured on each (Phase 3 slice 12, §3C.2 "Visibility").
 *
 * <p><b>Link state is a measurement, so the client cannot derive it.</b> Whether a hop is UP, DEGRADED
 * or DOWN depends on every block along it and on its Fresnel zone, on the rain at its midpoint and on
 * the server's link figures (a datapack), none of which the client may compute from. So the server
 * sends the two dish positions and its verdict, and the lens draws a line between them in the
 * verdict's colour. The RSL and the margin are sent for the line's label only.
 *
 * <p>Sent about once a second ({@code world.BackhaulNetwork}) to each player who wears an RF Lens with
 * the lobes layer on, for the hops that pass within {@code BackhaulNetwork.LENS_RANGE_BLOCKS} of
 * them, nearest first, at most {@value #MAX_LINKS}. An empty payload clears the client's lines. Not a
 * field of {@code LensSettings}: its stream codec is at the six-field ceiling of
 * {@code StreamCodec.composite} (VISION_STEP3.md), and this is the server's answer, not a lens setting.
 *
 * @param version wire version, {@value #VERSION}.
 * @param links   at most {@value #MAX_LINKS}, nearest first.
 */
public record BackhaulLinksPayload(int version, List<Link> links) implements CustomPacketPayload {

    /** Wire version, written first. <b>1</b>: Phase 3 slice 12. */
    public static final int VERSION = 1;

    /** Wire cap on hops per payload. A count above it is rejected on read, never clamped. */
    public static final int MAX_LINKS = 64;

    private static final MicrowaveLink.LinkState[] STATES = MicrowaveLink.LinkState.values();

    public static final CustomPacketPayload.Type<BackhaulLinksPayload> TYPE =
            new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(RanCraft.MOD_ID, "backhaul_links"));

    public static final StreamCodec<FriendlyByteBuf, BackhaulLinksPayload> STREAM_CODEC =
            StreamCodec.of(BackhaulLinksPayload::write, BackhaulLinksPayload::read);

    /**
     * One hop: dish A's block and dish B's block (the line runs between their centres), the state the
     * server measured, its RSL and its margin to the UP threshold (negative below UP). Floats on the
     * wire: the label shows a tenth of a dB.
     */
    public record Link(int ax, int ay, int az, int bx, int by, int bz,
                       MicrowaveLink.LinkState state, float rslDbm, float marginDb) {

        public Link {
            if (state == null) {
                throw new IllegalArgumentException("state");
            }
        }
    }

    public BackhaulLinksPayload {
        links = List.copyOf(links);
        if (links.size() > MAX_LINKS) {
            throw new IllegalArgumentException(links.size() + " backhaul links exceed the cap of " + MAX_LINKS);
        }
    }

    public BackhaulLinksPayload(List<Link> links) {
        this(VERSION, links);
    }

    /** Nothing in range: the client stops drawing hops. */
    public static BackhaulLinksPayload empty() {
        return new BackhaulLinksPayload(VERSION, List.of());
    }

    private static void write(FriendlyByteBuf buf, BackhaulLinksPayload payload) {
        buf.writeVarInt(payload.version);
        buf.writeVarInt(payload.links.size());
        for (Link link : payload.links) {
            buf.writeVarInt(link.ax());
            buf.writeVarInt(link.ay());
            buf.writeVarInt(link.az());
            buf.writeVarInt(link.bx());
            buf.writeVarInt(link.by());
            buf.writeVarInt(link.bz());
            buf.writeByte(link.state().ordinal());
            buf.writeFloat(link.rslDbm());
            buf.writeFloat(link.marginDb());
        }
    }

    /**
     * The count is checked against the cap before anything is allocated, and a state byte must name a
     * state: either violation is rejected rather than clamped, since reading on would desynchronise
     * every field that follows (as {@link LensLinksPayload} does).
     */
    private static BackhaulLinksPayload read(FriendlyByteBuf buf) {
        int version = buf.readVarInt();
        int count = buf.readVarInt();
        if (count < 0 || count > MAX_LINKS) {
            throw new DecoderException("RANCraft backhaul links: " + count + " links (max " + MAX_LINKS + ")");
        }
        List<Link> links = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            int ax = buf.readVarInt();
            int ay = buf.readVarInt();
            int az = buf.readVarInt();
            int bx = buf.readVarInt();
            int by = buf.readVarInt();
            int bz = buf.readVarInt();
            int state = buf.readByte();
            if (state < 0 || state >= STATES.length) {
                throw new DecoderException("RANCraft backhaul links: unknown link state " + state);
            }
            float rsl = buf.readFloat();
            float margin = buf.readFloat();
            links.add(new Link(ax, ay, az, bx, by, bz, STATES[state], rsl, margin));
        }
        return new BackhaulLinksPayload(version, links);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
