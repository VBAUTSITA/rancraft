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
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.piston.PistonStructureResolver;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.common.util.BlockSnapshot;
import net.neoforged.neoforge.event.level.BlockEvent;
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
 * <p>{@link #total()} is the sum of every bin's epoch: it goes up on every bump anywhere in the
 * dimension, which is the old per-dimension epoch's meaning. {@code SignalTicker.blockEpochOf}
 * returns it, so {@code CoverageSurveyor} works unchanged (floored by
 * {@code coverageMinIntervalTicks}, as before).
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
 * </ul>
 *
 * <h2>Gaps that remain (NOTES.md, Phase 3 slice 7)</h2>
 * Block changes with no event here do not invalidate a cached sample: fluid flow (water is 15 dB),
 * fire spread and burn-out (fire is 0 dB, but it removes planks and logs), leaf decay (1 dB),
 * sapling and mushroom growth, falling sand and gravel, ice forming and melting, snow, mob griefing
 * other than explosions (an enderman carrying a block), commands ({@code /setblock}, {@code /fill},
 * {@code /clone}) and other mods writing blocks directly. The same gaps existed with the
 * per-dimension epoch. A cached sample is corrected by the next event in any of its bins, the next
 * antenna change, or the receiver moving half a block.
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

    private static final Map<ResourceKey<Level>, RegionEpochs> BY_LEVEL = new ConcurrentHashMap<>();

    private final Long2LongOpenHashMap epochs = new Long2LongOpenHashMap();
    private final ArrayDeque<Deferred> deferred = new ArrayDeque<>();
    private long total;

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
    }

    /** Blocks in each of these bins changed: each epoch up by one (a key listed twice counts once). */
    public synchronized void bumpBins(long[] binKeys) {
        long[] distinct = BinTraversal.sortedDistinct(binKeys);
        for (long key : distinct) {
            epochs.addTo(key, 1L);
        }
        total += distinct.length;
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
