package dev.rancraft.device;

import dev.rancraft.rf.DeviceRequirement;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;

/**
 * A device that is a block: it receives the network where it stands (Phase 3, §3B.3). The Radio Link
 * (§3B.4) is the first.
 *
 * <p>The block-bound counterpart of {@link SignalDevice}, with the same contract. <b>Devices never
 * compute RF.</b> The server evaluates each fixed receiver at most once per
 * {@code evaluationIntervalTicks} ({@code world.FixedReceiverTicker}) and hands the device the one
 * {@code SignalSample} it produced, with this device's {@link DeviceRequirement.Verdict} on it, in a
 * {@link DeviceContext}. A device reads that; it never runs the engine, marches a ray or picks a
 * serving cell.
 *
 * <p><b>Where it receives.</b> At the centre of its block. The ray march skips the receiver's own
 * voxel, so the device block never shadows itself.
 *
 * <p><b>Honest label (NOTES.md, slice 8):</b> a fixed receiver hears the network exactly as a player's
 * eye does, through the same 0 dBi receive antenna ({@code rf.RfMath}) and the same evaluation. A real
 * fixed terminal (a rooftop CPE, a telemetry modem) usually has a directional antenna with gain and is
 * mounted for line of sight; here height helps only by clearing obstruction, as for masts (slice 6).
 *
 * <p><b>Replays.</b> A fixed receiver never moves, so while nothing along the rays of its last
 * evaluation changed (no block event in any of its dependency bins, {@code world.RegionEpochs}) and
 * no antenna changed, the ticker replays that evaluation instead of running a new one, and
 * dispatches the replay here too. A replayed sample is the same object with the same
 * {@link dev.rancraft.rf.SignalSample#timestampTick() timestampTick}; {@link DeviceContext#tick()} is
 * the game time of the dispatch. {@code onSample} must be idempotent on the sample's tick (see
 * {@link ReplayGuard}); in steady state almost every dispatch is a replay.
 *
 * <p><b>Registration.</b> A device is known to the ticker only while its block is loaded:
 * {@code world.FixedReceiverRegistry}, keyed by {@code BlockPos.asLong()}, filled and emptied on all
 * four lifecycle paths (the block entity's {@code onLoad} and {@code setRemoved}, and the chunk load
 * and unload events). Extending {@code block.FixedDeviceBlockEntity} wires the first two; the
 * registry's chunk listeners find any block entity that is a {@code FixedDevice}, or whose block is.
 * A device in an unloaded chunk does not run.
 *
 * <p>Called on the server thread, inside the server tick. Handover state (the serving cell and any
 * armed candidate) is kept by the ticker per position, not by the device.
 */
public interface FixedDevice {

    /**
     * What this device needs from the serving cell. {@link DeviceRequirement#NONE} asks nothing. The
     * ticker checks it against every sample it dispatches, replays included.
     */
    DeviceRequirement requirement();

    /**
     * Server side, once per evaluation or replay, at most once per {@code evaluationIntervalTicks}.
     * Must tolerate a replayed (cached) sample.
     *
     * @param level the level the device is in (also {@code ctx.level()}).
     * @param pos   the device's block. The sample was evaluated at its centre.
     */
    void onSample(ServerLevel level, BlockPos pos, DeviceContext ctx);
}
