package dev.rancraft.gametest;

import dev.rancraft.RanCraft;
import dev.rancraft.RanCraftConfig;
import dev.rancraft.block.AntennaBlockEntity;
import dev.rancraft.block.SectorAntennaBlockEntity;
import dev.rancraft.block.SignalMastBlockEntity;
import dev.rancraft.block.SiteGeneratorBlock;
import dev.rancraft.block.SiteGeneratorBlockEntity;
import dev.rancraft.registry.ModBlocks;
import dev.rancraft.rf.CellParams;
import dev.rancraft.rf.PowerModel;
import dev.rancraft.world.SitePower;
import dev.rancraft.world.SiteRegistry;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Consumer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.AfterBatch;
import net.minecraft.gametest.framework.BeforeBatch;
import net.minecraft.gametest.framework.GameTestGenerator;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.TestFunction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.HopperBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.energy.IEnergyStorage;
import net.neoforged.neoforge.gametest.GameTestHolder;

/**
 * Power at runtime (Phase 3 slice 15, §3C.5): the Site Generator, the antennas' FE capability, the
 * dimension's {@link SitePower} draw and the restart latch on a live server level. {@code PowerModelTest}
 * and {@code EnergyBufferTest} pin the model and the latch headless; these check the wiring.
 *
 * <ul>
 *   <li><b>Fuel</b> (the 3C done-when): a 30 dBm sector burns fuel about seven times faster than a 20
 *       dBm one, each fed by real generators burning real sticks.</li>
 *   <li><b>Out</b>: a cell that runs out goes off the air (unregistered, {@code OnAir} false in the
 *       update tag) and comes back only above 10 %, fed through the real capability lookup; its save
 *       carries the buffer, its update tag does not.</li>
 *   <li><b>Weak supply</b>: one generator (40 FE/t) on a 30 dBm sector (70.7 FE/t) cycles slowly
 *       through the 10 % band, never every tick.</li>
 *   <li><b>Column</b>: a generator beside a column's top mast feeds the base; a mast placed under the
 *       base takes the energy with it; a sector put on top (a mounting pole) is fed by the same
 *       generator.</li>
 *   <li><b>Slot</b>: a hopper fills the generator with fuel and not with dirt; a lava bucket burns and
 *       its bucket comes out to a hopper below; use with fuel and sneak + use, as a player.</li>
 *   <li><b>Cost</b>: the draw over 200 cells on the air, and an idle generator's tick.</li>
 *   <li><b>Off</b> ({@code requirePower} off, the default, in a batch of its own): antennas with empty
 *       buffers are on the air, take no energy, a generator next to them burns nothing and the site
 *       registry never moves; the served-receivers seam counts a Radio Link receiver.</li>
 * </ul>
 *
 * <p>Built far from every other test, in the overworld, in forced chunks at y {@value #Y} (open air in
 * the flat test world). {@code requirePower} is global, so the tests that need it on share one batch,
 * whose {@link BeforeBatch} turns it on and whose {@link AfterBatch} restores it; each batch's
 * {@link AfterBatch} also removes the blocks (emptying containers first, so nothing drops) and releases
 * the chunks, also when a test failed.
 */
@GameTestHolder(RanCraft.MOD_ID)
public final class PowerGameTests {

    private static final String BATCH_ON = "rancraft_power_on";
    /** The cost test drives the dimension's draw directly, which would drain the other tests' cells. */
    private static final String BATCH_COST = "rancraft_power_cost";
    private static final String BATCH_OFF = "rancraft_power_off";
    /** Block entities run {@code onLoad} on the next tick. */
    private static final int SETTLE = 3;

    /** Open air in the flat test world. */
    private static final int Y = 100;
    private static final int X = 20_480;
    private static final int FUEL_Z = 20_480;
    private static final int OUT_Z = 20_736;
    private static final int WEAK_Z = 20_992;
    private static final int COLUMN_Z = 21_248;
    private static final int SLOT_Z = 21_504;
    private static final int COST_X = 20_736;
    private static final int COST_Z = 20_480;
    private static final int OFF_X = 20_736;
    private static final int OFF_Z = 20_992;

    /** The measured window of the fuel test, in ticks. */
    private static final int FUEL_WINDOW = 1200;
    private static final int WEAK_WINDOW = 600;

    /** Undone by each batch's {@link AfterBatch} method, pass or fail. Server thread. */
    private static final List<Runnable> CLEANUPS = new ArrayList<>();
    private static Boolean previousRequire;

    private PowerGameTests() {
    }

