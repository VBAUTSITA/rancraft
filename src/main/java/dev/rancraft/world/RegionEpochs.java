package dev.rancraft.world;

import dev.rancraft.RanCraft;
import dev.rancraft.rf.BinTraversal;
import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ChunkLevel;
import net.minecraft.server.level.FullChunkStatus;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.piston.PistonStructureResolver;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.common.util.BlockSnapshot;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.neoforge.event.level.BlockGrowFeatureEvent;
import net.neoforged.neoforge.event.level.ChunkEvent;
import net.neoforged.neoforge.event.level.ChunkTicketLevelUpdatedEvent;
import net.neoforged.neoforge.event.level.ExplosionEvent;
import net.neoforged.neoforge.event.level.PistonEvent;
import net.neoforged.neoforge.event.tick.LevelTickEvent;

/**
 * Block-change counters per 128 x 128 XZ region ("bin"), per dimension. Phase 3 slice 7 (§3B.2),
 * replacing the single per-dimension block epoch.
 *
 * <p>Each bin ({@code SiteRegistry.BIN_SIZE}, the bins the site registry already uses) has an epoch
 * that goes up whenever a block in it is placed or broken, blown up, or moved by a piston. A cached
 * evaluation records the epochs of the bins its marched rays cross ({@link #snapshot}, from
 * {@code RfEngine.Evaluation.dependencyBins}) and is replayed only while every one of them is
 * unchanged ({@link #unchanged}). Why those bins are exactly the ones that matter is argued in
 * {@link BinTraversal}'s class javadoc: which cells get a ray is decided with no obstruction term,
 * and a pruned cell cannot be revived by any block change, so only the marched rays read the world.
 *
 * <p>{@link #total()} is the sum of every <em>block-change</em> bump: it goes up on every block event
 * anywhere in the dimension, which is the old per-dimension epoch's meaning. {@code
 * SignalTicker.blockEpochOf} returns it, so {@code CoverageSurveyor} works unchanged (floored by
 * {@code coverageMinIntervalTicks}, as before). The chunk bumps below are left out of it, so a coverage
 * survey is not redone whenever any player walks across a chunk border, which the old epoch never
 * did either.
 *
 * <h2>What bumps a bin</h2>
 * <ul>
 *   <li>{@link BlockEvent.BreakEvent} (a player breaks a block) and {@link BlockEvent.EntityPlaceEvent}
 *       (an entity places one; a multi-block placement such as a bed or a door bumps the bin of every
 *       block it placed): the block's bin. As before slice 7, the bump happens whether or not a
 *       later listener cancels the event (a needless re-evaluation, never a stale sample).
 *   <li>{@link ExplosionEvent.Detonate}: every bin holding a block on the explosion's list, and the
 *       centre's. New in slice 7; before it an explosion invalidated nothing.
 *   <li>{@link PistonEvent.Pre}: every bin within {@link #PISTON_REACH_BLOCKS} of the piston in x and
 *       z, at once and again {@link #PISTON_SETTLE_TICKS} game ticks later. New in slice 7. The
 *       second bump is needed because the moved blocks spend two ticks as {@code moving_piston}
 *       (non-occluding, so 0 dB) and only then become themselves again, with no event: an
 *       evaluation during the move would otherwise stay cached with the wall missing.
 *   <li>{@link BlockGrowFeatureEvent} (a sapling grows into a tree, a mushroom or fungus into a huge
 *       one): every bin within {@link #FEATURE_REACH_BLOCKS}. Beyond §3B.2's list (see
 *       {@link #onFeatureGrow}).
 *   <li><b>A chunk becoming readable, or ceasing to be</b> (Phase 3B review fix): the chunk's bin (a
 *       16-block chunk lies inside one 128-block bin). The probe reads a chunk that is not loaded as
 *       FULL as air ({@link LevelWorldProbe}), so what a ray reads changes when a chunk on it reaches
 *       or leaves FULL, with no block event. Bumped on {@link ChunkEvent.Load} (a chunk generated or
 *       read from disk becomes FULL) and on a ticket-level change across FULL
 *       ({@link ChunkTicketLevelUpdatedEvent}: a chunk kept in memory just outside the loaded area,
 *       ticket level 34 to 44, goes back to FULL with no load event, or leaves FULL with no unload
 *       event). See {@link #onTicketLevel} for why that covers every change.
 * </ul>
 *
 * <h2>Gaps that remain (NOTES.md, Phase 3 slice 7)</h2>
 * Block changes with no event here do not invalidate a cached sample: fluid flow (water is 15 dB),
 * fire spread and burn-out (fire is 0 dB, but it removes planks and logs), leaf decay (1 dB), crop
 * and vine growth, falling sand and gravel, ice forming and melting, snow, mob griefing
 * other than explosions (an enderman carrying a block), commands ({@code /setblock}, {@code /fill},
 * {@code /clone}) and other mods writing blocks directly. The same gaps existed with the
 * per-dimension epoch. A cached sample is corrected by the next event in any of its bins, the next
 * antenna change, or the receiver moving half a block. (Chunk loading was one more gap until the
 * Phase 3B review: a fixed receiver could replay, indefinitely, a sample that read a ridge as air
 * because the ridge's chunk was not loaded when it ran, or the reverse.)
 *
 * <p><b>Honest label (NOTES.md, slice 7).</b> Bins are a cache granularity, not an RF concept:
 * nothing about propagation depends on them, and a replay is bit for bit the evaluation a fresh one
 * would produce. What is incomplete is the list of change sources above, not the dependency set.
 *
 * <p>Server thread only in practice (every event above is fired there); the methods are
 * synchronized anyway, as {@link SiteRegistry}'s are.
 */
