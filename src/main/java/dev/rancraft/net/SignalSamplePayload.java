package dev.rancraft.net;

import dev.rancraft.RanCraft;
import dev.rancraft.rf.CellParams;
import dev.rancraft.rf.CellSample;
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
 * goes out at 1 Hz per player.
 *
 * <p><b>The client computes nothing.</b> Gain, SINR, service level and the choice of serving cell
 * are all decided server-side and shipped as values. Everything here is a number to render.
 *
 * @param version               protocol version, {@value #VERSION} in Phase 2.
 * @param servingConflictNote   one pre-rendered PCI warning line for the serving cell, or empty.
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
        String servingConflictNote
) implements CustomPacketPayload {

    public static final int MAX_CELLS = 4;

    /** Bumped from 1 when Phase 2 added SINR, the serving cell id and the pattern figures. */
    public static final int VERSION = 2;

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

    public static SignalSamplePayload of(
            SignalSample sample,
            String bandId,
            double frequencyMhz,
            double metersPerBlock,
            String conflictNote) {

        List<CellSample> cells = sample.cells();
        return new SignalSamplePayload(
                VERSION,
                cells.size() <= MAX_CELLS ? cells : List.copyOf(cells.subList(0, MAX_CELLS)),
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
                conflictNote == null ? "" : conflictNote);
    }

    public static SignalSamplePayload empty(long tick, int handoverCount) {
        return new SignalSamplePayload(
                VERSION, List.of(), tick, ReceiverState.NO_CELL,
                Double.NEGATIVE_INFINITY, Double.NEGATIVE_INFINITY, 0.0,
                ServiceLevel.NONE, handoverCount, 0, "", 0.0, 1.0, "");
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

        return new SignalSamplePayload(
                version, cells, tick, servingCellId, sinrDb, interferenceDbm, noiseDbm,
                serviceLevel, handoverCount, coChannelCount, bandId, frequencyMhz, metersPerBlock, note);
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
