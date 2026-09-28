package dev.rancraft.net;

import dev.rancraft.RanCraft;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * Server to client: open the antenna configuration screen for this antenna.
 *
 * <p>Carries the antenna's current settings plus the list of loaded band ids, so the screen never
 * has to guess what exists, and the PCI conflicts already detected for this cell, pre-rendered as
 * text by {@code PciConflict.describe()}.
 *
 * <p>The client renders these values and sends back edits. It is never trusted on the way back --
 * see {@link UpdateCellParamsPayload}.
 */
public record OpenAntennaConfigPayload(
        BlockPos pos,
        String bandId,
        double txPowerDbm,
        double gainDbi,
        double azimuthDeg,
        double tiltDeg,
        double hBeamwidthDeg,
        double vBeamwidthDeg,
        int pci,
        List<String> availableBandIds,
        List<String> conflicts
) implements CustomPacketPayload {

    private static final int MAX_BANDS = 64;
    private static final int MAX_CONFLICTS = 16;
    private static final int MAX_STRING = 200;

    public static final CustomPacketPayload.Type<OpenAntennaConfigPayload> TYPE =
            new CustomPacketPayload.Type<>(
                    ResourceLocation.fromNamespaceAndPath(RanCraft.MOD_ID, "open_antenna_config"));

    public static final StreamCodec<FriendlyByteBuf, OpenAntennaConfigPayload> STREAM_CODEC =
            StreamCodec.of(OpenAntennaConfigPayload::write, OpenAntennaConfigPayload::read);

    private static void write(FriendlyByteBuf buf, OpenAntennaConfigPayload payload) {
        buf.writeBlockPos(payload.pos);
        buf.writeUtf(payload.bandId, MAX_STRING);
        buf.writeDouble(payload.txPowerDbm);
        buf.writeDouble(payload.gainDbi);
        buf.writeDouble(payload.azimuthDeg);
        buf.writeDouble(payload.tiltDeg);
        buf.writeDouble(payload.hBeamwidthDeg);
        buf.writeDouble(payload.vBeamwidthDeg);
        buf.writeVarInt(payload.pci);

        buf.writeVarInt(Math.min(payload.availableBandIds.size(), MAX_BANDS));
        for (String id : payload.availableBandIds.subList(0, Math.min(payload.availableBandIds.size(), MAX_BANDS))) {
            buf.writeUtf(id, MAX_STRING);
        }

        buf.writeVarInt(Math.min(payload.conflicts.size(), MAX_CONFLICTS));
        for (String note : payload.conflicts.subList(0, Math.min(payload.conflicts.size(), MAX_CONFLICTS))) {
            buf.writeUtf(note, MAX_STRING);
        }
    }

    private static OpenAntennaConfigPayload read(FriendlyByteBuf buf) {
        BlockPos pos = buf.readBlockPos();
        String bandId = buf.readUtf(MAX_STRING);
        double txPowerDbm = buf.readDouble();
        double gainDbi = buf.readDouble();
        double azimuthDeg = buf.readDouble();
        double tiltDeg = buf.readDouble();
        double hBeamwidthDeg = buf.readDouble();
        double vBeamwidthDeg = buf.readDouble();
        int pci = buf.readVarInt();

        int bandCount = Math.min(buf.readVarInt(), MAX_BANDS);
        List<String> bands = new ArrayList<>(bandCount);
        for (int i = 0; i < bandCount; i++) {
            bands.add(buf.readUtf(MAX_STRING));
        }

        int conflictCount = Math.min(buf.readVarInt(), MAX_CONFLICTS);
        List<String> conflicts = new ArrayList<>(conflictCount);
        for (int i = 0; i < conflictCount; i++) {
            conflicts.add(buf.readUtf(MAX_STRING));
        }

        return new OpenAntennaConfigPayload(pos, bandId, txPowerDbm, gainDbi,
                azimuthDeg, tiltDeg, hBeamwidthDeg, vBeamwidthDeg, pci, bands, conflicts);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
