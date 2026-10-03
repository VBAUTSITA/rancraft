package dev.rancraft.gametest;

import dev.rancraft.RanCraft;
import dev.rancraft.registry.ModBlocks;
import dev.rancraft.registry.ModCreativeTabs;
import dev.rancraft.registry.ModItems;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import net.minecraft.core.BlockPos;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTestGenerator;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.TestFunction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeManager;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.item.crafting.ShapedRecipe;
import net.minecraft.world.item.crafting.ShapelessRecipe;
import net.minecraft.world.level.block.Block;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.registries.DeferredHolder;

/**
 * Survival crafting check (§3C.6, Phase 3 slice 16): every item RANCraft registers, blocks included,
 * can be crafted from items a survival player can get, and every one is in the creative tab.
 *
 * <p>One test per entry in {@link ModItems#ITEMS}, so an item added later is covered with no edit
 * here (the pattern {@link HarvestGameTests} uses for blocks). Each test, on the recipes the server
 * actually loaded from {@code data/rancraft/recipe/}:
 * <ol>
 *   <li>At least one crafting recipe makes the item. A recipe file that fails to parse is logged
 *       and skipped by {@code RecipeManager.apply}, so it shows up here as "no recipe".</li>
 *   <li>Each such recipe is shaped or shapeless, and every ingredient resolves to at least one real
 *       item. An empty or misspelled tag loads without any error and makes the recipe uncraftable;
 *       NeoForge lists it as a placeholder barrier, which this check rejects.</li>
 *   <li>The recipe's own grid, built from real item stacks, is matched by that recipe and no other
 *       ({@code RecipeManager.getRecipesFor}): no vanilla recipe, and no other RANCraft one, takes the
 *       same grid. Every alternative of every ingredient is tried in turn, the other cells holding
 *       their first item, so an alternative (a repeater for the comparator, another mod's copper)
 *       cannot collide unseen either. Assembling the grid yields the item.</li>
 *   <li>A RANCraft ingredient (the Signal Mast in the Sector Antenna) can itself be crafted from
 *       non-RANCraft items, so the chain ends in vanilla.</li>
 *   <li>An advancement unlocks the recipe in the recipe book ({@code data/rancraft/advancement/recipes/}).
 *       Without one the recipe still works in a crafting table, but the recipe book never shows it,
 *       and with the {@code doLimitedCrafting} game rule on it could never be crafted.</li>
 * </ol>
 * Each check was seen to fail on a deliberately broken copy of the data (NOTES.md, Phase 3 slice 16).
 *
 * <p>Test-harness note: the grid is matched with {@code RecipeManager}, as the crafting table's
 * result slot does ({@code CraftingMenu.slotChangedCraftingGrid}), but no menu or player is involved.
 * That a survival player can obtain the vanilla ingredients is taken as given.
 *
 * <p>Run with {@code ./gradlew runGameTestServer}; not part of {@code ./gradlew build}.
 */
@GameTestHolder(RanCraft.MOD_ID)
public final class RecipeGameTests {

    private static final String BATCH = "rancraft_recipes";
    private static final int TIMEOUT_TICKS = 100;
    private static final BlockPos ORIGIN = BlockPos.ZERO;

    private RecipeGameTests() {
    }

    /**
     * One test per registered item, named {@code recipegametests.<item path>}, plus the creative tab
     * check.
     */
    @GameTestGenerator
    public static Collection<TestFunction> everyItemIsCraftable() {
        String prefix = RecipeGameTests.class.getSimpleName().toLowerCase(Locale.ROOT) + ".";
        List<TestFunction> tests = new ArrayList<>();
        for (DeferredHolder<Item, ? extends Item> entry : ModItems.ITEMS.getEntries()) {
            ResourceLocation id = entry.getId();
            tests.add(new TestFunction(BATCH, prefix + id.getPath(), HarvestGameTests.EMPTY_TEMPLATE,
                    TIMEOUT_TICKS, 0L, true, helper -> assertCraftable(helper, id, entry.get())));
        }
        tests.add(new TestFunction(BATCH, prefix + "every_block_and_item_is_in_the_creative_tab",
                HarvestGameTests.EMPTY_TEMPLATE, TIMEOUT_TICKS, 0L, true, RecipeGameTests::creativeTab));
        return tests;
    }

