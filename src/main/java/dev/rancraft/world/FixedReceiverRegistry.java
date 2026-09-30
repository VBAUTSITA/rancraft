package dev.rancraft.world;

import dev.rancraft.RanCraft;
import dev.rancraft.device.FixedDevice;
import dev.rancraft.rf.ReceiverStateStore;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.chunk.LevelChunk;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.level.ChunkEvent;

/**
 * The fixed receivers of one dimension: device blocks that receive the network where they stand
 * (Phase 3 slice 8, §3B.3). Keyed by {@code BlockPos.asLong()}; {@link FixedReceiverTicker} evaluates
 * them round-robin.
 *
 * <p><b>Lifecycle on all four paths.</b> A receiver is registered while, and only while, its block is
 * loaded:
 * <ol>
 *   <li>the block entity's {@code onLoad} ({@link #hostLoaded}, from
 *       {@code block.FixedDeviceBlockEntity}); since slice 9 also its {@code clearRemoved}, which
 *       {@code LevelChunk.setBlockEntity} calls the moment the entity joins a chunk, so a device placed
 *       where block entities are not ticking registers at once rather than at the deferred {@code onLoad};
 *   <li>its {@code setRemoved} ({@link #hostRemoved}): the block broken or replaced, and also every
 *       block entity of an unloading chunk ({@code ServerLevel.unload} → {@code clearAllBlockEntities});
 *   <li>{@link ChunkEvent.Load} ({@link #onChunkLoad}): every block entity of the chunk that is a
 *       device, or whose block is. It fires as the chunk becomes FULL, whereas {@code onLoad} is
 *       deferred to the dimension's next block-entity tick, which does not run in a dimension with no
 *       player and no forced chunk for 300 ticks;
 *   <li>{@link ChunkEvent.Unload} ({@link #onChunkUnload}): <em>every</em> receiver in the chunk, by
 *       position, whatever its type.
 * </ol>
 * NOTES.md records that sector antennas once missed the chunk paths (a type check matched only the
 * mast) and kept transmitting from unloaded chunks. Here the unload path does not depend on a type
 * check at all, and the ticker also skips a receiver whose chunk is not loaded as FULL.
 *
 * <p>Unregistering by a block entity is by identity ({@link #unregister(long, FixedDevice)}): the
 * late {@code setRemoved} of a replaced entity cannot drop its successor at the same position.
 * Unregistering also forgets the receiver's handover state and cached sample, as a player's logout
 * does: a receiver that comes back starts afresh.
 *
 * <p><b>Handover state</b> is a {@link ReceiverStateStore}{@code <Long>} per dimension (a position is
 * only unique within one), the store made generic in Phase 2 for exactly this.
 *
 * <p>Server thread in practice (block entity hooks, chunk events and the ticker all run there); the
 * methods are synchronized anyway, as {@link SiteRegistry}'s are.
 */
@EventBusSubscriber(modid = RanCraft.MOD_ID)
public final class FixedReceiverRegistry {

    /** The due tick of a receiver the ticker has not scheduled yet. */
    static final long UNSCHEDULED = Long.MIN_VALUE;

    private static final Map<ResourceKey<Level>, FixedReceiverRegistry> BY_LEVEL = new ConcurrentHashMap<>();

    /**
     * One registered receiver. Its cached sample lives here, so it goes when the receiver does; its
     * due tick lives in the registry's parallel array ({@link #dueAt}).
     */
    static final class Entry {

        /** {@code BlockPos.asLong()} of the device block. */
        final long key;
        FixedDevice device;
        /** The last evaluation, replayed while nothing it depends on changed. Ticker only. */
        FixedReceiverTicker.Cached cached;

        Entry(long key, FixedDevice device) {
            this.key = key;
            this.device = device;
        }
    }

    private final Long2ObjectOpenHashMap<Entry> byKey = new Long2ObjectOpenHashMap<>();
    /** Round-robin order: registration order, removals shift the rest down. */
    private final List<Entry> order = new ArrayList<>();
    /**
     * Server tick at which {@code order.get(i)} is next due, or {@link #UNSCHEDULED}; ticker only.
     * A primitive array beside the entries rather than a field in them, because the ticker looks at
     * every receiver every tick to find the few that are due: with the due tick in each entry object
     * the scan cost about 100 ns per receiver (cache misses; 20 µs per tick for 200 receivers in the
     * slice 8 game test), with this array 8-12 µs per tick for 200, loop and lock included (NOTES.md,
     * slice 8). The scan is still linear in the receivers registered, due or not.
     */
    private long[] due = new long[16];
    private final Long2ObjectOpenHashMap<LongOpenHashSet> byChunk = new Long2ObjectOpenHashMap<>();
    /** Where the ticker resumes: the index of the next entry it looks at. */
    private int cursor;

