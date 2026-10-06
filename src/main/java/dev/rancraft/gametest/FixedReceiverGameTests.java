package dev.rancraft.gametest;

import dev.rancraft.RanCraft;
import dev.rancraft.RanCraftConfig;
import dev.rancraft.block.FixedDeviceBlockEntity;
import dev.rancraft.data.RfDataLoader;
import dev.rancraft.device.DeviceContext;
import dev.rancraft.device.FixedDevice;
import dev.rancraft.rf.CellParams;
import dev.rancraft.rf.CellSample;
import dev.rancraft.rf.DeviceRequirement;
import dev.rancraft.rf.ServiceLevel;
import dev.rancraft.rf.SignalSample;
import dev.rancraft.world.FixedReceiverRegistry;
import dev.rancraft.world.FixedReceiverTicker;
import dev.rancraft.world.RegionEpochs;
import dev.rancraft.world.SiteRegistry;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.AfterBatch;
import net.minecraft.gametest.framework.GameTestGenerator;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.TestFunction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.chunk.LevelChunk;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.level.ChunkEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;

/**
 * Fixed receivers at runtime (Phase 3 slice 8, §3B.3): the real ticker, on the real server tick,
 * evaluates a registered device's block centre, replays while its bins are quiet, re-evaluates when a
 * block on its link path changes, stops when unregistered; the block entity hooks and the chunk paths
 * register and unregister; and 200 receivers cost what they cost in steady state.
 * {@code FixedReceiverTickerTest} and {@code FixedReceiverRegistryTest} pin the rules headless.
 *
 * <p><b>Harness abstractions, stated plainly.</b>
 * <ul>
 *   <li>Slice 8 adds no device block (the Radio Link is slice 9). The devices here are test objects:
 *       plain {@link FixedDevice}s registered by position, and a {@link FixedDeviceBlockEntity}
 *       subclass that borrows the vanilla chest's block entity type (a new type cannot be created
 *       once registries are frozen). The chest-typed entity is saved as a plain chest, so reloading
 *       its chunk brings back a chest, not the device: "reloading resumes it" needs a real device
 *       block, and {@code RadioLinkGameTests} (slice 9) checks it with the Radio Link.
 *   <li>The ticker tests run in <b>the Nether</b>, at y 200 above its roof, in a chunk the test
 *       forces. The overworld is busy between batches (the runner unforces each batch's chunks, and
 *       masts left by other tests unregister as they unload, which moves the site registry version),
 *       so a replay could not be asserted exactly there. Nothing else happens in the Nether.
 *   <li>The cells are {@link CellParams} registered straight into the site registry, not placed
 *       masts; the link-path block is {@code setBlock} plus the bump the placement listener would make
 *       ({@link RegionEpochs#bumpBlock}), whose event wiring slice 7's {@code RegionEpochGameTests}
 *       checks.
 * </ul>
 *
 * <p>Each test runs in a batch of its own, and each batch's {@link AfterBatch} method undoes what the
 * test registered, also when it failed.
 */
@GameTestHolder(RanCraft.MOD_ID)
public final class FixedReceiverGameTests {

    private static final String BATCH_TICKER = "rancraft_fixed_receivers_ticker";
    private static final String BATCH_CHUNKS = "rancraft_fixed_receivers_chunks";
    private static final String BATCH_HOOKS = "rancraft_fixed_receivers_hooks";
    private static final String BATCH_COST = "rancraft_fixed_receivers_cost";
    private static final String BATCH_ACCESS = "rancraft_fixed_receivers_chunk_access";
    private static final String BATCH_AGE = "rancraft_fixed_receivers_max_replay_age";
    private static final int TIMEOUT_TICKS = 900;

    /** Nether chunks, far from anything, one per test that needs one. */
    private static final int TICKER_CHUNK_X = 256;
    private static final int CHUNKS_CHUNK_X = 260;
    private static final int COST_CHUNK_X = 264;
    private static final int CHUNK_Z = 256;
    /**
     * The chunk-access test's row (Phase 3B review): the receiver's chunk, the wall's chunk and the
     * cell's chunk, four apart, so with only the first and last forced the middle one sits at ticket
     * level 35: kept in memory, not FULL. A row of its own, far from every other test's chunks.
     */
    private static final int ACCESS_CHUNK_Z = 320;
    private static final int ACCESS_RX_CHUNK_X = 256;
    private static final int ACCESS_WALL_CHUNK_X = 260;
    private static final int ACCESS_CELL_CHUNK_X = 264;
    /**
     * The maximum-replay-age test (row 16d): a row of its own, region bins (32, 36) that no other test's
     * chunks touch, so no other test's chunk load or unload can wake its receiver early.
     */
    private static final int AGE_CHUNK_X = 256;
    private static final int AGE_CHUNK_Z = 288;
    /** The maximum replay age that test sets: its receiver's own limit is 51 to 100 ticks. */
    private static final int MAX_REPLAY_TEST_TICKS = 100;
    /** Above the Nether's bedrock roof (y 127): open air. */
    private static final int AIR_Y = 200;
    private static final int RECEIVERS_FOR_COST = 200;
    private static final int COST_WINDOW_TICKS = 100;
    /** The evaluation warm-up (interval 1, a bump every tick). */
    private static final int WARMUP_TICKS = 100;
    /**
     * The steady-state warm-up. The round robin runs once per tick, so its scan loop is compiled only
     * after tens of thousands of loop iterations; at 200 receivers per tick that is a few hundred ticks
     * (a running server gets there within a minute).
     */
    private static final int STEADY_WARMUP_TICKS = 400;
    /** 3B done-when: "200 radio links on a quiet server cost under 0.1 ms/tick in steady state". */
    private static final double STEADY_STATE_LIMIT_US = 100.0;

