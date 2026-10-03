package dev.rancraft.gametest;

import dev.rancraft.RanCraft;
import dev.rancraft.RanCraftConfig;
import dev.rancraft.block.AntennaBlockEntity;
import dev.rancraft.block.BackhaulDishBlockEntity;
import dev.rancraft.data.RfDataLoader;
import dev.rancraft.device.DeviceContext;
import dev.rancraft.net.BackhaulLinksPayload;
import dev.rancraft.net.SignalSamplePayload;
import dev.rancraft.registry.ModBlocks;
import dev.rancraft.registry.ModDataComponents;
import dev.rancraft.registry.ModItems;
import dev.rancraft.rf.BackhaulGraph.BackhaulState;
import dev.rancraft.rf.BandTable;
import dev.rancraft.rf.CellParams;
import dev.rancraft.rf.DeviceRequirement;
import dev.rancraft.rf.MicrowaveLink;
import dev.rancraft.rf.ReceiverState;
import dev.rancraft.rf.RfConfig;
import dev.rancraft.rf.RfEngine;
import dev.rancraft.rf.ServiceLevel;
import dev.rancraft.rf.SignalSample;
import dev.rancraft.world.BackhaulNetwork;
import dev.rancraft.world.LevelWorldProbe;
import dev.rancraft.world.SiteRegistry;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Consumer;
import net.minecraft.commands.CommandSource;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.GlobalPos;
import net.minecraft.gametest.framework.AfterBatch;
import net.minecraft.gametest.framework.GameTestAssertException;
import net.minecraft.gametest.framework.GameTestGenerator;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.TestFunction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec2;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;

/**
 * Backhaul at runtime (Phase 3 slice 12, §3C.2): Core Sites, Backhaul Dishes, the Link Tool and the
 * dimension's {@link BackhaulNetwork} on a live server level. {@code MicrowaveLinkTest} and
 * {@code BackhaulGraphTest} pin the budget and the graph headless; these check the wiring: the real
 * blocks and item, the server-tick recompute and its triggers, and the effects on the site registry,
 * {@code OnAir} and the device cap.
 *
 * <ul>
 *   <li><b>Chain</b> ({@code requireBackhaul} on): a core and three sites chained by microwave, paired
 *       with the real Link Tool, are FULL and on the air. A stone placed (with its event) on the
 *       1000-block middle hop makes it DEGRADED at the next allowed recompute (§3C.2's sanity check:
 *       −69.55 dBm), and the two sites behind it LIMITED, capped at FAIR. Breaking the middle dish takes
 *       them off the air (unregistered, {@code OnAir} false in the update tag: the lens greys them).
 *       {@code /rancraft backhaul status} lists them. Turning {@code requireBackhaul} off puts them
 *       back on the air with no cap (they are NONE). The cost of a march, a recompute and a quiet tick
 *       is logged.</li>
 *   <li><b>Weather</b> ({@code requireBackhaul} off, the default): a marginal 1000-block hop (one stone,
 *       DEGRADED) drops to DOWN in a thunderstorm and in rain, and recovers when it clears, re-budgeted
 *       without a march. A cell with no backhaul at all stays on the air, uncapped, and the site
 *       registry never moves.</li>
 *   <li><b>March budget</b> (Phase 3C review, finding 2): 64 parallel 1000-block hops, all new (the
 *       first recompute after a server start) and then all dirtied by one block in the region bin they
 *       all cross, are marched over several ticks under {@code backhaulMarchBudgetMs}, no tick much over
 *       it, and the states change only when all are measured. The costs are logged.</li>
 * </ul>
 *
 * <p>Each builds far from every other test, in the overworld (weather needs a sky), in forced chunks at
 * y {@value #Y} (open air in the flat test world), and wait for the server's own recompute (at most
 * {@code backhaulRecomputeTicks} after a trigger) wherever a trigger is under test. Each runs in a batch
 * of its own: {@code requireBackhaul} and the weather are global. Each batch's {@link AfterBatch} method
 * puts them back, removes the blocks and releases the chunks, also when the test failed.
 */
@GameTestHolder(RanCraft.MOD_ID)
public final class BackhaulGameTests {

    private static final String BATCH_CHAIN = "rancraft_backhaul_chain";
    private static final String BATCH_WEATHER = "rancraft_backhaul_weather";
    private static final String BATCH_BUDGET = "rancraft_backhaul_march_budget";
    private static final int CHAIN_TIMEOUT_TICKS = 1200;
    private static final int WEATHER_TIMEOUT_TICKS = 600;
    private static final int BUDGET_TIMEOUT_TICKS = 900;
    /** Block entities run {@code onLoad} on the next tick. */
    private static final int SETTLE = 3;

    /** Open air in the flat test world. */
    private static final int Y = 100;
    /** Chunk 512, 8 blocks in: the dishes 3 blocks further south stay in the chunk row. */
    private static final int CHAIN_X = 8192;
    private static final int CHAIN_Z = 8200;
    /** Twelve chunks south of the chain, another 128-block bin. */
    private static final int WEATHER_X = 8192;
    private static final int WEATHER_Z = 8392;
    /**
     * The march-budget hops: their west ends in chunk 1536 (8 blocks in) and one 128-block bin row
     * (z 24576 to 24702, 2 blocks apart), so every hop crosses the bin of their west ends.
     */
    private static final int BUDGET_X = 24_584;
    private static final int BUDGET_Z = 24_576;
    private static final int BUDGET_HOPS = 64;
    private static final int BUDGET_HOP_BLOCKS = 1000;
    /**
     * Allowance on top of the bound for a GC pause or a safepoint landing in a tick: the bound itself
     * (the budget plus one march, or the solve alone) holds by construction ({@code BudgetedQueueTest}).
     */
    private static final long BUDGET_SLACK_NANOS = 2_000_000L;

    /** The game test server's weather (GameTestServer.initServer), put back after the weather test. */
    private static final int TEST_SERVER_WEATHER_TIME = 20_000_000;

    /** Undone by each batch's {@link AfterBatch} method, pass or fail. Server thread. */
    private static final List<Runnable> CLEANUPS = new ArrayList<>();

    private BackhaulGameTests() {
    }

    @GameTestGenerator
    public static Collection<TestFunction> backhaulTests() {
        String prefix = BackhaulGameTests.class.getSimpleName().toLowerCase(Locale.ROOT) + ".";
        List<TestFunction> tests = new ArrayList<>();
        tests.add(new TestFunction(BATCH_CHAIN, prefix + "three_sites_chained_limited_then_off_air",
                HarvestGameTests.EMPTY_TEMPLATE, CHAIN_TIMEOUT_TICKS, 0L, true, BackhaulGameTests::chain));
        tests.add(new TestFunction(BATCH_WEATHER, prefix + "a_marginal_hop_drops_in_a_thunderstorm_and_recovers",
                HarvestGameTests.EMPTY_TEMPLATE, WEATHER_TIMEOUT_TICKS, 0L, true, BackhaulGameTests::weather));
        tests.add(new TestFunction(BATCH_BUDGET, prefix + "many_dirty_long_hops_are_marched_over_ticks_under_the_budget",
                HarvestGameTests.EMPTY_TEMPLATE, BUDGET_TIMEOUT_TICKS, 0L, true, BackhaulGameTests::marchBudget));
        return tests;
    }

