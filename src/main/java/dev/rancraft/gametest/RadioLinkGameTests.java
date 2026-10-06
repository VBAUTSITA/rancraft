package dev.rancraft.gametest;

import dev.rancraft.RanCraft;
import dev.rancraft.RanCraftConfig;
import dev.rancraft.block.RadioLinkBlock;
import dev.rancraft.block.RadioLinkBlockEntity;
import dev.rancraft.block.RadioLinkReceiverBlock;
import dev.rancraft.block.RadioLinkReceiverBlockEntity;
import dev.rancraft.block.RadioLinkTransmitterBlockEntity;
import dev.rancraft.device.RadioLinkNetwork;
import dev.rancraft.registry.ModBlocks;
import dev.rancraft.rf.BlerModel;
import dev.rancraft.rf.CellParams;
import dev.rancraft.rf.ServiceLevel;
import dev.rancraft.world.FixedReceiverRegistry;
import dev.rancraft.world.FixedReceiverTicker;
import dev.rancraft.world.SiteRegistry;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.AfterBatch;
import net.minecraft.gametest.framework.GameTestGenerator;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.GameTestSequence;
import net.minecraft.gametest.framework.TestFunction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LeverBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.AttachFace;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;

/**
 * The Radio Link at runtime (Phase 3 slice 9, §3B.4): real blocks, the real fixed-receiver ticker on the
 * real server tick. A receiver follows its transmitter and not another address's; a lost message holds
 * the old state; breaking a transmitter turns its receivers off; a POOR link drops updates at the rate
 * the BLER curve predicts and a FAIR one does not; unloading a chunk stops its radio links and
 * reloading resumes them; and 200 radio links cost what they cost in steady state.
 * {@code BlerModelTest}, {@code SplitMix64Test}, {@code RadioLinkNetworkTest} and
 * {@code RadioLinkMemoryTest} pin the rules headless.
 *
 * <p><b>Harness abstractions, stated plainly.</b> As in {@code FixedReceiverGameTests}: the tests run in
 * <b>the Nether</b> at y 200, above its roof, in chunks they force, where nothing else moves the site
 * registry version; the cells are {@link CellParams} registered straight into the site registry, not
 * placed antennas; the redstone input is a redstone block put under the transmitter with
 * {@code setBlock} (once, a lever on stone beside it, pulled as a player would). The blocks themselves are
 * placed with {@code setBlock}, as a command would.
 *
 * <p>Each test runs in a batch of its own; each batch's {@link AfterBatch} method undoes what the test
 * did, also when it failed.
 */
@GameTestHolder(RanCraft.MOD_ID)
public final class RadioLinkGameTests {

    private static final String BATCH_FOLLOW = "rancraft_radio_link_follow";
    private static final String BATCH_BLER = "rancraft_radio_link_bler";
    private static final String BATCH_CHUNKS = "rancraft_radio_link_chunks";
    private static final String BATCH_COST = "rancraft_radio_link_cost";
    private static final String BATCH_OUTSIDE_FULL = "rancraft_radio_link_outside_full";
    private static final String BATCH_SILENT = "rancraft_radio_link_silent_timeout";
    private static final int TIMEOUT_TICKS = 1_600;
    /** The transmitter timeout the silent-transmitter test sets (row 16d): 3 intervals at the default. */
    private static final int SILENT_TIMEOUT_TICKS = 60;

    /** Nether chunks, far from slice 8's (x 256-264, z 256) and from each other. */
    private static final int CHUNK_Z = 272;
    private static final int FOLLOW_CHUNK_X = 272;
    private static final int POOR_CHUNK_X = 280;
    private static final int FAIR_CHUNK_X = 288;
    /**
     * The reload test's two chunks, 16 apart: a forced chunk keeps every chunk within 13 of it in
     * memory (ticket level 31, plus one per chunk, up to 44), so a released chunk only really unloads
     * with no forced chunk that close.
     */
    private static final int RELOAD_CHUNK_X = 340;
    private static final int RELOAD_OTHER_CHUNK_X = 356;
    /**
     * The not-FULL test's chunk (Phase 3B review) and the forced chunk four away that keeps it in
     * memory, at ticket level 35, once it is released.
     */
    private static final int OUTSIDE_FULL_CHUNK_X = 380;
    private static final int OUTSIDE_FULL_KEEPER_CHUNK_X = 384;
    private static final int COST_CHUNK_X = 304;
    private static final int SILENT_CHUNK_X = 296;
    private static final int AIR_Y = 200;

    /** Sends counted per link in the BLER test, at interval {@value #BLER_INTERVAL}. */
    private static final int BLER_SENDS = 200;
    private static final int BLER_INTERVAL = 2;

    private static final int LINKS_FOR_COST = 200;
    private static final int COST_WARMUP_FAST_TICKS = 100;
    private static final int COST_WARMUP_TICKS = 400;
    private static final int COST_WINDOW_TICKS = 100;
    private static final int COST_WINDOWS = 3;
    /** 3B done-when: "200 radio links on a quiet server cost under 0.1 ms/tick in steady state". */
    private static final double STEADY_STATE_LIMIT_US = 100.0;

    private static final List<Runnable> CLEANUPS = new ArrayList<>();

    private RadioLinkGameTests() {
    }

    @GameTestGenerator
    public static Collection<TestFunction> radioLinkTests() {
        String prefix = RadioLinkGameTests.class.getSimpleName().toLowerCase(Locale.ROOT) + ".";
        List<TestFunction> tests = new ArrayList<>();
        tests.add(test(BATCH_FOLLOW, prefix + "follows_its_transmitter_holds_when_lost_forgets_a_broken_one",
                RadioLinkGameTests::follow));
        tests.add(test(BATCH_BLER, prefix + "poor_link_drops_updates_fair_link_is_solid", RadioLinkGameTests::bler));
        tests.add(test(BATCH_CHUNKS, prefix + "unloading_stops_it_reloading_resumes_it", RadioLinkGameTests::reload));
        tests.add(test(BATCH_COST, prefix + "two_hundred_radio_links_in_steady_state", RadioLinkGameTests::cost));
        tests.add(test(BATCH_OUTSIDE_FULL, prefix + "a_receiver_outside_full_hears_but_does_not_write_its_block",
                RadioLinkGameTests::outsideFull));
        tests.add(test(BATCH_SILENT, prefix + "a_receiver_forgets_a_transmitter_silent_past_the_timeout",
                RadioLinkGameTests::silentTimeout));
        return tests;
    }