    @GameTestGenerator
    public static Collection<TestFunction> powerTests() {
        List<TestFunction> tests = new ArrayList<>();
        tests.add(test(BATCH_ON, "a_30_dbm_sector_burns_fuel_about_seven_times_faster", FUEL_WINDOW + 400,
                PowerGameTests::fuel));
        tests.add(test(BATCH_ON, "out_of_energy_off_the_air_back_above_ten_percent", 400, PowerGameTests::out));
        tests.add(test(BATCH_ON, "a_weak_supply_cycles_slowly_never_every_tick", WEAK_WINDOW + 300,
                PowerGameTests::weakSupply));
        tests.add(test(BATCH_ON, "a_generator_by_any_mast_feeds_the_column", 600, PowerGameTests::column));
        tests.add(test(BATCH_ON, "the_fuel_slot_hoppers_buckets_and_hands", 400, PowerGameTests::slot));
        tests.add(test(BATCH_COST, "the_draw_over_two_hundred_cells", 400, PowerGameTests::cost));
        tests.add(test(BATCH_OFF, "require_power_off_changes_nothing", 400, PowerGameTests::off));
        return tests;
    }

    private static TestFunction test(String batch, String name, int timeout, Consumer<GameTestHelper> body) {
        String prefix = PowerGameTests.class.getSimpleName().toLowerCase(Locale.ROOT) + ".";
        return new TestFunction(batch, prefix + name, HarvestGameTests.EMPTY_TEMPLATE, timeout, 0L, true, body);
    }

    @BeforeBatch(batch = BATCH_ON)
    public static void beforeOn(ServerLevel level) {
        requirePowerOn();
    }

    @AfterBatch(batch = BATCH_ON)
    public static void afterOn(ServerLevel level) {
        restoreRequirePower();
        runCleanups();
    }

    @BeforeBatch(batch = BATCH_COST)
    public static void beforeCost(ServerLevel level) {
        requirePowerOn();
    }

    @AfterBatch(batch = BATCH_COST)
    public static void afterCost(ServerLevel level) {
        restoreRequirePower();
        runCleanups();
    }

    private static void requirePowerOn() {
        previousRequire = RanCraftConfig.REQUIRE_POWER.get();
        RanCraftConfig.REQUIRE_POWER.set(true);
    }

    private static void restoreRequirePower() {
        RanCraftConfig.REQUIRE_POWER.set(previousRequire == null ? Boolean.FALSE : previousRequire);
        previousRequire = null;
    }

    @AfterBatch(batch = BATCH_OFF)
    public static void afterOff(ServerLevel level) {
        runCleanups();
    }

    private static void runCleanups() {
        for (int i = CLEANUPS.size() - 1; i >= 0; i--) {
            try {
                CLEANUPS.get(i).run();
            } catch (RuntimeException failure) {
                RanCraft.LOGGER.error("RANCraft power game test cleanup failed", failure);
            }
        }
        CLEANUPS.clear();
    }

    // ---- 1. the fuel bill (3C done-when) ----------------------------------------------------------

