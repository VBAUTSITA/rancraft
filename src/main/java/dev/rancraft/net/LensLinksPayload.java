package dev.rancraft.net;

import dev.rancraft.RanCraft;
import dev.rancraft.rf.CellParams;
import dev.rancraft.rf.CellSample;
import dev.rancraft.rf.LinkTracer;
import io.netty.handler.codec.DecoderException;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * Server to client: the link rays for an RF Lens wearer (Step 2a).
 *
 * <p>One entry per cell the server heard at the wearer's head on its last sample, strongest first.
 * Each carries where the antenna is, what the server measured over that link, and where along it
 * the terrain took its dB, as {@code (t, cumulativeDb)} breakpoints from {@link LinkTracer}.
 *
 * <p><b>The client computes nothing from this.</b> It draws a line between two points it already
 * knows (the antenna and its own eye) and colours it by the server's cumulative loss at each
 * breakpoint. It never marches a ray, never sums an obstruction and never picks a serving cell --
 * obstruction is a measurement, and a client that could compute it could see through terrain it
 * has not loaded.
 *
 * <p>Sent at the sample rate (1 Hz per wearer), from the same evaluation as
 * {@link SignalSamplePayload}. Small: {@value #MAX_LINKS} links with every breakpoint slot used is
 * under 4 KB, and the default of 8 links at 24 breakpoints is about half that.
 *
 * @param timestampTick game time of the evaluation the links came from.
 * @param links         at most {@value #MAX_LINKS}, strongest first.
 */
public record LensLinksPayload(long timestampTick, List<Link> links) implements CustomPacketPayload {

    /** Wire cap on links. {@code lensMaxLinks} in the config is bounded by this. */
    public static final int MAX_LINKS = 12;

    /** Wire cap on breakpoints per link. {@link LinkTracer#DEFAULT_MAX_BREAKPOINTS} sits under it. */
    public static final int MAX_BREAKPOINTS = 32;

    private static final int MAX_BAND_ID = CellParams.MAX_BAND_ID_LENGTH;

    public static final CustomPacketPayload.Type<LensLinksPayload> TYPE =
            new CustomPacketPayload.Type<>(
                    ResourceLocation.fromNamespaceAndPath(RanCraft.MOD_ID, "lens_links"));

    public static final StreamCodec<FriendlyByteBuf, LensLinksPayload> STREAM_CODEC =
            StreamCodec.of(LensLinksPayload::write, LensLinksPayload::read);

    /**
     * One antenna-to-wearer link.
     *
     * <p>The two breakpoint arrays are parallel: breakpoint {@code k} is
     * {@code (breakpointT[k], breakpointDb[k])}. Both are non-decreasing, and when non-empty the
     * last {@code breakpointDb} is {@code obstructionDb}. Empty arrays mean a clear line of sight.
     * The arrays are shared, not copied: treat them as read-only.
     *
     * @param x             radiating point, block coordinates ({@code CellParams.x()} etc., which
     *                      is the block <em>above</em> the antenna). Draw from its centre.
     * @param bandId        clamped to {@link CellParams#MAX_BAND_ID_LENGTH} chars, the wire cap.
     *                      An over-long id would make the encoder throw and disconnect the wearer;
     *                      cut short, it only matches no filter and takes the default hue.
     * @param rsrpDbm       what the server measured for this cell at the wearer's head.
     * @param serving       true for the cell the server chose to serve the wearer, which handover
     *                      hysteresis can hold below the strongest.
     * @param obstructionDb total terrain loss on the link, already scaled by the band's
     *                      penetration factor.
     * @param breakpointT   position along the link, 0 = antenna, 1 = wearer's eye.
     * @param breakpointDb  loss accumulated from the antenna up to and including that position.
     */
    public record Link(long cellId, int x, int y, int z,
                       String bandId, int pci, float rsrpDbm, boolean serving,
                       float obstructionDb, float[] breakpointT, float[] breakpointDb) {

        public Link {
            bandId = WireText.clamp(bandId, MAX_BAND_ID);
            if (breakpointT.length != breakpointDb.length) {
                throw new IllegalArgumentException("breakpoint arrays differ in length: "
                        + breakpointT.length + " vs " + breakpointDb.length);
            }
            if (breakpointT.length > MAX_BREAKPOINTS) {
                throw new IllegalArgumentException(
                        breakpointT.length + " breakpoints exceed the cap of " + MAX_BREAKPOINTS);
            }
        }

        /**
         * Builds a link from one evaluated cell and its trace.
         *
         * <p>Floats on the wire, doubles in the engine: a float carries a dB figure to far better
         * than the 0.1 dB anyone reads off a line colour.
         */
        public static Link of(CellSample cell, boolean serving, LinkTracer.Trace trace) {
            List<LinkTracer.Breakpoint> breakpoints = trace.breakpoints();
            int count = Math.min(breakpoints.size(), MAX_BREAKPOINTS);
            float[] t = new float[count];
            float[] db = new float[count];
            // Keep the tail if a caller ever traces with a larger cap: the last breakpoint is the
            // one that must equal the total.
            int offset = breakpoints.size() - count;
            for (int k = 0; k < count; k++) {
                LinkTracer.Breakpoint breakpoint = breakpoints.get(offset + k);
                t[k] = (float) breakpoint.t();
                db[k] = (float) breakpoint.cumulativeDb();
            }
            return new Link(
                    cell.cellId(), cell.x(), cell.y(), cell.z(),
                    cell.bandId(), cell.pci(), (float) cell.rsrpDbm(), serving,
                    (float) trace.obstructionDb(), t, db);
        }

        public int breakpointCount() {
            return breakpointT.length;
        }

        /** Centre of the radiating voxel, where the server's ray started. */
        public double centerX() {
            return x + 0.5;
        }

        public double centerY() {
            return y + 0.5;
        }

        public double centerZ() {
            return z + 0.5;
        }
    }

    public LensLinksPayload {
        links = List.copyOf(links);
        if (links.size() > MAX_LINKS) {
            throw new IllegalArgumentException(links.size() + " links exceed the cap of " + MAX_LINKS);
        }
    }

    /** No cell heard, or none on the lens's band: the client stops drawing rays. */
    public static LensLinksPayload empty(long timestampTick) {
        return new LensLinksPayload(timestampTick, List.of());
    }

    /** The serving link, or {@code null} when it is not among these (filtered out, or no service). */
    public Link serving() {
        for (Link link : links) {
            if (link.serving()) {
                return link;
            }
        }
        return null;
    }

    private static void write(FriendlyByteBuf buf, LensLinksPayload payload) {
        buf.writeVarLong(payload.timestampTick);
        buf.writeVarInt(payload.links.size());
        for (Link link : payload.links) {
            buf.writeLong(link.cellId());
            buf.writeVarInt(link.x());
            buf.writeVarInt(link.y());
            buf.writeVarInt(link.z());
            buf.writeUtf(link.bandId(), MAX_BAND_ID);
            buf.writeVarInt(link.pci());
            buf.writeFloat(link.rsrpDbm());
            buf.writeBoolean(link.serving());
            buf.writeFloat(link.obstructionDb());
            int count = link.breakpointCount();
            buf.writeVarInt(count);
            for (int k = 0; k < count; k++) {
                buf.writeFloat(link.breakpointT()[k]);
                buf.writeFloat(link.breakpointDb()[k]);
            }
        }
    }

    /**
     * Every count is checked against its cap before anything is allocated for it. An out-of-range
     * count is rejected rather than clamped: the entries past the cap would still be on the wire,
     * and reading on after skipping them would desynchronise every field that follows.
     */
    private static LensLinksPayload read(FriendlyByteBuf buf) {
        long tick = buf.readVarLong();
        int linkCount = checkedCount(buf.readVarInt(), MAX_LINKS, "link");
        List<Link> links = new ArrayList<>(linkCount);
        for (int i = 0; i < linkCount; i++) {
            long cellId = buf.readLong();
            int x = buf.readVarInt();
            int y = buf.readVarInt();
            int z = buf.readVarInt();
            String bandId = buf.readUtf(MAX_BAND_ID);
            int pci = buf.readVarInt();
            float rsrpDbm = buf.readFloat();
            boolean serving = buf.readBoolean();
            float obstructionDb = buf.readFloat();
            int count = checkedCount(buf.readVarInt(), MAX_BREAKPOINTS, "breakpoint");
            float[] t = new float[count];
            float[] db = new float[count];
            for (int k = 0; k < count; k++) {
                t[k] = buf.readFloat();
                db[k] = buf.readFloat();
            }
            links.add(new Link(cellId, x, y, z, bandId, pci, rsrpDbm, serving, obstructionDb, t, db));
        }
        return new LensLinksPayload(tick, links);
    }

    private static int checkedCount(int count, int max, String what) {
        if (count < 0 || count > max) {
            throw new DecoderException("RANCraft lens links: " + count + " " + what + "s (max " + max + ")");
        }
        return count;
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
