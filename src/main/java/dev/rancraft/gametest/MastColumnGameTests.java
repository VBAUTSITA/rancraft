package dev.rancraft.gametest;

import dev.rancraft.RanCraft;
import dev.rancraft.RanCraftConfig;
import dev.rancraft.block.AntennaBlockEntity;
import dev.rancraft.block.MastColumn;
import dev.rancraft.block.SignalMastBlock;
import dev.rancraft.block.SignalMastBlockEntity;
import dev.rancraft.registry.ModBlocks;
import dev.rancraft.rf.CellParams;
import dev.rancraft.world.MastColumnCensus;
import dev.rancraft.world.SiteRegistry;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestGenerator;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.TestFunction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.gametest.GameTestHolder;

/**
 * Mast columns at runtime (Phase 3 slice 6, §3B.1): the real blocks, block entities, shape updates
 * and {@link SiteRegistry} of a live server level. {@code ColumnScanTest} pins the rule itself
 * headless; these check that the game wiring (updateShape / neighborChanged re-scans, the base-only
 * registration, PCI planning, the {@code OnAir} update tag) does what the rule says.
 *
 * <p>Each test builds in one x/z column of its own 3x14x3 test area and reads the registry for that
 * x/z only, so the masts other tests leave standing (the harvest tests place one) do not matter.
 * Freshly placed block entities run {@code onLoad} on the next tick, so every check waits
 * {@link #SETTLE} ticks.
 *
 * <p>The redstone and height-cap tests change a COMMON config value in memory ({@code set}, never
 * {@code save}, so the file is untouched) and put it back, including when an assertion fails. They
 * run in batches of their own, because batches run one after another and the tests of one batch run
 * side by side: the value is global.
 */
@GameTestHolder(RanCraft.MOD_ID)
public final class MastColumnGameTests {

    /** A 3 x 14 x 3 empty template: room for a nine-mast column with a sector antenna on top. */
    public static final String TALL_TEMPLATE = RanCraft.MOD_ID + ":gametest/empty_3x14x3";

    private static final String BATCH = "rancraft_mast_columns";
    private static final String BATCH_REDSTONE = "rancraft_mast_columns_redstone";
    private static final String BATCH_CAP = "rancraft_mast_columns_cap";
    private static final int TIMEOUT_TICKS = 100;
    private static final int SETTLE = 3;
    /** The census logs after {@code MastColumnCensus.QUIET_TICKS} (100) quiet ticks. */
    private static final int CENSUS_TIMEOUT_TICKS = 200;

    /** The column's x/z inside the test area. */
    private static final int X = 1;
    private static final int Z = 1;

    private MastColumnGameTests() {
    }

    @GameTestGenerator
    public static Collection<TestFunction> mastColumnTests() {
        String prefix = MastColumnGameTests.class.getSimpleName().toLowerCase(Locale.ROOT) + ".";
        List<TestFunction> tests = new ArrayList<>();
        tests.add(test(BATCH, prefix + "nine_masts_are_one_cell", MastColumnGameTests::nineMastsAreOneCell));
        tests.add(test(BATCH, prefix + "extending_keeps_id_and_pci", MastColumnGameTests::extendingKeepsIdAndPci));
        tests.add(test(BATCH, prefix + "sector_on_top_silences_the_column", MastColumnGameTests::sectorOnTopSilences));
        tests.add(test(BATCH, prefix + "breaking_the_base_promotes_the_next_mast", MastColumnGameTests::breakingTheBasePromotes));
        tests.add(test(BATCH, prefix + "gaps_split_and_a_new_bottom_mast_takes_over", MastColumnGameTests::gapsAndNewBase));
        tests.add(new TestFunction(BATCH, prefix + "saved_stack_is_logged_once", TALL_TEMPLATE,
                CENSUS_TIMEOUT_TICKS, 0L, true, MastColumnGameTests::savedStackIsLogged));
        tests.add(test(BATCH_REDSTONE, prefix + "any_powered_mast_powers_the_column", MastColumnGameTests::anyPoweredMast));
        tests.add(test(BATCH_CAP, prefix + "height_cap_limits_the_radiating_point", MastColumnGameTests::heightCap));
        return tests;
    }

    private static TestFunction test(String batch, String name, Consumer<GameTestHelper> body) {
        return new TestFunction(batch, name, TALL_TEMPLATE, TIMEOUT_TICKS, 0L, true, body);
    }

