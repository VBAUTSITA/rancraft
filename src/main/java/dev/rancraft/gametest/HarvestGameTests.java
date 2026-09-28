package dev.rancraft.gametest;

import dev.rancraft.RanCraft;
import dev.rancraft.registry.ModBlocks;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestGenerator;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.TestFunction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.registries.DeferredHolder;

/**
 * Survival harvest check: every block RANCraft registers can be mined with an iron pickaxe and
 * drops itself.
 *
 * <p>Why it exists ({@code PHASE_3_PROMPT.md} §0, known problem 3): Minecraft 1.21 renamed the data
 * pack folders to singular ({@code loot_table/}, {@code tags/block/}, {@code recipe/}). RANCraft
 * still shipped {@code loot_tables/} and {@code tags/blocks/}. 1.21 does not read those folders and
 * does not warn about them. So both antennas dropped nothing in survival, and a pickaxe mined them no
 * faster than a bare hand. All testing until then had been in creative, where neither problem shows.
 * These tests failed on that tree and pass on the fix (numbers in {@code NOTES.md}, Phase 3 slice 1).
 *
 * <p>One test per entry in {@link ModBlocks#BLOCKS}, so blocks added later (§3C.6) are covered with
 * no edit here. A block that should not be pickaxe-mined would need an explicit exemption.
 *
 * <p>What it checks, in the order {@code ServerPlayerGameMode.destroyBlock} decides a survival break:
 * <ol>
 *   <li>{@code canHarvestBlock} for a survival player holding an iron pickaxe. Both antennas are
 *       {@code requiresCorrectToolForDrops()}, so if this is false the loot table is never rolled.</li>
 *   <li>{@code Block.getDrops} with that pickaxe: the loot table must exist and yield the block.</li>
 * </ol>
 * It also checks the {@code #minecraft:mineable/pickaxe} tag and the pickaxe's mining speed directly,
 * so a failure names its cause instead of only saying "no drop".
 *
 * <p>Test-harness abstraction, stated plainly: the miner is vanilla's mock {@link Player}
 * ({@link GameTestHelper#makeMockPlayer}), not a connected {@code ServerPlayer}. The test runs the
 * two decisions {@code destroyBlock} makes rather than {@code destroyBlock} itself, which would need a
 * fake network connection and a player left on the server's player list.
 *
 * <p>Run with {@code ./gradlew runGameTestServer}. The game test server exits with the number of
 * failed required tests, so the Gradle task fails if any block does not drop. It is not part of
 * {@code ./gradlew build}. Registered only in a dev run with the {@code rancraft} game test namespace
 * enabled ({@code build.gradle}); NeoForge never registers game tests in production.
 */
@GameTestHolder(RanCraft.MOD_ID)
public final class HarvestGameTests {

    /**
     * An empty 3x3x3 template, shipped as {@code data/rancraft/structure/gametest/empty_3x3x3.nbt}.
     * Every game test is placed from a template, and NeoForge 21.1.251 has no built-in empty one
     * (its test framework's {@code @EmptyTemplate} is not in the runtime jar), so the mod carries it.
     * It holds no blocks, so {@code /place template} with it places nothing. Its namespace must stay
     * {@code rancraft}: for generated tests, vanilla's {@code GameTestRegistry.register} (NeoForge
     * patch) keeps only those whose template namespace is in {@code neoforge.enabledGameTestNamespaces}.
     */
    public static final String EMPTY_TEMPLATE = RanCraft.MOD_ID + ":gametest/empty_3x3x3";

    private static final String BATCH = "rancraft_harvest";
    private static final int TIMEOUT_TICKS = 100;
    /** The centre of the 3x3x3 template, relative to its origin. */
    private static final BlockPos CENTRE = new BlockPos(1, 1, 1);

    private HarvestGameTests() {
    }

    /**
     * One test per registered block, named {@code harvestgametests.<block path>} (vanilla's
     * class-prefix convention, so the test names group by class the way {@code @GameTest} ones do).
     */
    @GameTestGenerator
    public static Collection<TestFunction> everyBlockDropsItselfToAnIronPickaxe() {
        String prefix = HarvestGameTests.class.getSimpleName().toLowerCase(Locale.ROOT) + ".";
        List<TestFunction> tests = new ArrayList<>();
        for (DeferredHolder<Block, ? extends Block> entry : ModBlocks.BLOCKS.getEntries()) {
            ResourceLocation id = entry.getId();
            tests.add(new TestFunction(BATCH, prefix + id.getPath(), EMPTY_TEMPLATE, TIMEOUT_TICKS, 0L, true,
                    helper -> assertSurvivalHarvest(helper, id, entry.get())));
        }
        return tests;
    }

    private static void assertSurvivalHarvest(GameTestHelper helper, ResourceLocation id, Block block) {
        helper.setBlock(CENTRE, block);
        ServerLevel level = helper.getLevel();
        BlockPos pos = helper.absolutePos(CENTRE);
        BlockState state = level.getBlockState(pos);
        if (!state.is(block)) {
            helper.fail(id + ": placing the block failed, found " + state, CENTRE);
        }

        ItemStack pickaxe = new ItemStack(Items.IRON_PICKAXE);
        Player miner = helper.makeMockPlayer(GameType.SURVIVAL);
        miner.setItemInHand(InteractionHand.MAIN_HAND, pickaxe.copy());

        // Collect every problem before failing, so one run names all causes.
        List<String> problems = new ArrayList<>();
        if (!state.is(BlockTags.MINEABLE_WITH_PICKAXE)) {
            problems.add("not in #minecraft:mineable/pickaxe (data/minecraft/tags/block/mineable/pickaxe.json)");
        }
        float speed = pickaxe.getDestroySpeed(state);
        if (speed <= 1.0F) {
            problems.add("an iron pickaxe mines it at bare-hand speed (" + speed + ")");
        }
        if (!state.canHarvestBlock(level, pos, miner)) {
            problems.add("a survival player holding an iron pickaxe cannot harvest it, so breaking it drops nothing");
        }
        List<ItemStack> drops = Block.getDrops(state, level, pos, level.getBlockEntity(pos), miner, pickaxe);
        if (drops.isEmpty()) {
            problems.add("loot table " + block.getLootTable().location()
                    + " yields nothing (missing, or not under data/<ns>/loot_table/)");
        } else if (drops.stream().noneMatch(stack -> stack.is(block.asItem()))) {
            problems.add("drops " + drops + " but not itself");
        }

        if (!problems.isEmpty()) {
            helper.fail(id + ": " + String.join("; ", problems), CENTRE);
        }
        helper.succeed();
    }
}