    /** Handover state per receiver (§3B.3). Cleared with the receiver. */
    final ReceiverStateStore<Long> states = new ReceiverStateStore<>();

    FixedReceiverRegistry() {
    }

    /** The receivers of one dimension, created on first use. */
    public static FixedReceiverRegistry of(ServerLevel level) {
        return BY_LEVEL.computeIfAbsent(level.dimension(), key -> new FixedReceiverRegistry());
    }

    /** The receivers of one dimension, or {@code null} if nothing was ever registered there. */
    static FixedReceiverRegistry existing(ServerLevel level) {
        return BY_LEVEL.get(level.dimension());
    }

    /** Called on server stop so a second world load in the same JVM starts clean. */
    public static void clearAll() {
        BY_LEVEL.clear();
    }

    // ---- registration -----------------------------------------------------------------------------

    public boolean register(BlockPos pos, FixedDevice device) {
        return register(pos.asLong(), device);
    }

    /**
     * Adds a receiver. Registering the same device again is a no-op (the block entity's {@code onLoad}
     * and the chunk load both register it). A different device at a registered position replaces the
     * old one and keeps its schedule, handover state and cached sample, which belong to the position.
     *
     * @return whether a new receiver was added.
     */
    synchronized boolean register(long key, FixedDevice device) {
        if (device == null) {
            throw new IllegalArgumentException("device");
        }
        Entry existing = byKey.get(key);
        if (existing != null) {
            existing.device = device;
            return false;
        }
        Entry entry = new Entry(key, device);
        byKey.put(key, entry);
        if (order.size() == due.length) {
            due = java.util.Arrays.copyOf(due, due.length * 2);
        }
        due[order.size()] = UNSCHEDULED;
        order.add(entry);
        byChunk.computeIfAbsent(chunkKeyOf(key), chunk -> new LongOpenHashSet()).add(key);
        return true;
    }

    public boolean unregister(BlockPos pos, FixedDevice device) {
        return unregister(pos.asLong(), device);
    }

    /**
     * Removes the receiver at {@code key} if {@code device} is the one registered there (identity).
     *
     * @return whether it was removed.
     */
    synchronized boolean unregister(long key, FixedDevice device) {
        Entry entry = byKey.get(key);
        if (entry == null || entry.device != device) {
            return false;
        }
        LongOpenHashSet one = new LongOpenHashSet(1);
        one.add(key);
        return removeAll(one) == 1;
    }

    /**
     * Removes every receiver in one chunk column, whatever its device (the chunk unload path).
     *
     * @return how many were removed.
     */
    synchronized int unregisterChunk(int chunkX, int chunkZ) {
        LongOpenHashSet keys = byChunk.get(ChunkPos.asLong(chunkX, chunkZ));
        if (keys == null || keys.isEmpty()) {
            return 0;
        }
        return removeAll(new LongOpenHashSet(keys));
    }

    /**
     * One pass over the round-robin order: removes the given receivers, keeps the rest in order and
     * the cursor on the same next entry, and forgets their handover state.
     */
    private int removeAll(LongSet keys) {
        int removed = 0;
        int removedBeforeCursor = 0;
        int kept = 0;
        int size = order.size();
        for (int i = 0; i < size; i++) {
            Entry entry = order.get(i);
            if (keys.contains(entry.key)) {
                removed++;
                if (i < cursor) {
                    removedBeforeCursor++;
                }
            } else {
                // Compact in place, entries and due ticks together.
                order.set(kept, entry);
                due[kept] = due[i];
                kept++;
            }
        }
        if (removed == 0) {
            return 0;
        }
        order.subList(kept, size).clear();
        cursor = order.isEmpty() ? 0 : (cursor - removedBeforeCursor) % order.size();
        for (long key : keys) {
            if (byKey.remove(key) != null) {
                long chunk = chunkKeyOf(key);
                LongOpenHashSet inChunk = byChunk.get(chunk);
                if (inChunk != null) {
                    inChunk.remove(key);
                    if (inChunk.isEmpty()) {
                        byChunk.remove(chunk);
                    }
                }
            }
            states.clear(key);
        }
        return removed;
    }

    // ---- queries ----------------------------------------------------------------------------------

    public synchronized int size() {
        return order.size();
    }

    public synchronized boolean contains(BlockPos pos) {
        return byKey.containsKey(pos.asLong());
    }

    /** The device registered at {@code pos}, or {@code null}. */
    public synchronized FixedDevice deviceAt(BlockPos pos) {
        Entry entry = byKey.get(pos.asLong());
        return entry == null ? null : entry.device;
    }

    /** How many receivers are registered in one chunk column. */
    public synchronized int countInChunk(int chunkX, int chunkZ) {
        LongOpenHashSet keys = byChunk.get(ChunkPos.asLong(chunkX, chunkZ));
        return keys == null ? 0 : keys.size();
    }

