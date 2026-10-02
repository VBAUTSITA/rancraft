package dev.rancraft.gametest;

import dev.rancraft.RanCraft;
import dev.rancraft.RanCraftConfig;
import dev.rancraft.block.AntennaBlockEntity;
import dev.rancraft.block.RadioLinkBlockEntity;
import dev.rancraft.block.RadioLinkReceiverBlock;
import dev.rancraft.block.RadioLinkReceiverBlockEntity;
import dev.rancraft.block.RadioLinkTransmitterBlockEntity;
import dev.rancraft.data.RfDataLoader;
import dev.rancraft.device.DeviceMemory;
import dev.rancraft.device.StorageTerminal;
import dev.rancraft.device.TerminalLink;
import dev.rancraft.menu.RemoteContainerMenu;
import dev.rancraft.registry.ModBlocks;
import dev.rancraft.registry.ModDataComponents;
import dev.rancraft.registry.ModItems;
import dev.rancraft.rf.BackhaulGraph.BackhaulState;
import dev.rancraft.rf.CellParams;
import dev.rancraft.rf.DeviceRequirement.Verdict;
import dev.rancraft.rf.MicrowaveLink;
import dev.rancraft.rf.ReceiverState;
import dev.rancraft.rf.RfConfig;
import dev.rancraft.rf.RfEngine;
import dev.rancraft.rf.ServiceLevel;
import dev.rancraft.rf.SignalSample;
import dev.rancraft.world.BackhaulNetwork;
import dev.rancraft.world.LevelWorldProbe;
import dev.rancraft.world.SignalTicker;
import dev.rancraft.world.SiteRegistry;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Consumer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.GlobalPos;
import net.minecraft.gametest.framework.AfterBatch;
import net.minecraft.gametest.framework.GameTestGenerator;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.TestFunction;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.ChestType;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import org.jetbrains.annotations.Nullable;

/**
 * The Wireless Storage Terminal at runtime (Phase 3 slice 13, §3C.3). {@code StorageTerminalTest} pins
 * the rules headless; these run them on a live server level: the real item use paths, vanilla's
 * {@code ServerPlayer.openMenu} and the {@code stillValid} check in {@code ServerPlayer.tick} that closes
 * a menu, real chests and barrels, real chunk unloading, and the backhaul cap of slice 12.
 *
 * <ul>
 *   <li><b>Session</b>: binding (only a chest or barrel, only near a Core Site), the reasons a use is
 *       refused (no reading, another dimension, a tier-1 band, a weak signal, no core, not storage), a
 *       session over a single chest, a double chest and a barrel that reads and writes the real
 *       container without lifting the lid, held while the verdicts stay OK (from the hotbar too), and
 *       closed by a weak signal, by the terminal going unheard, by a broken half and by the core going.
 *       The cost of the per-tick session check is logged.</li>
 *   <li><b>Unloaded</b>: the storage's chunk released while a session is open closes it ("storage
 *       unreachable"); a use then is refused and loads nothing, before and after the chunk really
 *       unloads.</li>
 *   <li><b>Backhaul</b> ({@code requireBackhaul} on): the 3C done-when "a tree grown into a link path
 *       turns it DEGRADED, the cells behind it read BH: LIMITED, and the Storage Terminal stops working
 *       while the Radio Link keeps working". Six leaves placed on a 1000-block hop (about 18 dB, §3C.2)
 *       stand in for the canopy (slice 7 showed tree growth moves the same region epochs).</li>
 * </ul>
 *
 * <p><b>The player.</b> A {@link SilentServerPlayer}: a real {@code ServerPlayer}, not on the player list
 * and not in the level, whose connection sends nothing. The server does not tick it, so each test ticks
 * it itself every tick ({@link Feeder}), as {@code ServerLevel} does a connected player; and the real
 * {@code SignalTicker} never evaluates it, so the feeder stands in for the ticker's per-player path: once
 * per evaluation interval it evaluates the player's eye position with the engine against the site
 * registry's cells in range, and hands the sample to {@link SignalTicker#dispatchToCarried}, which scans
 * the carried devices and dispatches with the serving cell's backhaul cap exactly as the ticker does.
 *
 * <p>Each test builds far from every other, in forced overworld chunks at y {@value #Y} (open air in the
 * flat test world), in a batch of its own; each batch's {@link AfterBatch} method removes the blocks,
 * releases the chunks and puts {@code requireBackhaul} back, also when the test failed.
 */
@GameTestHolder(RanCraft.MOD_ID)
public final class StorageTerminalGameTests {

    private static final String BATCH_SESSION = "rancraft_storage_terminal_session";
    private static final String BATCH_UNLOADED = "rancraft_storage_terminal_unloaded";
    private static final String BATCH_BACKHAUL = "rancraft_storage_terminal_backhaul";
    private static final int TIMEOUT_TICKS = 1_600;
    /** Block entities run {@code onLoad} on the next tick. */
    private static final int SETTLE = 3;

    /** Open air in the flat test world. */
    private static final int Y = 100;
    /** Chunk (768, 768): 4000 blocks from the backhaul tests. */
    private static final int SESSION_X = 12_288;
    private static final int SESSION_Z = 12_288;
    /**
     * The unloaded test's storage, chunk (800, 800), and its player's cell 16 chunks east: a forced chunk
     * keeps every chunk within 13 of it in memory ({@code RadioLinkGameTests}), so the storage's chunk
     * really unloads once it is released.
     */
    private static final int UNLOADED_X = 12_800;
    private static final int UNLOADED_Z = 12_800;
    private static final int UNLOADED_PLAYER_X = 13_056;
    /** Chunk (768, 784): the core end of the backhaul test's 1000-block hop. */
    private static final int BACKHAUL_X = 12_288;
    private static final int BACKHAUL_Z = 12_552;

    private static final String REFUSED = "rancraft.storage_terminal.refused";
    private static final String LOST = "rancraft.storage_terminal.lost";
    private static final String BOUND = "rancraft.storage_terminal.bound";
    private static final String BIND_NO_CORE = "rancraft.storage_terminal.bind_no_core";
    private static final String BIND_NOT_STORAGE = "rancraft.storage_terminal.bind_not_storage";

    /** Undone by each batch's {@link AfterBatch} method, newest first, pass or fail. Server thread. */
    private static final List<Runnable> CLEANUPS = new ArrayList<>();