    /**
     * Sector A at 20 dBm with one generator under it; sector B at 30 dBm with two (one generator makes
     * 40 FE/t, under B's 70.7). Both start with full buffers, so the window measures the steady state:
     * every FE drawn is made again by burning fuel. Over {@value #FUEL_WINDOW} ticks the burn ticks
     * must be the model's ({@code fePerTick x ticks / 40}, to within a few ticks of what the buffers and
     * the generators' one-tick store hold), their ratio 6.625 ("about 7x"), and both sectors must stay
     * on the air throughout. Real sticks (100 burn ticks each) are counted too.
     */
    private static void fuel(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos a = new BlockPos(X, Y, FUEL_Z);
        BlockPos b = new BlockPos(X + 24, Y, FUEL_Z);
        BlockPos ga = a.below();
        BlockPos gb1 = b.below();
        BlockPos gb2 = b.east();
        force(level, a, b, gb2);
        SectorAntennaBlockEntity sectorA = sector(helper, level, a, 20.0);
        SectorAntennaBlockEntity sectorB = sector(helper, level, b, 30.0);
        SiteGeneratorBlockEntity genA = generator(helper, level, ga, new ItemStack(Items.STICK, 64));
        SiteGeneratorBlockEntity genB1 = generator(helper, level, gb1, new ItemStack(Items.STICK, 64));
        SiteGeneratorBlockEntity genB2 = generator(helper, level, gb2, new ItemStack(Items.STICK, 64));
        long[] start = new long[6];
        boolean[] stayedOn = {true, true};

        helper.startSequence()
                .thenIdle(SETTLE)
                .thenExecute(() -> {
                    fill(sectorA, RanCraftConfig.powerBufferFe(), true);
                    fill(sectorB, RanCraftConfig.powerBufferFe(), true);
                    helper.assertTrue(sectorA.onAir() && sectorB.onAir(), "both sectors are on the air when full");
                })
                // A few ticks to reach the steady state (each generator lights its first stick).
                .thenIdle(20)
                .thenExecute(() -> {
                    start[0] = genA.burnedTicks();
                    start[1] = genB1.burnedTicks() + genB2.burnedTicks();
                    start[2] = genA.itemsLit();
                    start[3] = genB1.itemsLit() + genB2.itemsLit();
                    start[4] = sectorA.energyBuffer().stored();
                    start[5] = sectorB.energyBuffer().stored();
                })
                .thenExecuteFor(FUEL_WINDOW, () -> {
                    stayedOn[0] &= sectorA.onAir();
                    stayedOn[1] &= sectorB.onAir();
                })
                .thenExecute(() -> {
                    PowerModel model = RanCraftConfig.powerModel();
                    double rateA = sectorA.fePerTick(model);
                    double rateB = sectorB.fePerTick(model);
                    int output = RanCraftConfig.siteGeneratorFePerTick();
                    long burnedA = genA.burnedTicks() - start[0];
                    long burnedB = genB1.burnedTicks() + genB2.burnedTicks() - start[1];
                    long sticksA = genA.itemsLit() - start[2];
                    long sticksB = genB1.itemsLit() + genB2.itemsLit() - start[3];
                    double expectedA = rateA * FUEL_WINDOW / output;
                    double expectedB = rateB * FUEL_WINDOW / output;
                    double ratio = (double) burnedB / burnedA;
                    RanCraft.LOGGER.info(String.format(Locale.ROOT,
                            "RANCraft power: 20 dBm sector %.3f FE/t burned %d ticks (model %.1f), %d stick(s); 30 dBm"
                                    + " sector %.3f FE/t burned %d ticks (model %.1f), %d stick(s); ratio %.3f (model %.3f)"
                                    + " over %d ticks; buffers %d -> %d and %d -> %d FE",
                            rateA, burnedA, expectedA, sticksA, rateB, burnedB, expectedB, sticksB, ratio,
                            rateB / rateA, FUEL_WINDOW, start[4], sectorA.energyBuffer().stored(), start[5],
                            sectorB.energyBuffer().stored()));
                    helper.assertTrue(stayedOn[0] && stayedOn[1], "both sectors stayed on the air through the window");
                    // Slack: what the buffers and the generators' one-tick stores held at either end.
                    helper.assertTrue(Math.abs(burnedA - expectedA) <= 4.0,
                            "the 20 dBm sector's generator burned " + burnedA + " ticks, the model says " + expectedA);
                    helper.assertTrue(Math.abs(burnedB - expectedB) <= 6.0,
                            "the 30 dBm sector's generators burned " + burnedB + " ticks, the model says " + expectedB);
                    helper.assertTrue(ratio > 6.3 && ratio < 6.95,
                            "the 30 dBm sector burns about 7x the fuel (6.625x by the model): " + ratio);
                    helper.assertTrue(sticksB > 5 * sticksA, "sticks burned: " + sticksB + " against " + sticksA);
                    helper.assertTrue(level.getBlockState(ga).getValue(SiteGeneratorBlock.LIT),
                            "a generator that burns is lit");
                })
                .thenSucceed();
    }

    // ---- 2. out of energy, back above 10 % --------------------------------------------------------