    /**
     * {@link #size()} for a caller that already holds this registry's lock: the ticker, which takes
     * it once per batch rather than twice per receiver it looks at.
     */
    int sizeHeld() {
        return order.size();
    }

    /**
     * The index at the round-robin cursor, moving the cursor on; -1 when empty. The ticker calls it
     * once per receiver it looks at, holding this registry's lock, so the next tick resumes where this
     * one stopped. The index is valid until the registry next changes. Not synchronized itself (see
     * {@link #sizeHeld}).
     */
    int advanceIndex() {
        int size = order.size();
        if (size == 0) {
            return -1;
        }
        if (cursor >= size) {
            cursor = 0;
        }
        int index = cursor;
        cursor = index + 1 == size ? 0 : index + 1;
        return index;
    }

    /** The due tick at {@code index} (lock held). */
    long dueAt(int index) {
        return due[index];
    }

    /** Sets the due tick at {@code index} (lock held). */
    void setDueAt(int index, long tick) {
        due[index] = tick;
    }

    /** The entry at {@code index} (lock held). */
    Entry entryAt(int index) {
        return order.get(index);
    }

    /** {@link #advanceIndex} as an entry, or {@code null} when empty. For tests. */
    Entry advance() {
        int index = advanceIndex();
        return index < 0 ? null : order.get(index);
    }

    /** The receiver's entry, for tests and the ticker; {@code null} when not registered. */
    synchronized Entry entry(long key) {
        return byKey.get(key);
    }

    /** A registered receiver's due tick ({@link #UNSCHEDULED} before its first turn). For tests. */
    synchronized long dueOf(long key) {
        return due[indexOf(key)];
    }

    /** Sets a registered receiver's due tick. For tests. */
    synchronized void setDueOf(long key, long tick) {
        due[indexOf(key)] = tick;
    }

    private int indexOf(long key) {
        for (int i = 0; i < order.size(); i++) {
            if (order.get(i).key == key) {
                return i;
            }
        }
        throw new IllegalArgumentException("not registered: " + BlockPos.of(key));
    }

    private static long chunkKeyOf(long blockKey) {
        return ChunkPos.asLong(BlockPos.getX(blockKey) >> 4, BlockPos.getZ(blockKey) >> 4);
    }

    // ---- the four lifecycle paths -----------------------------------------------------------------

    /**
     * The device a block entity hosts: the entity itself if it is a {@link FixedDevice}, else its block
     * if that is one, else {@code null}.
     */
    static FixedDevice deviceOf(BlockEntity blockEntity) {
        if (blockEntity instanceof FixedDevice device) {
            return device;
        }
        return blockEntity.getBlockState().getBlock() instanceof FixedDevice device ? device : null;
    }

    /** Path 1: a device's block entity loaded (its {@code onLoad}). Server levels only. */
    public static void hostLoaded(BlockEntity blockEntity) {
        if (!(blockEntity.getLevel() instanceof ServerLevel level)) {
            return;
        }
        FixedDevice device = deviceOf(blockEntity);
        if (device != null) {
            of(level).register(blockEntity.getBlockPos(), device);
        }
    }

    /** Path 2: a device's block entity removed (its {@code setRemoved}): broken, replaced or unloaded. */
    public static void hostRemoved(BlockEntity blockEntity) {
        if (!(blockEntity.getLevel() instanceof ServerLevel level)) {
            return;
        }
        FixedReceiverRegistry registry = existing(level);
        FixedDevice device = deviceOf(blockEntity);
        if (registry != null && device != null) {
            registry.unregister(blockEntity.getBlockPos(), device);
        }
    }

    /**
     * Path 3: a chunk became FULL. Registers every device in it at once, rather than waiting for
     * {@code onLoad}, which NeoForge defers to the dimension's next block-entity tick.
     */
    @SubscribeEvent
    public static void onChunkLoad(ChunkEvent.Load event) {
        if (!(event.getLevel() instanceof ServerLevel level) || !(event.getChunk() instanceof LevelChunk chunk)) {
            return;
        }
        for (BlockEntity blockEntity : chunk.getBlockEntities().values()) {
            FixedDevice device = deviceOf(blockEntity);
            if (device != null) {
                of(level).register(blockEntity.getBlockPos(), device);
            }
        }
    }

    /**
     * Path 4: a chunk is unloading. Drops every receiver in it by position, not by type, so no device
     * can be missed and keep running from an unloaded chunk. Fired before the chunk's block entities
     * are removed (path 2 then finds nothing left to do).
     */
    @SubscribeEvent
    public static void onChunkUnload(ChunkEvent.Unload event) {
        if (!(event.getLevel() instanceof ServerLevel level)) {
            return;
        }
        FixedReceiverRegistry registry = existing(level);
        if (registry != null) {
            ChunkPos chunk = event.getChunk().getPos();
            registry.unregisterChunk(chunk.x, chunk.z);
        }
    }
}