    private StorageTerminalGameTests() {
    }

    @GameTestGenerator
    public static Collection<TestFunction> storageTerminalTests() {
        String prefix = StorageTerminalGameTests.class.getSimpleName().toLowerCase(Locale.ROOT) + ".";
        List<TestFunction> tests = new ArrayList<>();
        tests.add(test(BATCH_SESSION, prefix + "binds_near_a_core_opens_and_closes_when_service_is_lost",
                StorageTerminalGameTests::session));
        tests.add(test(BATCH_UNLOADED, prefix + "an_unloaded_chunk_is_unreachable_and_never_loaded",
                StorageTerminalGameTests::unloaded));
        tests.add(test(BATCH_BACKHAUL, prefix + "a_limited_cell_stops_the_terminal_the_radio_link_keeps_working",
                StorageTerminalGameTests::backhaulLimited));
        return tests;
    }

    private static TestFunction test(String batch, String name, Consumer<GameTestHelper> body) {
        return new TestFunction(batch, name, HarvestGameTests.EMPTY_TEMPLATE, TIMEOUT_TICKS, 0L, true, body);
    }

    @AfterBatch(batch = BATCH_SESSION)
    public static void afterSession(ServerLevel level) {
        runCleanups();
    }

    @AfterBatch(batch = BATCH_UNLOADED)
    public static void afterUnloaded(ServerLevel level) {
        runCleanups();
    }

    @AfterBatch(batch = BATCH_BACKHAUL)
    public static void afterBackhaul(ServerLevel level) {
        runCleanups();
    }

    private static void runCleanups() {
        for (int i = CLEANUPS.size() - 1; i >= 0; i--) {
            try {
                CLEANUPS.get(i).run();
            } catch (RuntimeException failure) {
                RanCraft.LOGGER.error("RANCraft storage terminal game test cleanup failed", failure);
            }
        }
        CLEANUPS.clear();
    }

    // ---- 1. the session ---------------------------------------------------------------------------

    /** The session test's blocks: storage round a core, a sector antenna 20 blocks south. */
    private record SessionSite(BlockPos core, BlockPos chest, BlockPos left, BlockPos right, BlockPos barrel,
                               BlockPos dispenser, BlockPos farChest, BlockPos sector) {

        static SessionSite at(int x, int y, int z) {
            return new SessionSite(new BlockPos(x, y, z), new BlockPos(x + 4, y, z),
                    new BlockPos(x + 4, y, z + 4), new BlockPos(x + 5, y, z + 4), new BlockPos(x + 4, y, z + 8),
                    new BlockPos(x + 4, y, z + 12), new BlockPos(x + 40, y, z), new BlockPos(x, y, z + 20));
        }

        List<BlockPos> all() {
            return List.of(core, chest, left, right, barrel, dispenser, farChest, sector);
        }

        /** 12 blocks east of the sector, on its boresight, at its height. */
        Vec3 goodEye() {
            return new Vec3(sector.getX() + 12.5, sector.getY() + 0.5, sector.getZ() + 0.5);
        }
    }