    private static void assertCraftable(GameTestHelper helper, ResourceLocation id, Item item) {
        ServerLevel level = helper.getLevel();
        RecipeManager recipes = level.getRecipeManager();
        RegistryAccess access = level.registryAccess();

        List<RecipeHolder<CraftingRecipe>> making = recipesMaking(recipes, access, item);
        if (making.isEmpty()) {
            helper.fail(id + ": no crafting recipe makes it (data/rancraft/recipe/; a file that fails to"
                    + " parse is logged as \"Parsing error loading recipe\" and skipped)", ORIGIN);
        }

        // Collect every problem before failing, so one run names all causes.
        List<String> problems = new ArrayList<>();
        for (RecipeHolder<CraftingRecipe> holder : making) {
            checkRecipe(helper, recipes, access, item, holder, problems);
            if (!unlockedByAnAdvancement(level, holder.id())) {
                problems.add("recipe " + holder.id() + ": no advancement unlocks it in the recipe book"
                        + " (data/rancraft/advancement/recipes/)");
            }
        }
        if (!problems.isEmpty()) {
            helper.fail(id + ": " + String.join("; ", problems), ORIGIN);
        }
        helper.succeed();
    }

    private static List<RecipeHolder<CraftingRecipe>> recipesMaking(
            RecipeManager recipes, RegistryAccess access, Item item) {
        return recipes.getAllRecipesFor(RecipeType.CRAFTING).stream()
                .filter(holder -> holder.value().getResultItem(access).is(item))
                .toList();
    }

    private static void checkRecipe(GameTestHelper helper, RecipeManager recipes, RegistryAccess access,
            Item item, RecipeHolder<CraftingRecipe> holder, List<String> problems) {
        CraftingRecipe recipe = holder.value();
        int width;
        int height;
        if (recipe instanceof ShapedRecipe shaped) {
            width = shaped.getWidth();
            height = shaped.getHeight();
        } else if (recipe instanceof ShapelessRecipe) {
            width = 3;
            height = 3;
        } else {
            problems.add("recipe " + holder.id() + ": " + recipe.getClass().getSimpleName()
                    + " is neither shaped nor shapeless, so this test cannot build its grid");
            return;
        }

        List<Ingredient> ingredients = recipe.getIngredients();
        if (ingredients.size() > width * height) {
            problems.add("recipe " + holder.id() + ": " + ingredients.size() + " ingredients do not fit a crafting grid");
            return;
        }
        ItemStack[][] options = new ItemStack[ingredients.size()][];
        for (int i = 0; i < ingredients.size(); i++) {
            Ingredient ingredient = ingredients.get(i);
            options[i] = ingredient.isEmpty() ? new ItemStack[0] : ingredient.getItems();
            // NeoForge lists an empty tag as one barrier named "Empty Tag: <tag>" (Ingredient.TagValue),
            // which the ingredient then accepts, so a grid built from getItems() would still match.
            // A barrier is never a real ingredient, so any barrier means an empty tag somewhere in it.
            if (!ingredient.isEmpty() && (options[i].length == 0
                    || Arrays.stream(options[i]).anyMatch(stack -> stack.is(Items.BARRIER)))) {
                problems.add("recipe " + holder.id() + ": ingredient " + i + " matches no item (an empty or"
                        + " unknown tag)");
                return;
            }
            for (ItemStack option : options[i]) {
                checkModIngredient(recipes, access, holder.id(), option.getItem(), problems);
            }
        }

        // The base grid (every cell its first item), then each alternative of each cell in turn.
        checkGrid(helper, recipes, access, item, holder, width, height, options, -1, 0, problems);
        for (int cell = 0; cell < options.length; cell++) {
            for (int alternative = 1; alternative < options[cell].length; alternative++) {
                checkGrid(helper, recipes, access, item, holder, width, height, options, cell, alternative, problems);
            }
        }
    }

