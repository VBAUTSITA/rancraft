package dev.rancraft.block;

import dev.rancraft.RanCraftConfig;
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
 * timer; here the transmitter's removal and the receiver's look at its block stand in for the detach.
 * The inactivity timer exists since row 16d ({@link RadioLinkMemory#expire}): a transmitter not heard
 * from for {@code radioLinkTransmitterTimeoutTicks} (60 s), unloaded or out of service, is forgotten.
 *
 * <p><b>Changing the receiver's own address</b> forgets everything it heard: the old address's
 * transmitters say nothing about the new one's. Its output goes to 0 until a message on the new address
 * gets through.
 *
 * <p>The output is the block's {@code POWERED} state ({@link RadioLinkReceiverBlock}), set by the server
 * with a full neighbour update, so the redstone next to it reacts at once, and synced to clients by
 * vanilla block-state sync. Only while its chunk is loaded as FULL: a receiver in a chunk just outside
 * FULL (still in memory, still in the address book) hears and forgets as usual, and its output catches
 * up on its next turn (Phase 3B review fix, {@link #memoryChanged}).
 */
public class RadioLinkReceiverBlockEntity extends RadioLinkBlockEntity implements RadioLinkNetwork.Receiver {

    private final RadioLinkMemory memory = new RadioLinkMemory();

    private long delivered;
    private long lastDeliveredTick = Long.MIN_VALUE;
    /** The memory changed while the chunk was not FULL: mark the chunk unsaved on the next turn. */
    private boolean unsavedChange;

    public RadioLinkReceiverBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.RADIO_LINK_RECEIVER.get(), pos, state);
    }

    /**
     * Joins the address book (first, so that from now on no departure is missed), looks up the
     * transmitters remembered from a save that are not yet verified, forgets the ones that are gone and
     * the ones silent past the timeout (row 16d), and makes the output match. In steady state nothing is
     * unverified and nothing is looked up.
     */
    @Override
    protected void afterSample(ServerLevel level, BlockPos pos, DeviceContext ctx) {
        RadioLinkNetwork.of(level).attach(pos.asLong(), this);
        if (memory.unverifiedCount() > 0 && memory.verify(txKey -> presence(level, txKey))) {
            unsavedChange = true;
        }
        if (memory.expire(level.getGameTime(), RanCraftConfig.radioLinkTransmitterTimeoutTicks())) {
            unsavedChange = true;
        }
        if (unsavedChange) {
            unsavedChange = false;
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
        if (memory.hear(txKey, powered, gameTime)) {
            memoryChanged();
        }
    }

    @Override
    public void forget(long txKey) {
        if (memory.forget(txKey)) {
            memoryChanged();
        }
    }

    /**
     * The memory changed: saves it and moves the output, but only where the chunk is loaded as FULL
     * (Phase 3B review fix). A receiver stays in the address book while its chunk sits outside FULL
     * but still in memory (ticket level 34 to 44: the ring just beyond every player's loaded area, or
     * near a forced chunk), so messages and departures still reach it there. Writing its block state
     * then would load the chunk back to FULL on the spot ({@code Level.setBlock} ->
     * {@code getChunk(FULL, true)}, which waits on the server thread) and run redstone in a chunk the
     * ticker treats as unloaded. So there the memory is updated (a lost update stays a stale state, and
     * a departure is still heard), and the output and the save catch up on the receiver's next turn,
     * which comes only once the chunk is FULL again ({@link #afterSample}).
     */
    private void memoryChanged() {
        if (chunkFull()) {
            setChanged();
            syncOutput();
        } else {
            // BlockEntity.setChanged would skip marking the chunk (Level.blockEntityChanged checks
            // hasChunkAt), so remember to do it on the next turn.
            unsavedChange = true;
        }
    }

    /** Whether this block's chunk is loaded as FULL now. Never loads anything. */
    private boolean chunkFull() {
        return level instanceof ServerLevel serverLevel
                && serverLevel.getChunkSource().getChunkNow(worldPosition.getX() >> 4, worldPosition.getZ() >> 4) != null;
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

    /**
     * Sets {@code POWERED} to {@link #output()} if it differs, with a full neighbour update. Not while
     * the chunk is outside FULL (see {@link #memoryChanged}): the next turn does it.
     */
    private void syncOutput() {
        if (!(level instanceof ServerLevel serverLevel)) {
            return;
        }
        BlockState state = getBlockState();
        boolean on = memory.output();
        // The chunk check last: in steady state the output already matches and nothing is looked up.
        if (state.getBlock() instanceof RadioLinkReceiverBlock && state.getValue(RadioLinkReceiverBlock.POWERED) != on
                && chunkFull()) {
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