    /**
     * §3C.3 end to end, {@code requireBackhaul} off (the default). See the class comment; the steps are
     * commented in place.
     */
    private static void session(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        SessionSite site = SessionSite.at(SESSION_X, Y, SESSION_Z);
        List<Long> forced = new ArrayList<>();
        Set<Long> chunks = new TreeSet<>();
        for (BlockPos pos : site.all()) {
            chunks.add(ChunkPos.asLong(pos));
        }
        CLEANUPS.add(() -> BackhaulGameTests.release(level, forced));
        BackhaulGameTests.force(level, chunks, forced);
        CLEANUPS.add(() -> clearAndRemove(level, site.all()));

        level.setBlock(site.core(), ModBlocks.CORE_SITE.get().defaultBlockState(), Block.UPDATE_ALL);
        level.setBlock(site.chest(), Blocks.CHEST.defaultBlockState(), Block.UPDATE_ALL);
        // A double chest, as a player makes one: facing north, the left half's partner is east of it.
        BlockState chest = Blocks.CHEST.defaultBlockState().setValue(ChestBlock.FACING, Direction.NORTH);
        level.setBlock(site.left(), chest.setValue(ChestBlock.TYPE, ChestType.LEFT), Block.UPDATE_ALL);
        level.setBlock(site.right(), chest.setValue(ChestBlock.TYPE, ChestType.RIGHT), Block.UPDATE_ALL);
        level.setBlock(site.barrel(), Blocks.BARREL.defaultBlockState(), Block.UPDATE_ALL);
        level.setBlock(site.dispenser(), Blocks.DISPENSER.defaultBlockState(), Block.UPDATE_ALL);
        level.setBlock(site.farChest(), Blocks.CHEST.defaultBlockState(), Block.UPDATE_ALL);
        level.setBlock(site.sector(), ModBlocks.SECTOR_ANTENNA.get().defaultBlockState(), Block.UPDATE_ALL);

        SilentServerPlayer player = player(level, "rancraft_terminal");
        Feeder feeder = new Feeder(level, player);
        feeder.moveEyeTo(site.goodEye());
        ItemStack terminal = new ItemStack(ModItems.STORAGE_TERMINAL.get());
        player.setItemInHand(InteractionHand.MAIN_HAND, terminal);
        helper.onEachTick(feeder::tick);
        BackhaulNetwork network = BackhaulNetwork.of(level);
        RemoteContainerMenu[] menu = new RemoteContainerMenu[1];
        Vec3[] weakEye = new Vec3[1];
        long[] mark = new long[2];

        helper.startSequence()
                .thenIdle(SETTLE)
                .thenExecute(() -> {
                    helper.assertTrue(network.isCore(site.core()), "the core joined the network");

                    // Binding: only sneak + use, only a chest or a barrel, only near a Core Site.
                    player.setShiftKeyDown(false);
                    InteractionResult plain = useOn(player, terminal, site.chest());
                    helper.assertTrue(plain == InteractionResult.PASS && StorageTerminal.targetOf(terminal) == null,
                            "a use without sneaking binds nothing: " + plain);
                    player.setShiftKeyDown(true);
                    useOn(player, terminal, site.farChest());
                    assertLastKey(helper, player, BIND_NO_CORE);
                    helper.assertTrue(StorageTerminal.targetOf(terminal) == null, "a chest 40 blocks from the core is refused");
                    useOn(player, terminal, site.dispenser());
                    assertLastKey(helper, player, BIND_NOT_STORAGE);
                    helper.assertTrue(StorageTerminal.targetOf(terminal) == null, "a dispenser is refused");
                    bind(helper, player, terminal, level, site.chest());

                    // No reading yet: refused, nothing opened.
                    use(player, terminal);
                    assertRefused(helper, player, "no_reading");

                    // Another dimension: refused before anything else is looked at, and nothing loaded there.
                    ServerLevel nether = level.getServer().getLevel(Level.NETHER);
                    helper.assertTrue(nether != null, "the game test server has a Nether");
                    ItemStack elsewhere = terminal.copy();
                    elsewhere.set(ModDataComponents.STORAGE_TERMINAL_TARGET.get(), GlobalPos.of(Level.NETHER, site.chest()));
                    player.setItemInHand(InteractionHand.MAIN_HAND, elsewhere);
                    use(player, elsewhere);
                    assertRefused(helper, player, "other_dimension");
                    helper.assertTrue(nether.getChunkSource().getChunkNow(site.chest().getX() >> 4, site.chest().getZ() >> 4) == null,
                            "the Nether chunk was not loaded");
                    player.setItemInHand(InteractionHand.MAIN_HAND, terminal);

                    // The sector on band_900 (tier 1), its default, aimed at the player: GOOD or better, but LOW_TIER.
                    configure(helper, level, site.sector(), "band_900");
                    SignalSample onBand900 = feeder.dispatch();
                    helper.assertTrue(onBand900.serviceLevel().atLeast(ServiceLevel.GOOD) && onBand900.servingCellId() == site.sector().asLong(),
                            "fixture: GOOD on the sector, got " + onBand900.serviceLevel());
                    helper.assertTrue(link(helper, player).verdict() == Verdict.LOW_TIER, "band_900 is tier 1");
                    use(player, terminal);
                    assertRefused(helper, player, "low_tier", 2, "band_900", 1);

                    // band_1800 (tier 2), aimed at the player; the feeder dispatches once per interval from now.
                    configure(helper, level, site.sector(), "band_1800");
                    feeder.start();
                })
                .thenWaitUntil(() -> helper.assertTrue(feeder.dispatches() >= 2 && link(helper, player).verdict() == Verdict.OK,
                        "the terminal's verdict is OK on band_1800"))
                .thenExecute(() -> {
                    // A session over the single chest.
                    menu[0] = open(helper, player, terminal, 3, MenuType.GENERIC_9x3);
                    ChestBlockEntity chestEntity = chestAt(helper, level, site.chest());
                    helper.assertTrue(menu[0].storage() == chestEntity, "the session is the real chest");
                    menu[0].getSlot(0).set(new ItemStack(Items.DIAMOND, 5));
                    helper.assertTrue(chestEntity.getItem(0).is(Items.DIAMOND) && chestEntity.getItem(0).getCount() == 5,
                            "a stack put in through the terminal is in the chest");
                    chestEntity.setItem(1, new ItemStack(Items.EMERALD, 3));
                    helper.assertTrue(menu[0].getSlot(1).getItem().is(Items.EMERALD), "a stack put in the chest shows in the terminal");
                    player.getInventory().setItem(9, new ItemStack(Items.IRON_INGOT, 16));
                    menu[0].quickMoveStack(player, 3 * 9); // the first slot of the player's main inventory
                    helper.assertTrue(countIn(chestEntity, Items.IRON_INGOT) == 16 && player.getInventory().getItem(9).isEmpty(),
                            "shift-click moves a stack into the chest");
                    helper.assertTrue(ChestBlockEntity.getOpenCount(level, site.chest()) == 0,
                            "the lid stays shut: the storage is read over the network");
                    measureCost(helper, player, menu[0]);
                    mark[0] = feeder.dispatches();
                })
                .thenIdle(3 * interval())
                .thenExecute(() -> {
                    helper.assertTrue(feeder.dispatches() >= mark[0] + 3, "the feeder kept dispatching");
                    assertOpen(helper, player, menu[0], "three intervals of OK verdicts");
                    // Carried in the hotbar but not held: still dispatched (§3A.3), so the session holds.
                    player.getInventory().setItem(0, ItemStack.EMPTY);
                    player.getInventory().setItem(4, terminal);
                    mark[1] = link(helper, player).tick();
                })
                .thenIdle(2 * interval() + 2)
                .thenExecute(() -> {
                    helper.assertTrue(link(helper, player).tick() > mark[1], "a terminal in the hotbar is still dispatched");
                    assertOpen(helper, player, menu[0], "the terminal in the hotbar");
                    player.getInventory().setItem(4, ItemStack.EMPTY);
                    player.getInventory().setItem(0, terminal);

                    // Walk out until the signal is below GOOD.
                    weakEye[0] = weakPoint(helper, level, site);
                    feeder.moveEyeTo(weakEye[0]);
                })
                .thenWaitUntil(() -> assertClosed(helper, player, menu[0], "weak_signal"))
                .thenExecute(() -> {
                    TerminalLink weak = link(helper, player);
                    helper.assertTrue(weak.verdict() == Verdict.LOW_QUALITY && !weak.radio().atLeast(ServiceLevel.GOOD)
                                    && weak.radio() != ServiceLevel.NONE, "the radio itself is short: " + weak);
                    helper.assertTrue(ChestBlockEntity.getOpenCount(level, site.chest()) == 0,
                            "the chest's opener count is untouched by the remote close");
                    use(player, terminal);
                    assertRefused(helper, player, "weak_signal", weak.radio().label(), "GOOD");
                    feeder.moveEyeTo(site.goodEye());
                    mark[0] = feeder.dispatches();
                })
                .thenWaitUntil(() -> helper.assertTrue(feeder.dispatches() > mark[0] && link(helper, player).verdict() == Verdict.OK,
                        "back in coverage"))
                .thenExecute(() -> {
                    menu[0] = open(helper, player, terminal, 3, MenuType.GENERIC_9x3);
                    // Put away: the terminal stops being dispatched, and its last verdict ages out.
                    feeder.stop();
                    mark[1] = link(helper, player).tick();
                })
                .thenWaitUntil(() -> assertClosed(helper, player, menu[0], "no_reading"))
                .thenExecute(() -> {
                    long silent = level.getGameTime() - mark[1];
                    long maxAge = TerminalLink.maxAgeTicks(interval());
                    helper.assertTrue(silent > maxAge && silent <= maxAge + 2,
                            "the session ends once the last verdict is older than two intervals: " + silent + " ticks");

                    // A double chest: bound at its left half, 54 slots, both halves.
                    feeder.start();
                    bind(helper, player, terminal, level, site.left());
                    chestAt(helper, level, site.right()).setItem(0, new ItemStack(Items.GOLD_INGOT));
                    chestAt(helper, level, site.left()).setItem(0, new ItemStack(Items.LAPIS_LAZULI));
                })
                .thenWaitUntil(() -> helper.assertTrue(link(helper, player).verdict() == Verdict.OK
                        && link(helper, player).fresh(level.getGameTime(), interval()), "dispatched again"))
                .thenExecute(() -> {
                    menu[0] = open(helper, player, terminal, 6, MenuType.GENERIC_9x6);
                    helper.assertTrue(menu[0].storage().getContainerSize() == 54, "54 slots");
                    // Vanilla's order, as a double chest opened by hand: the right half first.
                    helper.assertTrue(menu[0].getSlot(0).getItem().is(Items.GOLD_INGOT)
                                    && menu[0].getSlot(27).getItem().is(Items.LAPIS_LAZULI),
                            "both halves, in vanilla's order: " + menu[0].getSlot(0).getItem() + ", " + menu[0].getSlot(27).getItem());
                    // Break the right half: the session ends at the next check.
                    chestAt(helper, level, site.right()).clearContent();
                    level.setBlock(site.right(), Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
                })
                .thenWaitUntil(() -> assertClosed(helper, player, menu[0], "not_storage"))
                .thenExecute(() -> {
                    // A barrel: 27 slots. Closed by the player: no "connection lost".
                    bind(helper, player, terminal, level, site.barrel());
                    menu[0] = open(helper, player, terminal, 3, MenuType.GENERIC_9x3);
                    helper.assertTrue(menu[0].storage() == level.getBlockEntity(site.barrel()), "the session is the barrel");
                    player.messages.clear();
                    player.closeContainer();
                    helper.assertTrue(player.containerMenu == player.inventoryMenu && menu[0].lost() == null
                            && player.last(LOST) == null, "closed by the player, nothing lost");
                    // No longer a chest or barrel.
                    level.setBlock(site.barrel(), Blocks.DISPENSER.defaultBlockState(), Block.UPDATE_ALL);
                    use(player, terminal);
                    assertRefused(helper, player, "not_storage");

                    // The core goes during a session.
                    bind(helper, player, terminal, level, site.chest());
                    menu[0] = open(helper, player, terminal, 3, MenuType.GENERIC_9x3);
                    helper.assertTrue(menu[0].core().equals(site.core()), "the session goes through the core");
                    level.setBlock(site.core(), Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
                    helper.assertFalse(network.isCore(site.core()), "the core left the network");
                })
                .thenWaitUntil(() -> assertClosed(helper, player, menu[0], "no_core"))
                .thenExecute(() -> {
                    use(player, terminal);
                    assertRefused(helper, player, "no_core");
                    player.setShiftKeyDown(true);
                    useOn(player, terminal, site.chest());
                    player.setShiftKeyDown(false);
                    assertLastKey(helper, player, BIND_NO_CORE);
                    helper.assertTrue(site.chest().equals(StorageTerminal.targetOf(terminal).pos()),
                            "a refused bind keeps the binding");
                })
                .thenSucceed();
    }

    /**
     * The first point east of the sector, along its boresight, where the radio is below GOOD but not
     * gone: a real evaluation, whatever the path-loss figures are.
     */
    private static Vec3 weakPoint(GameTestHelper helper, ServerLevel level, SessionSite site) {
        for (int d = 15; d <= 1_395; d += 5) {
            Vec3 eye = new Vec3(site.sector().getX() + 0.5 + d, site.sector().getY() + 0.5, site.sector().getZ() + 0.5);
            SignalSample sample = evaluate(level, eye);
            ServiceLevel radio = sample.serviceLevel();
            if (radio != ServiceLevel.NONE && !radio.atLeast(ServiceLevel.GOOD)) {
                RanCraft.LOGGER.info(String.format(Locale.ROOT,
                        "RANCraft storage terminal: the signal drops below GOOD %d blocks from the sector (%s, SINR %.1f dB)",
                        d, radio.label(), sample.sinrDb()));
                return eye;
            }
        }
        helper.fail("no point below GOOD with service on the sector's boresight");
        return null;
    }

    /**
     * The session check's cost: {@code stillValid}, which {@code ServerPlayer.tick} runs once per tick
     * for an open menu, while every rule holds (the full check, nothing short-circuited by a failure).
     */
    private static void measureCost(GameTestHelper helper, SilentServerPlayer player, RemoteContainerMenu menu) {
        int warmup = 5_000;
        int reps = 20_000;
        boolean valid = true;
        for (int i = 0; i < warmup; i++) {
            valid &= menu.stillValid(player);
        }
        long t0 = System.nanoTime();
        for (int i = 0; i < reps; i++) {
            valid &= menu.stillValid(player);
        }
        long t1 = System.nanoTime();
        helper.assertTrue(valid, "the session held while measured: " + menu.lost());
        double us = (t1 - t0) / 1_000.0 / reps;
        RanCraft.LOGGER.info(String.format(Locale.ROOT,
                "RANCraft storage terminal cost: the open session's per-tick check (stillValid) %.3f us", us));
        helper.assertTrue(us < 100.0, "the per-tick check costs well under 0.1 ms (" + us + " us)");
    }

    // ---- 2. an unloaded chunk ---------------------------------------------------------------------

    /**
     * A session over a chest whose forced chunk is then released. The chunk leaves FULL, and the session
     * closes with "storage unreachable: its chunk is not loaded". A use is then refused, and loads
     * nothing: the chunk stays out of FULL, before and after it really unloads (its entities removed).
     */
    private static void unloaded(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos core = new BlockPos(UNLOADED_X + 4, Y, UNLOADED_Z + 4);
        BlockPos chestPos = new BlockPos(UNLOADED_X + 8, Y, UNLOADED_Z + 8);
        BlockPos sector = new BlockPos(UNLOADED_PLAYER_X + 4, Y, UNLOADED_Z + 8);
        int storageChunkX = chestPos.getX() >> 4;
        int storageChunkZ = chestPos.getZ() >> 4;
        List<Long> forced = new ArrayList<>();
        CLEANUPS.add(() -> BackhaulGameTests.release(level, forced));
        BackhaulGameTests.force(level, Set.of(ChunkPos.asLong(chestPos), ChunkPos.asLong(sector)), forced);
        CLEANUPS.add(() -> {
            // Back in memory first: the core and chest must leave through their chunk, not stay in the save.
            level.setChunkForced(storageChunkX, storageChunkZ, true);
            level.getChunk(storageChunkX, storageChunkZ);
            clearAndRemove(level, List.of(core, chestPos, sector));
            level.setChunkForced(storageChunkX, storageChunkZ, false);
        });
        level.setBlock(core, ModBlocks.CORE_SITE.get().defaultBlockState(), Block.UPDATE_ALL);
        level.setBlock(chestPos, Blocks.CHEST.defaultBlockState(), Block.UPDATE_ALL);
        level.setBlock(sector, ModBlocks.SECTOR_ANTENNA.get().defaultBlockState(), Block.UPDATE_ALL);

        SilentServerPlayer player = player(level, "rancraft_unloaded");
        Feeder feeder = new Feeder(level, player);
        feeder.moveEyeTo(new Vec3(sector.getX() + 12.5, Y + 0.5, sector.getZ() + 0.5));
        ItemStack terminal = new ItemStack(ModItems.STORAGE_TERMINAL.get());
        player.setItemInHand(InteractionHand.MAIN_HAND, terminal);
        helper.onEachTick(feeder::tick);
        RemoteContainerMenu[] menu = new RemoteContainerMenu[1];
        ChestBlockEntity[] chest = new ChestBlockEntity[1];

        helper.startSequence()
                .thenIdle(SETTLE)
                .thenExecute(() -> {
                    configure(helper, level, sector, "band_1800");
                    player.setShiftKeyDown(true);
                    bind(helper, player, terminal, level, chestPos);
                    chest[0] = chestAt(helper, level, chestPos);
                    feeder.start();
                })
                .thenWaitUntil(() -> helper.assertTrue(link(helper, player).verdict() == Verdict.OK, "the verdict is OK"))
                .thenExecute(() -> {
                    menu[0] = open(helper, player, terminal, 3, MenuType.GENERIC_9x3);
                    // Release the storage's chunk; the player's cell stays forced, 16 chunks away.
                    level.setChunkForced(storageChunkX, storageChunkZ, false);
                    forced.remove(Long.valueOf(ChunkPos.asLong(chestPos)));
                })
                .thenWaitUntil(() -> assertClosed(helper, player, menu[0], "unloaded"))
                .thenExecute(() -> {
                    helper.assertTrue(level.getChunkSource().getChunkNow(storageChunkX, storageChunkZ) == null,
                            "the storage's chunk left FULL");
                    helper.assertTrue(link(helper, player).verdict() == Verdict.OK, "the radio is fine: only the storage is gone");
                    use(player, terminal);
                    assertRefused(helper, player, "unloaded");
                    helper.assertTrue(level.getChunkSource().getChunkNow(storageChunkX, storageChunkZ) == null,
                            "the use did not load the chunk");
                })
                .thenWaitUntil(() -> helper.assertTrue(chest[0].isRemoved(), "the storage's chunk really unloads"))
                .thenExecute(() -> {
                    use(player, terminal);
                    assertRefused(helper, player, "unloaded");
                    helper.assertTrue(level.getChunkSource().getChunkNow(storageChunkX, storageChunkZ) == null
                                    && !level.getChunkSource().hasChunk(storageChunkX, storageChunkZ),
                            "a use on an unloaded chunk loads nothing");
                })
                .thenSucceed();
    }

    // ---- 3. a backhaul-limited cell -----------------------------------------------------------------

    /**
     * Along +x at one height: the core, dish D0 4 blocks from it and a chest 2 blocks the other side
     * (both near the core); 1000 blocks east dish D1, with a sector antenna 3 blocks north of it (within
     * {@code siteRadiusBlocks}) aimed east at the player, 12 blocks out, and a Radio Link pair 3 blocks
     * north of the player's line. The hop D0-D1 is the cell's only backhaul.
     */
    private record LimitedSite(BlockPos core, BlockPos d0, BlockPos chest, BlockPos d1, BlockPos sector,
                               BlockPos tx, BlockPos rx, List<BlockPos> leaves) {

        static LimitedSite at(int x, int y, int z) {
            List<BlockPos> leaves = new ArrayList<>();
            for (int dx = 502; dx <= 507; dx++) {
                leaves.add(new BlockPos(x + dx, y, z + 3)); // six blocks round the hop's midpoint (x + 504.5)
            }
            return new LimitedSite(new BlockPos(x, y, z), new BlockPos(x + 4, y, z + 3), new BlockPos(x + 2, y, z - 2),
                    new BlockPos(x + 1004, y, z + 3), new BlockPos(x + 1004, y, z),
                    new BlockPos(x + 1012, y, z - 3), new BlockPos(x + 1020, y, z - 3), List.copyOf(leaves));
        }

        Vec3 eye() {
            return new Vec3(sector.getX() + 12.5, sector.getY() + 0.5, sector.getZ() + 0.5);
        }

        List<BlockPos> all() {
            List<BlockPos> all = new ArrayList<>(List.of(core, d0, chest, d1, sector, tx, rx, tx.below()));
            all.addAll(leaves);
            return all;
        }
    }

    /**
     * 3C done-when, with {@code requireBackhaul} on: "a tree grown into a link path turns it DEGRADED,
     * the cells behind it read BH: LIMITED, and the Storage Terminal stops working while the Radio Link
     * keeps working". The session closes with "backhaul limited", the radio's own level still GOOD or
     * better, while the Radio Link served by the same cell still follows its transmitter. The leaves gone,
     * the hop is UP again and the terminal opens again.
     */
    private static void backhaulLimited(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        LimitedSite site = LimitedSite.at(BACKHAUL_X, Y, BACKHAUL_Z);
        boolean previousRequire = RanCraftConfig.REQUIRE_BACKHAUL.get();
        List<Long> forced = new ArrayList<>();
        Set<Long> chunks = new TreeSet<>();
        for (BlockPos pos : site.all()) {
            chunks.add(ChunkPos.asLong(pos));
        }
        CLEANUPS.add(() -> BackhaulGameTests.release(level, forced));
        BackhaulGameTests.force(level, chunks, forced);
        CLEANUPS.add(() -> {
            RanCraftConfig.REQUIRE_BACKHAUL.set(previousRequire);
            clearAndRemove(level, site.all());
            BackhaulNetwork.of(level).recomputeNow(level, false);
        });
        RanCraftConfig.REQUIRE_BACKHAUL.set(true);

        level.setBlock(site.core(), ModBlocks.CORE_SITE.get().defaultBlockState(), Block.UPDATE_ALL);
        level.setBlock(site.d0(), ModBlocks.BACKHAUL_DISH.get().defaultBlockState(), Block.UPDATE_ALL);
        level.setBlock(site.d1(), ModBlocks.BACKHAUL_DISH.get().defaultBlockState(), Block.UPDATE_ALL);
        level.setBlock(site.chest(), Blocks.CHEST.defaultBlockState(), Block.UPDATE_ALL);
        level.setBlock(site.sector(), ModBlocks.SECTOR_ANTENNA.get().defaultBlockState(), Block.UPDATE_ALL);
        level.setBlock(site.tx(), ModBlocks.RADIO_LINK_TRANSMITTER.get().defaultBlockState(), Block.UPDATE_ALL);
        level.setBlock(site.rx(), ModBlocks.RADIO_LINK_RECEIVER.get().defaultBlockState(), Block.UPDATE_ALL);

        SilentServerPlayer player = player(level, "rancraft_limited");
        Feeder feeder = new Feeder(level, player);
        feeder.moveEyeTo(site.eye());
        ItemStack terminal = new ItemStack(ModItems.STORAGE_TERMINAL.get());
        player.setItemInHand(InteractionHand.MAIN_HAND, terminal);
        helper.onEachTick(feeder::tick);
        Player placer = helper.makeMockPlayer(GameType.CREATIVE);
        BackhaulNetwork network = BackhaulNetwork.of(level);
        long cellId = site.sector().asLong();
        RemoteContainerMenu[] menu = new RemoteContainerMenu[1];
        RadioLinkReceiverBlockEntity[] rx = new RadioLinkReceiverBlockEntity[1];

        helper.startSequence()
                .thenIdle(SETTLE)
                .thenExecute(() -> {
                    // Pair first: a recompute may already have judged the unpaired sector NONE (off the air).
                    network.pair(level, site.d0(), site.d1());
                    network.recomputeNow(level, false);
                    configure(helper, level, site.sector(), "band_1800");
                    MicrowaveLink.Budget clear = hop(helper, network, site.d1());
                    helper.assertTrue(clear.state() == MicrowaveLink.LinkState.UP && Math.abs(clear.rslDbm() - (-33.55)) < 0.01,
                            "the 1000-block hop is UP at -33.55 dBm: " + clear);
                    helper.assertTrue(network.stateOf(cellId) == BackhaulState.FULL && BackhaulNetwork.serviceCapAt(level, cellId) == ServiceLevel.EXCELLENT,
                            "the sector is FULL, uncapped: " + network.stateOf(cellId));
                    if (!(level.getBlockEntity(site.tx()) instanceof RadioLinkTransmitterBlockEntity)
                            || !(level.getBlockEntity(site.rx()) instanceof RadioLinkReceiverBlockEntity receiver)) {
                        helper.fail("no radio link entities");
                        return;
                    }
                    rx[0] = receiver;
                    level.setBlock(site.tx().below(), Blocks.REDSTONE_BLOCK.defaultBlockState(), Block.UPDATE_ALL);
                    player.setShiftKeyDown(true);
                    bind(helper, player, terminal, level, site.chest());
                    feeder.start();
                })
                .thenWaitUntil(() -> helper.assertTrue(link(helper, player).verdict() == Verdict.OK && radioOn(level, site.rx()),
                        "the terminal is OK and the Radio Link follows its powered transmitter"))
                .thenExecute(() -> {
                    menu[0] = open(helper, player, terminal, 3, MenuType.GENERIC_9x3);
                    // The canopy: six leaves on the hop, placed as a player places them (each placement
                    // event moves its region bin, which the next allowed recompute re-marches).
                    for (BlockPos leaf : site.leaves()) {
                        BackhaulGameTests.placeWithItem(helper, placer, leaf, new ItemStack(Items.OAK_LEAVES));
                    }
                })
                .thenWaitUntil(() -> helper.assertTrue(network.stateOf(cellId) == BackhaulState.LIMITED,
                        "the leaves make the hop DEGRADED and the cell behind it LIMITED"))
                .thenExecute(() -> {
                    MicrowaveLink.Budget degraded = hop(helper, network, site.d1());
                    double leavesDb = 6 * RfDataLoader.materials().attenuationDb(Blocks.OAK_LEAVES.defaultBlockState())
                            * RfDataLoader.microwave().penetrationFactor();
                    helper.assertTrue(degraded.state() == MicrowaveLink.LinkState.DEGRADED
                                    && Math.abs(degraded.obstructionDb() - leavesDb) < 1e-9 && degraded.fresnelClear(),
                            "§3C.2: about six leaves (" + leavesDb + " dB) is DEGRADED: " + degraded);
                    helper.assertTrue(BackhaulNetwork.serviceCapAt(level, cellId) == ServiceLevel.FAIR, "capped at FAIR");
                    helper.assertTrue(SiteRegistry.of(level).near(site.sector().getX() + 0.5, Y + 0.5, site.sector().getZ() + 0.5, 3.0)
                                    .stream().anyMatch(cell -> cell.cellId() == cellId),
                            "a LIMITED cell stays on the air");
                })
                .thenWaitUntil(() -> assertClosed(helper, player, menu[0], "backhaul_limited"))
                .thenExecute(() -> {
                    TerminalLink capped = link(helper, player);
                    SignalSample sample = feeder.last();
                    helper.assertTrue(capped.verdict() == Verdict.LOW_QUALITY && capped.serviceCap() == ServiceLevel.FAIR
                                    && capped.radio().atLeast(ServiceLevel.GOOD), "only the cap fails it: " + capped);
                    helper.assertTrue(sample.serviceLevel().atLeast(ServiceLevel.GOOD) && sample.servingCellId() == cellId,
                            "the sample itself is untouched: " + sample.serviceLevel());
                    use(player, terminal);
                    assertRefused(helper, player, "backhaul_limited", "FAIR", "GOOD");
                    // The Radio Link on the same cell (POOR, tier 1) keeps working: off and on again.
                    helper.assertTrue(rx[0].served(), "the radio link is served under the cap (its radio "
                            + rx[0].serviceLevel() + ")");
                    helper.assertTrue(RadioLinkBlockEntity.REQUIREMENT.check(sample, RfDataLoader.bands(), ServiceLevel.FAIR) == Verdict.OK,
                            "POOR is under the FAIR cap");
                    level.setBlock(site.tx().below(), Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
                })
                .thenWaitUntil(() -> helper.assertFalse(radioOn(level, site.rx()), "the receiver follows the transmitter off"))
                .thenExecute(() -> level.setBlock(site.tx().below(), Blocks.REDSTONE_BLOCK.defaultBlockState(), Block.UPDATE_ALL))
                .thenWaitUntil(() -> helper.assertTrue(radioOn(level, site.rx()), "and on again, under the cap"))
                .thenExecute(() -> {
                    helper.assertTrue(network.stateOf(cellId) == BackhaulState.LIMITED, "still LIMITED");
                    // Cut the leaves out of the line, with their events.
                    for (BlockPos leaf : site.leaves()) {
                        NeoForge.EVENT_BUS.post(new BlockEvent.BreakEvent(level, leaf, level.getBlockState(leaf), placer));
                        level.setBlock(leaf, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
                    }
                })
                .thenWaitUntil(() -> helper.assertTrue(network.stateOf(cellId) == BackhaulState.FULL
                        && link(helper, player).verdict() == Verdict.OK, "the hop clear, FULL again and the terminal OK"))
                .thenExecute(() -> {
                    menu[0] = open(helper, player, terminal, 3, MenuType.GENERIC_9x3);
                    helper.assertTrue(hop(helper, network, site.d1()).state() == MicrowaveLink.LinkState.UP, "UP again");
                })
                .thenSucceed();
    }

    private static MicrowaveLink.Budget hop(GameTestHelper helper, BackhaulNetwork network, BlockPos dish) {
        BackhaulNetwork.HopStatus hop = network.hopOf(dish);
        if (hop == null) {
            helper.fail("no measured hop at the dish " + dish);
        }
        return hop.budget();
    }

    private static boolean radioOn(ServerLevel level, BlockPos receiver) {
        BlockState state = level.getBlockState(receiver);
        return state.getBlock() instanceof RadioLinkReceiverBlock && state.getValue(RadioLinkReceiverBlock.POWERED);
    }

    // ---- the player and the ticker's stand-in -------------------------------------------------------

    /**
     * Stands in for the server where the {@link SilentServerPlayer} is concerned: every tick it ticks the
     * player ({@code ServerPlayer.tick}, which checks an open menu's {@code stillValid} and closes it
     * when it fails), and, while {@link #start started}, once per evaluation interval it evaluates the
     * player's eye position and dispatches the sample to the carried devices
     * ({@link SignalTicker#dispatchToCarried}): what {@code SignalTicker} does for a connected player.
     */
    private static final class Feeder {

        private final ServerLevel level;
        private final SilentServerPlayer player;
        private boolean feeding;
        private long nextDispatch = Long.MIN_VALUE;
        private int dispatches;
        private @Nullable SignalSample last;

        Feeder(ServerLevel level, SilentServerPlayer player) {
            this.level = level;
            this.player = player;
        }

        void tick() {
            if (feeding && level.getGameTime() >= nextDispatch) {
                dispatch();
            }
            player.tick();
        }

        /** Dispatches from the next tick on, then once per interval. */
        void start() {
            feeding = true;
            nextDispatch = Long.MIN_VALUE;
        }

        /** The terminal is no longer dispatched: as if put away. */
        void stop() {
            feeding = false;
        }

        SignalSample dispatch() {
            SignalSample sample = evaluate(level, player.getEyePosition());
            SignalTicker.dispatchToCarried(player, sample, RfDataLoader.bands(), RanCraftConfig.snapshot());
            last = sample;
            dispatches++;
            nextDispatch = level.getGameTime() + interval();
            return sample;
        }

        void moveEyeTo(Vec3 eye) {
            player.moveTo(eye.x, eye.y - player.getEyeHeight(), eye.z);
        }

        int dispatches() {
            return dispatches;
        }

        SignalSample last() {
            return last;
        }
    }

    /** The ticker's evaluation of a point: the site registry's cells in range, no handover state. */
    private static SignalSample evaluate(ServerLevel level, Vec3 eye) {
        RfConfig config = RanCraftConfig.snapshot();
        Collection<CellParams> candidates = SiteRegistry.of(level).near(eye.x, eye.y, eye.z, config.maxEvaluationRangeBlocks());
        return RfEngine.evaluate(new LevelWorldProbe(level, RfDataLoader.materials()), eye.x, eye.y, eye.z, candidates,
                RfDataLoader.bands(), config, level.getGameTime(), ReceiverState.NONE).sample();
    }

    private static SilentServerPlayer player(ServerLevel level, String name) {
        SilentServerPlayer player = new SilentServerPlayer(level, name);
        CLEANUPS.add(() -> {
            if (player.containerMenu != player.inventoryMenu) {
                player.doCloseContainer();
            }
            DeviceMemory.forget(player.getUUID());
        });
        return player;
    }

    private static int interval() {
        return Math.max(1, RanCraftConfig.evaluationIntervalTicks());
    }

    // ---- actions and assertions -------------------------------------------------------------------

    /** Points the sector east, level, on {@code bandId} (as the config screen would, validated here). */
    private static void configure(GameTestHelper helper, ServerLevel level, BlockPos pos, String bandId) {
        if (!(level.getBlockEntity(pos) instanceof AntennaBlockEntity sector)) {
            helper.fail("no sector antenna at " + pos);
            return;
        }
        sector.applyConfiguration(bandId, sector.txPowerDbm(), 90.0, 0.0, sector.hBeamwidthDeg(),
                sector.vBeamwidthDeg(), sector.pci());
        helper.assertTrue(sector.bandId().equals(bandId) && sector.onAir(), "the sector is on the air on " + bandId);
    }

    /** {@code ItemStack.useOn} on a block, as a player's use reaches it (NeoForge's item-use hook runs). */
    private static InteractionResult useOn(Player player, ItemStack stack, BlockPos pos) {
        BlockHitResult hit = new BlockHitResult(Vec3.atCenterOf(pos), Direction.UP, pos, false);
        return stack.useOn(new UseOnContext(player, InteractionHand.MAIN_HAND, hit));
    }

    /** Sneak + use: bound to {@code pos} in this dimension, and told so. */
    private static void bind(GameTestHelper helper, SilentServerPlayer player, ItemStack terminal, ServerLevel level, BlockPos pos) {
        player.setShiftKeyDown(true);
        InteractionResult result = useOn(player, terminal, pos);
        player.setShiftKeyDown(false);
        GlobalPos target = StorageTerminal.targetOf(terminal);
        helper.assertTrue(result.consumesAction() && target != null && target.pos().equals(pos)
                && target.dimension().equals(level.dimension()), "bound to " + pos + ": " + result + ", " + target);
        assertLastKey(helper, player, BOUND);
    }

    /** A plain use in the air ({@code ItemStack.use}), with the terminal in the main hand. */
    private static void use(SilentServerPlayer player, ItemStack terminal) {
        player.messages.clear();
        terminal.use(player.level(), player, InteractionHand.MAIN_HAND);
    }

    /** A use that opens a session of {@code rows} rows. */
    private static RemoteContainerMenu open(GameTestHelper helper, SilentServerPlayer player, ItemStack terminal,
                                           int rows, MenuType<?> type) {
        use(player, terminal);
        if (!(player.containerMenu instanceof RemoteContainerMenu menu)) {
            helper.fail("no session opened: " + player.containerMenu + ", told " + texts(player));
            return null;
        }
        helper.assertTrue(menu.getRowCount() == rows && menu.getType() == type && menu.lost() == null,
                "a " + rows + "-row session on vanilla's chest menu type");
        helper.assertTrue(player.last(REFUSED) == null, "no refusal: " + texts(player));
        return menu;
    }

    private static void assertOpen(GameTestHelper helper, SilentServerPlayer player, RemoteContainerMenu menu, String why) {
        helper.assertTrue(player.containerMenu == menu && menu.lost() == null, "the session holds: " + why + ", lost "
                + menu.lost());
    }

    /** The session closed (vanilla's {@code ServerPlayer.tick} closed it) and the player was told why. */
    private static void assertClosed(GameTestHelper helper, SilentServerPlayer player, RemoteContainerMenu menu, String problem) {
        helper.assertTrue(player.containerMenu == player.inventoryMenu, "the session is still open");
        helper.assertTrue(menu.lost() != null && StorageTerminal.problemKey(problem).equals(keyOf(menu.lost())),
                "closed for " + problem + ", got " + (menu.lost() == null ? null : menu.lost().getString()));
        Component told = player.last(LOST);
        helper.assertTrue(told != null && StorageTerminal.problemKey(problem).equals(SilentServerPlayer.innerKey(told)),
                "the player is told \"connection lost (" + problem + ")\": " + texts(player));
    }

    /** The last use was refused for {@code problem}, with these arguments if any, and opened nothing. */
    private static void assertRefused(GameTestHelper helper, SilentServerPlayer player, String problem, Object... args) {
        Component told = player.last(REFUSED);
        helper.assertTrue(told != null && StorageTerminal.problemKey(problem).equals(SilentServerPlayer.innerKey(told)),
                "refused for " + problem + ": " + texts(player));
        helper.assertTrue(player.containerMenu == player.inventoryMenu, "a refused use opens nothing");
        if (args.length > 0) {
            TranslatableContents inner = (TranslatableContents) ((Component) ((TranslatableContents) told.getContents())
                    .getArgs()[0]).getContents();
            helper.assertTrue(List.of(args).equals(List.of(inner.getArgs())),
                    "with " + List.of(args) + ", got " + List.of(inner.getArgs()));
        }
    }

    private static void assertLastKey(GameTestHelper helper, SilentServerPlayer player, String key) {
        Component last = player.messages.isEmpty() ? null : player.messages.get(player.messages.size() - 1);
        helper.assertTrue(key.equals(keyOf(last)), "told " + key + ", got " + texts(player));
    }

    private static @Nullable String keyOf(@Nullable Component message) {
        return message != null && message.getContents() instanceof TranslatableContents contents ? contents.getKey() : null;
    }

    private static List<String> texts(SilentServerPlayer player) {
        List<String> texts = new ArrayList<>();
        for (Component message : player.messages) {
            texts.add(keyOf(message) + " \"" + message.getString() + "\"");
        }
        return texts;
    }

    private static TerminalLink link(GameTestHelper helper, SilentServerPlayer player) {
        TerminalLink link = StorageTerminal.linkOf(player.getUUID());
        if (link == null) {
            helper.fail("no verdict kept for the player yet");
        }
        return link;
    }

    private static ChestBlockEntity chestAt(GameTestHelper helper, ServerLevel level, BlockPos pos) {
        if (!(level.getBlockEntity(pos) instanceof ChestBlockEntity chest)) {
            helper.fail("no chest at " + pos);
            return null;
        }
        return chest;
    }

    private static int countIn(Container container, Item item) {
        int count = 0;
        for (int slot = 0; slot < container.getContainerSize(); slot++) {
            if (container.getItem(slot).is(item)) {
                count += container.getItem(slot).getCount();
            }
        }
        return count;
    }

    /** Empties any container first (so nothing drops), then back to air. */
    private static void clearAndRemove(ServerLevel level, List<BlockPos> positions) {
        for (BlockPos pos : positions) {
            BlockEntity entity = level.getBlockEntity(pos);
            if (entity instanceof Container container) {
                container.clearContent();
            }
        }
        BackhaulGameTests.removeAll(level, positions);
    }
}