@EventBusSubscriber(modid = RanCraft.MOD_ID)
public final class RegionEpochs {

    /** Bin edge in blocks: the site registry's bins. */
    public static final int BIN_SIZE = SiteRegistry.BIN_SIZE;

    /**
     * How far from a piston a move can change blocks, in x or z: a pushed structure holds at most
     * {@link PistonStructureResolver#MAX_PUSH_DEPTH} (12) connected blocks, starting one block from
     * the piston (two when a sticky piston pulls), each block moves one step, and a block it breaks
     * is next to the structure. 14 covers all of it, slime and honey side branches included, so a
     * move bumps at most the four bins round the piston, and usually one.
     */
    static final int PISTON_REACH_BLOCKS = PistonStructureResolver.MAX_PUSH_DEPTH + 2;

    /**
     * Game ticks after a piston fires before its second bump. A moving block's entity advances 0.5
     * per tick and places the real block on the tick after it reaches 1.0, so a move started at game
     * time T is finished by T + 2 (the piston's block event runs before block entities tick in the
     * same level tick; {@code PistonMovingBlockEntity.tick}, 1.21.1 sources). Two ticks of margin.
     */
    static final int PISTON_SETTLE_TICKS = 4;

    /**
     * How far from the sapling (or mushroom, fungus, azalea) a grown feature can place blocks, in x
     * or z. Vanilla's widest are the 2x2 trees (mega jungle, dark oak, mega spruce: canopies about
     * five blocks out from a trunk that may start one block off the sapling) and fancy oak branches;
     * 16 is a generous bound, so a growth bumps at most four bins.
     */
    static final int FEATURE_REACH_BLOCKS = 16;

    private static final Map<ResourceKey<Level>, RegionEpochs> BY_LEVEL = new ConcurrentHashMap<>();

    private final Long2LongOpenHashMap epochs = new Long2LongOpenHashMap();
    private final ArrayDeque<Deferred> deferred = new ArrayDeque<>();
    private long total;
    /** Every bump, chunk bumps included (unlike {@link #total}). See {@link #bumps()}. */
    private long bumps;

    /** A bump that waits for moved blocks to settle. */
    private record Deferred(long[] bins, long dueGameTime) {
    }

    /**
     * The epochs of an evaluation's dependency bins, as they were when it ran.
     *
     * @param bins   the dependency set: sorted, distinct bin keys ({@link BinTraversal#key}).
     * @param epochs each bin's epoch at the time, index for index.
     */
    public record Snapshot(long[] bins, long[] epochs) {

        /** No dependency at all: an evaluation that marched nothing. Never invalidated by blocks. */
        public static final Snapshot NONE = new Snapshot(new long[0], new long[0]);

        public Snapshot {
            if (bins.length != epochs.length) {
                throw new IllegalArgumentException(bins.length + " bins but " + epochs.length + " epochs");
            }
        }

        public int size() {
            return bins.length;
        }

        /** Whether the bin is in the dependency set. */
        public boolean covers(long binKey) {
            return Arrays.binarySearch(bins, binKey) >= 0;
        }
    }

    RegionEpochs() {
    }

    /** The counters of one dimension, created on first use. */
    public static RegionEpochs of(ServerLevel level) {
        return BY_LEVEL.computeIfAbsent(level.dimension(), key -> new RegionEpochs());
    }