    private static TestFunction test(String batch, String name, Consumer<GameTestHelper> body) {
        return new TestFunction(batch, name, HarvestGameTests.EMPTY_TEMPLATE, TIMEOUT_TICKS, 0L, true, body);
    }

    @AfterBatch(batch = BATCH_FOLLOW)
    public static void afterFollow(ServerLevel level) {
        runCleanups();
    }

    @AfterBatch(batch = BATCH_BLER)
    public static void afterBler(ServerLevel level) {
        runCleanups();
    }

    @AfterBatch(batch = BATCH_CHUNKS)
    public static void afterChunks(ServerLevel level) {
        runCleanups();
    }

    @AfterBatch(batch = BATCH_COST)
    public static void afterCost(ServerLevel level) {
        runCleanups();
    }

    @AfterBatch(batch = BATCH_OUTSIDE_FULL)
    public static void afterOutsideFull(ServerLevel level) {
        runCleanups();
    }

    @AfterBatch(batch = BATCH_SILENT)
    public static void afterSilent(ServerLevel level) {
        runCleanups();
    }

    /** Runs the cleanups newest first, so blocks go before the chunks holding them are released. */
    private static void runCleanups() {
        for (int i = CLEANUPS.size() - 1; i >= 0; i--) {
            try {
                CLEANUPS.get(i).run();
            } catch (RuntimeException failure) {
                RanCraft.LOGGER.error("RANCraft radio link game test cleanup failed", failure);
            }
        }
        CLEANUPS.clear();
    }

    // ---- helpers ----------------------------------------------------------------------------------

    private static ServerLevel nether(GameTestHelper helper) {
        ServerLevel nether = helper.getLevel().getServer().getLevel(Level.NETHER);
        if (nether == null) {
            helper.fail("the game test server has no Nether");
        }
        return nether;
    }

    /**
     * Forces a chunk (loaded now) and registers its release; the chunks it makes FULL are loaded now
     * too, so their region-epoch bumps (Phase 3B review) do not land in a measured window
     * ({@code FixedReceiverGameTests.loadFullRings}).
     */
    private static void forceChunk(ServerLevel nether, int chunkX, int chunkZ) {
        nether.setChunkForced(chunkX, chunkZ, true);
        CLEANUPS.add(() -> nether.setChunkForced(chunkX, chunkZ, false));
        nether.getChunk(chunkX, chunkZ);
        FixedReceiverGameTests.loadFullRings(nether, chunkX, chunkZ);
    }

    private static void cell(ServerLevel nether, CellParams cell) {
        SiteRegistry sites = SiteRegistry.of(nether);
        sites.register(cell);
        CLEANUPS.add(() -> sites.unregister(cell.cellId()));
    }

    /** Places a block and registers its removal. */
    private static void place(ServerLevel nether, BlockPos pos, Block block) {
        nether.setBlock(pos, block.defaultBlockState(), Block.UPDATE_ALL);
        CLEANUPS.add(() -> nether.setBlock(pos, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL));
    }

    private static RadioLinkTransmitterBlockEntity transmitter(GameTestHelper helper, ServerLevel nether, BlockPos pos) {
        place(nether, pos, ModBlocks.RADIO_LINK_TRANSMITTER.get());
        if (nether.getBlockEntity(pos) instanceof RadioLinkTransmitterBlockEntity tx) {
            return tx;
        }
        helper.fail("no transmitter entity at " + pos);
        return null;
    }

    private static RadioLinkReceiverBlockEntity receiver(GameTestHelper helper, ServerLevel nether, BlockPos pos) {
        place(nether, pos, ModBlocks.RADIO_LINK_RECEIVER.get());
        if (nether.getBlockEntity(pos) instanceof RadioLinkReceiverBlockEntity rx) {
            return rx;
        }
        helper.fail("no receiver entity at " + pos);
        return null;
    }

    /** The redstone input: a redstone block under the transmitter, or air. */
    private static void power(ServerLevel nether, BlockPos transmitter, boolean on) {
        nether.setBlock(transmitter.below(), (on ? Blocks.REDSTONE_BLOCK : Blocks.AIR).defaultBlockState(),
                Block.UPDATE_ALL);
    }

    private static boolean lit(ServerLevel nether, BlockPos pos) {
        BlockState state = nether.getBlockState(pos);
        return state.getBlock() instanceof RadioLinkBlock && state.getValue(RadioLinkBlock.LIT);
    }

    private static boolean outputOn(ServerLevel nether, BlockPos pos) {
        BlockState state = nether.getBlockState(pos);
        return state.getBlock() instanceof RadioLinkReceiverBlock && state.getValue(RadioLinkReceiverBlock.POWERED);
    }

    /** What a redstone component next to the receiver reads from it. */
    private static int signalFrom(ServerLevel nether, BlockPos receiver) {
        return nether.getSignal(receiver, Direction.UP);
    }

    private static int interval() {
        return Math.max(1, RanCraftConfig.snapshot().evaluationIntervalTicks());
    }

    /** Uses the block as a player would, sneaking or not. */
    private static void use(GameTestHelper helper, ServerLevel nether, BlockPos pos, boolean sneaking) {
        Player player = helper.makeMockPlayer(GameType.SURVIVAL);
        player.setShiftKeyDown(sneaking);
        nether.getBlockState(pos).useWithoutItem(nether, player,
                new BlockHitResult(Vec3.atCenterOf(pos), Direction.UP, pos, false));
    }

    // ---- 1b. a silent transmitter times out (row 16d) ---------------------------------------------