    @AfterBatch(batch = BATCH_BUDGET)
    public static void afterBudget(ServerLevel level) {
        runCleanups();
    }

    @AfterBatch(batch = BATCH_CHAIN)
    public static void afterChain(ServerLevel level) {
        runCleanups();
    }

    @AfterBatch(batch = BATCH_WEATHER)
    public static void afterWeather(ServerLevel level) {
        runCleanups();
    }

    private static void runCleanups() {
        for (Runnable cleanup : CLEANUPS) {
            try {
                cleanup.run();
            } catch (RuntimeException failure) {
                RanCraft.LOGGER.error("RANCraft backhaul game test cleanup failed", failure);
            }
        }
        CLEANUPS.clear();
    }

    // ---- the chain --------------------------------------------------------------------------------

    /**
     * Along +x at one height: the core; dish D0 4 blocks from it (on fiber); site 1 (a Signal Mast) 40
     * blocks out with dishes D1a and D1b either side; the 1000-block middle hop from D1b to D2a; site 2
     * with D2a and D2b; site 3 40 blocks further with D3a. Masts on the core's row, dishes 3 blocks
     * south of it (within {@code siteRadiusBlocks} of their own mast, beyond it from every other), so
     * no hop passes a mast. Site 1 is beyond {@code fiberRadiusBlocks} of the core: it is reached by
     * microwave only.
     */
    private record Chain(BlockPos core, BlockPos d0,
                         BlockPos s1, BlockPos d1a, BlockPos d1b,
                         BlockPos s2, BlockPos d2a, BlockPos d2b,
                         BlockPos s3, BlockPos d3a,
                         BlockPos stone) {

        static Chain at(int x, int y, int z) {
            return new Chain(
                    new BlockPos(x, y, z), new BlockPos(x + 4, y, z + 3),
                    new BlockPos(x + 40, y, z), new BlockPos(x + 38, y, z + 3), new BlockPos(x + 42, y, z + 3),
                    new BlockPos(x + 1044, y, z), new BlockPos(x + 1042, y, z + 3), new BlockPos(x + 1046, y, z + 3),
                    new BlockPos(x + 1084, y, z), new BlockPos(x + 1082, y, z + 3),
                    // The middle hop's midpoint.
                    new BlockPos(x + 542, y, z + 3));
        }

        List<BlockPos> dishes() {
            return List.of(d0, d1a, d1b, d2a, d2b, d3a);
        }

        List<BlockPos> masts() {
            return List.of(s1, s2, s3);
        }

        List<BlockPos> all() {
            List<BlockPos> all = new ArrayList<>();
            all.add(core);
            all.addAll(dishes());
            all.addAll(masts());
            all.add(stone);
            return all;
        }
    }

