package dev.rancraft.net;

import dev.rancraft.RanCraft;
import dev.rancraft.rf.CellParams;
import dev.rancraft.rf.CellSample;
import dev.rancraft.rf.DriveTestLog;
import dev.rancraft.rf.ReceiverState;
import dev.rancraft.rf.ServiceLevel;
import dev.rancraft.rf.SignalSample;
import dev.rancraft.rf.SinrCalculator;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * Server to client signal readout.
 *
 * <p>Only the top {@value #MAX_CELLS} cells are serialized; the HUD never needs more and the packet
 * goes out at 1 Hz per player. The serving cell is always among them, even when hysteresis holds it
 * below the fourth strongest (see {@link #topCells}).
 *
 * <p><b>The client computes nothing.</b> Gain, SINR, service level and the choice of serving cell
 * are all decided server-side and shipped as values. Everything here is a number to render.
 *
 * <p>Sent with every evaluation the server runs for a player: one carrying a signal device (the Field
 * Test Meter, whose HUD draws only while it is held) in a hand or the hotbar, or wearing an RF Lens
 * that shows the drive-test trail or link rays. All of them come from the same single evaluation per
 * interval. Every evaluation is sent, because the client's drive-test log reads handovers off
 * consecutive samples and must not miss one; see {@code world.SignalTicker.sendsSample}. It is sent
 * by the ticker, never by a device.
 *
 * @param version               wire version, {@value #VERSION} since RF Vision Step 3a.
 * @param servingConflictNote   one pre-rendered PCI warning line for the serving cell, or empty.
 * @param rxX                   <b>Step 3a.</b> Where the server evaluated: the receiver's eye
 *                              position at the moment of measurement. The drive-test trail is drawn
 *                              here rather than where the client happens to be when the packet
 *                              lands, which lags by latency times walking speed. NaN on the client's
 *                              "nothing received yet" placeholder ({@link #empty(long, int)}).
 * @param cellsHeard            <b>Step 3a.</b> How many cells the evaluation heard in total, which
 *                              can exceed the {@value #MAX_CELLS} carried in {@link #cells()}. The
 *                              drive-test log's {@code cells} column needs the real count: capped at
 *                              four it would hide exactly the pilot-pollution case it exists for.
 */
public record SignalSamplePayload(
        int version,
        List<CellSample> cells,
        long timestampTick,
        long servingCellId,
        double sinrDb,
        double interferenceDbm,
        double noiseDbm,
        ServiceLevel serviceLevel,
        int handoverCount,
        int coChannelCount,
        String servingBandId,
        double servingFrequencyMhz,
        double metersPerBlock,
        String servingConflictNote,
        // ---- RF Vision Step 3a (VERSION 3) ----
        double rxX,
        double rxY,
        double rxZ,
        int cellsHeard
) implements CustomPacketPayload {

    public static final int MAX_CELLS = 4;

    /**
     * Wire version, written as the first field.
     *
     * <ul>
     *   <li><b>2</b> -- Phase 2 added SINR, the serving cell id and the pattern figures.
     *   <li><b>3</b> -- RF Vision Step 3a appended the evaluation point ({@code rxX, rxY, rxZ}) and
     *       {@code cellsHeard}, for the drive-test trail.
     * </ul>
     */
    public static final int VERSION = 3;

    private static final int MAX_NOTE_LENGTH = 160;

    /**
     * Wire cap on band ids. Ids are clamped to it on write ({@link WireText#clamp}): the encoder
     * throws on a longer string, which would disconnect the meter holder on every sample.
     */
    private static final int MAX_BAND_ID = CellParams.MAX_BAND_ID_LENGTH;

    public static final CustomPacketPayload.Type<SignalSamplePayload> TYPE =
            new CustomPacketPayload.Type<>(
                    ResourceLocation.fromNamespaceAndPath(RanCraft.MOD_ID, "signal_sample"));

    public static final StreamCodec<FriendlyByteBuf, SignalSamplePayload> STREAM_CODEC =
            StreamCodec.of(SignalSamplePayload::write, SignalSamplePayload::read);

    /**
     * @param rxX the receiver position the sample was evaluated at (the eye), so the drive-test
     *            trail can mark the exact point measured.
     */
    public static SignalSamplePayload of(
            SignalSample sample,
            String bandId,
            double frequencyMhz,
            double metersPerBlock,
            String conflictNote,
            double rxX, double rxY, double rxZ) {

        List<CellSample> cells = sample.cells();
        return new SignalSamplePayload(
                VERSION,
                topCells(cells, sample.servingCellId(), MAX_CELLS),
                sample.timestampTick(),
                sample.servingCellId(),
                sample.sinrDb(),
                sample.interferenceDbm(),
                sample.noiseDbm(),
                sample.serviceLevel(),
                sample.handoverCount(),
                sample.coChannelCount(),
                bandId,
                frequencyMhz,
                metersPerBlock,
                conflictNote == null ? "" : conflictNote,
                rxX, rxY, rxZ,
                cells.size());
    }

    /** An out-of-service sample evaluated at this point. */
    public static SignalSamplePayload empty(long tick, int handoverCount, double rxX, double rxY, double rxZ) {
        return new SignalSamplePayload(
                VERSION, List.of(), tick, ReceiverState.NO_CELL,
                Double.NEGATIVE_INFINITY, Double.NEGATIVE_INFINITY, 0.0,
                ServiceLevel.NONE, handoverCount, 0, "", 0.0, 1.0, "",
                rxX, rxY, rxZ, 0);
    }

    /**
     * The client's "nothing received yet" placeholder. It has no evaluation point (NaN), so it is
     * never mistaken for a measurement; see {@link #hasReceiverPosition()}.
     */
    public static SignalSamplePayload empty(long tick, int handoverCount) {
        return empty(tick, handoverCount, Double.NaN, Double.NaN, Double.NaN);
    }

    /**
     * The strongest {@code max} cells, strongest first, with one exception: when hysteresis holds the
     * serving cell below the cut, it takes the last kept slot.
     *
     * <p>Before RF Vision Step 3a this was a plain cut, so a receiver camped on its fifth-strongest
     * cell -- easy beside a column of stacked co-channel masts, all within the handover hysteresis of
     * each other -- got a payload whose serving cell was missing. The HUD then drew NO SERVICE over a
     * perfectly real (if interference-drowned) serving link, hiding the very diagnosis it exists for,
     * and the drive-test log would have recorded an outage that never happened. The lens's link rays
     * already used this rule ({@code SignalTicker.chooseLinks}); the HUD now agrees with them.
     *
     * <p>The list stays strongest-first, because the serving cell is weaker than everything it now
     * follows.
     */
    static List<CellSample> topCells(List<CellSample> cells, long servingCellId, int max) {
        if (cells.size() <= max) {
            return cells;
        }
        List<CellSample> top = new ArrayList<>(cells.subList(0, max));
        for (int i = max; i < cells.size(); i++) {
            if (cells.get(i).cellId() == servingCellId) {
                top.set(max - 1, cells.get(i));
                break;
            }
        }
        return List.copyOf(top);
    }

    /** False only on the client's placeholder: every server sample carries its evaluation point. */
    public boolean hasReceiverPosition() {
        return Double.isFinite(rxX) && Double.isFinite(rxY) && Double.isFinite(rxZ);
    }

    /**
     * This sample as one drive-test log entry: every field copied from what the server sent, nothing
     * derived. RSRP and PCI are the serving cell's; with no serving cell the sample is logged as
     * NO SERVICE with no RSRP, exactly as the HUD would draw it.
     */
    public DriveTestLog.Sample toDriveTestSample() {
        CellSample serving = serving();
        boolean hasServing = serving != null;
        return new DriveTestLog.Sample(
                timestampTick,
                rxX, rxY, rxZ,
                hasServing ? servingCellId : ReceiverState.NO_CELL,
                hasServing ? serving.pci() : 0,
                hasServing ? servingBandId : "",
                hasServing ? serving.rsrpDbm() : Double.NaN,
                sinrDb,
                serviceLevel,
                handoverCount,
                cellsHeard);
    }

    /** The cell the server chose, which hysteresis may hold below the strongest. */
    public CellSample serving() {
        for (CellSample cell : cells) {
            if (cell.cellId() == servingCellId) {
                return cell;
            }
        }
        return null;
    }

    public boolean hasServing() {
        return serving() != null;
    }

    /** SINR clamped to a sane display range; the raw value stays on {@link #sinrDb()}. */
    public double displaySinrDb() {
        return Math.clamp(sinrDb, SinrCalculator.DISPLAY_MIN_DB, SinrCalculator.DISPLAY_MAX_DB);
    }

    private static void write(FriendlyByteBuf buf, SignalSamplePayload payload) {
        buf.writeVarInt(payload.version);
        buf.writeVarInt(payload.cells.size());
        for (CellSample cell : payload.cells) {
            buf.writeLong(cell.cellId());
            buf.writeVarInt(cell.x());
            buf.writeVarInt(cell.y());
            buf.writeVarInt(cell.z());
            buf.writeDouble(cell.rsrpDbm());
            buf.writeDouble(cell.distanceBlocks());
            buf.writeDouble(cell.pathLossDb());
            buf.writeDouble(cell.obstructionDb());
            buf.writeUtf(WireText.clamp(cell.bandId(), MAX_BAND_ID), MAX_BAND_ID);
            buf.writeVarInt(cell.pci());
            buf.writeDouble(cell.effectiveGainDbi());
            buf.writeDouble(cell.azimuthOffsetDeg());
            buf.writeDouble(cell.elevationOffsetDeg());
        }
        buf.writeVarLong(payload.timestampTick);
        buf.writeLong(payload.servingCellId);
        buf.writeDouble(payload.sinrDb);
        buf.writeDouble(payload.interferenceDbm);
        buf.writeDouble(payload.noiseDbm);
        buf.writeByte(payload.serviceLevel.ordinal());
        buf.writeVarInt(payload.handoverCount);
        buf.writeVarInt(payload.coChannelCount);
        buf.writeUtf(WireText.clamp(payload.servingBandId, MAX_BAND_ID), MAX_BAND_ID);
        buf.writeDouble(payload.servingFrequencyMhz);
        buf.writeDouble(payload.metersPerBlock);
        buf.writeUtf(payload.servingConflictNote, MAX_NOTE_LENGTH);
        // ---- VERSION 3 ----
        buf.writeDouble(payload.rxX);
        buf.writeDouble(payload.rxY);
        buf.writeDouble(payload.rxZ);
        buf.writeVarInt(payload.cellsHeard);
    }

    private static SignalSamplePayload read(FriendlyByteBuf buf) {
        int version = buf.readVarInt();
        int count = buf.readVarInt();
        List<CellSample> cells = new ArrayList<>(Math.min(count, MAX_CELLS));
        for (int i = 0; i < count; i++) {
            cells.add(new CellSample(
                    buf.readLong(),
                    buf.readVarInt(), buf.readVarInt(), buf.readVarInt(),
                    buf.readDouble(), buf.readDouble(), buf.readDouble(), buf.readDouble(),
                    buf.readUtf(MAX_BAND_ID), buf.readVarInt(),
                    buf.readDouble(), buf.readDouble(), buf.readDouble()));
        }
        long tick = buf.readVarLong();
        long servingCellId = buf.readLong();
        double sinrDb = buf.readDouble();
        double interferenceDbm = buf.readDouble();
        double noiseDbm = buf.readDouble();
        ServiceLevel serviceLevel = serviceLevelOf(buf.readByte());
        int handoverCount = buf.readVarInt();
        int coChannelCount = buf.readVarInt();
        String bandId = buf.readUtf(MAX_BAND_ID);
        double frequencyMhz = buf.readDouble();
        double metersPerBlock = buf.readDouble();
        String note = buf.readUtf(MAX_NOTE_LENGTH);
        double rxX = buf.readDouble();
        double rxY = buf.readDouble();
        double rxZ = buf.readDouble();
        int cellsHeard = buf.readVarInt();

        return new SignalSamplePayload(
                version, cells, tick, servingCellId, sinrDb, interferenceDbm, noiseDbm,
                serviceLevel, handoverCount, coChannelCount, bandId, frequencyMhz, metersPerBlock, note,
                rxX, rxY, rxZ, cellsHeard);
    }

    private static ServiceLevel serviceLevelOf(byte ordinal) {
        ServiceLevel[] values = ServiceLevel.values();
        return ordinal >= 0 && ordinal < values.length ? values[ordinal] : ServiceLevel.NONE;
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