    /**
     * Row 16d: a receiver forgets a transmitter it has not heard from for
     * {@code radioLinkTransmitterTimeoutTicks} ({@value #SILENT_TIMEOUT_TICKS} here). A powered
     * transmitter and its receiver on one omni; the cell is taken away, so both lose service and the
     * transmitter's messages stop getting through. The receiver holds 15 for a while (a lost message is
     * a stale state, as in the follow test), then lets go at its first turn at or past the timeout after
     * the last delivered message. The cell comes back: the next message turns it on again.
     */
    private static void silentTimeout(GameTestHelper helper) {
        ServerLevel nether = nether(helper);
        forceChunk(nether, SILENT_CHUNK_X, CHUNK_Z);
        int baseX = SILENT_CHUNK_X * 16;
        int baseZ = CHUNK_Z * 16;
        BlockPos txPos = new BlockPos(baseX + 2, AIR_Y, baseZ + 2);
        BlockPos rxPos = new BlockPos(baseX + 12, AIR_Y, baseZ + 2);
        CellParams cell = CellParams.omniDefaults(-9_100_051L, baseX + 7, AIR_Y, baseZ + 12);
        int interval = interval();
        int configuredTimeout = RanCraftConfig.RADIO_LINK_TRANSMITTER_TIMEOUT_TICKS.get();
        CLEANUPS.add(() -> RanCraftConfig.RADIO_LINK_TRANSMITTER_TIMEOUT_TICKS.set(configuredTimeout));
        CLEANUPS.add(() -> power(nether, txPos, false));
        RanCraftConfig.RADIO_LINK_TRANSMITTER_TIMEOUT_TICKS.set(SILENT_TIMEOUT_TICKS);

        RadioLinkTransmitterBlockEntity tx = transmitter(helper, nether, txPos);
        RadioLinkReceiverBlockEntity rx = receiver(helper, nether, rxPos);
        cell(nether, cell);
        long[] lastHeard = new long[1];

        helper.startSequence()
                .thenWaitUntil(() -> helper.assertTrue(tx.served() && rx.served(), "both served"))
                .thenExecute(() -> power(nether, txPos, true))
                .thenWaitUntil(() -> helper.assertTrue(outputOn(nether, rxPos), "the receiver follows the transmitter"))
                .thenExecute(() -> SiteRegistry.of(nether).unregister(cell.cellId()))
                .thenWaitUntil(() -> helper.assertTrue(!tx.served() && !rx.served(), "no cell: both lose service"))
                .thenExecute(() -> lastHeard[0] = rx.lastDeliveredTick())
                .thenWaitUntil(() -> helper.assertTrue(nether.getGameTime() - lastHeard[0] >= SILENT_TIMEOUT_TICKS / 2,
                        "half the timeout since the last message"))
                .thenExecute(() -> helper.assertTrue(outputOn(nether, rxPos) && rx.lastDeliveredTick() == lastHeard[0],
                        "within the timeout the receiver holds its last state, and nothing more got through"))
                .thenWaitUntil(() -> helper.assertTrue(!outputOn(nether, rxPos), "silent past the timeout: forgotten"))
                .thenExecute(() -> {
                    long silentFor = nether.getGameTime() - lastHeard[0];
                    helper.assertTrue(silentFor >= SILENT_TIMEOUT_TICKS && silentFor <= SILENT_TIMEOUT_TICKS + interval + 1,
                            "let go at its first turn at or past the timeout: silent for " + silentFor + " ticks");
                    helper.assertTrue(rx.knownOnCount() == 0 && signalFrom(nether, rxPos) == 0, "nothing remembered, no signal");
                    SiteRegistry.of(nether).register(cell);
                })
                .thenWaitUntil(() -> helper.assertTrue(outputOn(nether, rxPos), "service back: the next message turns it on"))
                .thenSucceed();
    }

    // ---- 1. follow, hold, forget ------------------------------------------------------------------

