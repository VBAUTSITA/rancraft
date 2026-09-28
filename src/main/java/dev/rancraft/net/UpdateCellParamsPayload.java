package dev.rancraft.net;

import dev.rancraft.RanCraft;
import dev.rancraft.block.AntennaBlockEntity;
import dev.rancraft.data.RfDataLoader;
import dev.rancraft.rf.PciPlanner;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;

/**
 * Client to server: apply these settings to the antenna at {@code pos}.
 *
 * <p><b>Nothing in this packet is trusted.</b> {@link #applyOn(ServerPlayer, UpdateCellParamsPayload)}
 * re-checks reach, that the target is actually an antenna, and that every value is inside its
 * allowed range, before anything is written. A rejected packet is logged and dropped silently from
 * the player's point of view -- there is no legitimate client that sends an invalid one.
 *
 * <p>Note that gain is deliberately <em>absent</em>. It is derived from the beamwidths server-side,
 * so a client cannot ask for 20 dBi on a 360-degree antenna.
 */
public record UpdateCellParamsPayload(
        BlockPos pos,
        String bandId,
        double txPowerDbm,
        double azimuthDeg,
        double tiltDeg,
        double hBeamwidthDeg,
        double vBeamwidthDeg,
        int pci
) implements CustomPacketPayload {

    /** Square of the maximum distance a player may configure an antenna from. */
    public static final double MAX_REACH_SQR = 64.0;

    public static final double MIN_TX_DBM = 0.0;
    public static final double MAX_TX_DBM = 30.0;
    public static final double MIN_TILT_DEG = -10.0;
    public static final double MAX_TILT_DEG = 20.0;

    /** The only beamwidths the GUI offers, and therefore the only ones the server accepts. */
    public static final double[] H_BEAMWIDTHS = {30.0, 45.0, 65.0, 90.0, 120.0, 360.0};
    public static final double[] V_BEAMWIDTHS = {5.0, 7.0, 10.0, 15.0, 30.0};

    private static final int MAX_STRING = 200;

    public static final CustomPacketPayload.Type<UpdateCellParamsPayload> TYPE =
            new CustomPacketPayload.Type<>(
                    ResourceLocation.fromNamespaceAndPath(RanCraft.MOD_ID, "update_cell_params"));

    public static final StreamCodec<FriendlyByteBuf, UpdateCellParamsPayload> STREAM_CODEC =
            StreamCodec.of(UpdateCellParamsPayload::write, UpdateCellParamsPayload::read);

    private static void write(FriendlyByteBuf buf, UpdateCellParamsPayload payload) {
        buf.writeBlockPos(payload.pos);
        buf.writeUtf(payload.bandId, MAX_STRING);
        buf.writeDouble(payload.txPowerDbm);
        buf.writeDouble(payload.azimuthDeg);
        buf.writeDouble(payload.tiltDeg);
        buf.writeDouble(payload.hBeamwidthDeg);
        buf.writeDouble(payload.vBeamwidthDeg);
        buf.writeVarInt(payload.pci);
    }

    private static UpdateCellParamsPayload read(FriendlyByteBuf buf) {
        return new UpdateCellParamsPayload(
                buf.readBlockPos(),
                buf.readUtf(MAX_STRING),
                buf.readDouble(),
                buf.readDouble(),
                buf.readDouble(),
                buf.readDouble(),
                buf.readDouble(),
                buf.readVarInt());
    }

    /**
     * Validates and applies, on the server thread.
     *
     * @return true when the configuration was accepted.
     */
    public static boolean applyOn(ServerPlayer player, UpdateCellParamsPayload payload) {
        if (player.level().getBlockEntity(payload.pos) instanceof AntennaBlockEntity antenna) {
            if (!payload.isValid(player)) {
                RanCraft.LOGGER.warn(
                        "RANCraft rejected an antenna configuration from {} for {}: out of range or invalid",
                        player.getGameProfile().getName(), payload.pos);
                return false;
            }
            antenna.applyConfiguration(
                    payload.bandId, payload.txPowerDbm, payload.azimuthDeg, payload.tiltDeg,
                    payload.hBeamwidthDeg, payload.vBeamwidthDeg, payload.pci);
            return true;
        }

        RanCraft.LOGGER.warn(
                "RANCraft rejected an antenna configuration from {}: no antenna at {}",
                player.getGameProfile().getName(), payload.pos);
        return false;
    }

    private boolean isValid(ServerPlayer player) {
        return player.distanceToSqr(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5) < MAX_REACH_SQR
                && RfDataLoader.bands().get(bandId).isPresent()
                && inRange(txPowerDbm, MIN_TX_DBM, MAX_TX_DBM)
                && inRange(azimuthDeg, 0.0, 359.0)
                && inRange(tiltDeg, MIN_TILT_DEG, MAX_TILT_DEG)
                && isOneOf(hBeamwidthDeg, H_BEAMWIDTHS)
                && isOneOf(vBeamwidthDeg, V_BEAMWIDTHS)
                && PciPlanner.isValidPci(pci);
    }

    private static boolean inRange(double value, double min, double max) {
        return Double.isFinite(value) && value >= min && value <= max;
    }

    private static boolean isOneOf(double value, double[] allowed) {
        for (double candidate : allowed) {
            if (Math.abs(candidate - value) < 1e-9) {
                return true;
            }
        }
        return false;
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