    /**
     * A 30 dBm sector with 700 FE and no generator runs out within about ten ticks: off the air,
     * unregistered, {@code OnAir} false in the update tag, empty, latch off. Fed 900 FE (9 %) through
     * the real capability lookup it stays off (no draw while off); 101 FE more (10.01 %) puts it back on
     * at the next tick, and it runs out again. The save carries {@code Energy} and {@code PowerOn} at
     * {@code DataVersion} 4 and loads them back; the update tag carries neither; a v3 save loads empty.
     */
    private static void out(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos pos = new BlockPos(X, Y, OUT_Z);
        force(level, pos);
        SectorAntennaBlockEntity sector = sector(helper, level, pos, 30.0);
        SitePower power = SitePower.of(level);
        long[] outages = new long[1];
        int[] switches = new int[1];
        boolean[] last = new boolean[1];

        helper.startSequence()
                .thenIdle(SETTLE)
                .thenExecute(() -> {
                    helper.assertFalse(sector.onAir(), "a new sector with an empty buffer is off the air");
                    helper.assertTrue(power.tracks(sector.cellId()), "the sector is tracked by the dimension's power");
                    fill(sector, 700, true);
                    helper.assertTrue(sector.onAir() && registered(level, pos), "on the air with 700 FE");
                    outages[0] = power.outages();
                })
                .thenWaitUntil(() -> helper.assertFalse(sector.onAir(), "the sector runs out"))
                .thenExecute(() -> {
                    helper.assertTrue(sector.energyBuffer().stored() == 0 && !sector.energyBuffer().on(),
                            "empty, latch off");
                    helper.assertFalse(registered(level, pos), "out of energy: unregistered");
                    helper.assertFalse(sector.getUpdateTag(level.registryAccess()).getBoolean(AntennaBlockEntity.ON_AIR_TAG),
                            "the update tag says OnAir false (the lens greys it)");
                    helper.assertTrue(power.outages() > outages[0], "the outage was counted");

                    IEnergyStorage storage = level.getCapability(Capabilities.EnergyStorage.BLOCK, pos, Direction.NORTH);
                    helper.assertTrue(storage != null && storage.canReceive() && !storage.canExtract(),
                            "the sector exposes a receive-only FE capability");
                    helper.assertTrue(storage.getMaxEnergyStored() == 10_000, "a 10,000 FE buffer");
                    helper.assertTrue(storage.extractEnergy(100, false) == 0, "nothing can be extracted");
                    helper.assertTrue(storage.receiveEnergy(900, true) == 900 && sector.energyBuffer().stored() == 0,
                            "simulating takes nothing");
                    helper.assertTrue(storage.receiveEnergy(900, false) == 900, "900 FE taken");
                    last[0] = sector.onAir();
                })
                .thenExecuteFor(40, () -> {
                    if (sector.onAir() != last[0]) {
                        switches[0]++;
                        last[0] = sector.onAir();
                    }
                })
                .thenExecute(() -> {
                    helper.assertTrue(switches[0] == 0 && !sector.onAir(), "9 % is not enough: still off after 40 ticks");
                    helper.assertTrue(sector.energyBuffer().stored() == 900, "no draw while off");
                    IEnergyStorage storage = level.getCapability(Capabilities.EnergyStorage.BLOCK, pos, Direction.UP);
                    helper.assertTrue(storage.receiveEnergy(101, false) == 101 && storage.getEnergyStored() == 1001,
                            "1001 FE");
                })
                .thenIdle(2)
                .thenExecute(() -> {
                    helper.assertTrue(sector.onAir() && registered(level, pos), "above 10 %: back on the air");
                    helper.assertTrue(sector.getUpdateTag(level.registryAccess()).getBoolean(AntennaBlockEntity.ON_AIR_TAG),
                            "the update tag says OnAir true");
                    // The save: v4, with the buffer; the update tag without it.
                    CompoundTag saved = sector.saveWithoutMetadata(level.registryAccess());
                    helper.assertTrue(saved.getInt("DataVersion") == 4 && saved.contains(AntennaBlockEntity.ENERGY_TAG)
                                    && saved.getBoolean(AntennaBlockEntity.POWER_ON_TAG),
                            "the save is v4 with Energy and PowerOn: " + saved);
                    CompoundTag update = sector.getUpdateTag(level.registryAccess());
                    helper.assertFalse(update.contains(AntennaBlockEntity.ENERGY_TAG)
                            || update.contains(AntennaBlockEntity.POWER_ON_TAG), "the update tag carries no buffer");
                    BlockState state = level.getBlockState(pos);
                    CompoundTag full = sector.saveWithFullMetadata(level.registryAccess());
                    BlockEntity copy = BlockEntity.loadStatic(pos, state, full, level.registryAccess());
                    helper.assertTrue(copy instanceof AntennaBlockEntity loaded
                                    && loaded.energyBuffer().stored() == sector.energyBuffer().stored()
                                    && loaded.energyBuffer().on(),
                            "the buffer and the latch load back");
                    full.putInt("DataVersion", 3);
                    full.remove(AntennaBlockEntity.ENERGY_TAG);
                    full.remove(AntennaBlockEntity.POWER_ON_TAG);
                    BlockEntity v3 = BlockEntity.loadStatic(pos, state, full, level.registryAccess());
                    helper.assertTrue(v3 instanceof AntennaBlockEntity old
                                    && old.energyBuffer().stored() == 0 && !old.energyBuffer().on(),
                            "a v3 save loads with an empty buffer, latch off");
                })
                .thenWaitUntil(() -> helper.assertFalse(sector.onAir(), "1001 FE runs out again"))
                .thenSucceed();
    }

    // ---- 3. a weak supply cycles slowly -----------------------------------------------------------

    /**
     * One generator (40 FE/t) beside a 30 dBm sector (70.7 FE/t), the sector empty at the start. Over
     * {@value #WEAK_WINDOW} ticks the sector charges past 1000 FE (about 26 ticks off), drains (about 34
     * ticks on) and so on: every phase lasts at least 10 ticks, and there are several cycles. That is
     * the 10 % band doing its job; without it the cell would flap every tick.
     */
    private static void weakSupply(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos pos = new BlockPos(X, Y, WEAK_Z);
        BlockPos gen = pos.below();
        force(level, pos);
        SectorAntennaBlockEntity sector = sector(helper, level, pos, 30.0);
        generator(helper, level, gen, new ItemStack(Items.COAL, 16));
        List<Integer> phases = new ArrayList<>();
        int[] phase = new int[1];
        boolean[] last = new boolean[1];

        helper.startSequence()
                .thenIdle(SETTLE)
                .thenExecute(() -> last[0] = sector.onAir())
                .thenExecuteFor(WEAK_WINDOW, () -> {
                    phase[0]++;
                    if (sector.onAir() != last[0]) {
                        phases.add(phase[0]);
                        phase[0] = 0;
                        last[0] = sector.onAir();
                    }
                })
                .thenExecute(() -> {
                    RanCraft.LOGGER.info("RANCraft power: a 30 dBm sector on one generator switched {} times in {} ticks;"
                            + " phases (ticks) {}", phases.size(), WEAK_WINDOW, phases);
                    helper.assertTrue(phases.size() >= 6, "several cycles: " + phases);
                    // The first phase starts mid-way (the sector was already charging); the rest are whole.
                    for (int i = 1; i < phases.size(); i++) {
                        helper.assertTrue(phases.get(i) >= 10, "phase " + i + " lasted " + phases.get(i) + " ticks: " + phases);
                    }
                })
                .thenSucceed();
    }