    /**
     * 3C done-when, with {@code requireBackhaul} on: "three sites chained by microwave from a core are
     * on air; breaking the middle dish takes the far site off air, and the lens shows it greyed", and
     * the server half of "a tree grown into a link path turns it DEGRADED, the cells behind it read BH:
     * LIMITED" (a stone stands in for the tree: one block of known loss, §3C.2's sanity check).
     */
    private static void chain(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Chain chain = Chain.at(CHAIN_X, Y, CHAIN_Z);
        boolean previousRequire = RanCraftConfig.REQUIRE_BACKHAUL.get();
        List<Long> forced = new ArrayList<>();
        CLEANUPS.add(() -> {
            RanCraftConfig.REQUIRE_BACKHAUL.set(previousRequire);
            removeAll(level, chain.all());
            // Applies the restored flag now: every cell it held off the air is refreshed.
            BackhaulNetwork.of(level).recomputeNow(level, false);
            release(level, forced);
        });
        RanCraftConfig.REQUIRE_BACKHAUL.set(true);

        Set<Long> chunks = new TreeSet<>();
        for (BlockPos pos : chain.all()) {
            chunks.add(chunkKey(pos));
        }
        force(level, chunks, forced);
        level.setBlock(chain.core(), ModBlocks.CORE_SITE.get().defaultBlockState(), 3);
        for (BlockPos dish : chain.dishes()) {
            level.setBlock(dish, ModBlocks.BACKHAUL_DISH.get().defaultBlockState(), 3);
        }
        for (BlockPos mast : chain.masts()) {
            level.setBlock(mast, ModBlocks.SIGNAL_MAST.get().defaultBlockState(), 3);
        }
        BackhaulNetwork network = BackhaulNetwork.of(level);
        Player player = helper.makeMockPlayer(GameType.CREATIVE);
        long[] mark = new long[1];

        helper.startSequence()
                .thenIdle(SETTLE)
                .thenExecute(() -> {
                    helper.assertTrue(network.isCore(chain.core()), "the core joined the network when placed");
                    for (BlockPos dish : chain.dishes()) {
                        helper.assertTrue(network.isDish(dish), "dish " + dish + " joined the network when placed");
                    }
                    for (BlockPos mast : chain.masts()) {
                        helper.assertTrue(network.isCell(mast.asLong()), "the mast at " + mast + " is a cell the graph knows");
                    }
                    pairWithTool(helper, network, player, chain);
                })
                .thenExecute(() -> {
                    long nanos = network.recomputeNow(level, false);
                    helper.assertTrue(network.hops().size() == 3, "three hops, got " + network.hops());
                    for (BackhaulNetwork.HopStatus hop : network.hops()) {
                        helper.assertTrue(hop.budget().state() == MicrowaveLink.LinkState.UP, "every hop is clear: " + hop);
                    }
                    MicrowaveLink.Budget middle = hopAt(helper, network, chain.d1b()).budget();
                    // §3C.2's sanity check, measured on the live level: 1000 m, FSPL 117.55, RSL -33.55.
                    helper.assertTrue(Math.abs(middle.distanceMeters() - 1000.0) < 1e-9, "the middle hop is 1000 m: " + middle);
                    helper.assertTrue(Math.abs(middle.fsplDb() - 117.55) < 0.01, "FSPL 117.55 dB: " + middle);
                    helper.assertTrue(Math.abs(middle.rslDbm() - (-33.55)) < 0.01, "RSL -33.55 dBm: " + middle);
                    helper.assertTrue(middle.fresnelClear() && middle.rainLossDb() == 0.0, "clear zone, no rain: " + middle);
                    assertSameAsFreshMarch(helper, level, chain.d1b(), chain.d2a(), middle);
                    for (BlockPos mast : chain.masts()) {
                        assertCell(helper, level, network, mast, BackhaulState.FULL, true, ServiceLevel.EXCELLENT);
                    }
                    lensLinks(helper, network, chain);
                    RanCraft.LOGGER.info(String.format(Locale.ROOT,
                            "RANCraft backhaul chain: first recompute %.1f us (3 hops marched, %d cells known)",
                            nanos / 1_000.0, network.cells().size()));
                    measureCost(level, network, chain);

                    // A stone on the middle hop, placed as a player places it (the placement event bumps
                    // its region bin). The next allowed recompute re-marches that hop only.
                    mark[0] = network.lastRecomputeTick();
                    placeWithItem(helper, player, chain.stone(), new ItemStack(Items.STONE));
                })
                .thenWaitUntil(() -> helper.assertTrue(
                        hopAt(helper, network, chain.d1b()).budget().state() == MicrowaveLink.LinkState.DEGRADED,
                        "the stone makes the middle hop DEGRADED at the next recompute"))
                .thenExecute(() -> {
                    long waited = network.lastRecomputeTick() - mark[0];
                    helper.assertTrue(waited >= RanCraftConfig.backhaulRecomputeTicks(),
                            "at most one recompute per backhaulRecomputeTicks: " + waited + " ticks after the last");
                    helper.assertTrue(network.lastMarched() == 1, "only the hop whose bins moved was marched again, got "
                            + network.lastMarched());
                    MicrowaveLink.Budget middle = hopAt(helper, network, chain.d1b()).budget();
                    double stoneDb = RfDataLoader.materials().attenuationDb(Blocks.STONE.defaultBlockState())
                            * RfDataLoader.microwave().penetrationFactor();
                    helper.assertTrue(Math.abs(middle.obstructionDb() - stoneDb) < 1e-9,
                            "one stone's loss (" + stoneDb + " dB): " + middle);
                    helper.assertTrue(Math.abs(middle.rslDbm() - (-69.55)) < 0.01,
                            "§3C.2: one stone on 1000 m is DEGRADED at -69.55 dBm: " + middle);
                    assertSameAsFreshMarch(helper, level, chain.d1b(), chain.d2a(), middle);

                    assertCell(helper, level, network, chain.s1(), BackhaulState.FULL, true, ServiceLevel.EXCELLENT);
                    assertCell(helper, level, network, chain.s2(), BackhaulState.LIMITED, true, ServiceLevel.FAIR);
                    assertCell(helper, level, network, chain.s3(), BackhaulState.LIMITED, true, ServiceLevel.FAIR);
                    limitedCapsDevicesNotTheSample(helper, level, chain.s2());

                    // The stone goes, with its event.
                    BlockState stone = level.getBlockState(chain.stone());
                    NeoForge.EVENT_BUS.post(new BlockEvent.BreakEvent(level, chain.stone(), stone, player));
                    level.setBlock(chain.stone(), Blocks.AIR.defaultBlockState(), 3);
                })
                .thenWaitUntil(() -> helper.assertTrue(
                        hopAt(helper, network, chain.d1b()).budget().state() == MicrowaveLink.LinkState.UP,
                        "the stone gone, the middle hop is UP again"))
                .thenExecute(() -> {
                    for (BlockPos mast : chain.masts()) {
                        assertCell(helper, level, network, mast, BackhaulState.FULL, true, ServiceLevel.EXCELLENT);
                    }
                    // Break the middle dish (site 2's end of the middle hop). Its partner is unpaired.
                    level.destroyBlock(chain.d2a(), false);
                    helper.assertFalse(network.isDish(chain.d2a()), "the broken dish left the network");
                    helper.assertTrue(network.partnerOf(chain.d1b()) == null, "and its partner is unpaired");
                    helper.assertTrue(dishEntity(helper, level, chain.d1b()).partner() == null,
                            "the partner's own entity forgot it too");
                })
                .thenWaitUntil(() -> helper.assertFalse(antenna(helper, level, chain.s3()).onAir(),
                        "the far site goes off the air"))
                .thenExecute(() -> {
                    assertCell(helper, level, network, chain.s1(), BackhaulState.FULL, true, ServiceLevel.EXCELLENT);
                    assertCell(helper, level, network, chain.s2(), BackhaulState.NONE, false, ServiceLevel.NONE);
                    assertCell(helper, level, network, chain.s3(), BackhaulState.NONE, false, ServiceLevel.NONE);
                    helper.assertTrue(network.hops().size() == 2, "two hops left, got " + network.hops());
                    statusCommand(helper, level, chain);
                    RanCraftConfig.REQUIRE_BACKHAUL.set(false);
                })
                .thenWaitUntil(() -> helper.assertTrue(antenna(helper, level, chain.s3()).onAir(),
                        "requireBackhaul off: the far site is back on the air"))
                .thenExecute(() -> {
                    // With the flag off, no cell is held off the air and a NONE cell is not capped; the
                    // states are still worked out (for the lens, the dish and the command). A LIMITED
                    // cell would still be capped at FAIR (Phase 3C review; StorageTerminalGameTests).
                    assertCell(helper, level, network, chain.s2(), BackhaulState.NONE, true, ServiceLevel.EXCELLENT);
                    assertCell(helper, level, network, chain.s3(), BackhaulState.NONE, true, ServiceLevel.EXCELLENT);
                    assertCell(helper, level, network, chain.s1(), BackhaulState.FULL, true, ServiceLevel.EXCELLENT);
                })
                .thenSucceed();
    }

    /**
     * Pairs the three hops with the real Link Tool (a mock player's {@code ItemStack.useOn}, so
     * NeoForge's item-use hook runs as for a player): the first use selects, using the same dish again
     * changes nothing, the second dish pairs and clears the selection. Then sneak + use unpairs a hop
     * (both ends), and it is paired again.
     */
    private static void pairWithTool(GameTestHelper helper, BackhaulNetwork network, Player player, Chain chain) {
        ServerLevel level = helper.getLevel();
        ItemStack tool = new ItemStack(ModItems.LINK_TOOL.get());

        useTool(helper, player, tool, chain.d0());
        GlobalPos selected = tool.get(ModDataComponents.LINK_TOOL_SOURCE.get());
        helper.assertTrue(selected != null && selected.pos().equals(chain.d0())
                        && selected.dimension().equals(level.dimension()),
                "the first use selects the dish, with its dimension: " + selected);
        useTool(helper, player, tool, chain.d0());
        helper.assertTrue(Objects.equals(tool.get(ModDataComponents.LINK_TOOL_SOURCE.get()), selected),
                "the same dish again keeps the selection");
        useTool(helper, player, tool, chain.d1a());
        helper.assertTrue(tool.get(ModDataComponents.LINK_TOOL_SOURCE.get()) == null,
                "pairing clears the selection (it survives NeoForge's item-use hook)");
        assertPaired(helper, network, level, chain.d0(), chain.d1a());

        useTool(helper, player, tool, chain.d1b());
        useTool(helper, player, tool, chain.d2a());
        assertPaired(helper, network, level, chain.d1b(), chain.d2a());
        useTool(helper, player, tool, chain.d2b());
        useTool(helper, player, tool, chain.d3a());
        assertPaired(helper, network, level, chain.d2b(), chain.d3a());

        player.setShiftKeyDown(true);
        useTool(helper, player, tool, chain.d3a());
        player.setShiftKeyDown(false);
        helper.assertTrue(network.partnerOf(chain.d2b()) == null && network.partnerOf(chain.d3a()) == null,
                "sneak + use unpairs both ends");
        helper.assertTrue(dishEntity(helper, level, chain.d2b()).partner() == null
                        && dishEntity(helper, level, chain.d3a()).partner() == null,
                "and both entities forget their partner");
        useTool(helper, player, tool, chain.d2b());
        useTool(helper, player, tool, chain.d3a());
        assertPaired(helper, network, level, chain.d2b(), chain.d3a());
    }