    // ---- the tests --------------------------------------------------------------------------------

    /**
     * 3B done-when: the nine-mast column reads as one cell with its lobe at the top. One registered
     * cell in the column, owned by the base, radiating from above the ninth mast; the other eight are
     * structure. {@code OnAir} is in the update tag (true for the base, false for structure) and not
     * in the saved data.
     */
    private static void nineMastsAreOneCell(GameTestHelper helper) {
        masts(helper, 0, 8);
        helper.startSequence()
                .thenIdle(SETTLE)
                .thenExecute(() -> {
                    List<CellParams> cells = cellsInColumn(helper);
                    helper.assertTrue(cells.size() == 1, "expected one cell for nine stacked masts, got " + cells);
                    CellParams cell = cells.get(0);
                    helper.assertTrue(cell.cellId() == abs(helper, 0).asLong(),
                            "the base owns the cell: id " + BlockPos.of(cell.cellId()) + ", base " + abs(helper, 0));
                    helper.assertTrue(cell.y() == abs(helper, 9).getY(),
                            "radiates from top.above(): y " + cell.y() + ", expected " + abs(helper, 9).getY());

                    ServerLevel level = helper.getLevel();
                    SignalMastBlockEntity base = mast(helper, 0);
                    helper.assertTrue(base.onAir(), "the base is on the air");
                    helper.assertTrue(onAirInUpdateTag(level, base), "the base's update tag says OnAir true");
                    for (int y = 1; y <= 8; y++) {
                        SignalMastBlockEntity structure = mast(helper, y);
                        helper.assertFalse(structure.isTransmitting(), "mast " + y + " is structure");
                        helper.assertFalse(structure.onAir(), "mast " + y + " is not on the air");
                        helper.assertFalse(onAirInUpdateTag(level, structure), "mast " + y + "'s update tag says OnAir false");
                    }
                    CompoundTag saved = base.saveWithoutMetadata(level.registryAccess());
                    helper.assertFalse(saved.contains(AntennaBlockEntity.ON_AIR_TAG), "OnAir is never saved");

                    measureScanCost(level, abs(helper, 0), abs(helper, 5));
                })
                .thenSucceed();
    }

    /**
     * Logs what a column costs on live ground: a full scan of the nine-mast column (11 block reads)
     * from its base, the same from the sixth mast (16), and the two-read base test the lens
     * and every structure mast use. Wall-clock over many repetitions, after a warm-up, so it is an
     * order of magnitude, not a benchmark. A scan runs on a block change next to a mast, on a chunk
     * load and, on the client, once per drawn column per frame; never per player evaluation.
     */
    private static void measureScanCost(ServerLevel level, BlockPos base, BlockPos middle) {
        final int reps = 20_000;
        long sink = 0;
        for (int i = 0; i < reps; i++) {
            sink += MastColumn.bounds(level, base).topY() + (MastColumn.isBase(level, middle) ? 1 : 0);
        }
        long t0 = System.nanoTime();
        for (int i = 0; i < reps; i++) {
            sink += MastColumn.bounds(level, base).topY();
        }
        long t1 = System.nanoTime();
        for (int i = 0; i < reps; i++) {
            sink += MastColumn.bounds(level, middle).topY();
        }
        long t2 = System.nanoTime();
        for (int i = 0; i < reps; i++) {
            sink += MastColumn.isBase(level, middle) ? 1 : 0;
        }
        long t3 = System.nanoTime();
        RanCraft.LOGGER.info(
                "RANCraft mast column cost: {} ns per 9-mast scan from the base (11 reads), {} ns from the middle (16 reads), "
                        + "{} ns per isBase (2 reads) [{}]",
                (t1 - t0) / reps, (t2 - t1) / reps, (t3 - t2) / reps, sink);
    }

