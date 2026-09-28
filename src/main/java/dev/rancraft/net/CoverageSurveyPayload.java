package dev.rancraft.net;

import dev.rancraft.RanCraft;
import dev.rancraft.rf.CellParams;
import dev.rancraft.rf.CoverageSurvey;
import dev.rancraft.rf.ServiceLevel;
import dev.rancraft.rf.SurfaceProbe;
import io.netty.handler.codec.DecoderException;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * Server to client: one finished best-server survey around an RF Lens wearer (Step 2b).
 *
 * <p>A square grid of {@code size x size} ground points, {@code step} blocks apart. Point (i, j)
 * stands for the {@code step x step} tile {@code [blockX(i), blockX(i) + step) x [blockZ(j),
 * blockZ(j) + step)}, whose minimum corner is {@code (originX + i*step, originZ + j*step)}, and
 * was measured at that tile's centre: the ground read at its middle column, the receiver over its
 * geometric centre ({@link CoverageSurvey.Spec#sampleX}, {@link CoverageSurvey.Spec#receiverX}).
 * Arrays are indexed {@code j*size + i}, exactly as in {@link CoverageSurvey.Spec#index}.
 *
 * <p><b>Every value is server-computed.</b> The client paints tiles from {@code cellIndex} and
 * {@code level}; it never evaluates a point, and it cannot, because it does not have the terrain
 * the server marched through. What the plot means -- stateless best-server with no hysteresis,
 * band-filtered surveys leaving out other-band interference -- is documented on
 * {@link CoverageSurvey}.
 *
 * <p>Sent once per finished survey, not per tick. The default 48 x 48 grid is about 15 KB; the
 * largest allowed ({@value #MAX_SIZE} x {@value #MAX_SIZE}) is about 120 KB, well inside the 1 MiB
 * custom payload limit.
 *
 * @param bandFilter the band this survey was restricted to, or {@code ""} for all. The renderer
 *                   draws it only while the lens's filter still matches. Clamped, like every
 *                   band id here, to {@link CellParams#MAX_BAND_ID_LENGTH} chars, the wire cap:
 *                   longer, the encoder would throw and disconnect the wearer.
 * @param palette    every cell that serves at least one point, at most {@value #MAX_PALETTE}.
 * @param surfaceY   the y each point stood on (feet level), or {@link SurfaceProbe#UNLOADED}.
 * @param cellIndex  index into {@code palette} of the serving cell, or {@link CoverageSurvey#NO_CELL}.
 * @param level      {@link ServiceLevel#ordinal()}, or {@link CoverageSurvey#UNSURVEYED}.
 */
public record CoverageSurveyPayload(int originX, int originZ, int step, int size, String bandFilter,
                                    List<PaletteEntry> palette, int[] surfaceY, int[] cellIndex, byte[] level)
        implements CustomPacketPayload {

    /** Largest grid side on the wire. {@code coverageGridSize} in the config is bounded by this. */
    public static final int MAX_SIZE = 128;

    /** Most distinct serving cells one survey can name. */
    public static final int MAX_PALETTE = 256;

    /**
     * Largest step accepted off the wire. The config allows 16; this only exists so a malformed
     * packet cannot describe a grid that overflows an int of block coordinates.
     */
    public static final int MAX_STEP = 64;

    private static final int MAX_BAND_ID = CellParams.MAX_BAND_ID_LENGTH;

    public static final CustomPacketPayload.Type<CoverageSurveyPayload> TYPE =
            new CustomPacketPayload.Type<>(
                    ResourceLocation.fromNamespaceAndPath(RanCraft.MOD_ID, "coverage_survey"));

    public static final StreamCodec<FriendlyByteBuf, CoverageSurveyPayload> STREAM_CODEC =
            StreamCodec.of(CoverageSurveyPayload::write, CoverageSurveyPayload::read);

    /**
     * What the renderer needs to know about one serving cell: its identity for a stable colour and
     * its position for anything drawn at the site. Pattern figures are left out -- the lens already
     * has them from the antenna's own block-entity sync.
     *
     * <p>{@code bandId} is clamped to the wire cap. It is display, colour and filter only, so a
     * cut-short id costs a default hue, never a wrong measurement.
     */
    public record PaletteEntry(long cellId, int pci, String bandId, int x, int y, int z) {

        public PaletteEntry {
            bandId = WireText.clamp(bandId, MAX_BAND_ID);
        }

        public static PaletteEntry of(CellParams cell) {
            return new PaletteEntry(cell.cellId(), cell.pci(), cell.bandId(), cell.x(), cell.y(), cell.z());
        }
    }

    /**
     * Validates everything the renderer will index with, so a payload that exists is safe to draw:
     * the grid shape, the array lengths, and that every {@code cellIndex} and {@code level} is
     * either its sentinel or in range. The arrays are shared, not copied; treat them as read-only.
     *
     * @throws IllegalArgumentException on any violation.
     */
    public CoverageSurveyPayload {
        if (size < 1 || size > MAX_SIZE) {
            throw new IllegalArgumentException("size must be in [1, " + MAX_SIZE + "], was " + size);
        }
        if (step < 1 || step > MAX_STEP) {
            throw new IllegalArgumentException("step must be in [1, " + MAX_STEP + "], was " + step);
        }
        // A lens filter is an unbounded item-component string. CoverageSurveyor never surveys for a
        // filter that names no loaded band, so this only guards the encoder; a clamped filter
        // matches no lens and is simply not drawn.
        bandFilter = WireText.clamp(bandFilter, MAX_BAND_ID);
        palette = List.copyOf(palette);
        if (palette.size() > MAX_PALETTE) {
            throw new IllegalArgumentException(palette.size() + " palette entries exceed " + MAX_PALETTE);
        }
        int points = size * size;
        if (surfaceY.length != points || cellIndex.length != points || level.length != points) {
            throw new IllegalArgumentException("arrays must all hold " + points + " points");
        }
        int levels = ServiceLevel.values().length;
        for (int k = 0; k < points; k++) {
            int cell = cellIndex[k];
            if (cell != CoverageSurvey.NO_CELL && (cell < 0 || cell >= palette.size())) {
                throw new IllegalArgumentException("cellIndex[" + k + "] = " + cell + " is outside the palette");
            }
            byte value = level[k];
            if (value != CoverageSurvey.UNSURVEYED && (value < 0 || value >= levels)) {
                throw new IllegalArgumentException("level[" + k + "] = " + value + " is not a service level");
            }
        }
    }

    /**
     * Packs a finished survey for the wire.
     *
     * <p>The survey itself has no palette cap. In the practically unreachable case that more than
     * {@value #MAX_PALETTE} distinct cells serve somewhere on the grid, the first
     * {@value #MAX_PALETTE} (in first-seen order) are kept and the points served by the rest are
     * sent as {@link CoverageSurvey#UNSURVEYED} -- a hole in the plot, which is honest, rather than
     * a wrong colour, which is not.
     *
     * @throws IllegalArgumentException if the grid is larger than {@value #MAX_SIZE} per side.
     */
    public static CoverageSurveyPayload of(CoverageSurvey.Result result) {
        CoverageSurvey.Spec spec = result.spec();
        List<CellParams> cells = result.palette();

        int kept = Math.min(cells.size(), MAX_PALETTE);
        List<PaletteEntry> palette = new ArrayList<>(kept);
        for (int p = 0; p < kept; p++) {
            palette.add(PaletteEntry.of(cells.get(p)));
        }

        int[] cellIndex = result.cellIndex();
        byte[] level = result.level();
        if (cells.size() > MAX_PALETTE) {
            cellIndex = cellIndex.clone();
            level = level.clone();
            int dropped = 0;
            for (int k = 0; k < cellIndex.length; k++) {
                if (cellIndex[k] >= MAX_PALETTE) {
                    cellIndex[k] = CoverageSurvey.NO_CELL;
                    level[k] = CoverageSurvey.UNSURVEYED;
                    dropped++;
                }
            }
            RanCraft.LOGGER.debug("RANCraft coverage survey named {} serving cells; sent the first {}, "
                    + "{} point(s) served by the rest went as unsurveyed", cells.size(), MAX_PALETTE, dropped);
        }

        return new CoverageSurveyPayload(
                spec.originX(), spec.originZ(), spec.step(), spec.size(), spec.bandFilter(),
                palette, result.surfaceY(), cellIndex, level);
    }

    // ---- read helpers for the renderer ------------------------------------------------------

    public int pointCount() {
        return size * size;
    }

    /** Point index of (i, j). */
    public int index(int i, int j) {
        return j * size + i;
    }

    /** Block x of column {@code i}; the tile runs from here to {@code blockX(i) + step}. */
    public int blockX(int i) {
        return originX + i * step;
    }

    /** Block z of row {@code j}. */
    public int blockZ(int j) {
        return originZ + j * step;
    }

    /** Side length of the painted square in blocks. */
    public int spanBlocks() {
        return size * step;
    }

    public boolean isSurveyed(int index) {
        return level[index] != CoverageSurvey.UNSURVEYED;
    }

    /** The serving cell at a point, or {@code null} when nothing serves it or it was not surveyed. */
    public PaletteEntry servingAt(int index) {
        int cell = cellIndex[index];
        return cell == CoverageSurvey.NO_CELL ? null : palette.get(cell);
    }

    /** The service level at a point, or {@code null} when it was not surveyed. */
    public ServiceLevel levelAt(int index) {
        byte value = level[index];
        return value == CoverageSurvey.UNSURVEYED ? null : ServiceLevel.values()[value];
    }

    // ---- wire -------------------------------------------------------------------------------

    /**
     * {@code surfaceY} goes as a fixed 4-byte int because it can be {@link SurfaceProbe#UNLOADED}
     * ({@code Integer.MIN_VALUE}), which is the worst case for a varint. {@code cellIndex} goes as a
     * varint of {@code cellIndex + 1}, so the common {@link CoverageSurvey#NO_CELL} is one byte
     * rather than the five a negative varint costs. {@code level} goes as raw bytes.
     */
    private static void write(FriendlyByteBuf buf, CoverageSurveyPayload payload) {
        buf.writeVarInt(payload.originX);
        buf.writeVarInt(payload.originZ);
        buf.writeVarInt(payload.step);
        buf.writeVarInt(payload.size);
        buf.writeUtf(payload.bandFilter, MAX_BAND_ID);

        buf.writeVarInt(payload.palette.size());
        for (PaletteEntry entry : payload.palette) {
            buf.writeLong(entry.cellId());
            buf.writeVarInt(entry.pci());
            buf.writeUtf(entry.bandId(), MAX_BAND_ID);
            buf.writeVarInt(entry.x());
            buf.writeVarInt(entry.y());
            buf.writeVarInt(entry.z());
        }

        for (int y : payload.surfaceY) {
            buf.writeInt(y);
        }
        for (int cell : payload.cellIndex) {
            buf.writeVarInt(cell + 1);
        }
        buf.writeBytes(payload.level);
    }

    /**
     * The grid side and palette count are checked before anything is sized from them, so a
     * malformed packet cannot make the client allocate more than {@value #MAX_SIZE} squared points.
     * Anything else malformed is caught by the constructor's validation. Either way the packet is
     * rejected (and the connection dropped), never half-applied.
     */
    private static CoverageSurveyPayload read(FriendlyByteBuf buf) {
        int originX = buf.readVarInt();
        int originZ = buf.readVarInt();
        int step = buf.readVarInt();
        int size = buf.readVarInt();
        if (size < 1 || size > MAX_SIZE) {
            throw new DecoderException("RANCraft coverage survey: grid size " + size + " (max " + MAX_SIZE + ")");
        }
        String bandFilter = buf.readUtf(MAX_BAND_ID);

        int paletteCount = buf.readVarInt();
        if (paletteCount < 0 || paletteCount > MAX_PALETTE) {
            throw new DecoderException(
                    "RANCraft coverage survey: " + paletteCount + " palette entries (max " + MAX_PALETTE + ")");
        }
        List<PaletteEntry> palette = new ArrayList<>(paletteCount);
        for (int p = 0; p < paletteCount; p++) {
            palette.add(new PaletteEntry(
                    buf.readLong(), buf.readVarInt(), buf.readUtf(MAX_BAND_ID),
                    buf.readVarInt(), buf.readVarInt(), buf.readVarInt()));
        }

        int points = size * size;
        int[] surfaceY = new int[points];
        for (int k = 0; k < points; k++) {
            surfaceY[k] = buf.readInt();
        }
        int[] cellIndex = new int[points];
        for (int k = 0; k < points; k++) {
            cellIndex[k] = buf.readVarInt() - 1;
        }
        byte[] level = new byte[points];
        buf.readBytes(level);

        try {
            return new CoverageSurveyPayload(
                    originX, originZ, step, size, bandFilter, palette, surfaceY, cellIndex, level);
        } catch (IllegalArgumentException malformed) {
            throw new DecoderException("RANCraft coverage survey: " + malformed.getMessage(), malformed);
        }
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