    private static void useTool(GameTestHelper helper, Player player, ItemStack tool, BlockPos dish) {
        player.setItemInHand(InteractionHand.MAIN_HAND, tool);
        BlockHitResult hit = new BlockHitResult(Vec3.atCenterOf(dish), Direction.UP, dish, false);
        InteractionResult result = tool.useOn(new UseOnContext(player, InteractionHand.MAIN_HAND, hit));
        helper.assertTrue(result.consumesAction(), "the Link Tool acted on the dish at " + dish + ": " + result);
    }

    /** §3C.2: "both block entities store the partner's position", and the network agrees. */
    private static void assertPaired(GameTestHelper helper, BackhaulNetwork network, ServerLevel level,
                                     BlockPos a, BlockPos b) {
        helper.assertTrue(b.equals(network.partnerOf(a)) && a.equals(network.partnerOf(b)),
                "the network pairs " + a + " with " + b);
        helper.assertTrue(b.equals(dishEntity(helper, level, a).partner())
                        && a.equals(dishEntity(helper, level, b).partner()),
                "both entities store the partner's position");
        CompoundTag saved = dishEntity(helper, level, a).saveWithoutMetadata(level.registryAccess());
        helper.assertTrue(saved.getLong("Partner") == b.asLong(), "and save it: " + saved);
    }

    /**
     * The lens's data (§3C.2 "Visibility"): the hops within range of a point, nearest first, with the
     * server's state and figures, fit in one {@link BackhaulLinksPayload}. From site 1 at 64 blocks:
     * its two hops, not the far one.
     */
    private static void lensLinks(GameTestHelper helper, BackhaulNetwork network, Chain chain) {
        List<BackhaulLinksPayload.Link> links = network.linksNear(
                chain.s1().getX() + 0.5, chain.s1().getY() + 1.5, chain.s1().getZ() + 0.5, 64.0,
                BackhaulLinksPayload.MAX_LINKS);
        helper.assertTrue(links.size() == 2, "site 1 sees its two hops, got " + links);
        Set<Long> ends = new TreeSet<>();
        for (BackhaulLinksPayload.Link link : links) {
            helper.assertTrue(link.state() == MicrowaveLink.LinkState.UP && link.marginDb() > 0.0f,
                    "the server's state and margin: " + link);
            ends.add(new BlockPos(link.ax(), link.ay(), link.az()).asLong());
            ends.add(new BlockPos(link.bx(), link.by(), link.bz()).asLong());
        }
        helper.assertTrue(ends.equals(new TreeSet<>(List.of(chain.d0().asLong(), chain.d1a().asLong(),
                chain.d1b().asLong(), chain.d2a().asLong()))), "the two hops' ends, got " + ends);
        new BackhaulLinksPayload(links); // within the wire cap
    }

    /**
     * 3C test, on the live cap: a device served by a LIMITED cell gets FAIR at most, in its
     * {@link DeviceContext} and verdict, while the {@link SignalSample} stays the radio link's own. The
     * sample is a real evaluation of site 2 alone, 10 blocks from it. A GOOD requirement (the Storage
     * Terminal's) is refused, a POOR one (the Radio Link's) is not, and the meter's payload says
     * "BH: LIMITED (capped FAIR)".
     */
    private static void limitedCapsDevicesNotTheSample(GameTestHelper helper, ServerLevel level, BlockPos site) {
        CellParams cell = registered(level, site);
        helper.assertTrue(cell != null, "site 2 is on the air while LIMITED");
        RfConfig config = RanCraftConfig.snapshot();
        BandTable bands = RfDataLoader.bands();
        double rxX = cell.x() + 10.5;
        double rxY = cell.y() + 0.5;
        double rxZ = cell.z() + 0.5;
        SignalSample sample = RfEngine.evaluate(new LevelWorldProbe(level, RfDataLoader.materials()),
                rxX, rxY, rxZ, List.of(cell), bands, config, level.getGameTime(), ReceiverState.NONE).sample();
        helper.assertTrue(sample.servingCellId() == cell.cellId(), "served by site 2: " + sample);
        ServiceLevel radio = sample.serviceLevel();
        helper.assertTrue(radio.atLeast(ServiceLevel.GOOD), "fixture: 10 blocks from the mast the radio is GOOD+, got " + radio);

        ServiceLevel cap = BackhaulNetwork.serviceCapAt(level, sample.servingCellId());
        helper.assertTrue(cap == ServiceLevel.FAIR, "a LIMITED serving cell caps at FAIR, got " + cap);
        DeviceRequirement.Verdict terminal = new DeviceRequirement(ServiceLevel.GOOD, 1).check(sample, bands, cap);
        DeviceRequirement.Verdict radioLink = new DeviceRequirement(ServiceLevel.POOR, 1).check(sample, bands, cap);
        helper.assertTrue(terminal == DeviceRequirement.Verdict.LOW_QUALITY, "GOOD is refused under the cap: " + terminal);
        helper.assertTrue(radioLink == DeviceRequirement.Verdict.OK, "POOR still works: " + radioLink);
        helper.assertTrue(new DeviceRequirement(ServiceLevel.GOOD, 1).check(sample, bands) == DeviceRequirement.Verdict.OK,
                "uncapped, the same sample is good enough");
        DeviceContext ctx = new DeviceContext(sample, terminal, bands, config, level, level.getGameTime(), cap);
        helper.assertTrue(ctx.effectiveServiceLevel() == ServiceLevel.FAIR && ctx.backhaulLimited(),
                "the context carries the cap: " + ctx.effectiveServiceLevel());
        helper.assertTrue(ctx.sample() == sample && sample.serviceLevel() == radio,
                "the sample is untouched: " + sample.serviceLevel());
        String note = SignalSamplePayload.of(sample, cell.bandId(), 900.0, config.metersPerBlock(), "", rxX, rxY, rxZ)
                .withServiceCap(cap).backhaulNote();
        helper.assertTrue(note.equals("BH: LIMITED (capped FAIR)"), "the meter's line: " + note);
    }