    /** 3B done-when: adding a mast to the top of a column keeps its PCI (and its id); only the point moves. */
    private static void extendingKeepsIdAndPci(GameTestHelper helper) {
        masts(helper, 0, 2);
        CellParams[] before = new CellParams[1];
        helper.startSequence()
                .thenIdle(SETTLE)
                .thenExecute(() -> {
                    before[0] = onlyCell(helper);
                    helper.assertTrue(before[0].y() == abs(helper, 3).getY(), "three masts radiate from above the third");
                    helper.setBlock(new BlockPos(X, 3, Z), ModBlocks.SIGNAL_MAST.get());
                })
                .thenIdle(SETTLE)
                .thenExecute(() -> {
                    CellParams after = onlyCell(helper);
                    helper.assertTrue(after.cellId() == before[0].cellId(), "same cell id after extending");
                    helper.assertTrue(after.pci() == before[0].pci(),
                            "same PCI after extending: " + before[0].pci() + " -> " + after.pci());
                    helper.assertTrue(after.y() == before[0].y() + 1, "the radiating point moved up one block");
                })
                .thenSucceed();
    }

    /**
     * A sector antenna directly on the top mast makes the column a mounting pole: the column is off
     * the air, the sector is its own cell. Taking the sector off puts the column back.
     */
    private static void sectorOnTopSilences(GameTestHelper helper) {
        masts(helper, 0, 2);
        helper.setBlock(new BlockPos(X, 3, Z), ModBlocks.SECTOR_ANTENNA.get());
        helper.startSequence()
                .thenIdle(SETTLE)
                .thenExecute(() -> {
                    List<CellParams> cells = cellsInColumn(helper);
                    helper.assertTrue(cells.size() == 1 && cells.get(0).cellId() == abs(helper, 3).asLong(),
                            "only the sector transmits from a mounting pole, got " + cells);
                    helper.assertFalse(mast(helper, 0).onAir(), "the pole's base is off the air");
                    helper.setBlock(new BlockPos(X, 3, Z), Blocks.AIR);
                })
                .thenIdle(SETTLE)
                .thenExecute(() -> {
                    CellParams cell = onlyCell(helper);
                    helper.assertTrue(cell.cellId() == abs(helper, 0).asLong() && cell.y() == abs(helper, 3).getY(),
                            "without the sector the column transmits again from its top, got " + cell);
                    helper.assertTrue(mast(helper, 0).onAir(), "back on the air");
                })
                .thenSucceed();
    }

    /** Breaking the base promotes the next mast up: a new cell id at the same radiating point. */
    private static void breakingTheBasePromotes(GameTestHelper helper) {
        masts(helper, 0, 3);
        helper.startSequence()
                .thenIdle(SETTLE)
                .thenExecute(() -> {
                    helper.assertTrue(onlyCell(helper).cellId() == abs(helper, 0).asLong(), "the base owns the cell");
                    helper.destroyBlock(new BlockPos(X, 0, Z));
                })
                .thenIdle(SETTLE)
                .thenExecute(() -> {
                    CellParams cell = onlyCell(helper);
                    helper.assertTrue(cell.cellId() == abs(helper, 1).asLong(),
                            "the next mast up owns the cell now, got " + BlockPos.of(cell.cellId()));
                    helper.assertTrue(cell.y() == abs(helper, 4).getY(), "same top, same radiating point");
                    helper.assertTrue(mast(helper, 1).onAir(), "the promoted base is on the air");
                })
                .thenSucceed();
    }

    /**
     * A gap splits a column into two cells; filling it merges them again (the upper base goes quiet);
     * a mast placed under the base becomes the new base and the old one stops transmitting.
     */
    private static void gapsAndNewBase(GameTestHelper helper) {
        masts(helper, 1, 5);
        helper.startSequence()
                .thenIdle(SETTLE)
                .thenExecute(() -> helper.setBlock(new BlockPos(X, 3, Z), Blocks.AIR))
                .thenIdle(SETTLE)
                .thenExecute(() -> {
                    List<CellParams> cells = cellsInColumn(helper);
                    helper.assertTrue(cells.size() == 2, "a gap makes two columns, got " + cells);
                    CellParams lower = cellWithId(helper, cells, abs(helper, 1));
                    CellParams upper = cellWithId(helper, cells, abs(helper, 4));
                    helper.assertTrue(lower.y() == abs(helper, 3).getY(), "the lower column radiates into the gap");
                    helper.assertTrue(upper.y() == abs(helper, 6).getY(), "the upper column radiates from its top");
                    helper.setBlock(new BlockPos(X, 3, Z), ModBlocks.SIGNAL_MAST.get());
                })
                .thenIdle(SETTLE)
                .thenExecute(() -> {
                    CellParams merged = onlyCell(helper);
                    helper.assertTrue(merged.cellId() == abs(helper, 1).asLong() && merged.y() == abs(helper, 6).getY(),
                            "filled gap: one column again, owned by the lowest mast, got " + merged);
                    helper.assertFalse(mast(helper, 4).onAir(), "the upper base went quiet");
                    helper.setBlock(new BlockPos(X, 0, Z), ModBlocks.SIGNAL_MAST.get());
                })
                .thenIdle(SETTLE)
                .thenExecute(() -> {
                    CellParams cell = onlyCell(helper);
                    helper.assertTrue(cell.cellId() == abs(helper, 0).asLong(),
                            "a mast placed under the base is the new base, got " + BlockPos.of(cell.cellId()));
                    helper.assertTrue(cell.y() == abs(helper, 6).getY(), "same top");
                    helper.assertFalse(mast(helper, 1).onAir(), "the old base is structure now");
                })
                .thenSucceed();
    }