    /** Undone by each batch's {@link AfterBatch} method, pass or fail. Server thread. */
    private static final List<Runnable> CLEANUPS = new ArrayList<>();

    private FixedReceiverGameTests() {
    }

    @GameTestGenerator
    public static Collection<TestFunction> fixedReceiverTests() {
        String prefix = FixedReceiverGameTests.class.getSimpleName().toLowerCase(Locale.ROOT) + ".";
        List<TestFunction> tests = new ArrayList<>();
        tests.add(test(BATCH_TICKER, prefix + "evaluated_then_replayed_then_reevaluated_on_its_link_path",
                FixedReceiverGameTests::tickerLifecycle));
        tests.add(test(BATCH_CHUNKS, prefix + "chunk_load_registers_and_unload_stops_it",
                FixedReceiverGameTests::chunkPaths));
        tests.add(test(BATCH_HOOKS, prefix + "block_entity_on_load_and_set_removed", FixedReceiverGameTests::hooks));
        tests.add(test(BATCH_COST, prefix + "two_hundred_receivers_in_steady_state", FixedReceiverGameTests::cost));
        tests.add(test(BATCH_ACCESS, prefix + "a_chunk_on_its_ray_reaching_or_leaving_full_reevaluates_it",
                FixedReceiverGameTests::chunkAccess));
        tests.add(test(BATCH_AGE, prefix + "an_eventless_change_on_its_ray_is_seen_at_the_max_replay_age",
                FixedReceiverGameTests::maxReplayAge));
        return tests;
    }

    private static TestFunction test(String batch, String name, Consumer<GameTestHelper> body) {
        return new TestFunction(batch, name, HarvestGameTests.EMPTY_TEMPLATE, TIMEOUT_TICKS, 0L, true, body);
    }

    @AfterBatch(batch = BATCH_TICKER)
    public static void afterTicker(ServerLevel level) {
        runCleanups();
    }

    @AfterBatch(batch = BATCH_CHUNKS)
    public static void afterChunks(ServerLevel level) {
        runCleanups();
    }

    @AfterBatch(batch = BATCH_HOOKS)
    public static void afterHooks(ServerLevel level) {
        runCleanups();
    }

    @AfterBatch(batch = BATCH_COST)
    public static void afterCost(ServerLevel level) {
        runCleanups();
    }

    @AfterBatch(batch = BATCH_ACCESS)
    public static void afterAccess(ServerLevel level) {
        runCleanups();
    }

    @AfterBatch(batch = BATCH_AGE)
    public static void afterAge(ServerLevel level) {
        runCleanups();
    }

    private static void runCleanups() {
        for (Runnable cleanup : CLEANUPS) {
            try {
                cleanup.run();
            } catch (RuntimeException failure) {
                RanCraft.LOGGER.error("RANCraft fixed receiver game test cleanup failed", failure);
            }
        }
        CLEANUPS.clear();
    }

    // ---- test devices -----------------------------------------------------------------------------

    /** One dispatch as the device saw it. */
    record Dispatch(SignalSample sample, DeviceRequirement.Verdict verdict, long tick, BlockPos pos) {
    }

    /** A fixed device that records what it is handed. */
    static final class RecordingDevice implements FixedDevice {

        private final DeviceRequirement requirement;
        final List<Dispatch> dispatches = new ArrayList<>();

        RecordingDevice(DeviceRequirement requirement) {
            this.requirement = requirement;
        }

        @Override
        public DeviceRequirement requirement() {
            return requirement;
        }

        @Override
        public void onSample(ServerLevel level, BlockPos pos, DeviceContext ctx) {
            dispatches.add(new Dispatch(ctx.sample(), ctx.verdict(), ctx.tick(), pos.immutable()));
        }

        int count() {
            return dispatches.size();
        }

        Dispatch last() {
            return dispatches.get(dispatches.size() - 1);
        }
    }

    /**
     * A device block entity for the hook tests: the real {@link FixedDeviceBlockEntity} hooks, on the
     * vanilla chest's block entity type (see the class javadoc).
     */
    static final class TestDeviceBlockEntity extends FixedDeviceBlockEntity {

        final RecordingDevice recorder = new RecordingDevice(DeviceRequirement.NONE);

        TestDeviceBlockEntity(BlockPos pos) {
            super(BlockEntityType.CHEST, pos, Blocks.CHEST.defaultBlockState());
        }

        @Override
        public DeviceRequirement requirement() {
            return recorder.requirement();
        }

        @Override
        public void onSample(ServerLevel level, BlockPos pos, DeviceContext ctx) {
            recorder.onSample(level, pos, ctx);
        }
    }

    private static ServerLevel nether(GameTestHelper helper) {
        ServerLevel nether = helper.getLevel().getServer().getLevel(Level.NETHER);
        if (nether == null) {
            helper.fail("the game test server has no Nether");
        }
        return nether;
    }

    /**
     * Forces a Nether chunk (loaded now) and registers its release. The chunks the forced one makes
     * FULL (two rings round it) are loaded now as well: since the Phase 3B review a chunk reaching
     * FULL bumps its region epoch, and left to load in the background they would do so in the middle
     * of a test that counts replays.
     */
    private static LevelChunk forceNetherChunk(ServerLevel nether, int chunkX, int chunkZ) {
        nether.setChunkForced(chunkX, chunkZ, true);
        CLEANUPS.add(() -> nether.setChunkForced(chunkX, chunkZ, false));
        LevelChunk chunk = nether.getChunk(chunkX, chunkZ);
        loadFullRings(nether, chunkX, chunkZ);
        return chunk;
    }