    /**
     * {@code /rancraft backhaul status 2000} from site 1, run through the server's command dispatcher
     * with a source that keeps what it is told: the two off-air sites, no LIMITED cell, the two links
     * left with their RSL, margin, Fresnel state and rain loss.
     */
    private static void statusCommand(GameTestHelper helper, ServerLevel level, Chain chain) {
        Capture capture = new Capture();
        CommandSourceStack source = new CommandSourceStack(capture, Vec3.atCenterOf(chain.s1()), Vec2.ZERO, level, 4,
                "rancraft-game-test", Component.literal("rancraft-game-test"), level.getServer(), null);
        level.getServer().getCommands().performPrefixedCommand(source, "rancraft backhaul status 2000");
        List<String> lines = new ArrayList<>();
        for (Component message : capture.messages) {
            lines.add(message.getString());
        }
        RanCraft.LOGGER.info("RANCraft backhaul status, as the game test ran it:\n  " + String.join("\n  ", lines));
        helper.assertTrue(translated(capture, "commands.rancraft.backhaul.header") != null, "a header: " + lines);
        helper.assertTrue(firstArg(capture, "commands.rancraft.backhaul.off_air") == 2,
                "two cells off the air for want of backhaul: " + lines);
        helper.assertTrue(firstArg(capture, "commands.rancraft.backhaul.limited") == 0, "no LIMITED cell: " + lines);
        helper.assertTrue(firstArg(capture, "commands.rancraft.backhaul.links") == 2, "two links: " + lines);
        for (BlockPos site : List.of(chain.s2(), chain.s3())) {
            String where = "cell at [" + site.getX() + ", " + site.getY() + ", " + site.getZ() + "]";
            helper.assertTrue(lines.stream().anyMatch(line -> line.contains(where)), "lists the " + where + ": " + lines);
        }
        long linkLines = lines.stream()
                .filter(line -> line.contains("UP, RSL") && line.contains("margin") && line.contains("Fresnel clear")
                        && line.contains("rain 0.0 dB"))
                .count();
        helper.assertTrue(linkLines == 2, "each link with its RSL, margin, Fresnel state and rain loss: " + lines);
    }

    /** A command source that keeps every message (success and failure). */
    private static final class Capture implements CommandSource {

        final List<Component> messages = new ArrayList<>();

        @Override
        public void sendSystemMessage(Component message) {
            messages.add(message);
        }

        @Override
        public boolean acceptsSuccess() {
            return true;
        }

        @Override
        public boolean acceptsFailure() {
            return true;
        }

        @Override
        public boolean shouldInformAdmins() {
            return false;
        }
    }

    private static TranslatableContents translated(Capture capture, String key) {
        for (Component message : capture.messages) {
            if (message.getContents() instanceof TranslatableContents contents && contents.getKey().equals(key)) {
                return contents;
            }
        }
        return null;
    }

    /** The first argument of the message with this key, as an int; -1 when there is none. */
    private static int firstArg(Capture capture, String key) {
        TranslatableContents contents = translated(capture, key);
        if (contents == null || contents.getArgs().length == 0 || !(contents.getArgs()[0] instanceof Integer count)) {
            return -1;
        }
        return count;
    }

    // ---- the weather ------------------------------------------------------------------------------

    /**
     * 3C done-when, server half: "a marginal link drops in a thunderstorm and recovers after". One stone
     * on a 1000-block hop leaves it DEGRADED 0.45 dB above DOWN, so rain (2.5 dB/km) or thunder (6 dB/km)
     * drops it. The server's recompute picks up each weather change by itself and re-budgets the hop
     * without marching it. With {@code requireBackhaul} off (the default) a mast beside one dish, with no
     * core anywhere (backhaul NONE), stays on the air with no cap, and nothing moves the site registry.
     */
    private static void weather(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos a = new BlockPos(WEATHER_X, Y, WEATHER_Z);
        BlockPos b = new BlockPos(WEATHER_X + 1000, Y, WEATHER_Z);
        BlockPos stone = new BlockPos(WEATHER_X + 500, Y, WEATHER_Z);
        BlockPos mast = new BlockPos(WEATHER_X + 2, Y, WEATHER_Z - 3);
        List<BlockPos> placed = List.of(a, b, stone, mast);
        List<Long> forced = new ArrayList<>();
        CLEANUPS.add(() -> {
            setWeather(level, false, false);
            removeAll(level, placed);
            BackhaulNetwork.of(level).recomputeNow(level, false);
            release(level, forced);
        });
        helper.assertFalse(RanCraftConfig.requireBackhaul(), "fixture: requireBackhaul is off (the default)");

        Set<Long> chunks = new TreeSet<>();
        for (BlockPos pos : placed) {
            chunks.add(chunkKey(pos));
        }
        force(level, chunks, forced);
        setWeather(level, false, false);
        level.setBlock(a, ModBlocks.BACKHAUL_DISH.get().defaultBlockState(), 3);
        level.setBlock(b, ModBlocks.BACKHAUL_DISH.get().defaultBlockState(), 3);
        level.setBlock(stone, Blocks.STONE.defaultBlockState(), 3);
        level.setBlock(mast, ModBlocks.SIGNAL_MAST.get().defaultBlockState(), 3);
        BackhaulNetwork network = BackhaulNetwork.of(level);
        MicrowaveLink link = RfDataLoader.microwave();
        long[] siteVersion = new long[1];

        helper.startSequence()
                .thenIdle(SETTLE)
                .thenExecute(() -> {
                    network.pair(level, a, b);
                    network.recomputeNow(level, true);
                    BlockPos midpoint = BlockPos.containing((a.getX() + b.getX() + 1) * 0.5, Y + 0.5, WEATHER_Z + 0.5);
                    helper.assertTrue(BackhaulNetwork.precipitationAt(level, midpoint) == MicrowaveLink.Weather.RAIN,
                            "fixture: the flat test world's plains rain at the midpoint, got "
                                    + BackhaulNetwork.precipitationAt(level, midpoint));
                    BackhaulNetwork.HopStatus hop = hopAt(helper, network, a);
                    helper.assertTrue(hop.weather() == MicrowaveLink.Weather.CLEAR, "clear sky: " + hop);
                    helper.assertTrue(hop.budget().state() == MicrowaveLink.LinkState.DEGRADED
                                    && Math.abs(hop.budget().rslDbm() - (-69.55)) < 0.01,
                            "one stone: DEGRADED at -69.55 dBm: " + hop.budget());
                    assertCell(helper, level, network, mast, BackhaulState.NONE, true, ServiceLevel.EXCELLENT);
                    siteVersion[0] = SiteRegistry.of(level).version();
                    setWeather(level, true, true);
                })
                .thenWaitUntil(() -> helper.assertTrue(hopAt(helper, network, a).weather() == MicrowaveLink.Weather.THUNDER,
                        "the recompute sees the thunderstorm"))
                .thenExecute(() -> {
                    MicrowaveLink.Budget budget = hopAt(helper, network, a).budget();
                    helper.assertTrue(Math.abs(budget.rainLossDb() - link.thunderDbPerKm()) < 1e-9,
                            "thunder: " + link.thunderDbPerKm() + " dB/km x 1 km: " + budget);
                    helper.assertTrue(budget.state() == MicrowaveLink.LinkState.DOWN
                                    && Math.abs(budget.rslDbm() - (-69.55 - link.thunderDbPerKm())) < 0.01,
                            "the marginal hop drops in the storm: " + budget);
                    helper.assertTrue(network.lastMarched() == 0, "a weather change re-budgets without a march, marched "
                            + network.lastMarched());
                    setWeather(level, true, false);
                })
                .thenWaitUntil(() -> helper.assertTrue(hopAt(helper, network, a).weather() == MicrowaveLink.Weather.RAIN,
                        "the recompute sees plain rain"))
                .thenExecute(() -> {
                    MicrowaveLink.Budget budget = hopAt(helper, network, a).budget();
                    helper.assertTrue(Math.abs(budget.rainLossDb() - link.rainDbPerKm()) < 1e-9,
                            "rain: " + link.rainDbPerKm() + " dB/km x 1 km: " + budget);
                    helper.assertTrue(budget.state() == MicrowaveLink.LinkState.DOWN, "still DOWN in rain: " + budget);
                    setWeather(level, false, false);
                })
                .thenWaitUntil(() -> helper.assertTrue(hopAt(helper, network, a).weather() == MicrowaveLink.Weather.CLEAR,
                        "the recompute sees the sky clear"))
                .thenExecute(() -> {
                    MicrowaveLink.Budget budget = hopAt(helper, network, a).budget();
                    helper.assertTrue(budget.state() == MicrowaveLink.LinkState.DEGRADED && budget.rainLossDb() == 0.0
                                    && Math.abs(budget.rslDbm() - (-69.55)) < 0.01,
                            "it recovers after: " + budget);
                    assertCell(helper, level, network, mast, BackhaulState.NONE, true, ServiceLevel.EXCELLENT);
                    helper.assertTrue(SiteRegistry.of(level).version() == siteVersion[0],
                            "requireBackhaul off: three recomputes never touched the site registry");
                })
                .thenSucceed();
    }