    /** Called on server stop, with the caches that recorded these epochs. */
    public static void clearAll() {
        BY_LEVEL.clear();
    }

    /** The sum of every bin's epoch: moves on any bump in the dimension (the pre-slice-7 epoch). */
    public synchronized long total() {
        return total;
    }

    /**
     * How many bumps the dimension has had, of any kind: block changes and chunks crossing FULL alike
     * ({@link #total()} leaves the chunk bumps out). Phase 3 slice 12: while it has not moved, no bin's
     * epoch has, so a holder of many snapshots (the backhaul hops) can skip checking them one by one.
     */
    public synchronized long bumps() {
        return bumps;
    }

    /** One bin's epoch; 0 for a bin nothing has bumped. */
    public synchronized long epochOf(long binKey) {
        return epochs.get(binKey);
    }

    /** One bin's epoch, by the bin's block coordinates. */
    public long epochAtBlock(int blockX, int blockZ) {
        return epochOf(BinTraversal.keyOfBlock(blockX, blockZ, BIN_SIZE));
    }

    /** A block at {@code (blockX, blockZ)} changed. */
    public void bumpBlock(int blockX, int blockZ) {
        bumpBin(BinTraversal.keyOfBlock(blockX, blockZ, BIN_SIZE));
    }

    /** A block in this bin changed. */
    public synchronized void bumpBin(long binKey) {
        epochs.addTo(binKey, 1L);
        total++;
        bumps++;
    }

    /** Blocks in each of these bins changed: each epoch up by one (a key listed twice counts once). */
    public synchronized void bumpBins(long[] binKeys) {
        long[] distinct = BinTraversal.sortedDistinct(binKeys);
        for (long key : distinct) {
            epochs.addTo(key, 1L);
        }
        total += distinct.length;
        bumps += distinct.length;
    }

    /**
     * The chunk at chunk coordinates {@code (chunkX, chunkZ)} became readable (FULL) or stopped being
     * readable: its bin's epoch goes up by one. Not counted in {@link #total()} (see the class
     * javadoc): it is not a block change.
     */
    public synchronized void bumpChunk(int chunkX, int chunkZ) {
        // A chunk's 16 blocks never straddle a bin edge (BIN_SIZE is a multiple of 16), so its
        // minimum corner names its bin.
        epochs.addTo(BinTraversal.keyOfBlock(chunkX << 4, chunkZ << 4, BIN_SIZE), 1L);
        bumps++;
    }

    /**
     * Whether a ticket-level change moves a chunk into or out of FULL: a chunk is FULL (the only
     * state in which the probe reads it) at {@code fullLevel} or lower. Pure, so it is unit-tested
     * without touching {@code ChunkLevel}, whose class initialisation needs the game's registries.
     *
     * @param fullLevel the highest FULL ticket level, {@code ChunkLevel.byStatus(FullChunkStatus.FULL)}
     *                  (33 in 1.21.1).
     */
    static boolean crossesFull(int oldTicketLevel, int newTicketLevel, int fullLevel) {
        return (oldTicketLevel <= fullLevel) != (newTicketLevel <= fullLevel);
    }

    /** Bumps these bins now and again once {@code dueGameTime} is reached ({@link #settle}). */
    synchronized void bumpNowAndLater(long[] binKeys, long dueGameTime) {
        bumpBins(binKeys);
        deferred.addLast(new Deferred(binKeys.clone(), dueGameTime));
    }

    /**
     * Runs the deferred bumps that are due at {@code gameTime}. Game time never goes backwards and
     * every deferral is the same length, so the queue is in due order.
     *
     * @return how many deferred bumps ran.
     */
    synchronized int settle(long gameTime) {
        int ran = 0;
        while (!deferred.isEmpty() && deferred.peekFirst().dueGameTime() <= gameTime) {
            bumpBins(deferred.pollFirst().bins());
            ran++;
        }
        return ran;
    }

    /** Deferred bumps still waiting. */
    synchronized int pending() {
        return deferred.size();
    }

    /** Records the current epoch of each bin in {@code bins} (sorted and distinct, as {@code dependencyBins} returns them). */
    public synchronized Snapshot snapshot(long[] bins) {
        if (bins.length == 0) {
            return Snapshot.NONE;
        }
        long[] now = new long[bins.length];
        for (int i = 0; i < bins.length; i++) {
            now[i] = epochs.get(bins[i]);
        }
        return new Snapshot(bins.clone(), now);
    }

