package dev.rancraft.gametest;

import dev.rancraft.RanCraft;
import dev.rancraft.RanCraftConfig;
import dev.rancraft.data.RfDataLoader;
import dev.rancraft.rf.BandTable;
import dev.rancraft.rf.BinTraversal;
import dev.rancraft.rf.CellParams;
import dev.rancraft.rf.RayMarcher;
import dev.rancraft.rf.ReceiverState;
import dev.rancraft.rf.RfConfig;
import dev.rancraft.rf.RfEngine;
import dev.rancraft.world.LevelWorldProbe;
import dev.rancraft.world.RegionEpochs;
import dev.rancraft.world.SignalTicker;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTestGenerator;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.TestFunction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.piston.PistonBaseBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.neoforge.event.level.BlockGrowFeatureEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;

/**
 * Region block epochs at runtime (Phase 3 slice 7, §3B.2): the real event paths of a live server
 * level bump the right 128-block bin, and a cached sample's dependency set ignores a block 500
 * blocks away but not one on its link path. {@code BinTraversalTest}, {@code RfEngineDependencyTest},
 * {@code RegionEpochsTest} and {@code SignalTickerCacheTest} pin the rules headless; these check the
 * wiring.
 *
 * <p>Blocks are placed the way a player places them: a mock player (vanilla's
 * {@link GameTestHelper#makeMockPlayer}, never on the player list) uses a block item, which runs
 * NeoForge's placement hook and fires {@link BlockEvent.EntityPlaceEvent} (a bed fires the
 * multi-block event). A break needs a connected {@code ServerPlayer} to go through the game's own
 * path, so the break test posts {@link BlockEvent.BreakEvent} on the event bus itself: it checks the
 * listener, not the game's decision to fire it.
 *
 * <p>Each test runs in a batch of its own: batches run one after another, the tests of one batch
 * side by side, and the tests count bumps in bins they may share.
 */
@GameTestHolder(RanCraft.MOD_ID)
public final class RegionEpochGameTests {

    private static final String BATCH_CACHE = "rancraft_region_epochs_cache";
    private static final String BATCH_PLACE = "rancraft_region_epochs_place";
    private static final String BATCH_EXPLOSION = "rancraft_region_epochs_explosion";
    private static final String BATCH_PISTON = "rancraft_region_epochs_piston";
    private static final int TIMEOUT_TICKS = 100;

    private RegionEpochGameTests() {
    }

    @GameTestGenerator
    public static Collection<TestFunction> regionEpochTests() {
        String prefix = RegionEpochGameTests.class.getSimpleName().toLowerCase(Locale.ROOT) + ".";
        List<TestFunction> tests = new ArrayList<>();
        tests.add(test(BATCH_CACHE, prefix + "far_block_keeps_the_cache_link_path_block_does_not",
                RegionEpochGameTests::farBlockVsLinkPath));
        tests.add(test(BATCH_PLACE, prefix + "placing_breaking_and_growth_bump_their_bin_only",
                RegionEpochGameTests::placeAndBreak));
        tests.add(test(BATCH_EXPLOSION, prefix + "an_explosion_bumps_its_bin", RegionEpochGameTests::explosion));
        tests.add(test(BATCH_PISTON, prefix + "a_piston_bumps_now_and_after_the_blocks_settle",
                RegionEpochGameTests::piston));
        return tests;
    }

    private static TestFunction test(String batch, String name, Consumer<GameTestHelper> body) {
        return new TestFunction(batch, name, HarvestGameTests.EMPTY_TEMPLATE, TIMEOUT_TICKS, 0L, true, body);
    }

    // ---- 3B done-when: 500 blocks away vs on the link path ----------------------------------------