    /** Loads, now, every chunk within two of a forced chunk: the ones its ticket makes FULL. */
    static void loadFullRings(ServerLevel level, int chunkX, int chunkZ) {
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                level.getChunk(chunkX + dx, chunkZ + dz);
            }
        }
    }

    private static int interval() {
        return Math.max(1, RanCraftConfig.snapshot().evaluationIntervalTicks());
    }

    // ---- 1. evaluate, replay, re-evaluate, stop ---------------------------------------------------

    /**
     * Two devices at block centres in a forced Nether chunk, a band_900 omni 13 blocks east. The first
     * dispatch is a fresh evaluation served by that cell, each device with its own verdict (POOR tier
     * 1: OK; POOR tier 3 on a tier-1 band: LOW_TIER). The next is a replay of the same sample, exactly
     * one interval later. A block change 500 blocks away keeps it a replay; stone on the link path
     * makes the next one fresh, reading the stone's loss more. Unregistered, the device hears nothing.
     */
    private static void tickerLifecycle(GameTestHelper helper) {
        ServerLevel nether = nether(helper);
        forceNetherChunk(nether, TICKER_CHUNK_X, CHUNK_Z);
        int baseX = TICKER_CHUNK_X * 16;
        int baseZ = CHUNK_Z * 16;
        BlockPos rx = new BlockPos(baseX + 1, AIR_Y, baseZ + 1);
        BlockPos lowTierRx = rx.above();
        BlockPos stone = new BlockPos(baseX + 7, AIR_Y, baseZ + 1);
        long cellId = -8_100_001L;
        CellParams cell = CellParams.omniDefaults(cellId, baseX + 14, AIR_Y, baseZ + 1);
        int interval = interval();

        SiteRegistry sites = SiteRegistry.of(nether);
        FixedReceiverRegistry registry = FixedReceiverRegistry.of(nether);
        RecordingDevice device = new RecordingDevice(new DeviceRequirement(ServiceLevel.POOR, 1));
        RecordingDevice lowTier = new RecordingDevice(new DeviceRequirement(ServiceLevel.POOR, 3));
        CLEANUPS.add(() -> {
            registry.unregister(rx, device);
            registry.unregister(lowTierRx, lowTier);
            sites.unregister(cellId);
            nether.setBlock(stone, Blocks.AIR.defaultBlockState(), 3);
        });
        sites.register(cell);
        helper.assertTrue(registry.register(rx, device) && registry.register(lowTierRx, lowTier), "registered");

        double stoneDb = RfDataLoader.materials().attenuationDb(Blocks.STONE.defaultBlockState())
                * RfDataLoader.bands().getOrFallback(cell.bandId()).penetrationFactor();
        int[] mark = new int[1];

        helper.startSequence()
                .thenWaitUntil(() -> helper.assertTrue(device.count() >= 1 && lowTier.count() >= 1,
                        "both devices are evaluated within one interval of registering"))
                .thenExecute(() -> {
                    Dispatch first = device.dispatches.get(0);
                    helper.assertTrue(first.sample().servingCellId() == cellId, "served by the cell 13 blocks east");
                    helper.assertTrue(first.tick() == first.sample().timestampTick(), "the first dispatch is fresh");
                    helper.assertTrue(first.pos().equals(rx), "the device is told its own block");
                    helper.assertTrue(first.verdict() == DeviceRequirement.Verdict.OK, "POOR tier 1: " + first.verdict());
                    helper.assertTrue(lowTier.dispatches.get(0).verdict() == DeviceRequirement.Verdict.LOW_TIER,
                            "POOR tier 3 on band_900 (tier 1): " + lowTier.dispatches.get(0).verdict());
                    helper.assertTrue(obstruction(first) == 0.0, "open air at first");
                })
                .thenWaitUntil(() -> helper.assertTrue(device.count() >= 2, "a second dispatch"))
                .thenExecute(() -> {
                    Dispatch first = device.dispatches.get(0);
                    Dispatch second = device.dispatches.get(1);
                    helper.assertTrue(second.sample() == first.sample(), "nothing changed: the cached sample is replayed");
                    helper.assertTrue(second.tick() - first.tick() == interval,
                            "exactly one interval later: " + (second.tick() - first.tick()));
                    // A block change 500 blocks south: another bin.
                    RegionEpochs.of(nether).bumpBlock(rx.getX(), rx.getZ() + 500);
                    mark[0] = device.count();
                })
                .thenWaitUntil(() -> helper.assertTrue(device.count() > mark[0], "a dispatch after the far change"))
                .thenExecute(() -> {
                    helper.assertTrue(device.last().sample() == device.dispatches.get(0).sample(),
                            "a block 500 blocks away does not invalidate the cached sample");
                    nether.setBlock(stone, Blocks.STONE.defaultBlockState(), 3);
                    RegionEpochs.of(nether).bumpBlock(stone.getX(), stone.getZ());
                    mark[0] = device.count();
                })
                .thenWaitUntil(() -> helper.assertTrue(device.count() > mark[0], "a dispatch after the path change"))
                .thenExecute(() -> {
                    Dispatch fresh = device.last();
                    helper.assertTrue(fresh.sample() != device.dispatches.get(0).sample()
                            && fresh.tick() == fresh.sample().timestampTick(), "a block on the link path: evaluated afresh");
                    helper.assertTrue(Math.abs(obstruction(fresh) - stoneDb) < 1e-9,
                            "and it reads the stone: " + obstruction(fresh) + " dB, stone " + stoneDb + " dB");
                    for (List<Dispatch> list : List.of(device.dispatches, lowTier.dispatches)) {
                        for (int i = 1; i < list.size(); i++) {
                            long gap = list.get(i).tick() - list.get(i - 1).tick();
                            helper.assertTrue(gap == interval, "every dispatch exactly one interval apart, got " + gap);
                        }
                    }
                    registry.unregister(rx, device);
                    registry.unregister(lowTierRx, lowTier);
                    mark[0] = device.count() + lowTier.count();
                })
                .thenIdle(2 * interval + 2)
                .thenExecute(() -> helper.assertTrue(device.count() + lowTier.count() == mark[0],
                        "unregistered devices hear nothing"))
                .thenSucceed();
    }

    // ---- 1b. the maximum replay age (row 16d) ---------------------------------------------------------

    /**
     * Row 16d: a block change that fires no event is still seen once the cached sample reaches the
     * receiver's age limit. Stone goes on the link path with a plain {@code setBlock} and no epoch bump,
     * as flowing water or {@code /fill} would put it there: the next dispatches replay the old, clear
     * sample, then a fresh evaluation at the limit ({@link FixedReceiverTicker#replayAgeLimitTicks}, 51
     * to 100 ticks here with {@value #MAX_REPLAY_TEST_TICKS}) reads the stone. With the limit off (0),
     * nothing would ever re-evaluate it.
     */
    private static void maxReplayAge(GameTestHelper helper) {
        ServerLevel nether = nether(helper);
        forceNetherChunk(nether, AGE_CHUNK_X, AGE_CHUNK_Z);
        int baseX = AGE_CHUNK_X * 16;
        int baseZ = AGE_CHUNK_Z * 16;
        BlockPos rx = new BlockPos(baseX + 1, AIR_Y, baseZ + 1);
        BlockPos stone = new BlockPos(baseX + 7, AIR_Y, baseZ + 1);
        long cellId = -8_100_003L;
        CellParams cell = CellParams.omniDefaults(cellId, baseX + 14, AIR_Y, baseZ + 1);
        int interval = interval();
        int limit = FixedReceiverTicker.replayAgeLimitTicks(rx.asLong(), MAX_REPLAY_TEST_TICKS);
        int configuredMaxReplay = RanCraftConfig.FIXED_RECEIVER_MAX_REPLAY_TICKS.get();

        SiteRegistry sites = SiteRegistry.of(nether);
        FixedReceiverRegistry registry = FixedReceiverRegistry.of(nether);
        RecordingDevice device = new RecordingDevice(new DeviceRequirement(ServiceLevel.POOR, 1));
        CLEANUPS.add(() -> {
            RanCraftConfig.FIXED_RECEIVER_MAX_REPLAY_TICKS.set(configuredMaxReplay);
            registry.unregister(rx, device);
            sites.unregister(cellId);
            nether.setBlock(stone, Blocks.AIR.defaultBlockState(), 3);
        });
        RanCraftConfig.FIXED_RECEIVER_MAX_REPLAY_TICKS.set(MAX_REPLAY_TEST_TICKS);
        sites.register(cell);
        helper.assertTrue(registry.register(rx, device), "registered");
        double stoneDb = RfDataLoader.materials().attenuationDb(Blocks.STONE.defaultBlockState())
                * RfDataLoader.bands().getOrFallback(cell.bandId()).penetrationFactor();

        helper.startSequence()
                .thenWaitUntil(() -> helper.assertTrue(device.count() >= 1, "evaluated once"))
                .thenExecute(() -> {
                    Dispatch first = device.dispatches.get(0);
                    helper.assertTrue(first.tick() == first.sample().timestampTick() && obstruction(first) == 0.0,
                            "a fresh evaluation in open air");
                    // No event and no epoch bump: invisible to the region epochs.
                    nether.setBlock(stone, Blocks.STONE.defaultBlockState(), 3);
                })
                .thenWaitUntil(() -> helper.assertTrue(firstFreshAfter(device, 1) >= 0, "evaluated afresh at last"))
                .thenExecute(() -> {
                    Dispatch first = device.dispatches.get(0);
                    int index = firstFreshAfter(device, 1);
                    Dispatch fresh = device.dispatches.get(index);
                    long age = fresh.tick() - first.tick();
                    helper.assertTrue(index >= 2, "at least one replay first: the change fired no event");
                    for (int i = 1; i < index; i++) {
                        helper.assertTrue(device.dispatches.get(i).sample() == first.sample(),
                                "before the limit the stone is not seen: dispatch " + i + " replays the clear sample");
                    }
                    helper.assertTrue(age >= limit && age < limit + interval,
                            "evaluated again at the first turn at or past the age limit " + limit + ": after " + age);
                    helper.assertTrue(Math.abs(obstruction(fresh) - stoneDb) < 1e-9,
                            "the fresh evaluation reads the stone: " + obstruction(fresh) + " dB, stone " + stoneDb + " dB");
                })
                .thenSucceed();
    }

    /** The index of the first fresh dispatch at or after {@code from}, or -1. */
    private static int firstFreshAfter(RecordingDevice device, int from) {
        for (int i = from; i < device.count(); i++) {
            Dispatch dispatch = device.dispatches.get(i);
            if (dispatch.tick() == dispatch.sample().timestampTick()) {
                return i;
            }
        }
        return -1;
    }

    private static double obstruction(Dispatch dispatch) {
        CellSample serving = dispatch.sample().serving().orElse(null);
        return serving == null ? Double.NaN : serving.obstructionDb();
    }

    // ---- 2. the chunk paths -----------------------------------------------------------------------

    /**
     * Paths 3 and 4 with a real chunk. A device block entity is put into a forced Nether chunk
     * ({@code Level.setBlockEntity}, which defers its {@code onLoad} but registers it at once through
     * {@code clearRemoved}, slice 9); dropped by hand, it is registered again by {@link ChunkEvent.Load},
     * posted on the event bus for that chunk, and it is served. Then the chunk is released and
     * really unloads: the unload event drops it (by position), the game removes the entity
     * ({@code setRemoved}, path 2 on unload), and it hears nothing more. Loading the chunk again fires
     * the real load event, which registers nothing: the saved entity came back as a plain chest (the
     * harness limit in the class javadoc).
     */
    private static void chunkPaths(GameTestHelper helper) {
        ServerLevel nether = nether(helper);
        int chunkX = CHUNKS_CHUNK_X;
        LevelChunk chunk = forceNetherChunk(nether, chunkX, CHUNK_Z);
        BlockPos pos = new BlockPos(chunkX * 16 + 3, AIR_Y, CHUNK_Z * 16 + 3);
        FixedReceiverRegistry registry = FixedReceiverRegistry.of(nether);
        TestDeviceBlockEntity device = new TestDeviceBlockEntity(pos);
        CLEANUPS.add(() -> {
            registry.unregister(pos, device);
            nether.setBlock(pos, Blocks.AIR.defaultBlockState(), 3);
        });

        nether.setBlock(pos, Blocks.CHEST.defaultBlockState(), 3);
        nether.setBlockEntity(device);
        helper.assertTrue(nether.getBlockEntity(pos) == device, "the device entity is in the chunk");
        helper.assertTrue(registry.deviceAt(pos) == device,
                "joining the chunk registered it at once (FixedDeviceBlockEntity.clearRemoved, slice 9)");
        // Path 3 on its own: drop it, then post the load event.
        registry.unregister(pos, device);
        helper.assertTrue(!registry.contains(pos), "dropped by hand");
        NeoForge.EVENT_BUS.post(new ChunkEvent.Load(chunk, false));
        helper.assertTrue(registry.deviceAt(pos) == device, "the chunk load event registered it (path 3)");
        int[] mark = new int[1];

        helper.startSequence()
                .thenWaitUntil(() -> helper.assertTrue(device.recorder.count() >= 1, "served while loaded"))
                .thenExecute(() -> {
                    helper.assertTrue(device.recorder.last().pos().equals(pos), "at its own block");
                    nether.setChunkForced(chunkX, CHUNK_Z, false);
                })
                .thenWaitUntil(() -> helper.assertTrue(!registry.contains(pos), "the chunk unloaded and the unload event dropped it"))
                .thenExecute(() -> {
                    helper.assertTrue(device.isRemoved(), "the game removed the entity too (setRemoved, path 2)");
                    helper.assertTrue(!nether.getChunkSource().hasChunk(chunkX, CHUNK_Z), "the chunk is gone");
                    helper.assertTrue(registry.countInChunk(chunkX, CHUNK_Z) == 0, "nothing left in that chunk");
                    mark[0] = device.recorder.count();
                })
                .thenIdle(2 * interval() + 2)
                .thenExecute(() -> {
                    helper.assertTrue(device.recorder.count() == mark[0], "an unloaded device hears nothing");
                    nether.getChunk(chunkX, CHUNK_Z);
                    helper.assertTrue(nether.getBlockEntity(pos) instanceof ChestBlockEntity,
                            "reloaded: the saved entity is a plain chest");
                    helper.assertTrue(!registry.contains(pos), "so the real load event registers nothing");
                })
                .thenSucceed();
    }

    // ---- 3. onLoad and setRemoved -----------------------------------------------------------------

    /**
     * Paths 1 and 2, called directly on entities that are not in a chunk: {@code onLoad} registers,
     * the ticker serves it at its block, and {@code setRemoved} is by identity, so the late removal of
     * a replaced entity does not drop its successor at the same position. An entity with no level
     * (the client's, before it is placed) registers nothing.
     */
    private static void hooks(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos pos = helper.absolutePos(new BlockPos(1, 1, 1));
        FixedReceiverRegistry registry = FixedReceiverRegistry.of(level);
        TestDeviceBlockEntity first = new TestDeviceBlockEntity(pos);
        TestDeviceBlockEntity successor = new TestDeviceBlockEntity(pos);
        CLEANUPS.add(() -> {
            registry.unregister(pos, first);
            registry.unregister(pos, successor);
        });

        new TestDeviceBlockEntity(pos).onLoad();
        helper.assertTrue(!registry.contains(pos), "no level, no registration");
        first.setLevel(level);
        first.onLoad();
        helper.assertTrue(registry.deviceAt(pos) == first, "onLoad registers (path 1)");
        int[] mark = new int[1];

        helper.startSequence()
                .thenWaitUntil(() -> helper.assertTrue(first.recorder.count() >= 1, "the ticker serves it"))
                .thenExecute(() -> {
                    helper.assertTrue(first.recorder.last().pos().equals(pos), "at its own block");
                    successor.setLevel(level);
                    successor.onLoad();
                    helper.assertTrue(registry.deviceAt(pos) == successor, "a new entity at the position takes over");
                    first.setRemoved();
                    helper.assertTrue(registry.deviceAt(pos) == successor,
                            "the old entity's late setRemoved does not drop its successor");
                    successor.setRemoved();
                    helper.assertTrue(!registry.contains(pos), "setRemoved unregisters (path 2)");
                    mark[0] = first.recorder.count() + successor.recorder.count();
                })
                .thenIdle(2 * interval() + 2)
                .thenExecute(() -> helper.assertTrue(first.recorder.count() + successor.recorder.count() == mark[0],
                        "removed devices hear nothing"))
                .thenSucceed();
    }

    // ---- 4. a chunk on the ray reaching or leaving FULL (Phase 3B review) -------------------------

    /**
     * The probe reads a chunk that is not FULL as air, so a chunk on a receiver's ray reaching or
     * leaving FULL changes what a fresh evaluation would give with no block event. Before the Phase 3B
     * review nothing bumped an epoch then, and a fixed receiver replayed its old sample indefinitely.
     *
     * <p>A receiver in a forced chunk, a band_900 omni about 140 blocks east in another, and a stone
     * wall (one block thick) across the link, in the chunk halfway, built while that chunk was forced.
     * <ol>
     *   <li>The middle chunk is released: it stays in memory (ticket level 35, four chunks from each
     *       forced one) but is no longer FULL. Its neighbours were let finish loading first, so nothing
     *       else loads later. The receiver is evaluated through air (obstruction 0) and replays.</li>
     *   <li>The middle chunk is forced again. It is the same {@code LevelChunk}, kept in memory, so no
     *       chunk load event fires; the ticket-level change is the only signal. The receiver is
     *       evaluated afresh and reads the wall.</li>
     *   <li>Released again (it leaves FULL with no unload event): evaluated afresh, through air.</li>
     * </ol>
     */
    private static void chunkAccess(GameTestHelper helper) {
        ServerLevel nether = nether(helper);
        int z = ACCESS_CHUNK_Z;
        int wallChunk = ACCESS_WALL_CHUNK_X;
        forceNetherChunk(nether, ACCESS_RX_CHUNK_X, z);
        forceNetherChunk(nether, ACCESS_CELL_CHUNK_X, z);
        LevelChunk middle = forceNetherChunk(nether, wallChunk, z);
        int baseZ = z * 16;
        BlockPos rx = new BlockPos(ACCESS_RX_CHUNK_X * 16 + 1, AIR_Y, baseZ + 1);
        int wallX = wallChunk * 16 + 8;
        long cellId = -8_100_301L;
        CellParams cell = CellParams.omniDefaults(cellId, ACCESS_CELL_CHUNK_X * 16 + 14, AIR_Y, baseZ + 1);
        List<BlockPos> wall = new ArrayList<>();
        for (int dy = -1; dy <= 1; dy++) {
            for (int dz = 0; dz <= 2; dz++) {
                wall.add(new BlockPos(wallX, AIR_Y + dy, baseZ + dz));
            }
        }
        for (BlockPos stone : wall) {
            nether.setBlock(stone, Blocks.STONE.defaultBlockState(), 3);
        }

        SiteRegistry sites = SiteRegistry.of(nether);
        FixedReceiverRegistry registry = FixedReceiverRegistry.of(nether);
        RecordingDevice device = new RecordingDevice(new DeviceRequirement(ServiceLevel.POOR, 1));
        // Runs after the three releases above (this class runs its cleanups oldest first).
        CLEANUPS.add(() -> {
            registry.unregister(rx, device);
            sites.unregister(cellId);
            nether.setChunkForced(wallChunk, z, true);
            nether.getChunk(wallChunk, z);
            for (BlockPos stone : wall) {
                nether.setBlock(stone, Blocks.AIR.defaultBlockState(), 3);
            }
            nether.setChunkForced(wallChunk, z, false);
        });
        double stoneDb = RfDataLoader.materials().attenuationDb(Blocks.STONE.defaultBlockState())
                * RfDataLoader.bands().getOrFallback(cell.bandId()).penetrationFactor();
        int interval = interval();
        long[] mark = new long[2];

        helper.startSequence()
                // Let every chunk the three forced ones made FULL finish loading, so no load event of
                // theirs lands later in the test.
                .thenWaitUntil(() -> {
                    for (int x = ACCESS_RX_CHUNK_X - 2; x <= ACCESS_CELL_CHUNK_X + 2; x++) {
                        helper.assertTrue(nether.getChunkSource().getChunkNow(x, z) != null, "chunk " + x + " not FULL yet");
                    }
                })
                .thenExecute(() -> nether.setChunkForced(wallChunk, z, false))
                .thenWaitUntil(() -> helper.assertTrue(nether.getChunkSource().getChunkNow(wallChunk, z) == null,
                        "the middle chunk leaves FULL"))
                .thenExecute(() -> {
                    sites.register(cell);
                    helper.assertTrue(registry.register(rx, device), "registered");
                })
                .thenWaitUntil(() -> helper.assertTrue(device.count() >= 2
                                && device.last().sample() == device.dispatches.get(device.count() - 2).sample(),
                        "evaluated, then replayed"))
                .thenExecute(() -> {
                    helper.assertTrue(device.dispatches.get(0).sample().servingCellId() == cellId,
                            "served by the cell to the east");
                    helper.assertTrue(obstruction(device.last()) == 0.0,
                            "the wall's chunk is not FULL: read as air, obstruction " + obstruction(device.last()));
                    helper.assertTrue(nether.getChunkSource().getChunkNow(wallChunk, z) == null, "still not FULL");
                    // 2. Back to FULL, from memory: no load event.
                    mark[0] = device.count();
                    mark[1] = nether.getGameTime();
                    nether.setChunkForced(wallChunk, z, true);
                })
                .thenWaitUntil(() -> helper.assertTrue(freshSince(device, (int) mark[0]) != null,
                        "a fresh evaluation after the wall's chunk came back to FULL"))
                .thenExecute(() -> {
                    Dispatch fresh = freshSince(device, (int) mark[0]);
                    helper.assertTrue(nether.getChunkSource().getChunkNow(wallChunk, z) == middle,
                            "the same chunk object came back: it was kept in memory, so no chunk load event fired");
                    helper.assertTrue(fresh.tick() - mark[1] <= 2L * interval + 2,
                            "within about an interval: " + (fresh.tick() - mark[1]) + " ticks");
                    helper.assertTrue(Math.abs(obstruction(fresh) - stoneDb) < 1e-9,
                            "it reads the wall: " + obstruction(fresh) + " dB, stone " + stoneDb + " dB");
                    // 3. Out of FULL again: no unload event (still in memory).
                    mark[0] = device.count();
                    mark[1] = nether.getGameTime();
                    nether.setChunkForced(wallChunk, z, false);
                })
                .thenWaitUntil(() -> helper.assertTrue(freshSince(device, (int) mark[0]) != null,
                        "a fresh evaluation after the wall's chunk left FULL"))
                .thenExecute(() -> {
                    Dispatch fresh = freshSince(device, (int) mark[0]);
                    helper.assertTrue(nether.getChunkSource().getChunkNow(wallChunk, z) == null, "not FULL");
                    helper.assertTrue(fresh.tick() - mark[1] <= 2L * interval + 2,
                            "within about an interval: " + (fresh.tick() - mark[1]) + " ticks");
                    helper.assertTrue(obstruction(fresh) == 0.0, "through air again: " + obstruction(fresh) + " dB");
                    registry.unregister(rx, device);
                })
                .thenSucceed();
    }

    /** The first fresh evaluation dispatched at or after index {@code from}, or {@code null}. */
    private static Dispatch freshSince(RecordingDevice device, int from) {
        for (int i = from; i < device.count(); i++) {
            Dispatch dispatch = device.dispatches.get(i);
            if (dispatch.tick() == dispatch.sample().timestampTick()) {
                return dispatch;
            }
        }
        return null;
    }

    // ---- 5. cost ----------------------------------------------------------------------------------

    /**
     * 200 receivers in a forced Nether chunk round one band_900 omni: the ticker's own cost, measured
     * (the devices only record; the Radio Link's work comes on top in slice 9). Every number is logged;
     * the done-when's 0.1 ms per tick is asserted on the warm steady state.
     *
     * <ol>
     *   <li><b>Cold</b>: the first interval after registering, on a JVM that has just started, so the
     *       code runs mostly interpreted. One fresh evaluation each.
     *   <li><b>Evaluation warm-up</b> (JIT): {@code evaluationIntervalTicks} set to 1 in memory (never
     *       saved, put back afterwards, also on failure), so every receiver is due every tick and the
     *       budget decides how many are served, with a block-change bump in their bin every tick, so
     *       every service is a fresh evaluation. {@value #WARMUP_TICKS} ticks.
     *   <li><b>Warm first interval</b>: interval restored, every receiver re-registered (fresh
     *       stagger, no cached sample): one fresh evaluation each, spread over the interval.
     *   <li><b>Steady-state warm-up</b>: {@value #STEADY_WARMUP_TICKS} ticks at the real interval, so
     *       the round robin is compiled for what a quiet server does (most receivers not due, a few
     *       replayed). A warm-up where every receiver is always due compiles the scan for the wrong
     *       case: the first measurements read the scan at about 50 ns per receiver.
     *   <li><b>Steady state</b>: a window of about {@value #COST_WINDOW_TICKS} ticks must be replays
     *       only, each receiver once per interval. Reported per tick, per tick without the slowest
     *       tick (so one GC pause cannot decide it), split into setup, scan and serving, and per replay.
     * </ol>
     */
    private static void cost(GameTestHelper helper) {
        ServerLevel nether = nether(helper);
        forceNetherChunk(nether, COST_CHUNK_X, CHUNK_Z);
        int baseX = COST_CHUNK_X * 16;
        int baseZ = CHUNK_Z * 16;
        long cellId = -8_100_002L;
        CellParams cell = CellParams.omniDefaults(cellId, baseX + 14, AIR_Y + 5, baseZ + 14);
        int interval = interval();
        int configuredInterval = RanCraftConfig.EVALUATION_INTERVAL_TICKS.get();

        SiteRegistry sites = SiteRegistry.of(nether);
        FixedReceiverRegistry registry = FixedReceiverRegistry.of(nether);
        List<BlockPos> positions = new ArrayList<>();
        List<RecordingDevice> devices = new ArrayList<>();
        for (int i = 0; i < RECEIVERS_FOR_COST; i++) {
            positions.add(new BlockPos(baseX + i % 10, AIR_Y + i / 100, baseZ + (i / 10) % 10));
            devices.add(new RecordingDevice(new DeviceRequirement(ServiceLevel.POOR, 1)));
        }
        Runnable registerAll = () -> {
            for (int i = 0; i < RECEIVERS_FOR_COST; i++) {
                registry.register(positions.get(i), devices.get(i));
            }
        };
        Runnable unregisterAll = () -> {
            for (int i = 0; i < RECEIVERS_FOR_COST; i++) {
                registry.unregister(positions.get(i), devices.get(i));
            }
        };
        // Row 16d: the steady state measured here is the replay path. The maximum replay age adds one
        // fresh evaluation per receiver per 300-600 ticks on top (NOTES.md, row 16d), so it is off here.
        int configuredMaxReplay = RanCraftConfig.FIXED_RECEIVER_MAX_REPLAY_TICKS.get();
        RanCraftConfig.FIXED_RECEIVER_MAX_REPLAY_TICKS.set(0);
        CLEANUPS.add(() -> {
            RanCraftConfig.EVALUATION_INTERVAL_TICKS.set(configuredInterval);
            RanCraftConfig.FIXED_RECEIVER_MAX_REPLAY_TICKS.set(configuredMaxReplay);
            unregisterAll.run();
            sites.unregister(cellId);
        });
        sites.register(cell);
        FixedReceiverTicker.resetStats();
        registerAll.run();

        FixedReceiverTicker.Stats[] phase = new FixedReceiverTicker.Stats[3];
        int[] counts = new int[RECEIVERS_FOR_COST];
        Runnable markCounts = () -> {
            for (int i = 0; i < RECEIVERS_FOR_COST; i++) {
                counts[i] = devices.get(i).count();
            }
        };
        Runnable eachServedSinceMark = () -> {
            for (int i = 0; i < RECEIVERS_FOR_COST; i++) {
                helper.assertTrue(devices.get(i).count() > counts[i], "receiver " + i + " not evaluated yet");
            }
        };

        helper.startSequence()
                // 1. Cold.
                .thenWaitUntil(() -> helper.assertTrue(devices.stream().allMatch(d -> d.count() >= 1),
                        "every receiver has had its first evaluation"))
                .thenExecute(() -> {
                    phase[0] = FixedReceiverTicker.stats();
                    helper.assertTrue(phase[0].evaluations() == RECEIVERS_FOR_COST, "one fresh evaluation each: " + phase[0]);
                    // 2. Warm-up.
                    RanCraftConfig.EVALUATION_INTERVAL_TICKS.set(1);
                    FixedReceiverTicker.resetStats();
                })
                .thenExecuteFor(WARMUP_TICKS, () -> RegionEpochs.of(nether).bumpBlock(baseX, baseZ))
                .thenExecute(() -> {
                    phase[1] = FixedReceiverTicker.stats();
                    // 3. Warm first interval.
                    RanCraftConfig.EVALUATION_INTERVAL_TICKS.set(configuredInterval);
                    unregisterAll.run();
                    markCounts.run();
                    FixedReceiverTicker.resetStats();
                    registerAll.run();
                })
                .thenWaitUntil(eachServedSinceMark)
                .thenExecute(() -> {
                    phase[2] = FixedReceiverTicker.stats();
                    helper.assertTrue(phase[2].evaluations() == RECEIVERS_FOR_COST,
                            "re-registered: one fresh evaluation each: " + phase[2]);
                })
                // 4. Steady-state warm-up, then 5. steady state.
                .thenIdle(STEADY_WARMUP_TICKS)
                .thenExecute(() -> {
                    FixedReceiverTicker.resetStats();
                    markCounts.run();
                })
                .thenIdle(COST_WINDOW_TICKS)
                .thenExecute(() -> {
                    FixedReceiverTicker.Stats steady = FixedReceiverTicker.stats();
                    // The window is the ticker runs between the two reads.
                    long fewest = steady.ticks() / interval;
                    long most = (steady.ticks() + interval - 1) / interval;
                    long total = 0;
                    for (int i = 0; i < RECEIVERS_FOR_COST; i++) {
                        int got = devices.get(i).count() - counts[i];
                        total += got;
                        helper.assertTrue(got >= fewest && got <= most, "receiver " + i + " served " + got
                                + " times in " + steady.ticks() + " ticks: once per interval");
                    }
                    helper.assertTrue(steady.evaluations() == 0 && steady.replays() == total && steady.skipped() == 0,
                            "steady state is replays only: " + steady + ", " + total + " dispatches");
                    double perTickUs = us(steady.nanos()) / steady.ticks();
                    double perTickWithoutWorstUs = us(steady.nanos() - steady.maxTickNanos()) / (steady.ticks() - 1);
                    FixedReceiverTicker.Stats cold = phase[0];
                    FixedReceiverTicker.Stats warmUp = phase[1];
                    FixedReceiverTicker.Stats warm = phase[2];
                    RanCraft.LOGGER.info(String.format(Locale.ROOT,
                            "RANCraft fixed receivers cost: %d receivers, interval %d, budget %.2f ms. "
                                    + "Cold JVM, first interval: %.1f us per fresh evaluation, %.1f us/tick, slowest tick %.1f us. "
                                    + "Evaluation warm-up at interval 1: %d evaluations (%d replays) over %d ticks, slowest tick %.1f us. "
                                    + "Warm, first interval: %.2f us per fresh evaluation, %.1f us/tick over %d ticks, slowest tick %.1f us. "
                                    + "Steady state over %d ticks: %.2f us/tick (%.2f without the slowest, which took %.1f us), "
                                    + "of which setup %.2f us/tick, scan %.2f us/tick, serving %.2f us/tick (%.3f us per "
                                    + "replay, dispatch included); %d replays, %d evaluations",
                            RECEIVERS_FOR_COST, interval, RanCraftConfig.fixedReceiverTickBudgetMs(),
                            us(cold.nanos()) / cold.evaluations(), us(cold.nanos()) / cold.ticks(), us(cold.maxTickNanos()),
                            warmUp.evaluations(), warmUp.replays(), warmUp.ticks(), us(warmUp.maxTickNanos()),
                            us(warm.nanos()) / warm.evaluations(), us(warm.nanos()) / warm.ticks(), warm.ticks(),
                            us(warm.maxTickNanos()),
                            steady.ticks(), perTickUs, perTickWithoutWorstUs, us(steady.maxTickNanos()),
                            us(steady.setupNanos()) / steady.ticks(),
                            us(steady.nanos() - steady.setupNanos() - steady.serveNanos()) / steady.ticks(),
                            us(steady.serveNanos()) / steady.ticks(), us(steady.serveNanos()) / steady.replays(),
                            steady.replays(), steady.evaluations()));
                    helper.assertTrue(warmUp.evaluations() >= WARMUP_TICKS, "the evaluation warm-up evaluated: " + warmUp);
                    helper.assertTrue(perTickWithoutWorstUs < STEADY_STATE_LIMIT_US,
                            "3B done-when: under 0.1 ms per tick in steady state, got " + perTickWithoutWorstUs + " us");
                })
                .thenSucceed();
    }

    private static double us(long nanos) {
        return nanos / 1_000.0;
    }
}