    /**
     * With {@code requireRedstone} on, the column transmits while any of its masts is powered: here
     * only the top one, by a redstone block beside it. Masts do not conduct redstone to each other,
     * so this is the column rule, not vanilla power.
     */
    private static void anyPoweredMast(GameTestHelper helper) {
        boolean previous = RanCraftConfig.REQUIRE_REDSTONE.get();
        Runnable restore = () -> RanCraftConfig.REQUIRE_REDSTONE.set(previous);
        RanCraftConfig.REQUIRE_REDSTONE.set(true);
        masts(helper, 0, 4);
        helper.startSequence()
                .thenIdle(SETTLE)
                .thenExecute(restoring(restore, () -> {
                    helper.assertTrue(cellsInColumn(helper).isEmpty(), "unpowered: no cell");
                    helper.setBlock(new BlockPos(X + 1, 4, Z), Blocks.REDSTONE_BLOCK);
                }))
                .thenIdle(SETTLE)
                .thenExecute(restoring(restore, () -> {
                    helper.assertFalse(helper.getLevel().getBlockState(abs(helper, 0)).getValue(SignalMastBlock.POWERED),
                            "the base itself is not powered");
                    helper.assertTrue(helper.getLevel().getBlockState(abs(helper, 4)).getValue(SignalMastBlock.POWERED),
                            "the top mast is");
                    CellParams cell = onlyCell(helper);
                    helper.assertTrue(cell.cellId() == abs(helper, 0).asLong() && cell.y() == abs(helper, 5).getY(),
                            "a powered top mast puts the column on the air, from the base, got " + cell);
                    helper.setBlock(new BlockPos(X + 1, 4, Z), Blocks.AIR);
                }))
                .thenIdle(SETTLE)
                .thenExecute(restoring(restore, () -> {
                    helper.assertTrue(cellsInColumn(helper).isEmpty(), "power removed: off the air again");
                    helper.assertFalse(mast(helper, 0).onAir(), "OnAir follows");
                }))
                .thenExecute(restore)
                .thenSucceed();
    }

    /** {@code maxMastHeight} 3: six masts radiate from above the third; the rest is structure. */
    private static void heightCap(GameTestHelper helper) {
        int previous = RanCraftConfig.MAX_MAST_HEIGHT.get();
        Runnable restore = () -> RanCraftConfig.MAX_MAST_HEIGHT.set(previous);
        RanCraftConfig.MAX_MAST_HEIGHT.set(3);
        masts(helper, 0, 5);
        helper.startSequence()
                .thenIdle(SETTLE)
                .thenExecute(restoring(restore, () -> {
                    CellParams cell = onlyCell(helper);
                    helper.assertTrue(cell.cellId() == abs(helper, 0).asLong() && cell.y() == abs(helper, 3).getY(),
                            "capped at 3: radiates from above the third mast, got " + cell);
                    // A sector on the highest mast (above the cap) still makes the column a pole.
                    helper.setBlock(new BlockPos(X, 6, Z), ModBlocks.SECTOR_ANTENNA.get());
                }))
                .thenIdle(SETTLE)
                .thenExecute(restoring(restore, () -> {
                    List<CellParams> cells = cellsInColumn(helper);
                    helper.assertTrue(cells.size() == 1 && cells.get(0).cellId() == abs(helper, 6).asLong(),
                            "a sector on the highest mast silences a capped column, got " + cells);
                }))
                .thenExecute(restore)
                .thenSucceed();
    }