    /**
     * 3B done-when, on live ground: "placing a block 500 blocks away no longer invalidates a
     * player's cached sample; placing one on the link path does."
     *
     * <p>The receiver is the eye point the ticker would use, inside the test area. The cell is a
     * band_900 omni 300 blocks east at the same height (a {@link CellParams} handed to the engine,
     * not a placed mast, so no leftover antenna of another test can join the evaluation), so its ray
     * crosses three bins. The evaluation is the ticker's: the same probe, engine call and dependency
     * snapshot ({@link RfEngine.Evaluation#dependencyBins}, {@link RegionEpochs#snapshot}) the cache
     * keeps. Then a mock player places stone 500 blocks south of the receiver (another bin): the
     * snapshot holds, and the march reads exactly the same obstruction. Then stone on the link
     * path, 150 blocks east, in a bin that holds neither end: the snapshot breaks, and the march
     * reads the stone's loss more. Both far chunks are loaded first (flat test world) and the stone
     * is removed afterwards without an event.
     */
    private static void farBlockVsLinkPath(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        RegionEpochs epochs = RegionEpochs.of(level);
        BlockPos rx = helper.absolutePos(new BlockPos(1, 1, 1));
        double eyeX = rx.getX() + 0.5;
        double eyeY = rx.getY() + 0.5;
        double eyeZ = rx.getZ() + 0.5;
        CellParams cell = CellParams.omniDefaults(-1L, rx.getX() + 300, rx.getY(), rx.getZ());
        RfConfig config = RanCraftConfig.snapshot();
        BandTable bands = RfDataLoader.bands();

        RfEngine.Evaluation evaluation = RfEngine.evaluate(new LevelWorldProbe(level, RfDataLoader.materials()),
                eyeX, eyeY, eyeZ, List.of(cell), bands, config, level.getGameTime(), ReceiverState.NONE);
        helper.assertTrue(evaluation.marched().size() == 1, "the cell 300 blocks east is marched");
        long[] bins = evaluation.dependencyBins(RegionEpochs.BIN_SIZE);
        // Along +x at one z: 3 bins, or 4 when the receiver stands 84 or more blocks into its bin (44
        // of 128 offsets). The test's position varies per run; the fixed "3" of slice 7 failed once
        // in about ten runs during slice 8.
        int expectedBins = Math.floorDiv(cell.x(), RegionEpochs.BIN_SIZE) - Math.floorDiv(rx.getX(), RegionEpochs.BIN_SIZE) + 1;
        helper.assertTrue(bins.length == expectedBins,
                "a 300-block ray crosses " + expectedBins + " bins from x " + rx.getX() + ", got " + bins.length);
        RegionEpochs.Snapshot cached = epochs.snapshot(bins);
        double obstructionBefore = obstruction(level, cell, eyeX, eyeY, eyeZ, bands, config);

        Player player = helper.makeMockPlayer(GameType.CREATIVE);
        BlockPos far = new BlockPos(rx.getX(), rx.getY(), rx.getZ() + 500);
        BlockPos onPath = new BlockPos(rx.getX() + 150, rx.getY(), rx.getZ());
        long farBin = BinTraversal.keyOfBlock(far.getX(), far.getZ(), RegionEpochs.BIN_SIZE);
        long pathBin = BinTraversal.keyOfBlock(onPath.getX(), onPath.getZ(), RegionEpochs.BIN_SIZE);
        helper.assertFalse(cached.covers(farBin), "500 blocks south is not a dependency bin");
        helper.assertTrue(cached.covers(pathBin), "the link path's middle bin is a dependency");
        helper.assertTrue(pathBin != BinTraversal.keyOfBlock(rx.getX(), rx.getZ(), RegionEpochs.BIN_SIZE)
                        && pathBin != BinTraversal.keyOfBlock(cell.x(), cell.z(), RegionEpochs.BIN_SIZE),
                "the path block's bin holds neither the receiver nor the antenna");

        try {
            long totalBefore = SignalTicker.blockEpochOf(level);
            placeWithItem(helper, player, far, new ItemStack(Items.STONE));
            helper.assertTrue(SignalTicker.blockEpochOf(level) == totalBefore + 1,
                    "the far placement was seen (the dimension-wide sum moved by one)");
            helper.assertTrue(epochs.unchanged(cached), "a block 500 blocks away no longer invalidates the cached sample");
            double obstructionFar = obstruction(level, cell, eyeX, eyeY, eyeZ, bands, config);
            helper.assertTrue(obstructionFar == obstructionBefore,
                    "and rightly: the evaluation reads the same obstruction (" + obstructionFar + " vs " + obstructionBefore + ")");

            placeWithItem(helper, player, onPath, new ItemStack(Items.STONE));
            helper.assertFalse(epochs.unchanged(cached), "a block on the link path invalidates it");
            double stoneDb = RfDataLoader.materials().attenuationDb(Blocks.STONE.defaultBlockState())
                    * bands.getOrFallback(cell.bandId()).penetrationFactor();
            double obstructionPath = obstruction(level, cell, eyeX, eyeY, eyeZ, bands, config);
            helper.assertTrue(Math.abs(obstructionPath - (obstructionBefore + stoneDb)) < 1e-9,
                    "and rightly: the march now reads the stone (" + obstructionBefore + " -> " + obstructionPath
                            + " dB, stone " + stoneDb + " dB)");

            measureDependencyCost(level, rx, config, bands);
        } finally {
            level.setBlock(far, Blocks.AIR.defaultBlockState(), 3);
            level.setBlock(onPath, Blocks.AIR.defaultBlockState(), 3);
        }
        helper.succeed();
    }