    /** True when no bin of the snapshot has been bumped since it was taken. */
    public synchronized boolean unchanged(Snapshot snapshot) {
        long[] bins = snapshot.bins();
        long[] then = snapshot.epochs();
        for (int i = 0; i < bins.length; i++) {
            if (epochs.get(bins[i]) != then[i]) {
                return false;
            }
        }
        return true;
    }

    /**
     * The bins a square of blocks overlaps: {@code [minX, maxX] x [minZ, maxZ]}, inclusive.
     * Package-private for the piston rule's test.
     */
    static long[] binsOverlapping(int minX, int minZ, int maxX, int maxZ) {
        int fromX = BinTraversal.binOf(minX, BIN_SIZE);
        int toX = BinTraversal.binOf(maxX, BIN_SIZE);
        int fromZ = BinTraversal.binOf(minZ, BIN_SIZE);
        int toZ = BinTraversal.binOf(maxZ, BIN_SIZE);
        long[] keys = new long[(toX - fromX + 1) * (toZ - fromZ + 1)];
        int at = 0;
        for (int binX = fromX; binX <= toX; binX++) {
            for (int binZ = fromZ; binZ <= toZ; binZ++) {
                keys[at++] = BinTraversal.key(binX, binZ);
            }
        }
        return keys;
    }

    /** The bins a piston at {@code (x, z)} can change blocks in ({@link #PISTON_REACH_BLOCKS}). */
    static long[] pistonBins(int x, int z) {
        return binsOverlapping(x - PISTON_REACH_BLOCKS, z - PISTON_REACH_BLOCKS,
                x + PISTON_REACH_BLOCKS, z + PISTON_REACH_BLOCKS);
    }

    /** The bins a feature grown at {@code (x, z)} can place blocks in ({@link #FEATURE_REACH_BLOCKS}). */
    static long[] featureBins(int x, int z) {
        return binsOverlapping(x - FEATURE_REACH_BLOCKS, z - FEATURE_REACH_BLOCKS,
                x + FEATURE_REACH_BLOCKS, z + FEATURE_REACH_BLOCKS);
    }

    /** The bins of an explosion: every listed block's, and the centre's. */
    static long[] explosionBins(List<BlockPos> affected, double centreX, double centreZ) {
        LongArrayList keys = new LongArrayList(affected.size() + 1);
        keys.add(BinTraversal.keyOfBlock((int) Math.floor(centreX), (int) Math.floor(centreZ), BIN_SIZE));
        for (BlockPos pos : affected) {
            keys.add(BinTraversal.keyOfBlock(pos.getX(), pos.getZ(), BIN_SIZE));
        }
        return BinTraversal.sortedDistinct(keys.toLongArray());
    }

    private static RegionEpochs ofServer(LevelAccessor level) {
        return level instanceof ServerLevel serverLevel ? of(serverLevel) : null;
    }

    @SubscribeEvent
    public static void onBlockBroken(BlockEvent.BreakEvent event) {
        RegionEpochs epochs = ofServer(event.getLevel());
        if (epochs != null) {
            epochs.bumpBlock(event.getPos().getX(), event.getPos().getZ());
        }
    }

    /**
     * Also receives {@link BlockEvent.EntityMultiPlaceEvent} (a subclass: the event bus hands
     * subclass events to a superclass listener), whose blocks can straddle a bin edge.
     */
    @SubscribeEvent
    public static void onBlockPlaced(BlockEvent.EntityPlaceEvent event) {
        RegionEpochs epochs = ofServer(event.getLevel());
        if (epochs == null) {
            return;
        }
        if (event instanceof BlockEvent.EntityMultiPlaceEvent multi) {
            List<BlockSnapshot> placed = multi.getReplacedBlockSnapshots();
            long[] keys = new long[placed.size() + 1];
            keys[0] = BinTraversal.keyOfBlock(event.getPos().getX(), event.getPos().getZ(), BIN_SIZE);
            for (int i = 0; i < placed.size(); i++) {
                BlockPos pos = placed.get(i).getPos();
                keys[i + 1] = BinTraversal.keyOfBlock(pos.getX(), pos.getZ(), BIN_SIZE);
            }
            epochs.bumpBins(keys);
        } else {
            epochs.bumpBlock(event.getPos().getX(), event.getPos().getZ());
        }
    }

    @SubscribeEvent
    public static void onExplosion(ExplosionEvent.Detonate event) {
        RegionEpochs epochs = ofServer(event.getLevel());
        if (epochs != null) {
            Vec3 centre = event.getExplosion().center();
            epochs.bumpBins(explosionBins(event.getAffectedBlocks(), centre.x, centre.z));
        }
    }

