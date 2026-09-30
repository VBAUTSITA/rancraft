package dev.rancraft.block;

import dev.rancraft.device.DeviceContext;
import dev.rancraft.device.RadioLinkMemory;
import dev.rancraft.device.RadioLinkNetwork;
import dev.rancraft.registry.ModBlockEntities;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;

/**
 * The Radio Link receiver (Phase 3 slice 9, §3B.4): outputs redstone 15 while any transmitter on its
 * address is powered, as far as the messages that got through tell it.
 *
 * <p><b>The rule</b> is {@link RadioLinkMemory}'s: it remembers, per transmitter, the state in the last
 * message from it that was delivered, and outputs 15 while any remembered state is "powered". A lost
 * message changes nothing, so a lost update is a <em>stale</em> state, never a toggled one. The memory
 * is saved with the block ({@code KnownOn}), so a reloaded receiver keeps its output.
 *
 * <p><b>When a transmitter is gone.</b> A transmitter that is broken, replaced or re-addressed tells the
 * network, and every loaded receiver on its old address forgets it at once
 * ({@link RadioLinkNetwork#transmitterGone}), so breaking a powered transmitter turns its receivers off.
 * A receiver that was not in the address book at the time (unloaded, or loaded but not yet through its
 * first turn) catches up on its turns: what it remembers from a save is unverified
 * ({@link RadioLinkMemory}), and each unverified transmitter is looked up, and forgotten if its chunk is
 * loaded and the block there is no longer a transmitter on this address. A transmitter in an unloaded
 * chunk is kept, and looked up again next turn: it cannot change its input while unloaded, so its last
 * delivered state is still the best knowledge there is (stale, as for a lost message). Honest label
 * (NOTES.md, slice 9): a real network learns that a terminal has gone from a detach or an inactivity
 * timer; here the transmitter's removal and the receiver's look at its block stand in for that, and an
 * unloaded transmitter is never timed out.
 *
 * <p><b>Changing the receiver's own address</b> forgets everything it heard: the old address's
 * transmitters say nothing about the new one's. Its output goes to 0 until a message on the new address
 * gets through.
 *
 * <p>The output is the block's {@code POWERED} state ({@link RadioLinkReceiverBlock}), set by the server
 * with a full neighbour update, so the redstone next to it reacts at once, and synced to clients by
 * vanilla block-state sync.
 */
public class RadioLinkReceiverBlockEntity extends RadioLinkBlockEntity implements RadioLinkNetwork.Receiver {

    private final RadioLinkMemory memory = new RadioLinkMemory();

    private long delivered;
    private long lastDeliveredTick = Long.MIN_VALUE;

    public RadioLinkReceiverBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.RADIO_LINK_RECEIVER.get(), pos, state);
    }

    /**
     * Joins the address book (first, so that from now on no departure is missed), looks up the
     * transmitters remembered from a save that are not yet verified, forgets the ones that are gone, and
     * makes the output match. In steady state nothing is unverified and nothing is looked up.
     */
    @Override
    protected void afterSample(ServerLevel level, BlockPos pos, DeviceContext ctx) {
        RadioLinkNetwork.of(level).attach(pos.asLong(), this);
        if (memory.unverifiedCount() > 0 && memory.verify(txKey -> presence(level, txKey))) {
            setChanged();
        }
        syncOutput();
    }

    // ---- RadioLinkNetwork.Receiver ----------------------------------------------------------------

    @Override
    public boolean attached() {
        return !isRemoved() && level instanceof ServerLevel;
    }

    @Override
    public double success() {
        return decodeSuccess();
    }

    @Override
    public void deliver(long txKey, boolean powered, long gameTime) {
        delivered++;
        lastDeliveredTick = gameTime;
        if (memory.hear(txKey, powered)) {
            setChanged();
            syncOutput();
        }
    }

    @Override
    public void forget(long txKey) {
        if (memory.forget(txKey)) {
            setChanged();
            syncOutput();
        }
    }

    // ---- output -----------------------------------------------------------------------------------

    /** Whether it outputs 15: some transmitter was last heard powered. */
    public boolean output() {
        return memory.output();
    }

    /** How many transmitters it last heard powered. */
    public int knownOnCount() {
        return memory.knownOnCount();
    }

    /** Messages delivered to this entity since it was created. For tests. */
    public long deliveredCount() {
        return delivered;
    }

    /** Game time of the last delivered message; {@code Long.MIN_VALUE} before the first. */
    public long lastDeliveredTick() {
        return lastDeliveredTick;
    }

    /** Sets {@code POWERED} to {@link #output()} if it differs, with a full neighbour update. */
    private void syncOutput() {
        if (!(level instanceof ServerLevel serverLevel)) {
            return;
        }
        BlockState state = getBlockState();
        boolean on = memory.output();
        if (state.getBlock() instanceof RadioLinkReceiverBlock && state.getValue(RadioLinkReceiverBlock.POWERED) != on) {
            serverLevel.setBlock(worldPosition, state.setValue(RadioLinkReceiverBlock.POWERED, on), Block.UPDATE_ALL);
        }
    }

    /** How many remembered transmitters still need a lookup (loaded from a save, not yet confirmed). */
    public int unverifiedCount() {
        return memory.unverifiedCount();
    }

    /**
     * Looks a remembered transmitter up: GONE if its chunk is loaded and the block there is not a
     * transmitter on this address, PRESENT if it is one, UNKNOWN if its chunk is not loaded. Never loads
     * a chunk: {@code getChunkNow} returns only one already loaded as FULL.
     */
    private RadioLinkMemory.Presence presence(ServerLevel level, long txKey) {
        LevelChunk chunk = level.getChunkSource().getChunkNow(BlockPos.getX(txKey) >> 4, BlockPos.getZ(txKey) >> 4);
        if (chunk == null) {
            return RadioLinkMemory.Presence.UNKNOWN;
        }
        BlockEntity there = chunk.getBlockEntity(BlockPos.of(txKey));
        return there instanceof RadioLinkTransmitterBlockEntity transmitter && transmitter.address() == address()
                ? RadioLinkMemory.Presence.PRESENT
                : RadioLinkMemory.Presence.GONE;
    }

    @Override
    protected void addressChanged(int previous) {
        if (level instanceof ServerLevel serverLevel) {
            RadioLinkNetwork network = RadioLinkNetwork.of(serverLevel);
            network.detach(worldPosition.asLong(), previous, this);
            network.attach(worldPosition.asLong(), this);
        }
        memory.clear();
        setChanged();
        syncOutput();
    }

    @Override
    public void setRemoved() {
        super.setRemoved();
        if (level instanceof ServerLevel serverLevel) {
            RadioLinkNetwork.of(serverLevel).detach(worldPosition.asLong(), address(), this);
        }
    }

    // ---- persistence ------------------------------------------------------------------------------

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        tag.putLongArray("KnownOn", memory.toArray());
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        memory.load(tag.getLongArray("KnownOn"));
    }
}