    private static double obstruction(ServerLevel level, CellParams cell, double eyeX, double eyeY, double eyeZ,
            BandTable bands, RfConfig config) {
        return RayMarcher.march(new LevelWorldProbe(level, RfDataLoader.materials()),
                cell.centerX(), cell.centerY(), cell.centerZ(), eyeX, eyeY, eyeZ,
                bands.getOrFallback(cell.bandId()).penetrationFactor(), config.maxObstructionDb(),
                config.maxRaySteps()).obstructionDb();
    }

    /**
     * Logs what the dependency set costs on top of an evaluation: twelve cells between 100 and 1300
     * blocks away round the receiver (the worst case: every ray long, {@code maxCellsEvaluated} 12),
     * evaluated on live ground (unloaded chunks read as air), then the dependency bins, the snapshot
     * and the replay check repeated. Wall-clock in a shared JVM after a warm-up: an order of
     * magnitude, not a benchmark.
     */
    private static void measureDependencyCost(ServerLevel level, BlockPos rx, RfConfig config, BandTable bands) {
        List<CellParams> cells = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            double angle = Math.toRadians(i * 30.0 + 7.0);
            int distance = 100 + i * 100;
            cells.add(CellParams.omniDefaults(-100L - i,
                    rx.getX() + (int) Math.round(Math.cos(angle) * distance), rx.getY() + 20,
                    rx.getZ() + (int) Math.round(Math.sin(angle) * distance)));
        }
        double eyeX = rx.getX() + 0.5;
        double eyeY = rx.getY() + 0.5;
        double eyeZ = rx.getZ() + 0.5;
        LevelWorldProbe probe = new LevelWorldProbe(level, RfDataLoader.materials());
        RegionEpochs epochs = RegionEpochs.of(level);