    // ---- 4. the column ----------------------------------------------------------------------------

    /**
     * A three-mast column with a generator beside its top mast: the base fills (the other masts hold
     * nothing, and the top mast's capability reads the base's buffer) and goes on the air above 10 %.
     * A mast placed under the base becomes the new base and takes the energy and the latch with it: on
     * the air at once, the old base empty. A sector put on the column's top makes it a mounting pole:
     * the column goes quiet and the same generator now fills the sector.
     */
    private static void column(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos base = new BlockPos(X, Y, COLUMN_Z);
        BlockPos middle = base.above();
        BlockPos top = middle.above();
        BlockPos under = base.below();
        BlockPos onTop = top.above();
        BlockPos gen = top.east();
        force(level, base, gen);
        place(level, base, ModBlocks.SIGNAL_MAST.get());
        place(level, middle, ModBlocks.SIGNAL_MAST.get());
        place(level, top, ModBlocks.SIGNAL_MAST.get());
        CLEANUPS.add(() -> level.setBlock(under, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL));
        CLEANUPS.add(() -> level.setBlock(onTop, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL));
        long[] stored = new long[1];

        helper.startSequence()
                .thenIdle(SETTLE)
                .thenExecute(() -> {
                    helper.assertFalse(mast(helper, level, base).onAir(), "the column starts off the air (empty)");
                    generator(helper, level, gen, new ItemStack(Items.COAL, 8));
                })
                .thenWaitUntil(() -> helper.assertTrue(mast(helper, level, base).onAir(),
                        "the column's base fills from the generator by its top mast and goes on the air"))
                .thenExecute(() -> {
                    SignalMastBlockEntity owner = mast(helper, level, base);
                    helper.assertTrue(owner.energyBuffer().stored() > 1000, "the base holds over 10 %");
                    helper.assertTrue(mast(helper, level, middle).energyBuffer().stored() == 0
                            && mast(helper, level, top).energyBuffer().stored() == 0, "structure masts hold nothing");
                    IEnergyStorage viaTop = level.getCapability(Capabilities.EnergyStorage.BLOCK, top, Direction.EAST);
                    helper.assertTrue(viaTop != null && viaTop.getEnergyStored() == owner.energyBuffer().stored(),
                            "the top mast's capability reads the base's buffer");
                    // Extend the tower downward.
                    stored[0] = owner.energyBuffer().stored();
                    level.setBlock(under, ModBlocks.SIGNAL_MAST.get().defaultBlockState(), Block.UPDATE_ALL);
                    SignalMastBlockEntity newBase = mast(helper, level, under);
                    helper.assertTrue(owner.energyBuffer().stored() == 0 && !owner.energyBuffer().on(),
                            "the old base handed its energy down");
                    helper.assertTrue(newBase.energyBuffer().stored() >= stored[0] - 100 && newBase.energyBuffer().on(),
                            "the new base holds it, latch on: " + newBase.energyBuffer().stored() + " of " + stored[0]);
                })
                .thenIdle(SETTLE)
                .thenExecute(() -> {
                    helper.assertTrue(mast(helper, level, under).onAir() && registered(level, under),
                            "the new base is on the air");
                    helper.assertFalse(registered(level, base), "the old base's cell is gone");
                    // A sector on top: a mounting pole.
                    level.setBlock(onTop, ModBlocks.SECTOR_ANTENNA.get().defaultBlockState(), Block.UPDATE_ALL);
                })
                .thenIdle(SETTLE)
                .thenExecute(() -> helper.assertFalse(mast(helper, level, under).onAir(),
                        "a sector on top: the column goes quiet"))
                .thenWaitUntil(() -> helper.assertTrue(
                        level.getBlockEntity(onTop) instanceof SectorAntennaBlockEntity sector && sector.onAir(),
                        "the pole's generator fills the sector on top, which goes on the air"))
                .thenSucceed();
    }

    // ---- 5. the fuel slot -------------------------------------------------------------------------