    /**
     * One band_900 omni, a transmitter and a receiver on address 3 about 10 blocks from it (clean
     * links, SINR tens of dB), and a receiver on address 4.
     *
     * <ol>
     *   <li>Placed with {@code setBlock}, both are registered at once (the {@code clearRemoved} path);
     *       use and sneak + use move the address and wrap; both light up at their first evaluation.</li>
     *   <li>Powering the transmitter turns the address-3 receiver on within one interval (it reads its
     *       input on its turn and the message is delivered at once); the address-4 receiver stays off.
     *       Unpowering turns it off again. So does a lever on stone next to the transmitter (power through
     *       a block, which the transmitter learns of from a neighbour update).</li>
     *   <li>Stale, not toggled: powered, then the cell is taken away, so both ends lose service and go
     *       dark; the transmitter is unpowered; three intervals later the receiver still outputs 15.
     *       The cell comes back and the receiver goes off.</li>
     *   <li>Powered again, then the transmitter is broken: the receiver forgets it and goes off at once
     *       (the broken transmitter tells the network).</li>
     * </ol>
     */
    private static void follow(GameTestHelper helper) {
        ServerLevel nether = nether(helper);
        forceChunk(nether, FOLLOW_CHUNK_X, CHUNK_Z);
        int baseX = FOLLOW_CHUNK_X * 16;
        int baseZ = CHUNK_Z * 16;
        BlockPos txPos = new BlockPos(baseX + 2, AIR_Y, baseZ + 2);
        BlockPos rxPos = new BlockPos(baseX + 12, AIR_Y, baseZ + 2);
        BlockPos otherPos = new BlockPos(baseX + 12, AIR_Y, baseZ + 8);
        BlockPos stonePos = txPos.west();
        BlockPos leverPos = txPos.west(2);
        CellParams cell = CellParams.omniDefaults(-9_100_001L, baseX + 7, AIR_Y, baseZ + 12);
        int interval = interval();
        CLEANUPS.add(() -> power(nether, txPos, false));

        RadioLinkTransmitterBlockEntity tx = transmitter(helper, nether, txPos);
        RadioLinkReceiverBlockEntity rx = receiver(helper, nether, rxPos);
        RadioLinkReceiverBlockEntity other = receiver(helper, nether, otherPos);
        FixedReceiverRegistry registry = FixedReceiverRegistry.of(nether);
        helper.assertTrue(registry.deviceAt(txPos) == tx && registry.deviceAt(rxPos) == rx,
                "placed by setBlock, registered at once (clearRemoved)");

        // Addresses by use: up three times from 0; sneak + use down from 0 wraps to 15, then back up to 4.
        for (int i = 0; i < 3; i++) {
            use(helper, nether, txPos, false);
            use(helper, nether, rxPos, false);
        }
        use(helper, nether, otherPos, true);
        helper.assertTrue(other.address() == 15, "sneak + use from 0 wraps to 15, got " + other.address());
        for (int i = 0; i < 5; i++) {
            use(helper, nether, otherPos, false);
        }
        helper.assertTrue(tx.address() == 3 && rx.address() == 3 && other.address() == 4,
                "addresses " + tx.address() + ", " + rx.address() + ", " + other.address());
        cell(nether, cell);
        long[] mark = new long[1];

        helper.startSequence()
                .thenWaitUntil(() -> helper.assertTrue(tx.served() && rx.served() && other.served()
                        && lit(nether, txPos) && lit(nether, rxPos) && lit(nether, otherPos),
                        "all three served and lit"))
                .thenExecute(() -> {
                    helper.assertTrue(tx.sinrDb() > 20.0 && rx.sinrDb() > 20.0,
                            "clean links: SINR " + tx.sinrDb() + " / " + rx.sinrDb() + " dB");
                    helper.assertTrue(!outputOn(nether, rxPos) && signalFrom(nether, rxPos) == 0, "off to start with");
                    power(nether, txPos, true);
                    mark[0] = nether.getGameTime();
                })
                .thenWaitUntil(() -> helper.assertTrue(outputOn(nether, rxPos), "the receiver follows the transmitter"))
                .thenExecute(() -> {
                    long took = nether.getGameTime() - mark[0];
                    helper.assertTrue(took <= interval + 1, "within one interval: " + took + " ticks");
                    helper.assertTrue(signalFrom(nether, rxPos) == 15, "redstone 15 next to it");
                    helper.assertTrue(!outputOn(nether, otherPos), "address 4 hears nothing from address 3");
                    power(nether, txPos, false);
                })
                .thenWaitUntil(() -> helper.assertTrue(!outputOn(nether, rxPos), "unpowered: the receiver goes off"))
                // Power through a block: a lever on stone next to the transmitter. The transmitter reads its
                // input again only after a neighbour update, which the lever sends to the stone's neighbours.
                .thenExecute(() -> {
                    place(nether, stonePos, Blocks.STONE);
                    nether.setBlock(leverPos, Blocks.LEVER.defaultBlockState().setValue(LeverBlock.FACE, AttachFace.WALL)
                            .setValue(LeverBlock.FACING, Direction.WEST), Block.UPDATE_ALL);
                    CLEANUPS.add(() -> nether.setBlock(leverPos, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL));
                    use(helper, nether, leverPos, false);
                    helper.assertTrue(nether.hasNeighborSignal(txPos), "the lever powers the stone, the stone the transmitter");
                })
                .thenWaitUntil(() -> helper.assertTrue(outputOn(nether, rxPos), "a lever on a block next to it works too"))
                .thenExecute(() -> use(helper, nether, leverPos, false))
                .thenWaitUntil(() -> helper.assertTrue(!outputOn(nether, rxPos), "lever off: the receiver goes off"))
                .thenExecute(() -> {
                    nether.setBlock(leverPos, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
                    nether.setBlock(stonePos, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
                    power(nether, txPos, true);
                })
                .thenWaitUntil(() -> helper.assertTrue(outputOn(nether, rxPos), "on again"))
                .thenExecute(() -> SiteRegistry.of(nether).unregister(cell.cellId()))
                .thenWaitUntil(() -> helper.assertTrue(!tx.served() && !rx.served()
                        && !lit(nether, txPos) && !lit(nether, rxPos), "no cell: both ends lose service and go dark"))
                .thenExecute(() -> {
                    power(nether, txPos, false);
                    mark[0] = RadioLinkNetwork.of(nether).stats().unsent();
                })
                .thenIdle(3 * interval)
                .thenExecute(() -> {
                    helper.assertTrue(outputOn(nether, rxPos),
                            "the transmitter's messages are lost: the receiver holds its last state (stale, not toggled)");
                    helper.assertTrue(RadioLinkNetwork.of(nether).stats().unsent() >= mark[0] + 2,
                            "the transmitter kept sending into no service");
                    SiteRegistry.of(nether).register(cell);
                })
                .thenWaitUntil(() -> helper.assertTrue(!outputOn(nether, rxPos), "service back: the unpowered state arrives"))
                .thenExecute(() -> power(nether, txPos, true))
                .thenWaitUntil(() -> helper.assertTrue(outputOn(nether, rxPos), "on again"))
                .thenExecute(() -> {
                    nether.setBlock(txPos, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
                    helper.assertTrue(!registry.contains(txPos), "the broken transmitter is unregistered");
                    helper.assertTrue(!outputOn(nether, rxPos) && rx.knownOnCount() == 0 && signalFrom(nether, rxPos) == 0,
                            "a broken transmitter is forgotten at once (it tells the network)");
                })
                .thenSucceed();
    }

    // ---- 2. POOR drops, FAIR is solid -------------------------------------------------------------

    /** One link and what was counted for it. */
    private record Link(String name, RadioLinkTransmitterBlockEntity tx, RadioLinkReceiverBlockEntity rx) {
    }

    /**
     * Two links, each between two co-channel band_900 omnis with the same PCI (so mod 3 collides and the
     * interferer counts double, +3 dB), placed so the stronger cell serves:
     * <ul>
     *   <li><b>POOR</b>: about 10 blocks from the server and 13 from the interferer, SINR about 1 dB at
     *       both ends: the model says about half the messages get through.</li>
     *   <li><b>FAIR</b>: 8 blocks from the server and 18 from the interferer, SINR about 9 dB: the model
     *       says fewer than one in ten thousand is lost.</li>
     * </ul>
     * Both ends of each are asserted to be at that service level, from the samples the server evaluated.
     * The interval is 2 ticks during the count (never saved, put back afterwards), so {@value #BLER_SENDS}
     * messages per link take {@value #BLER_SENDS} x 2 ticks. The delivered count is compared with the
     * model's prediction from the measured SINRs: the POOR link within 6 standard deviations (and it
     * must lose at least one), the FAIR link losing at most 2.
     */
    private static void bler(GameTestHelper helper) {
        ServerLevel nether = nether(helper);
        int configuredInterval = RanCraftConfig.EVALUATION_INTERVAL_TICKS.get();
        CLEANUPS.add(() -> RanCraftConfig.EVALUATION_INTERVAL_TICKS.set(configuredInterval));

        Link poor = linkBetweenTwoCells(helper, nether, "POOR", POOR_CHUNK_X, 1, -9_100_011L, 10, 23, 3);
        Link fair = linkBetweenTwoCells(helper, nether, "FAIR", FAIR_CHUNK_X, 2, -9_100_021L, 8, 26, 2);
        long[] start = new long[4];

        helper.startSequence()
                .thenWaitUntil(() -> helper.assertTrue(poor.tx().samples() >= 1 && poor.rx().samples() >= 1
                        && fair.tx().samples() >= 1 && fair.rx().samples() >= 1, "every end evaluated"))
                .thenExecute(() -> {
                    for (Link link : List.of(poor, fair)) {
                        ServiceLevel expected = link == poor ? ServiceLevel.POOR : ServiceLevel.FAIR;
                        helper.assertTrue(link.tx().serviceLevel() == expected && link.rx().serviceLevel() == expected,
                                link.name() + " link: both ends at " + expected + ", got " + link.tx().serviceLevel()
                                        + " (" + link.tx().sinrDb() + " dB) and " + link.rx().serviceLevel() + " ("
                                        + link.rx().sinrDb() + " dB)");
                        helper.assertTrue(link.tx().served() && link.rx().served(), link.name() + ": both served");
                    }
                    RanCraftConfig.EVALUATION_INTERVAL_TICKS.set(BLER_INTERVAL);
                })
                // Let the new interval settle: every device rescheduled at most one old interval away.
                .thenIdle(configuredInterval + BLER_INTERVAL)
                .thenExecute(() -> {
                    start[0] = poor.tx().samples();
                    start[1] = poor.rx().deliveredCount();
                    start[2] = fair.tx().samples();
                    start[3] = fair.rx().deliveredCount();
                })
                .thenWaitUntil(() -> helper.assertTrue(poor.tx().samples() - start[0] >= BLER_SENDS
                        && fair.tx().samples() - start[2] >= BLER_SENDS, "sent " + BLER_SENDS + " each"))
                .thenExecute(() -> {
                    RanCraftConfig.EVALUATION_INTERVAL_TICKS.set(configuredInterval);
                    BlerModel model = RanCraftConfig.snapshot().blerModel();
                    StringBuilder log = new StringBuilder("RANCraft radio link BLER check:");
                    long[] sends = {poor.tx().samples() - start[0], fair.tx().samples() - start[2]};
                    long[] got = {poor.rx().deliveredCount() - start[1], fair.rx().deliveredCount() - start[3]};
                    double[] p = new double[2];
                    Link[] links = {poor, fair};
                    for (int i = 0; i < 2; i++) {
                        Link link = links[i];
                        helper.assertTrue(link.tx().served() && link.rx().served(), link.name() + ": still served");
                        p[i] = model.deliveryProbability(link.tx().sinrDb(), link.rx().sinrDb());
                        log.append(String.format(Locale.ROOT,
                                " %s link: SINR %.2f dB (tx) / %.2f dB (rx), model P(deliver) %.4f, delivered %d of %d (%.3f), lost %d;",
                                link.name(), link.tx().sinrDb(), link.rx().sinrDb(), p[i], got[i], sends[i],
                                got[i] / (double) sends[i], sends[i] - got[i]));
                    }
                    RanCraft.LOGGER.info(log.toString());
                    double sd = Math.sqrt(sends[0] * p[0] * (1.0 - p[0]));
                    helper.assertTrue(Math.abs(got[0] - sends[0] * p[0]) <= 6.0 * sd + 1.0,
                            "POOR: delivered " + got[0] + " of " + sends[0] + ", model " + sends[0] * p[0]);
                    helper.assertTrue(got[0] < sends[0], "POOR: updates are visibly dropped");
                    helper.assertTrue(sends[1] - got[1] <= 2, "FAIR: solid, lost " + (sends[1] - got[1]));
                })
                .thenSucceed();
    }

    /**
     * A transmitter and a receiver (on {@code address}) {@code serverDistance} blocks east of a serving
     * omni, with a co-channel interferer {@code interfererDistance} blocks east of the server; the
     * receiver is {@code rxOffsetZ} blocks south of the transmitter, out of reach of its redstone.
     */
    private static Link linkBetweenTwoCells(GameTestHelper helper, ServerLevel nether, String name, int chunkX,
                                            int address, long cellId, int serverDistance, int interfererDistance,
                                            int rxOffsetZ) {
        forceChunk(nether, chunkX, CHUNK_Z);
        forceChunk(nether, chunkX + 1, CHUNK_Z);
        int x0 = chunkX * 16 + 2;
        int z0 = CHUNK_Z * 16 + 4;
        cell(nether, CellParams.omniDefaults(cellId, x0, AIR_Y, z0));
        cell(nether, CellParams.omniDefaults(cellId - 1, x0 + interfererDistance, AIR_Y, z0));
        BlockPos txPos = new BlockPos(x0 + serverDistance, AIR_Y, z0);
        BlockPos rxPos = new BlockPos(x0 + serverDistance, AIR_Y, z0 + rxOffsetZ);
        RadioLinkTransmitterBlockEntity tx = transmitter(helper, nether, txPos);
        RadioLinkReceiverBlockEntity rx = receiver(helper, nether, rxPos);
        tx.cycleAddress(address);
        rx.cycleAddress(address);
        return new Link(name, tx, rx);
    }

    // ---- 3. unload and reload ---------------------------------------------------------------------

    /**
     * A powered transmitter and a receiver on address 6 in chunk A, and a second receiver on address 6
     * in chunk B, 16 chunks away; each chunk forced, with a cell of its own.
     *
     * <ol>
     *   <li>Both receivers follow the transmitter.</li>
     *   <li>A is released and really unloads: its two radio links are unregistered and removed and are
     *       served no more. B's receiver holds 15: the transmitter's last delivered state (an unloaded
     *       transmitter is not forgotten).</li>
     *   <li>A is forced again and loads from disk: new entities, registered at once, with the address
     *       and the receiver's memory and output as saved, before their first turn; then served again,
     *       and messages arrive.</li>
     *   <li>B is released and unloads; the transmitter in A is broken, which A's receiver hears at once
     *       and B's, unloaded, cannot. B is forced again: its receiver comes back on (as saved), looks up
     *       the transmitter it remembers on its first turn, finds it gone, and goes off.</li>
     * </ol>
     */
    private static void reload(GameTestHelper helper) {
        ServerLevel nether = nether(helper);
        int chunkA = RELOAD_CHUNK_X;
        int chunkB = RELOAD_OTHER_CHUNK_X;
        for (int chunkX : new int[] {chunkA, chunkB}) {
            nether.setChunkForced(chunkX, CHUNK_Z, true);
            CLEANUPS.add(() -> nether.setChunkForced(chunkX, CHUNK_Z, false));
            nether.getChunk(chunkX, CHUNK_Z);
        }
        int baseZ = CHUNK_Z * 16;
        BlockPos txPos = new BlockPos(chunkA * 16 + 4, AIR_Y, baseZ + 4);
        BlockPos rxPos = new BlockPos(chunkA * 16 + 12, AIR_Y, baseZ + 4);
        BlockPos farPos = new BlockPos(chunkB * 16 + 4, AIR_Y, baseZ + 4);
        cell(nether, CellParams.omniDefaults(-9_100_031L, chunkA * 16 + 8, AIR_Y, baseZ + 12));
        cell(nether, CellParams.omniDefaults(-9_100_032L, chunkB * 16 + 8, AIR_Y, baseZ + 12));
        // Cleanups run newest first: the redstone block goes after the radio links, and the chunks are
        // loaded again before either.
        CLEANUPS.add(() -> power(nether, txPos, false));
        RadioLinkTransmitterBlockEntity tx = transmitter(helper, nether, txPos);
        RadioLinkReceiverBlockEntity rx = receiver(helper, nether, rxPos);
        RadioLinkReceiverBlockEntity far = receiver(helper, nether, farPos);
        CLEANUPS.add(() -> {
            nether.getChunk(chunkA, CHUNK_Z);
            nether.getChunk(chunkB, CHUNK_Z);
        });
        for (RadioLinkBlockEntity link : List.of(tx, rx, far)) {
            link.cycleAddress(6);
        }
        power(nether, txPos, true);
        FixedReceiverRegistry registry = FixedReceiverRegistry.of(nether);
        long[] mark = new long[2];
        RadioLinkReceiverBlockEntity[] reloaded = new RadioLinkReceiverBlockEntity[2];

        helper.startSequence()
                .thenWaitUntil(() -> helper.assertTrue(outputOn(nether, rxPos) && outputOn(nether, farPos),
                        "both receivers follow the powered transmitter"))
                // 2. Unload A.
                .thenExecute(() -> nether.setChunkForced(chunkA, CHUNK_Z, false))
                .thenWaitUntil(() -> helper.assertTrue(!registry.contains(txPos) && !registry.contains(rxPos),
                        "chunk A unloaded: both unregistered"))
                .thenExecute(() -> {
                    helper.assertTrue(!nether.getChunkSource().hasChunk(chunkA, CHUNK_Z), "chunk A is gone");
                    helper.assertTrue(tx.isRemoved() && rx.isRemoved(), "the game removed both entities");
                    mark[0] = tx.samples() + rx.samples();
                    mark[1] = rx.deliveredCount();
                })
                .thenIdle(3 * interval())
                .thenExecute(() -> {
                    helper.assertTrue(tx.samples() + rx.samples() == mark[0] && rx.deliveredCount() == mark[1],
                            "unloaded radio links are not served and hear nothing");
                    helper.assertTrue(outputOn(nether, farPos) && far.knownOnCount() == 1,
                            "B holds the unloaded transmitter's last state");
                    // 3. Reload A from disk.
                    nether.setChunkForced(chunkA, CHUNK_Z, true);
                    nether.getChunk(chunkA, CHUNK_Z);
                    BlockEntity rxNow = nether.getBlockEntity(rxPos);
                    BlockEntity txNow = nether.getBlockEntity(txPos);
                    helper.assertTrue(rxNow instanceof RadioLinkReceiverBlockEntity && rxNow != rx,
                            "a new receiver entity, loaded from disk: " + rxNow);
                    helper.assertTrue(txNow instanceof RadioLinkTransmitterBlockEntity && txNow != tx,
                            "a new transmitter entity: " + txNow);
                    reloaded[0] = (RadioLinkReceiverBlockEntity) rxNow;
                    helper.assertTrue(registry.deviceAt(rxPos) == rxNow && registry.deviceAt(txPos) == txNow,
                            "registered as soon as the chunk loaded");
                    helper.assertTrue(reloaded[0].address() == 6 && ((RadioLinkBlockEntity) txNow).address() == 6,
                            "addresses saved");
                    helper.assertTrue(reloaded[0].samples() == 0 && reloaded[0].output() && outputOn(nether, rxPos),
                            "before its first turn it holds its saved memory and output");
                    helper.assertTrue(reloaded[0].unverifiedCount() == 1, "what it remembers from the save is unverified");
                })
                .thenWaitUntil(() -> helper.assertTrue(reloaded[0].samples() >= 1 && reloaded[0].deliveredCount() >= 1,
                        "reloaded: served again, and messages arrive"))
                .thenExecute(() -> {
                    helper.assertTrue(outputOn(nether, rxPos) && lit(nether, rxPos) && lit(nether, txPos), "on and lit");
                    helper.assertTrue(reloaded[0].unverifiedCount() == 0 && reloaded[0].knownOnCount() == 1,
                            "the transmitter was found (or heard) and is verified");
                    // 4. Unload B.
                    nether.setChunkForced(chunkB, CHUNK_Z, false);
                })
                .thenWaitUntil(() -> helper.assertTrue(!registry.contains(farPos), "chunk B unloaded"))
                .thenExecute(() -> {
                    nether.setBlock(txPos, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
                    helper.assertTrue(!outputOn(nether, rxPos), "A's receiver forgets the broken transmitter at once");
                    nether.setChunkForced(chunkB, CHUNK_Z, true);
                    nether.getChunk(chunkB, CHUNK_Z);
                    BlockEntity farNow = nether.getBlockEntity(farPos);
                    helper.assertTrue(farNow instanceof RadioLinkReceiverBlockEntity && farNow != far, "B reloaded: " + farNow);
                    reloaded[1] = (RadioLinkReceiverBlockEntity) farNow;
                    helper.assertTrue(reloaded[1].samples() == 0 && outputOn(nether, farPos)
                                    && reloaded[1].unverifiedCount() == 1,
                            "B's receiver comes back on, as saved, unverified: it missed the news");
                })
                .thenWaitUntil(() -> helper.assertTrue(reloaded[1].samples() >= 1, "B's receiver served"))
                .thenExecute(() -> helper.assertTrue(!outputOn(nether, farPos) && reloaded[1].knownOnCount() == 0
                                && reloaded[1].unverifiedCount() == 0,
                        "on its first turn it looked the transmitter up, found it gone, and went off"))
                .thenSucceed();
    }

    // ---- 3b. a receiver whose chunk is not FULL (Phase 3B review) ---------------------------------

    /**
     * A receiver in a chunk that is outside FULL but still in memory (four chunks from a forced one:
     * ticket level 35, the ring just outside a player's loaded area in play) stays in the address book,
     * so messages and departures still reach it. Before the Phase 3B review it wrote its output block
     * there, which loaded the chunk back to FULL on the spot and ran redstone in it.
     *
     * <ol>
     *   <li>While FULL, a delivered "powered" message turns it on (the block is written).</li>
     *   <li>Its chunk is released and leaves FULL; the entity is not removed and is not served.</li>
     *   <li>A departure, a new "powered" message and that transmitter's "off" all change its memory
     *       (its output follows) but the block is not written and the chunk stays out of FULL.</li>
     *   <li>Forced again: on its next turn the block catches up (off).</li>
     * </ol>
     * The messages are handed to the receiver directly, as {@link RadioLinkNetwork} does.
     */
    private static void outsideFull(GameTestHelper helper) {
        ServerLevel nether = nether(helper);
        int chunkX = OUTSIDE_FULL_CHUNK_X;
        forceChunk(nether, OUTSIDE_FULL_KEEPER_CHUNK_X, CHUNK_Z);
        forceChunk(nether, chunkX, CHUNK_Z);
        LevelChunk chunk = nether.getChunk(chunkX, CHUNK_Z);
        BlockPos rxPos = new BlockPos(chunkX * 16 + 4, AIR_Y, CHUNK_Z * 16 + 4);
        RadioLinkReceiverBlockEntity rx = receiver(helper, nether, rxPos);
        // Cleanups run newest first: this one loads the chunk before the receiver is removed.
        CLEANUPS.add(() -> {
            nether.setChunkForced(chunkX, CHUNK_Z, true);
            nether.getChunk(chunkX, CHUNK_Z);
        });
        long txA = new BlockPos(chunkX * 16 + 1, AIR_Y, CHUNK_Z * 16 + 1).asLong();
        long txB = new BlockPos(chunkX * 16 + 2, AIR_Y, CHUNK_Z * 16 + 1).asLong();
        FixedReceiverRegistry registry = FixedReceiverRegistry.of(nether);
        long[] mark = new long[1];

        helper.startSequence()
                .thenWaitUntil(() -> helper.assertTrue(rx.samples() >= 1, "served once while FULL"))
                .thenExecute(() -> {
                    rx.deliver(txA, true, nether.getGameTime());
                    helper.assertTrue(rx.output() && outputOn(nether, rxPos), "FULL: a delivered message writes the block");
                    nether.setChunkForced(chunkX, CHUNK_Z, false);
                })
                .thenWaitUntil(() -> helper.assertTrue(nether.getChunkSource().getChunkNow(chunkX, CHUNK_Z) == null,
                        "the chunk leaves FULL"))
                .thenExecute(() -> {
                    helper.assertTrue(!rx.isRemoved() && rx.attached() && registry.deviceAt(rxPos) == rx,
                            "still in memory, still attached, still registered");
                    mark[0] = rx.samples();
                })
                .thenIdle(2 * interval() + 2)
                .thenExecute(() -> {
                    helper.assertTrue(rx.samples() == mark[0], "not served while its chunk is not FULL");
                    rx.forget(txA);
                    helper.assertTrue(!rx.output(), "the departure is heard: memory says off");
                    assertNotWritten(helper, nether, chunk, rxPos, chunkX, true);
                    rx.deliver(txB, true, nether.getGameTime());
                    helper.assertTrue(rx.output() && rx.knownOnCount() == 1, "a new message is heard: on");
                    rx.deliver(txB, false, nether.getGameTime());
                    helper.assertTrue(!rx.output() && rx.knownOnCount() == 0, "and its off: off");
                    assertNotWritten(helper, nether, chunk, rxPos, chunkX, true);
                    mark[0] = rx.samples();
                    nether.setChunkForced(chunkX, CHUNK_Z, true);
                })
                .thenWaitUntil(() -> helper.assertTrue(rx.samples() > mark[0], "served again once FULL"))
                .thenExecute(() -> helper.assertTrue(!outputOn(nether, rxPos) && signalFrom(nether, rxPos) == 0,
                        "its next turn writes the output it worked out meanwhile: off"))
                .thenSucceed();
    }

    /**
     * The receiver's block still says {@code POWERED = expected}, read from the chunk object without
     * loading anything, and the chunk is still outside FULL.
     */
    private static void assertNotWritten(GameTestHelper helper, ServerLevel nether, LevelChunk chunk, BlockPos pos,
                                         int chunkX, boolean expected) {
        BlockState state = chunk.getBlockState(pos);
        helper.assertTrue(state.getBlock() instanceof RadioLinkReceiverBlock
                        && state.getValue(RadioLinkReceiverBlock.POWERED) == expected,
                "the block was not written while the chunk is not FULL: " + state);
        helper.assertTrue(nether.getChunkSource().getChunkNow(chunkX, CHUNK_Z) == null,
                "and the chunk was not loaded back to FULL");
    }

    // ---- 4. cost ----------------------------------------------------------------------------------

    /**
     * 200 radio links (200 transmitters and 200 receivers, so 400 fixed devices) in one forced Nether
     * chunk: transmitters in one layer (every other one powered by a redstone block underneath),
     * receivers two layers up, addresses 0 to 15 in turn, so each transmitter reaches 12 or 13
     * receivers. One band_900 omni above them.
     *
     * <ol>
     *   <li>Until every radio link has been served once.</li>
     *   <li>Warm-up: {@value #COST_WARMUP_FAST_TICKS} ticks at interval 1 (in memory, put back after),
     *       every device replayed and every transmitter sending every tick (budget-bound), then
     *       {@value #COST_WARMUP_TICKS} ticks at the real interval, so the round robin is compiled for a
     *       quiet server.</li>
     *   <li>Steady state: {@value #COST_WINDOWS} windows of {@value #COST_WINDOW_TICKS} ticks, which must
     *       be replays only. The ticker's whole cost per tick, the Radio Links' own work included (it runs
     *       inside the dispatch), is logged per window without its slowest tick, and the median of the
     *       windows is asserted under 0.1 ms. The median, because a window can catch a pause the code did
     *       not cause, and the same code varies by up to a factor of two between runs on the development
     *       machine (NOTES.md, slice 9: medians 27 to 44 µs).</li>
     * </ol>
     */
    private static void cost(GameTestHelper helper) {
        ServerLevel nether = nether(helper);
        forceChunk(nether, COST_CHUNK_X, CHUNK_Z);
        int baseX = COST_CHUNK_X * 16;
        int baseZ = CHUNK_Z * 16;
        int configuredInterval = RanCraftConfig.EVALUATION_INTERVAL_TICKS.get();
        CLEANUPS.add(() -> RanCraftConfig.EVALUATION_INTERVAL_TICKS.set(configuredInterval));
        // Row 16d: the steady state measured here is the replay path. The maximum replay age adds one
        // fresh evaluation per receiver per 300-600 ticks on top (NOTES.md, row 16d), so it is off here.
        int configuredMaxReplay = RanCraftConfig.FIXED_RECEIVER_MAX_REPLAY_TICKS.get();
        CLEANUPS.add(() -> RanCraftConfig.FIXED_RECEIVER_MAX_REPLAY_TICKS.set(configuredMaxReplay));
        RanCraftConfig.FIXED_RECEIVER_MAX_REPLAY_TICKS.set(0);
        cell(nether, CellParams.omniDefaults(-9_100_041L, baseX + 8, AIR_Y + 8, baseZ + 8));

        List<RadioLinkTransmitterBlockEntity> txs = new ArrayList<>();
        List<RadioLinkReceiverBlockEntity> rxs = new ArrayList<>();
        for (int i = 0; i < LINKS_FOR_COST; i++) {
            BlockPos txPos = new BlockPos(baseX + i % 16, AIR_Y, baseZ + i / 16);
            BlockPos rxPos = txPos.above(2);
            RadioLinkTransmitterBlockEntity tx = transmitter(helper, nether, txPos);
            RadioLinkReceiverBlockEntity rx = receiver(helper, nether, rxPos);
            tx.cycleAddress(i);
            rx.cycleAddress(i);
            if (i % 2 == 0) {
                place(nether, txPos.below(), Blocks.REDSTONE_BLOCK);
            }
            txs.add(tx);
            rxs.add(rx);
        }
        RadioLinkNetwork network = RadioLinkNetwork.of(nether);
        FixedReceiverTicker.Stats[] warmUp = new FixedReceiverTicker.Stats[1];

        GameTestSequence sequence = helper.startSequence()
                .thenWaitUntil(() -> helper.assertTrue(txs.stream().allMatch(tx -> tx.samples() >= 1)
                        && rxs.stream().allMatch(rx -> rx.samples() >= 1), "every radio link served once"))
                .thenExecute(() -> {
                    RanCraftConfig.EVALUATION_INTERVAL_TICKS.set(1);
                    FixedReceiverTicker.resetStats();
                })
                .thenIdle(COST_WARMUP_FAST_TICKS)
                .thenExecute(() -> {
                    warmUp[0] = FixedReceiverTicker.stats();
                    RanCraftConfig.EVALUATION_INTERVAL_TICKS.set(configuredInterval);
                })
                .thenIdle(COST_WARMUP_TICKS);
        List<FixedReceiverTicker.Stats> windows = new ArrayList<>();
        List<RadioLinkNetwork.Stats> windowMessages = new ArrayList<>();
        for (int w = 0; w < COST_WINDOWS; w++) {
            sequence.thenExecute(() -> {
                        FixedReceiverTicker.resetStats();
                        network.resetStats();
                    })
                    .thenIdle(COST_WINDOW_TICKS)
                    .thenExecute(() -> {
                        windows.add(FixedReceiverTicker.stats());
                        windowMessages.add(network.stats());
                    });
        }
        sequence.thenExecute(() -> {
                    for (int i = 0; i < LINKS_FOR_COST; i++) {
                        boolean expectOn = (i % 16) % 2 == 0;
                        helper.assertTrue(rxs.get(i).output() == expectOn && txs.get(i).served() && rxs.get(i).served(),
                                "radio link " + i + ": served, and on exactly when its address has a powered transmitter");
                    }
                    StringBuilder log = new StringBuilder(String.format(Locale.ROOT,
                            "RANCraft radio link cost: %d radio links (%d fixed devices), interval %d, budget %.2f ms. "
                                    + "Warm-up at interval 1: %d replays over %d ticks, slowest tick %.1f us.",
                            LINKS_FOR_COST, 2 * LINKS_FOR_COST, configuredInterval, RanCraftConfig.fixedReceiverTickBudgetMs(),
                            warmUp[0].replays(), warmUp[0].ticks(), us(warmUp[0].maxTickNanos())));
                    double[] perTick = new double[windows.size()];
                    for (int w = 0; w < windows.size(); w++) {
                        FixedReceiverTicker.Stats steady = windows.get(w);
                        RadioLinkNetwork.Stats messages = windowMessages.get(w);
                        helper.assertTrue(steady.evaluations() == 0 && steady.skipped() == 0 && steady.failures() == 0,
                                "steady state is replays only: " + steady);
                        helper.assertTrue(messages.messages() > 0 && messages.delivered() == messages.attempts(),
                                "clean links deliver everything: " + messages);
                        perTick[w] = us(steady.nanos() - steady.maxTickNanos()) / (steady.ticks() - 1);
                        log.append(String.format(Locale.ROOT,
                                " Window %d, %d ticks: %.2f us/tick (%.2f without the slowest, which took %.1f us), of "
                                        + "which setup %.2f, scan %.2f, serving %.2f us/tick (%.3f us per dispatch, the "
                                        + "Radio Link's work included); %d replays, %d evaluations; %d messages, %d deliveries.",
                                w + 1, steady.ticks(), us(steady.nanos()) / steady.ticks(), perTick[w],
                                us(steady.maxTickNanos()), us(steady.setupNanos()) / steady.ticks(),
                                us(steady.nanos() - steady.setupNanos() - steady.serveNanos()) / steady.ticks(),
                                us(steady.serveNanos()) / steady.ticks(),
                                us(steady.serveNanos()) / Math.max(1, steady.replays()),
                                steady.replays(), steady.evaluations(), messages.messages(), messages.delivered()));
                    }
                    double[] sorted = perTick.clone();
                    Arrays.sort(sorted);
                    double median = sorted[sorted.length / 2];
                    log.append(String.format(Locale.ROOT, " Median of the windows: %.2f us/tick.", median));
                    RanCraft.LOGGER.info(log.toString());
                    helper.assertTrue(median < STEADY_STATE_LIMIT_US,
                            "3B done-when: under 0.1 ms per tick in steady state, got a median of " + median + " us");
                })
                .thenSucceed();
    }

    private static double us(long nanos) {
        return nanos / 1_000.0;
    }
}