        final int reps = 2_000;
        long sink = 0;
        RfEngine.Evaluation evaluation = null;
        for (int i = 0; i < reps / 10; i++) {
            evaluation = RfEngine.evaluate(probe, eyeX, eyeY, eyeZ, cells, bands, config, 0L, ReceiverState.NONE);
            sink += evaluation.dependencyBins(RegionEpochs.BIN_SIZE).length;
        }
        long t0 = System.nanoTime();
        for (int i = 0; i < reps / 10; i++) {
            evaluation = RfEngine.evaluate(probe, eyeX, eyeY, eyeZ, cells, bands, config, 0L, ReceiverState.NONE);
        }
        long t1 = System.nanoTime();
        long[] bins = null;
        for (int i = 0; i < reps; i++) {
            bins = evaluation.dependencyBins(RegionEpochs.BIN_SIZE);
            sink += bins.length;
        }
        long t2 = System.nanoTime();
        RegionEpochs.Snapshot snapshot = null;
        for (int i = 0; i < reps; i++) {
            snapshot = epochs.snapshot(bins);
            sink += snapshot.size();
        }
        long t3 = System.nanoTime();
        for (int i = 0; i < reps; i++) {
            sink += epochs.unchanged(snapshot) ? 1 : 0;
        }
        long t4 = System.nanoTime();
        RanCraft.LOGGER.info(String.format(Locale.ROOT,
                "RANCraft region epochs cost: %d rays marched (12 cells at 100-1300 blocks), %d dependency bins; evaluation %.1f us,"
                        + " dependency bins %.2f us, snapshot %.2f us, replay check %.2f us [%d]",
                evaluation.marched().size(), bins.length, (t1 - t0) / 1_000.0 / (reps / 10),
                (t2 - t1) / 1_000.0 / reps, (t3 - t2) / 1_000.0 / reps, (t4 - t3) / 1_000.0 / reps, sink));
    }

    // ---- the event paths --------------------------------------------------------------------------

    /**
     * A placement bumps its block's bin by one (and the dimension-wide sum), a bed (two blocks, the
     * multi-block event) by one more, a break by one more, and a feature growing next to it by one
     * more; a bin 500 blocks away never moves. The break and the growth are posted on the event bus
     * (see the class javadoc).
     */
    private static void placeAndBreak(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        RegionEpochs epochs = RegionEpochs.of(level);
        BlockPos stone = helper.absolutePos(new BlockPos(1, 0, 1));
        // Not (0, 0, 0): the test's structure block stands there. The bed's head goes south (the
        // mock player faces south), to (2, 1, 1).
        BlockPos bedFoot = helper.absolutePos(new BlockPos(2, 1, 0));
        int farX = stone.getX() + 500;
        long before = epochs.epochAtBlock(stone.getX(), stone.getZ());
        long farBefore = epochs.epochAtBlock(farX, stone.getZ());
        long totalBefore = epochs.total();

        Player player = helper.makeMockPlayer(GameType.CREATIVE);
        placeWithItem(helper, player, stone, new ItemStack(Items.STONE));
        helper.assertTrue(level.getBlockState(stone).is(Blocks.STONE), "the stone was placed");
        helper.assertTrue(epochs.epochAtBlock(stone.getX(), stone.getZ()) == before + 1, "placing bumps the bin once");
        helper.assertTrue(epochs.total() == totalBefore + 1, "and the dimension-wide sum once");

        long beforeBed = epochs.total();
        placeWithItem(helper, player, bedFoot, new ItemStack(Items.RED_BED));
        helper.assertTrue(level.getBlockState(bedFoot).is(Blocks.RED_BED)
                && level.getBlockState(bedFoot.south()).is(Blocks.RED_BED), "the bed was placed, two blocks");
        helper.assertTrue(epochs.total() > beforeBed,
                "the multi-block placement event reached the listener (bumped " + (epochs.total() - beforeBed) + " bin(s))");

        long beforeBreak = epochs.epochAtBlock(stone.getX(), stone.getZ());
        BlockState state = level.getBlockState(stone);
        NeoForge.EVENT_BUS.post(new BlockEvent.BreakEvent(level, stone, state, player));
        helper.assertTrue(epochs.epochAtBlock(stone.getX(), stone.getZ()) == beforeBreak + 1, "breaking bumps the bin once");

        // A sapling growing into a tree (posted, not grown: a tree would spill out of the test area).
        long beforeGrowth = epochs.epochAtBlock(stone.getX(), stone.getZ());
        NeoForge.EVENT_BUS.post(new BlockGrowFeatureEvent(level, level.random, stone, null));
        helper.assertTrue(epochs.epochAtBlock(stone.getX(), stone.getZ()) == beforeGrowth + 1,
                "a feature growing bumps the bins round it once");

        helper.assertTrue(epochs.epochAtBlock(farX, stone.getZ()) == farBefore, "a bin 500 blocks away never moved");
        helper.succeed();
    }

    /** {@code ExplosionEvent.Detonate} bumps the explosion's bins. No block is broken (interaction NONE). */
    private static void explosion(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        RegionEpochs epochs = RegionEpochs.of(level);
        BlockPos centre = helper.absolutePos(new BlockPos(1, 1, 1));
        long before = epochs.epochAtBlock(centre.getX(), centre.getZ());
        level.explode(null, centre.getX() + 0.5, centre.getY() + 0.5, centre.getZ() + 0.5, 1.0F,
                Level.ExplosionInteraction.NONE);
        helper.assertTrue(epochs.epochAtBlock(centre.getX(), centre.getZ()) == before + 1,
                "the explosion's bin was bumped once");
        helper.succeed();
    }

    /**
     * {@code PistonEvent.Pre} bumps the piston's bin at once, and again once the pushed block has
     * settled (it spends two ticks as {@code moving_piston}, 0 dB, with no event when it lands).
     */
    private static void piston(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        RegionEpochs epochs = RegionEpochs.of(level);
        BlockPos pistonPos = helper.absolutePos(new BlockPos(0, 0, 1));
        BlockPos pushed = new BlockPos(1, 0, 1);
        BlockPos landed = new BlockPos(2, 0, 1);
        helper.setBlock(new BlockPos(0, 0, 1), Blocks.PISTON.defaultBlockState().setValue(PistonBaseBlock.FACING, Direction.EAST));
        helper.setBlock(pushed, Blocks.STONE);
        long[] before = new long[1];

        helper.startSequence()
                .thenIdle(2)
                .thenExecute(() -> {
                    before[0] = epochs.epochAtBlock(pistonPos.getX(), pistonPos.getZ());
                    // helper.setBlock fires no placement event; only the piston will bump.
                    helper.setBlock(new BlockPos(0, 1, 1), Blocks.REDSTONE_BLOCK);
                })
                .thenWaitUntil(() -> helper.assertTrue(
                        epochs.epochAtBlock(pistonPos.getX(), pistonPos.getZ()) >= before[0] + 1,
                        "the piston's Pre event bumps at once"))
                .thenWaitUntil(() -> helper.assertTrue(helper.getBlockState(landed).is(Blocks.STONE),
                        "the stone lands one block east"))
                .thenWaitUntil(() -> helper.assertTrue(
                        epochs.epochAtBlock(pistonPos.getX(), pistonPos.getZ()) == before[0] + 2,
                        "and is bumped again after it settled"))
                .thenIdle(10)
                .thenExecute(() -> {
                    helper.assertTrue(epochs.epochAtBlock(pistonPos.getX(), pistonPos.getZ()) == before[0] + 2,
                            "exactly two bumps for one move");
                    helper.assertTrue(helper.getBlockState(landed).is(Blocks.STONE), "the stone stayed");
                })
                .thenSucceed();
    }

    // ---- helpers ----------------------------------------------------------------------------------

    /**
     * Places {@code stack} at the absolute {@code target} the way a player does: the item in the
     * main hand, used on the (replaceable, air) target block, through {@code ItemStack.useOn} and so
     * NeoForge's placement hook, which fires the placement event.
     */
    private static void placeWithItem(GameTestHelper helper, Player player, BlockPos target, ItemStack stack) {
        ServerLevel level = helper.getLevel();
        level.getChunk(target.getX() >> 4, target.getZ() >> 4); // load (flat test world) before placing
        if (!level.getBlockState(target).canBeReplaced()) {
            helper.fail("cannot place at " + target + ": " + level.getBlockState(target));
        }
        player.setItemInHand(InteractionHand.MAIN_HAND, stack);
        BlockHitResult hit = new BlockHitResult(Vec3.atCenterOf(target), Direction.UP, target, false);
        stack.useOn(new UseOnContext(player, InteractionHand.MAIN_HAND, hit));
        if (level.getBlockState(target).isAir()) {
            helper.fail("placing " + stack + " at " + target + " did nothing");
        }
    }
}