    // ---- the march budget (Phase 3C review, finding 2) ---------------------------------------------

    /**
     * 64 parallel hops of 1000 blocks along +x, their west ends 2 blocks apart in one bin row, so every
     * hop crosses the region bin of the west ends. Two recomputes run on the server's own tick:
     * <ol>
     *   <li>all 64 new, as after a server start: marched over several ticks, nothing published until
     *       the last is measured, then exactly what an unbounded recompute that marches them all again
     *       gives;</li>
     *   <li>one stone placed with its event on hop 0's line, 6 blocks from its west end, so in the bin
     *       every hop crosses: all 64 are dirty at once, the next allowed recompute marches them all again
     *       over several ticks, hop 0 still reads UP while it runs (the last published state stands) and
     *       DOWN at −75.55 dBm once it ends (the stone is on the line and, this near the dish, in the
     *       Fresnel zone too), every other hop still UP.</li>
     * </ol>
     * Each time no tick takes longer than the budget plus one march, or the solve in its own tick (plus
     * an allowance for a pause the JVM might take). Wall-clock in a shared JVM: logged, an order of
     * magnitude.
     */
    private static void marchBudget(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        List<BlockPos> west = new ArrayList<>();
        List<BlockPos> east = new ArrayList<>();
        for (int i = 0; i < BUDGET_HOPS; i++) {
            west.add(new BlockPos(BUDGET_X, Y, BUDGET_Z + 2 * i));
            east.add(new BlockPos(BUDGET_X + BUDGET_HOP_BLOCKS, Y, BUDGET_Z + 2 * i));
        }
        BlockPos stone = new BlockPos(BUDGET_X + 6, Y, BUDGET_Z);
        List<BlockPos> placed = new ArrayList<>(west);
        placed.addAll(east);
        placed.add(stone);
        List<Long> forced = new ArrayList<>();
        CLEANUPS.add(() -> {
            removeAll(level, placed);
            BackhaulNetwork.of(level).recomputeNow(level, false);
            release(level, forced);
        });

        Set<Long> chunks = new TreeSet<>();
        for (BlockPos pos : placed) {
            chunks.add(chunkKey(pos));
        }
        force(level, chunks, forced);
        for (int i = 0; i < BUDGET_HOPS; i++) {
            level.setBlock(west.get(i), ModBlocks.BACKHAUL_DISH.get().defaultBlockState(), 3);
            level.setBlock(east.get(i), ModBlocks.BACKHAUL_DISH.get().defaultBlockState(), 3);
        }
        BackhaulNetwork network = BackhaulNetwork.of(level);
        Player player = helper.makeMockPlayer(GameType.CREATIVE);
        long budget = BackhaulNetwork.marchBudgetNanos();
        long[] mark = new long[1];

        helper.startSequence()
                .thenIdle(SETTLE)
                .thenExecute(() -> {
                    helper.assertTrue(network.hops().isEmpty() && !network.measuring(),
                            "fixture: no other hop in the overworld, nothing in progress: " + network.hops().size());
                    for (int i = 0; i < BUDGET_HOPS; i++) {
                        network.pair(level, west.get(i), east.get(i));
                    }
                    mark[0] = network.recomputes();
                })
                .thenWaitUntil(() -> {
                    helper.assertTrue(network.measuring() && network.recomputes() == mark[0],
                            "the server's own recompute starts on the new hops");
                    // Checked in the same tick: the recompute may end in the next.
                    helper.assertTrue(network.hops().isEmpty() && network.pendingMarches() > 0,
                            "mid-way, nothing is published yet (" + network.pendingMarches() + " hops still to march)");
                })
                .thenWaitUntil(() -> helper.assertTrue(network.recomputes() > mark[0] && !network.measuring(),
                        "the recompute ends once every hop is measured"))
                .thenExecute(() -> {
                    assertSpread(helper, network, budget, "first measurement, every hop new");
                    List<BackhaulNetwork.HopStatus> spread = network.hops();
                    helper.assertTrue(spread.size() == BUDGET_HOPS, BUDGET_HOPS + " hops, got " + spread.size());
                    for (BackhaulNetwork.HopStatus hop : spread) {
                        helper.assertTrue(hop.budget().state() == MicrowaveLink.LinkState.UP, "every hop is clear: " + hop);
                    }
                    long unbounded = network.recomputeNow(level, true);
                    helper.assertTrue(network.lastMarched() == BUDGET_HOPS && network.lastRecomputeTicks() == 1,
                            "recomputeNow marches every hop again in one call");
                    helper.assertTrue(new HashSet<>(network.hops()).equals(new HashSet<>(spread)),
                            "the spread recompute gave exactly what one unbounded recompute gives");
                    RanCraft.LOGGER.info(String.format(Locale.ROOT,
                            "RANCraft backhaul march budget: the same %d hops marched at once (recomputeNow) %.1f us in one call",
                            BUDGET_HOPS, unbounded / 1_000.0));
                    mark[0] = network.recomputes();
                    placeWithItem(helper, player, stone, new ItemStack(Items.STONE));
                })
                .thenWaitUntil(() -> {
                    helper.assertTrue(network.measuring() && network.recomputes() == mark[0],
                            "the stone's bin dirties every hop: the next allowed recompute starts");
                    helper.assertTrue(network.pendingMarches() > 0, "mid-way: hops still to march");
                    helper.assertTrue(hopAt(helper, network, west.get(0)).budget().state() == MicrowaveLink.LinkState.UP,
                            "mid-way, hop 0 still reads its last published state, UP");
                })
                .thenWaitUntil(() -> helper.assertTrue(network.recomputes() > mark[0] && !network.measuring(),
                        "the recompute ends once every hop is measured again"))
                .thenExecute(() -> {
                    assertSpread(helper, network, budget, "one block in the bin every hop crosses");
                    // Six blocks from the dish the Fresnel offset paths still run through the stone's
                    // voxel: 36 dB on the line and the 6 dB Fresnel penalty, so DOWN at -75.55 dBm.
                    MicrowaveLink.Budget blocked = hopAt(helper, network, west.get(0)).budget();
                    helper.assertTrue(blocked.state() == MicrowaveLink.LinkState.DOWN && !blocked.fresnelClear()
                                    && Math.abs(blocked.rslDbm() - (-75.55)) < 0.01,
                            "hop 0 is DOWN by its stone, on the line and in the Fresnel zone: " + blocked);
                    assertSameAsFreshMarch(helper, level, west.get(0), east.get(0), blocked);
                    for (int i = 1; i < BUDGET_HOPS; i++) {
                        helper.assertTrue(hopAt(helper, network, west.get(i)).budget().state() == MicrowaveLink.LinkState.UP,
                                "hop " + i + " is still UP");
                    }
                })
                .thenSucceed();
    }