    private static void checkGrid(GameTestHelper helper, RecipeManager recipes, RegistryAccess access, Item item,
            RecipeHolder<CraftingRecipe> holder, int width, int height, ItemStack[][] options,
            int variedCell, int alternative, List<String> problems) {
        List<ItemStack> grid = new ArrayList<>(width * height);
        for (int i = 0; i < width * height; i++) {
            if (i >= options.length || options[i].length == 0) {
                grid.add(ItemStack.EMPTY);
            } else {
                grid.add(options[i][i == variedCell ? alternative : 0].copyWithCount(1));
            }
        }
        CraftingInput input = CraftingInput.of(width, height, grid);
        List<RecipeHolder<CraftingRecipe>> matches = recipes.getRecipesFor(RecipeType.CRAFTING, input, helper.getLevel());
        if (matches.size() != 1 || !matches.get(0).id().equals(holder.id())) {
            problems.add("recipe " + holder.id() + ": the grid " + describe(grid) + " is matched by "
                    + matches.stream().map(match -> match.id().toString()).toList()
                    + ", not by this recipe alone");
            return;
        }
        ItemStack result = holder.value().assemble(input, access);
        if (!result.is(item)) {
            problems.add("recipe " + holder.id() + ": assembling " + describe(grid) + " gives " + result + ", not the item");
        }
    }

    /**
     * A RANCraft item used as an ingredient must have a recipe made only of other mods' or vanilla
     * items, so a survival player can reach it.
     */
    private static void checkModIngredient(RecipeManager recipes, RegistryAccess access, ResourceLocation recipeId,
            Item ingredient, List<String> problems) {
        ResourceLocation ingredientId = BuiltInRegistries.ITEM.getKey(ingredient);
        if (!RanCraft.MOD_ID.equals(ingredientId.getNamespace())) {
            return;
        }
        boolean reachable = recipesMaking(recipes, access, ingredient).stream()
                .anyMatch(holder -> holder.value().getIngredients().stream()
                        .allMatch(part -> Arrays.stream(part.getItems()).noneMatch(stack ->
                                RanCraft.MOD_ID.equals(BuiltInRegistries.ITEM.getKey(stack.getItem()).getNamespace()))));
        if (!reachable) {
            problems.add("recipe " + recipeId + ": its ingredient " + ingredientId
                    + " has no recipe made from non-RANCraft items");
        }
    }

    private static boolean unlockedByAnAdvancement(ServerLevel level, ResourceLocation recipeId) {
        return level.getServer().getAdvancements().getAllAdvancements().stream()
                .anyMatch(advancement -> advancement.value().rewards().recipes().contains(recipeId));
    }

    private static String describe(List<ItemStack> grid) {
        return grid.stream()
                .map(stack -> stack.isEmpty() ? "-" : BuiltInRegistries.ITEM.getKey(stack.getItem()).getPath())
                .toList()
                .toString();
    }

    /**
     * Every registered item is in the RANCraft creative tab, and every registered block has an item.
     * Builds the tab's contents with the level's features, as the creative screen does on a client
     * ({@code CreativeModeTabs.tryRebuildTabContents}); on this dedicated test server no screen reads
     * them.
     */
    private static void creativeTab(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        CreativeModeTab tab = ModCreativeTabs.RANCRAFT.get();
        tab.buildContents(new CreativeModeTab.ItemDisplayParameters(level.enabledFeatures(), false,
                level.registryAccess()));
        Collection<ItemStack> shown = tab.getDisplayItems();

        List<String> problems = new ArrayList<>();
        for (DeferredHolder<Item, ? extends Item> entry : ModItems.ITEMS.getEntries()) {
            Item item = entry.get();
            if (shown.stream().noneMatch(stack -> stack.is(item))) {
                problems.add(entry.getId() + " is not in the creative tab (ModCreativeTabs)");
            }
        }
        for (DeferredHolder<Block, ? extends Block> entry : ModBlocks.BLOCKS.getEntries()) {
            if (entry.get().asItem() == Items.AIR) {
                problems.add(entry.getId() + " has no item (ModItems)");
            }
        }
        if (shown.size() != ModItems.ITEMS.getEntries().size()) {
            problems.add("the tab shows " + shown.size() + " stacks for " + ModItems.ITEMS.getEntries().size()
                    + " registered items");
        }
        if (!problems.isEmpty()) {
            helper.fail(String.join("; ", problems), ORIGIN);
        }
        helper.succeed();
    }
}