    /**
     * A hopper above a generator holding coal and dirt: the coal goes in, the dirt stays (fuel only). A
     * lava bucket lit in a second generator leaves its bucket, which a hopper below takes out. As a
     * player: use with coal fills the slot (taken from a survival hand), sneak + use with an empty hand
     * gives it back.
     */
    private static void slot(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        // A generator with nothing to feed (so it keeps what it is given) and a hopper on top of it.
        BlockPos pouredGen = new BlockPos(X, Y, SLOT_Z);
        BlockPos hopperAbove = pouredGen.above();
        // A generator under a sector (so it lights its fuel) and a hopper under it.
        BlockPos lavaSector = new BlockPos(X + 8, Y, SLOT_Z);
        BlockPos lavaGen = lavaSector.below();
        BlockPos hopperBelow = lavaGen.below();
        // A generator used by hand.
        BlockPos handGen = new BlockPos(X + 16, Y, SLOT_Z);
        force(level, pouredGen, lavaSector, handGen);
        sector(helper, level, lavaSector, 20.0);
        SiteGeneratorBlockEntity poured = generator(helper, level, pouredGen, ItemStack.EMPTY);
        place(level, hopperAbove, Blocks.HOPPER);
        HopperBlockEntity hopper = (HopperBlockEntity) level.getBlockEntity(hopperAbove);
        hopper.setItem(0, new ItemStack(Items.DIRT, 2));
        hopper.setItem(1, new ItemStack(Items.COAL, 3));
        SiteGeneratorBlockEntity lava = generator(helper, level, lavaGen, new ItemStack(Items.LAVA_BUCKET));
        place(level, hopperBelow, Blocks.HOPPER);
        HopperBlockEntity below = (HopperBlockEntity) level.getBlockEntity(hopperBelow);

        helper.startSequence()
                .thenWaitUntil(() -> helper.assertTrue(poured.fuel().is(Items.COAL) && poured.fuel().getCount() == 3,
                        "the hopper poured its 3 coal into the generator: " + poured.fuel()))
                .thenIdle(40)
                .thenExecute(() -> {
                    helper.assertTrue(countIn(hopper, Items.DIRT) == 2, "the dirt stays in the hopper (fuel only)");
                    helper.assertFalse(poured.canPlaceItem(0, new ItemStack(Items.DIRT)), "the slot refuses dirt");
                })
                .thenWaitUntil(() -> helper.assertTrue(countIn(below, Items.BUCKET) == 1,
                        "the lava bucket burned and its bucket came out to the hopper below"))
                .thenExecute(() -> {
                    helper.assertTrue(lava.itemsLit() == 1 && lava.burnTotal() == 20_000,
                            "one lava bucket lit, 20000 burn ticks (NeoForge's lookup)");
                    helper.assertTrue(lava.fuel().isEmpty(), "the slot is empty again");
                    // As a player, in survival.
                    SiteGeneratorBlockEntity byHand = generator(helper, level, handGen, ItemStack.EMPTY);
                    Player player = helper.makeMockPlayer(GameType.SURVIVAL);
                    ItemStack coal = new ItemStack(Items.COAL, 10);
                    player.setItemInHand(InteractionHand.MAIN_HAND, coal);
                    BlockHitResult hit = new BlockHitResult(Vec3.atCenterOf(handGen), Direction.UP, handGen, false);
                    level.getBlockState(handGen).useItemOn(coal, level, player, InteractionHand.MAIN_HAND, hit);
                    helper.assertTrue(byHand.fuel().is(Items.COAL) && byHand.fuel().getCount() == 10 && coal.isEmpty(),
                            "use with coal moved the stack into the slot: " + byHand.fuel() + ", hand " + coal);
                    ItemStack dirt = new ItemStack(Items.DIRT, 5);
                    level.getBlockState(handGen).useItemOn(dirt, level, player, InteractionHand.MAIN_HAND, hit);
                    helper.assertTrue(dirt.getCount() == 5 && byHand.fuel().getCount() == 10, "dirt is not fuel");
                    player.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
                    player.setShiftKeyDown(true);
                    level.getBlockState(handGen).useWithoutItem(level, player, hit);
                    helper.assertTrue(byHand.fuel().isEmpty() && player.getInventory().countItem(Items.COAL) == 10,
                            "sneak + use gave the coal back");
                })
                .thenSucceed();
    }

    // ---- 6. the cost ------------------------------------------------------------------------------