    /**
     * The last recompute marched every hop, over more than one tick, and no tick took longer than the
     * budget plus one march, or the solve (which gets a tick of its own after marches), plus
     * {@link #BUDGET_SLACK_NANOS}. Logs its figures.
     */
    private static void assertSpread(GameTestHelper helper, BackhaulNetwork network, long budgetNanos, String what) {
        int ticks = network.lastRecomputeTicks();
        long maxTick = network.lastRecomputeMaxTickNanos();
        long maxMarch = network.lastRecomputeMaxMarchNanos();
        long solve = network.lastSolveNanos();
        long total = network.lastRecomputeNanos();
        String figures = String.format(Locale.ROOT,
                "%s: %d hops of %d blocks marched over %d ticks; budget %.1f us, longest tick %.1f us, longest march"
                        + " %.1f us, solve %.1f us, total %.1f us (%.3f us per block)",
                what, network.lastMarched(), BUDGET_HOP_BLOCKS, ticks, budgetNanos / 1_000.0, maxTick / 1_000.0,
                maxMarch / 1_000.0, solve / 1_000.0, total / 1_000.0,
                total / 1_000.0 / Math.max(1, network.lastMarched()) / BUDGET_HOP_BLOCKS);
        RanCraft.LOGGER.info("RANCraft backhaul march budget: " + figures);
        helper.assertTrue(network.lastMarched() == BUDGET_HOPS, "every hop marched: " + figures);
        helper.assertTrue(ticks >= 2, "spread over ticks: " + figures);
        helper.assertTrue(maxTick <= Math.max(budgetNanos + maxMarch, solve) + BUDGET_SLACK_NANOS,
                "no tick much over the budget: " + figures);
    }

    /**
     * Sets the overworld's weather at once: the level data's flags and the rain and thunder levels
     * (which otherwise ramp by 0.01 a tick), so {@code isRaining()} and {@code isThundering()} answer
     * now. Clear is the game test server's own setting.
     */
    private static void setWeather(ServerLevel level, boolean raining, boolean thundering) {
        if (raining) {
            level.setWeatherParameters(0, TEST_SERVER_WEATHER_TIME, true, thundering);
        } else {
            level.setWeatherParameters(TEST_SERVER_WEATHER_TIME, TEST_SERVER_WEATHER_TIME, false, false);
        }
        level.setRainLevel(raining ? 1.0F : 0.0F);
        level.setThunderLevel(thundering ? 1.0F : 0.0F);
    }

    // ---- cost -------------------------------------------------------------------------------------

    /**
     * Logs what backhaul costs on a live level (NOTES.md, slice 12, measured): one hop's march (the
     * line and four Fresnel paths) over loaded chunks and over the middle hop's mostly unloaded ones, a
     * recompute that marches all three hops, one that marches none (graph, weather, effects), and the
     * per-tick check with nothing to do, inside and past the interval. Wall-clock in a shared JVM after
     * a warm-up: an order of magnitude, not a benchmark. Asserts only a loose bound on the quiet tick,
     * the one cost every server pays every tick.
     */
    private static void measureCost(ServerLevel level, BackhaulNetwork network, Chain chain) {
        MicrowaveLink link = RfDataLoader.microwave();
        LevelWorldProbe probe = new LevelWorldProbe(level, RfDataLoader.materials());
        // 99 blocks through loaded chunks (the forced chunks' rings), clear air: every path runs to its end.
        double lx = chain.core().getX() - 19.5;
        double ly = Y + 0.5;
        double lz = chain.core().getZ() + 8.5;
        double mx = chain.d1b().getX() + 0.5;
        double mz = chain.d1b().getZ() + 0.5;
        double nx = chain.d2a().getX() + 0.5;
        long sink = 0;
        int loadedSteps = link.stepsToReach(lx, ly, lz, lx + 99, ly, lz, 1.0);
        int middleSteps = link.stepsToReach(mx, ly, mz, nx, ly, mz, 1.0);
        for (int i = 0; i < 300; i++) {
            sink += (long) link.evaluate(probe, lx, ly, lz, lx + 99, ly, lz, 1.0, MicrowaveLink.Weather.CLEAR, loadedSteps).fsplDb();
            sink += (long) link.evaluate(probe, mx, ly, mz, nx, ly, mz, 1.0, MicrowaveLink.Weather.CLEAR, middleSteps).fsplDb();
        }
        final int reps = 300;
        long t0 = System.nanoTime();
        for (int i = 0; i < reps; i++) {
            sink += (long) link.evaluate(probe, lx, ly, lz, lx + 99, ly, lz, 1.0, MicrowaveLink.Weather.CLEAR, loadedSteps).fsplDb();
        }
        long t1 = System.nanoTime();
        for (int i = 0; i < reps; i++) {
            sink += (long) link.evaluate(probe, mx, ly, mz, nx, ly, mz, 1.0, MicrowaveLink.Weather.CLEAR, middleSteps).fsplDb();
        }
        long t2 = System.nanoTime();
        final int recomputes = 100;
        for (int i = 0; i < recomputes; i++) {
            sink += network.recomputeNow(level, true);
        }
        long t3 = System.nanoTime();
        for (int i = 0; i < recomputes; i++) {
            sink += network.recomputeNow(level, true);
        }
        long t4 = System.nanoTime();
        for (int i = 0; i < recomputes; i++) {
            sink += network.recomputeNow(level, false);
        }
        long t5 = System.nanoTime();
        // The per-tick check. Inside the interval it stops at once; past it, with no trigger, it reads
        // the config, the weather, the registry version and the epochs' bump count, and stops.
        long last = network.lastRecomputeTick();
        long past = last + RanCraftConfig.backhaulRecomputeTicks();
        final int ticks = 20_000;
        for (int i = 0; i < ticks; i++) {
            network.tick(level, last + 1);
            network.tick(level, past);
        }
        long t6 = System.nanoTime();
        for (int i = 0; i < ticks; i++) {
            network.tick(level, last + 1);
        }
        long t7 = System.nanoTime();
        for (int i = 0; i < ticks; i++) {
            network.tick(level, past);
        }
        long t8 = System.nanoTime();
        check(network.lastRecomputeTick() == last, "the cost loop's quiet ticks recomputed nothing");
        double insideUs = (t7 - t6) / 1_000.0 / ticks;
        double pastUs = (t8 - t7) / 1_000.0 / ticks;
        RanCraft.LOGGER.info(String.format(Locale.ROOT,
                "RANCraft backhaul cost: march 99 blocks loaded %.1f us (%.3f us/block), 1000 blocks mostly unloaded %.1f us"
                        + " (%.3f us/block); recompute marching 3 hops (1070 blocks) %.1f us, marching none %.1f us"
                        + " (%d cells known); quiet tick %.3f us inside the interval, %.3f us past it [%d]",
                (t1 - t0) / 1_000.0 / reps, (t1 - t0) / 1_000.0 / reps / 99.0,
                (t2 - t1) / 1_000.0 / reps, (t2 - t1) / 1_000.0 / reps / 1000.0,
                (t4 - t3) / 1_000.0 / recomputes, (t5 - t4) / 1_000.0 / recomputes, network.cells().size(),
                insideUs, pastUs, sink));
        check(pastUs < 100.0, "a quiet tick costs well under 0.1 ms (" + pastUs + " us)");
    }