    /**
     * {@code Pre}, not {@code Post}: it fires for both extension and retraction before any block
     * moves. A listener that cancels it after this one leaves a needless bump, never a missed one.
     */
    @SubscribeEvent
    public static void onPistonMove(PistonEvent.Pre event) {
        if (!(event.getLevel() instanceof ServerLevel level)) {
            return;
        }
        BlockPos pos = event.getPos();
        of(level).bumpNowAndLater(pistonBins(pos.getX(), pos.getZ()), level.getGameTime() + PISTON_SETTLE_TICKS);
    }

    /**
     * A sapling, mushroom, fungus or azalea grows into a feature (a tree). Not in §3B.2's list; added
     * because a tree grown into a link path is exactly the change a link cares about (logs 4 dB,
     * leaves 1 dB; slice 12's backhaul reads these epochs too). The event fires just before the
     * feature is placed, in the same call, so one bump suffices; a cancelled growth leaves a needless
     * one.
     */
    @SubscribeEvent
    public static void onFeatureGrow(BlockGrowFeatureEvent event) {
        RegionEpochs epochs = ofServer(event.getLevel());
        if (epochs != null) {
            epochs.bumpBins(featureBins(event.getPos().getX(), event.getPos().getZ()));
        }
    }

    /**
     * A chunk was generated or read from disk and is now FULL (posted from the FULL step,
     * {@code ChunkStatusTasks.full}, the only place a chunk becomes a {@code LevelChunk}): from now on
     * the probe reads its blocks instead of air.
     */
    @SubscribeEvent
    public static void onChunkLoad(ChunkEvent.Load event) {
        RegionEpochs epochs = ofServer(event.getLevel());
        if (epochs != null) {
            ChunkPos pos = event.getChunk().getPos();
            epochs.bumpChunk(pos.x, pos.z);
        }
    }

    /**
     * A chunk's ticket level moved across FULL (33). Phase 3B review fix.
     *
     * <p><b>Why this and {@link #onChunkLoad} cover every change in what the probe sees</b> (1.21.1
     * sources). The probe reads a chunk through {@code ServerChunkCache.getChunkNow}, which returns it
     * only while its holder allows FULL (ticket level 33 or lower, set by
     * {@code GenerationChunkHolder.updateHighestAllowedStatus}) and its FULL step has completed. Both
     * change in exactly two places:
     * <ul>
     *   <li>{@code ServerChunkCache.runDistanceManagerUpdates}: {@code ChunkMap.updateChunkScheduling}
     *       sets the new ticket level and posts this event, then, in the same call, the holder's allowed
     *       status follows. A chunk that leaves FULL keeps its {@code LevelChunk} in memory up to level
     *       44 ({@code ChunkLevel.MAX_LEVEL}) with no unload event, and one that comes back while still
     *       in memory becomes readable at once, with no load event (its FULL step stays completed).
     *       Nothing is evaluated between the event and the change: it is all one call on the server
     *       thread.
     *   <li>The FULL step completing for a chunk generated or read from disk, which posts
     *       {@link ChunkEvent.Load}.
     * </ul>
     * An unload needs no bump of its own: a chunk is dropped only above level 44, so it left FULL (and
     * was bumped here) before.
     *
     * <p>Vanilla raises a ticket level in two steps (to 45, then to the new level), so a chunk that stays
     * FULL (31 to 32, say) crosses twice in one call: two needless bumps of its bin, never a missed one.
     * In play, chunks cross FULL along the edge of each player's loaded area as they walk, so a cached
     * evaluation whose rays pass there is redone: the edge really did change what those rays read.
     */
    @SubscribeEvent
    public static void onTicketLevel(ChunkTicketLevelUpdatedEvent event) {
        if (!crossesFull(event.getOldTicketLevel(), event.getNewTicketLevel(), ChunkLevel.byStatus(FullChunkStatus.FULL))) {
            return;
        }
        long pos = event.getChunkPos();
        of(event.getLevel()).bumpChunk(ChunkPos.getX(pos), ChunkPos.getZ(pos));
    }

    /**
     * Runs a dimension's due piston bumps after it has ticked, so they land before
     * {@code SignalTicker} evaluates at the end of the server tick.
     */
    @SubscribeEvent
    public static void onLevelTick(LevelTickEvent.Post event) {
        if (!(event.getLevel() instanceof ServerLevel level)) {
            return;
        }
        RegionEpochs epochs = BY_LEVEL.get(level.dimension());
        if (epochs != null) {
            epochs.settle(level.getGameTime());
        }
    }
}