    /**
     * §3B.1's one-line log: "N stacked masts now form M columns; N-M masts stopped transmitting."
     *
     * <p>Test-harness abstraction, stated plainly: there is no Phase 2 save to load here. The test
     * places nine masts and hands each block entity its own saved data before its {@code onLoad}
     * runs (next tick), which is the order a chunk load uses (create, {@code loadWithComponents},
     * {@code onLoad}). That exercises the census path of a column read from disk; the counting
     * itself is pinned headless in {@code MastColumnCensusTest}.
     */
    private static void savedStackIsLogged(GameTestHelper helper) {
        masts(helper, 0, 8);
        ServerLevel level = helper.getLevel();
        for (int y = 0; y <= 8; y++) {
            SignalMastBlockEntity mast = mast(helper, y);
            mast.loadWithComponents(mast.saveWithoutMetadata(level.registryAccess()), level.registryAccess());
        }
        helper.startSequence()
                .thenWaitUntil(() -> {
                    String logged = MastColumnCensus.lastLogged();
                    helper.assertTrue(logged != null
                                    && logged.contains("9 stacked masts now form 1 column; 8 masts stopped transmitting."),
                            "census line not logged yet: " + logged);
                })
                .thenExecute(() -> {
                    CellParams cell = onlyCell(helper);
                    helper.assertTrue(cell.cellId() == abs(helper, 0).asLong(), "still one cell, owned by the base");
                })
                .thenSucceed();
    }

    // ---- helpers ----------------------------------------------------------------------------------

    /** Masts from relative y {@code from} to {@code to} inclusive, in the test's column. */
    private static void masts(GameTestHelper helper, int from, int to) {
        for (int y = from; y <= to; y++) {
            helper.setBlock(new BlockPos(X, y, Z), ModBlocks.SIGNAL_MAST.get());
        }
    }

    private static BlockPos abs(GameTestHelper helper, int relativeY) {
        return helper.absolutePos(new BlockPos(X, relativeY, Z));
    }

    private static SignalMastBlockEntity mast(GameTestHelper helper, int relativeY) {
        if (helper.getLevel().getBlockEntity(abs(helper, relativeY)) instanceof SignalMastBlockEntity mast) {
            return mast;
        }
        helper.fail("no Signal Mast block entity at relative y " + relativeY, new BlockPos(X, relativeY, Z));
        throw new IllegalStateException("unreachable");
    }

    /** Every registered cell whose block (its id) stands in the test's x/z column. */
    private static List<CellParams> cellsInColumn(GameTestHelper helper) {
        BlockPos base = abs(helper, 0);
        return SiteRegistry.of(helper.getLevel()).near(base.getX() + 0.5, base.getY() + 7.0, base.getZ() + 0.5, 32.0)
                .stream()
                .filter(cell -> {
                    BlockPos owner = BlockPos.of(cell.cellId());
                    return owner.getX() == base.getX() && owner.getZ() == base.getZ();
                })
                .toList();
    }

    private static CellParams onlyCell(GameTestHelper helper) {
        List<CellParams> cells = cellsInColumn(helper);
        helper.assertTrue(cells.size() == 1, "expected exactly one cell in the column, got " + cells);
        return cells.get(0);
    }

    private static CellParams cellWithId(GameTestHelper helper, List<CellParams> cells, BlockPos owner) {
        for (CellParams cell : cells) {
            if (cell.cellId() == owner.asLong()) {
                return cell;
            }
        }
        helper.fail("no cell owned by " + owner + " in " + cells);
        throw new IllegalStateException("unreachable");
    }

    private static boolean onAirInUpdateTag(ServerLevel level, AntennaBlockEntity antenna) {
        CompoundTag tag = antenna.getUpdateTag(level.registryAccess());
        return tag.contains(AntennaBlockEntity.ON_AIR_TAG) && tag.getBoolean(AntennaBlockEntity.ON_AIR_TAG);
    }

    /** Runs {@code body}; if it throws (a failed assertion), puts the config back first. */
    private static Runnable restoring(Runnable restore, Runnable body) {
        return () -> {
            try {
                body.run();
            } catch (RuntimeException | Error e) {
                restore.run();
                throw e;
            }
        };
    }
}