    /** An assertion outside a {@link GameTestHelper} call site (the cost helper has none). */
    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new GameTestAssertException(message);
        }
    }

    // ---- helpers ----------------------------------------------------------------------------------

    /** The network's state, the registry, {@code OnAir} (also in the update tag: the lens) and the cap. */
    private static void assertCell(GameTestHelper helper, ServerLevel level, BackhaulNetwork network, BlockPos mast,
                                   BackhaulState state, boolean onAir, ServiceLevel cap) {
        long cellId = mast.asLong();
        helper.assertTrue(network.stateOf(cellId) == state,
                "the cell at " + mast + " is " + state + ", got " + network.stateOf(cellId));
        helper.assertTrue((registered(level, mast) != null) == onAir,
                "the cell at " + mast + (onAir ? " is" : " is not") + " in the site registry");
        AntennaBlockEntity antenna = antenna(helper, level, mast);
        helper.assertTrue(antenna.onAir() == onAir, "OnAir " + onAir + " at " + mast);
        CompoundTag update = antenna.getUpdateTag(level.registryAccess());
        helper.assertTrue(update.getBoolean(AntennaBlockEntity.ON_AIR_TAG) == onAir,
                "the update tag says OnAir " + onAir + " (the lens greys an off-air cell)");
        helper.assertTrue(BackhaulNetwork.serviceCapAt(level, cellId) == cap,
                "the cap at " + mast + " is " + cap + ", got " + BackhaulNetwork.serviceCapAt(level, cellId));
    }

    /** The cell this mast registered, or {@code null}. */
    private static CellParams registered(ServerLevel level, BlockPos mast) {
        for (CellParams cell : SiteRegistry.of(level).near(mast.getX() + 0.5, mast.getY() + 1.0, mast.getZ() + 0.5, 3.0)) {
            if (cell.cellId() == mast.asLong()) {
                return cell;
            }
        }
        return null;
    }

    private static BackhaulNetwork.HopStatus hopAt(GameTestHelper helper, BackhaulNetwork network, BlockPos dish) {
        BackhaulNetwork.HopStatus hop = network.hopOf(dish);
        if (hop == null) {
            helper.fail("no measured hop at the dish " + dish);
        }
        return hop;
    }

    /** The network's budget is exactly a fresh march of the same hop, from the lower packed end. */
    private static void assertSameAsFreshMarch(GameTestHelper helper, ServerLevel level, BlockPos a, BlockPos b,
                                               MicrowaveLink.Budget measured) {
        BlockPos from = a.asLong() < b.asLong() ? a : b;
        BlockPos to = from == a ? b : a;
        MicrowaveLink link = RfDataLoader.microwave();
        double ax = from.getX() + 0.5;
        double ay = from.getY() + 0.5;
        double az = from.getZ() + 0.5;
        double bx = to.getX() + 0.5;
        double by = to.getY() + 0.5;
        double bz = to.getZ() + 0.5;
        MicrowaveLink.Budget fresh = link.evaluate(new LevelWorldProbe(level, RfDataLoader.materials()),
                ax, ay, az, bx, by, bz, RanCraftConfig.snapshot(), MicrowaveLink.Weather.CLEAR,
                link.stepsToReach(ax, ay, az, bx, by, bz, RanCraftConfig.snapshot().metersPerBlock()));
        helper.assertTrue(fresh.equals(measured), "the network's budget " + measured + " is a fresh march's " + fresh);
    }

    private static AntennaBlockEntity antenna(GameTestHelper helper, ServerLevel level, BlockPos pos) {
        if (!(level.getBlockEntity(pos) instanceof AntennaBlockEntity antenna)) {
            helper.fail("no antenna at " + pos);
            return null;
        }
        return antenna;
    }

    private static BackhaulDishBlockEntity dishEntity(GameTestHelper helper, ServerLevel level, BlockPos pos) {
        if (!(level.getBlockEntity(pos) instanceof BackhaulDishBlockEntity dish)) {
            helper.fail("no dish at " + pos);
            return null;
        }
        return dish;
    }

    static long chunkKey(BlockPos pos) {
        return ChunkPos.asLong(pos);
    }

    /**
     * Forces each chunk and loads, now, every chunk its ticket makes FULL: a chunk reaching FULL bumps
     * its region bin, and left to load in the background it would re-march a hop mid-test.
     */
    static void force(ServerLevel level, Set<Long> chunks, List<Long> forced) {
        for (long key : chunks) {
            int x = ChunkPos.getX(key);
            int z = ChunkPos.getZ(key);
            level.setChunkForced(x, z, true);
            forced.add(key);
            FixedReceiverGameTests.loadFullRings(level, x, z);
        }
    }

    static void release(ServerLevel level, List<Long> forced) {
        for (long key : forced) {
            level.setChunkForced(ChunkPos.getX(key),
                    ChunkPos.getZ(key), false);
        }
        forced.clear();
    }

    /** Back to air, without drops; a dish, core or mast leaves the network on its way. */
    static void removeAll(ServerLevel level, List<BlockPos> positions) {
        for (BlockPos pos : positions) {
            if (!level.getBlockState(pos).isAir()) {
                level.setBlock(pos, Blocks.AIR.defaultBlockState(), 3);
            }
        }
    }

    /**
     * Places {@code stack} at {@code target} the way a player does (NeoForge's placement hook fires the
     * placement event, which bumps the block's region bin), as {@code RegionEpochGameTests} does.
     */
    static void placeWithItem(GameTestHelper helper, Player player, BlockPos target, ItemStack stack) {
        ServerLevel level = helper.getLevel();
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