    /**
     * 200 sectors on the air with full buffers: the dimension's draw, timed on the live server ticks
     * (the median of 100) and in a direct loop; and an idle generator's tick (its neighbour full, so it
     * only asks its six sides). Logged for NOTES.md; the draw must stay well under a millisecond.
     */
    private static void cost(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        List<BlockPos> positions = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            for (int j = 0; j < 10; j++) {
                positions.add(new BlockPos(COST_X + 2 * i, Y, COST_Z + 2 * j));
            }
        }
        force(level, positions.toArray(new BlockPos[0]));
        List<SectorAntennaBlockEntity> sectors = new ArrayList<>();
        for (BlockPos pos : positions) {
            sectors.add(sector(helper, level, pos, 20.0));
        }
        BlockPos idleGen = positions.get(0).below();
        SitePower power = SitePower.of(level);
        List<Long> samples = new ArrayList<>();

        helper.startSequence()
                .thenIdle(SETTLE)
                .thenExecute(() -> {
                    for (SectorAntennaBlockEntity sector : sectors) {
                        fill(sector, RanCraftConfig.powerBufferFe(), true);
                    }
                    generator(helper, level, idleGen, new ItemStack(Items.COAL, 4));
                })
                .thenIdle(2)
                .thenExecuteFor(100, () -> samples.add(power.lastTickNanos()))
                .thenExecute(() -> {
                    long[] sorted = samples.stream().mapToLong(Long::longValue).toArray();
                    Arrays.sort(sorted);
                    double medianUs = sorted[sorted.length / 2] / 1_000.0;
                    int drawn = power.lastDrawn();
                    // A direct loop: the same work the server tick does.
                    final int reps = 200;
                    long t0 = System.nanoTime();
                    for (int i = 0; i < reps; i++) {
                        power.tick(level, level.getGameTime());
                    }
                    long t1 = System.nanoTime();
                    // An idle generator: its sector is full again, so it asks its six sides and burns nothing.
                    fill(sectors.get(0), RanCraftConfig.powerBufferFe(), true);
                    SiteGeneratorBlockEntity generator = (SiteGeneratorBlockEntity) level.getBlockEntity(idleGen);
                    BlockState state = level.getBlockState(idleGen);
                    long burnedBefore = generator.burnedTicks();
                    final int genReps = 10_000;
                    long t2 = System.nanoTime();
                    for (int i = 0; i < genReps; i++) {
                        SiteGeneratorBlockEntity.serverTick(level, idleGen, level.getBlockState(idleGen), generator);
                    }
                    long t3 = System.nanoTime();
                    long burnedInLoop = generator.burnedTicks() - burnedBefore;
                    double loopUs = (t1 - t0) / 1_000.0 / reps;
                    double genUs = (t3 - t2) / 1_000.0 / genReps;
                    RanCraft.LOGGER.info(String.format(Locale.ROOT,
                            "RANCraft power cost: %d cells on the air (%d tracked here), draw %.1f us/tick median of"
                                    + " 100 server ticks, %.1f us/tick in a direct loop (%.3f us per cell); an idle"
                                    + " generator's tick %.3f us",
                            drawn, power.cells().size(), medianUs, loopUs, loopUs / Math.max(1, drawn), genUs));
                    helper.assertTrue(drawn >= 200, "every sector paid its tick: " + drawn);
                    helper.assertTrue(medianUs < 1000.0, "the draw stays well under a millisecond: " + medianUs + " us");
                    helper.assertTrue(state.is(ModBlocks.SITE_GENERATOR.get()) && burnedInLoop == 0,
                            "the generator beside a full buffer burned nothing: " + burnedInLoop);
                    for (SectorAntennaBlockEntity sector : sectors) {
                        helper.assertTrue(sector.onAir(), "every sector is still on the air");
                    }
                })
                .thenSucceed();
    }

    // ---- 7. requirePower off (the default) --------------------------------------------------------

    /**
     * With {@code requirePower} off: a sector and a mast with empty buffers are on the air and
     * registered; their capability takes nothing; a generator with coal beside the sector does not
     * light, burns nothing and keeps its coal; the site registry's version does not move for 100 ticks.
     * A Radio Link receiver by the mast is served by it, and the seam counts it.
     */
    private static void off(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos sectorPos = new BlockPos(OFF_X, Y, OFF_Z);
        BlockPos gen = sectorPos.below();
        BlockPos mastPos = new BlockPos(OFF_X + 32, Y, OFF_Z);
        BlockPos receiverPos = mastPos.east(4);
        force(level, sectorPos, mastPos, receiverPos);
        SectorAntennaBlockEntity sector = sector(helper, level, sectorPos, 30.0);
        place(level, mastPos, ModBlocks.SIGNAL_MAST.get());
        SiteGeneratorBlockEntity generator = generator(helper, level, gen, new ItemStack(Items.COAL, 8));
        place(level, receiverPos, ModBlocks.RADIO_LINK_RECEIVER.get());
        long[] version = new long[1];

        helper.startSequence()
                .thenIdle(SETTLE)
                .thenExecute(() -> {
                    helper.assertFalse(RanCraftConfig.requirePower(), "requirePower is off (the default)");
                    helper.assertTrue(sector.onAir() && registered(level, sectorPos) && sector.isTransmitting(),
                            "an empty sector is on the air");
                    helper.assertTrue(mast(helper, level, mastPos).onAir() && registered(level, mastPos),
                            "an empty mast is on the air");
                    IEnergyStorage storage = level.getCapability(Capabilities.EnergyStorage.BLOCK, sectorPos, Direction.DOWN);
                    helper.assertTrue(storage != null && !storage.canReceive() && storage.receiveEnergy(500, false) == 0,
                            "the antenna takes no energy");
                    version[0] = SiteRegistry.of(level).version();
                })
                .thenIdle(100)
                .thenExecute(() -> {
                    helper.assertTrue(SiteRegistry.of(level).version() == version[0],
                            "the site registry did not move in 100 ticks");
                    helper.assertTrue(generator.burnedTicks() == 0 && generator.itemsLit() == 0
                                    && generator.fuel().getCount() == 8, "the generator burned nothing");
                    helper.assertFalse(level.getBlockState(gen).getValue(SiteGeneratorBlock.LIT), "and is not lit");
                    helper.assertTrue(sector.energyBuffer().stored() == 0, "the sector's buffer is still empty");
                    helper.assertTrue(sector.onAir() && mast(helper, level, mastPos).onAir(), "both still on the air");
                    int served = SitePower.of(level).servedCount(mastPos.asLong(), level.getGameTime());
                    helper.assertTrue(served == 1, "the seam: the mast served the Radio Link receiver (" + served + ")");
                })
                .thenSucceed();
    }

    // ---- helpers ----------------------------------------------------------------------------------

    /** Forces every chunk the positions are in, with their FULL rings, and registers their release. */
    private static void force(ServerLevel level, BlockPos... positions) {
        Set<Long> chunks = new TreeSet<>();
        for (BlockPos pos : positions) {
            chunks.add(BackhaulGameTests.chunkKey(pos));
        }
        List<Long> forced = new ArrayList<>();
        BackhaulGameTests.force(level, chunks, forced);
        CLEANUPS.add(() -> BackhaulGameTests.release(level, forced));
    }

    /** Places a block and registers its removal, a container emptied first so nothing drops. */
    private static void place(ServerLevel level, BlockPos pos, Block block) {
        level.setBlock(pos, block.defaultBlockState(), Block.UPDATE_ALL);
        CLEANUPS.add(() -> {
            if (level.getBlockEntity(pos) instanceof Container container) {
                container.clearContent();
            }
            level.setBlock(pos, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
        });
    }

    /** A Sector Antenna at {@code pos}, facing north, at {@code txDbm}. */
    private static SectorAntennaBlockEntity sector(GameTestHelper helper, ServerLevel level, BlockPos pos, double txDbm) {
        place(level, pos, ModBlocks.SECTOR_ANTENNA.get());
        if (!(level.getBlockEntity(pos) instanceof SectorAntennaBlockEntity sector)) {
            helper.fail("no sector entity at " + pos);
            return null;
        }
        sector.applyConfiguration(sector.bandId(), txDbm, sector.azimuthDeg(), sector.tiltDeg(),
                sector.hBeamwidthDeg(), sector.vBeamwidthDeg(), sector.pci());
        return sector;
    }

    /** A Site Generator at {@code pos} with {@code fuel} in its slot. */
    private static SiteGeneratorBlockEntity generator(GameTestHelper helper, ServerLevel level, BlockPos pos,
                                                      ItemStack fuel) {
        place(level, pos, ModBlocks.SITE_GENERATOR.get());
        if (!(level.getBlockEntity(pos) instanceof SiteGeneratorBlockEntity generator)) {
            helper.fail("no generator entity at " + pos);
            return null;
        }
        if (!fuel.isEmpty()) {
            generator.setItem(0, fuel);
        }
        return generator;
    }

    private static SignalMastBlockEntity mast(GameTestHelper helper, ServerLevel level, BlockPos pos) {
        if (!(level.getBlockEntity(pos) instanceof SignalMastBlockEntity mast)) {
            helper.fail("no mast entity at " + pos);
            return null;
        }
        return mast;
    }

    /** Sets a cell's buffer and latch, then refreshes it, as the power ticker would after a change. */
    private static void fill(AntennaBlockEntity antenna, int fe, boolean on) {
        antenna.energyBuffer().load(fe, on);
        antenna.refreshRegistration();
    }

    /** Whether the cell owned by the antenna at {@code pos} is in the site registry. */
    private static boolean registered(ServerLevel level, BlockPos pos) {
        for (CellParams cell : SiteRegistry.of(level).near(pos.getX() + 0.5, pos.getY() + 1.0, pos.getZ() + 0.5, 4.0)) {
            if (cell.cellId() == pos.asLong()) {
                return true;
            }
        }
        return false;
    }

    private static int countIn(Container container, Item item) {
        return container.countItem(item);
    }
}
