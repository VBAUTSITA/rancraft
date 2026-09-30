package dev.rancraft.block;

import dev.rancraft.device.DeviceContext;
import dev.rancraft.device.RadioLinkNetwork;
import dev.rancraft.registry.ModBlockEntities;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;

/**
 * The Radio Link transmitter (Phase 3 slice 9, §3B.4): on every evaluation it reads its redstone input
 * and sends it, as one status message, to every receiver on its address in this dimension.
 *
 * <p><b>About once a second, by design.</b> The input is sampled only on the transmitter's turn in the
 * fixed-receiver ticker, once per {@code evaluationIntervalTicks} (20 by default). A pulse shorter than
 * that can fall between two turns and never be sent, and a receiver follows about one interval behind at
 * best. The block's tooltip says so, so that nobody builds a clock with it.
 *
 * <p><b>Reading the input.</b> {@code Level.hasNeighborSignal}, as a redstone lamp reads its own, but
 * only after a neighbour changed ({@link RadioLinkTransmitterBlock#neighborChanged} marks it, as it
 * makes a lamp look) and on the first turn; otherwise the input is what it was. The redstone around a
 * block cannot change its power without a neighbour update, so this reads the same value as asking
 * every turn, without the thirty-odd block lookups a packed build costs.
 *
 * <p><b>Every turn sends, replays included.</b> The fixed-device contract asks a device to be
 * idempotent on the sample's {@code timestampTick}, because a replay is the same evaluation handed over
 * again. A replay is also a new interval, though: the channel is unchanged and time has moved on, so
 * the transmitter sends its status again, over the same SINR. What it guards against is two sends in
 * one dispatch tick ({@link DeviceContext#tick()}), which the ticker never does (at most once per
 * interval) and which game time frozen by {@code /tick freeze} would otherwise allow. Each message is
 * drawn afresh, seeded by this block and the dispatch tick.
 *
 * <p>Honest label: a real telemetry device would send on change plus a periodic keep-alive, and its
 * link would retransmit a failed block (HARQ). Here every message is a full status report and a failed
 * one is simply lost; the next one, an interval later, is the retry.
 */
public class RadioLinkTransmitterBlockEntity extends RadioLinkBlockEntity {

    private long lastSentTick = Long.MIN_VALUE;
    private boolean lastInput;
    /** Whether a neighbour changed since the input was last read. Starts true: read on the first turn. */
    private boolean inputStale = true;

    public RadioLinkTransmitterBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.RADIO_LINK_TRANSMITTER.get(), pos, state);
    }

    @Override
    protected void afterSample(ServerLevel level, BlockPos pos, DeviceContext ctx) {
        if (ctx.tick() == lastSentTick) {
            return;
        }
        lastSentTick = ctx.tick();
        if (inputStale) {
            inputStale = false;
            lastInput = level.hasNeighborSignal(pos);
        }
        RadioLinkNetwork.of(level).send(pos.asLong(), address(), lastInput, served(), decodeSuccess(), ctx.tick());
    }

    /** A neighbour changed: read the input again on the next turn. */
    void neighbourChanged() {
        inputStale = true;
    }

    /** Moved to another address: the receivers on the old one forget it at once. */
    @Override
    protected void addressChanged(int previous) {
        if (level instanceof ServerLevel serverLevel) {
            RadioLinkNetwork.of(serverLevel).transmitterGone(worldPosition.asLong(), previous);
        }
    }

    /** The redstone input it sent last; false before its first turn. */
    public boolean lastInput() {
        return lastInput;
    }

    /** Game time of its last send; {@code Long.MIN_VALUE} before its first turn. */
    public long lastSentTick() {
        return lastSentTick;
    }
}
