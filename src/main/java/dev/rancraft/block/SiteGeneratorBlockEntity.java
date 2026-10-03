package dev.rancraft.block;

import dev.rancraft.RanCraftConfig;
import dev.rancraft.registry.ModBlockEntities;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.NonNullList;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.ContainerHelper;
import net.minecraft.world.Containers;
import net.minecraft.world.WorldlyContainer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.capabilities.BlockCapabilityCache;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.energy.IEnergyStorage;
import org.jetbrains.annotations.Nullable;

/**
 * The Site Generator's entity (Phase 3 slice 15, §3C.5): one fuel slot, a burn timer and one tick's
 * worth of output.
 *
 * <p><b>Each server tick</b> ({@link #serverTick}):
 * <ol>
 *   <li>With nothing left over from the last tick, and a neighbour that would take energy now (asked
 *       with a simulated {@code receiveEnergy}), it burns one tick: lighting the next fuel item if the
 *       last one is spent, with NeoForge's burn-time lookup ({@code ItemStack.getBurnTime}, the
 *       furnace's own: a coal is 1600 ticks, a lava bucket 20000 and leaves the bucket), and makes
 *       {@code siteGeneratorFePerTick} (40) FE.</li>
 *   <li>It pushes what it holds to the FE receivers on its six sides
 *       ({@code Capabilities.EnergyStorage.BLOCK}), starting from a different side each tick so
 *       neighbours share. What is not taken waits for the next tick.</li>
 * </ol>
 * So it burns only while its output is taken: fuel lasts exactly as long as the energy drawn allows,
 * which is what makes a 30 dBm sector's fuel bill about seven times a 20 dBm one's (§3C.5). With
 * {@code requirePower} off no antenna takes energy, so next to antennas only it burns nothing.
 *
 * <p><b>Game abstractions, labelled (NOTES.md, slice 15):</b> output is a flat 40 FE/t whatever the
 * fuel (fuel sets only how long it burns, as in a furnace); a generator stores nothing beyond one tick
 * of output and is never wasteful (a real one idles at a part load and burns fuel doing so); a burning
 * item's remaining time pauses while nothing is taken, rather than burning away.
 *
 * <p><b>The slot</b> is a {@link WorldlyContainer} (vanilla hoppers) and an item handler through
 * {@code SidedInvWrapper} (pipes; {@code registry.ModCapabilities}): furnace fuel goes in from any side;
 * what fuel leaves behind (the empty bucket of a lava bucket) comes out from below only, as from a
 * furnace's fuel slot. There is no screen: use with fuel fills the slot, sneak + use with an empty hand
 * takes it out, use shows the state ({@link SiteGeneratorBlock}).
 */
public class SiteGeneratorBlockEntity extends BlockEntity implements WorldlyContainer {

    /** Save format. <b>1</b>: Phase 3 slice 15. */
    public static final int DATA_VERSION = 1;

    /**
     * How long {@code LIT} stays on after the last tick burned. A visual debounce, not gameplay: a
     * generator feeding a small load burns about one tick in four, and the lit state is a block state,
     * which would otherwise change (and re-render the chunk section on every client) most ticks.
     */
    static final int LIT_HOLD_TICKS = 20;

    private static final int[] SLOTS = {0};
    private static final Direction[] SIDES = Direction.values();

    private final NonNullList<ItemStack> items = NonNullList.withSize(1, ItemStack.EMPTY);
    /** Ticks left on the item burning now (saved). */
    private int burnTicks;
    /** That item's whole burn time (saved; the status line). */
    private int burnTotal;
    /** FE made and not yet taken (saved): at most one tick's output. */
    private int energy;
    /** The last game tick it burned; far in the past before the first. Not saved. */
    private long lastBurnTick = Long.MIN_VALUE / 2;
    private int pushStart;
    /** One cache per side, made on the first server tick (NeoForge invalidates them itself). */
    private BlockCapabilityCache<IEnergyStorage, Direction>[] neighbours;

    /** Statistics since the entity loaded (game tests, status): ticks burned, items lit, FE pushed. */
    private long burnedTicks;
    private long itemsLit;
    private long fePushed;

    private final IEnergyStorage output = new Output();

    public SiteGeneratorBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.SITE_GENERATOR.get(), pos, state);
    }

    // ---- the tick ---------------------------------------------------------------------------------

    /** The block's server ticker ({@link SiteGeneratorBlock#getTicker}). */
    public static void serverTick(Level level, BlockPos pos, BlockState state, SiteGeneratorBlockEntity generator) {
        if (level instanceof ServerLevel serverLevel) {
            generator.tick(serverLevel, pos, state);
        }
    }

    private void tick(ServerLevel level, BlockPos pos, BlockState state) {
        int rate = RanCraftConfig.siteGeneratorFePerTick();
        if (energy == 0 && wanted(level, rate)) {
            burn(rate, level.getGameTime());
        }
        if (energy > 0) {
            push(level);
        }
        boolean lit = lit(level.getGameTime());
        if (state.getValue(SiteGeneratorBlock.LIT) != lit) {
            level.setBlock(pos, state.setValue(SiteGeneratorBlock.LIT, lit), Block.UPDATE_ALL);
        }
    }

    /** Whether it burned within {@link #LIT_HOLD_TICKS}. */
    public boolean lit(long gameTime) {
        return gameTime - lastBurnTick < LIT_HOLD_TICKS;
    }

    /** Whether any neighbour would take some of a tick's output now (simulated). */
    private boolean wanted(ServerLevel level, int rate) {
        for (Direction side : SIDES) {
            IEnergyStorage target = neighbour(level, side);
            if (target != null && target.canReceive() && target.receiveEnergy(rate, true) > 0) {
                return true;
            }
        }
        return false;
    }

    private void burn(int rate, long gameTime) {
        if (burnTicks <= 0 && !light()) {
            return;
        }
        burnTicks--;
        energy = rate;
        burnedTicks++;
        lastBurnTick = gameTime;
        setChanged();
    }

    /**
     * Lights the next fuel item: one item from the slot, for its burn time. What it leaves behind (a
     * lava bucket's bucket) takes its place when the slot is empty, else drops on top. False when the
     * slot holds no fuel.
     */
    private boolean light() {
        ItemStack fuel = items.get(0);
        int burnTime = burnTimeOf(fuel);
        if (burnTime <= 0) {
            return false;
        }
        // Before shrinking: an emptied stack has no item left to ask.
        ItemStack remainder = fuel.hasCraftingRemainingItem() ? fuel.getCraftingRemainingItem() : ItemStack.EMPTY;
        fuel.shrink(1);
        if (!remainder.isEmpty()) {
            if (fuel.isEmpty()) {
                items.set(0, remainder);
            } else if (level != null) {
                BlockPos pos = getBlockPos();
                Containers.dropItemStack(level, pos.getX() + 0.5, pos.getY() + 1.0, pos.getZ() + 0.5, remainder);
            }
        }
        burnTicks = burnTime;
        burnTotal = burnTime;
        itemsLit++;
        return true;
    }

    /** Pushes what it holds to its neighbours, a different side first each tick. */
    private void push(ServerLevel level) {
        int before = energy;
        for (int i = 0; i < SIDES.length && energy > 0; i++) {
            IEnergyStorage target = neighbour(level, SIDES[(pushStart + i) % SIDES.length]);
            if (target == null || !target.canReceive()) {
                continue;
            }
            int taken = Math.min(energy, Math.max(0, target.receiveEnergy(energy, false)));
            energy -= taken;
            fePushed += taken;
        }
        pushStart = (pushStart + 1) % SIDES.length;
        if (energy != before) {
            setChanged();
        }
    }

    /** The FE storage on {@code side}, through this side's capability cache. */
    @SuppressWarnings("unchecked")
    private @Nullable IEnergyStorage neighbour(ServerLevel level, Direction side) {
        if (neighbours == null) {
            neighbours = new BlockCapabilityCache[SIDES.length];
            for (Direction direction : SIDES) {
                neighbours[direction.ordinal()] = BlockCapabilityCache.create(
                        Capabilities.EnergyStorage.BLOCK, level, getBlockPos().relative(direction),
                        direction.getOpposite(), () -> !isRemoved(), () -> { });
            }
        }
        return neighbours[side.ordinal()].getCapability();
    }

    /** Furnace fuel: NeoForge's burn-time lookup for smelting, as the furnace asks it. */
    public static int burnTimeOf(ItemStack stack) {
        return stack.isEmpty() ? 0 : stack.getBurnTime(RecipeType.SMELTING);
    }

    public static boolean isFuel(ItemStack stack) {
        return burnTimeOf(stack) > 0;
    }

    // ---- state, for the block's status line and the game tests ------------------------------------

    public ItemStack fuel() {
        return items.get(0);
    }

    public int burnTicks() {
        return burnTicks;
    }

    public int burnTotal() {
        return burnTotal;
    }

    public int energy() {
        return energy;
    }

    public long burnedTicks() {
        return burnedTicks;
    }

    public long itemsLit() {
        return itemsLit;
    }

    public long fePushed() {
        return fePushed;
    }

    /** Its FE capability: extract-only, the output not yet pushed (other mods' cables may pull it). */
    public IEnergyStorage energyStorage() {
        return output;
    }

    /**
     * Puts fuel from a player's hand into the slot (as much as fits). Returns how many items moved;
     * none for anything that is not fuel or does not stack with what is there.
     */
    public int insertFuel(ItemStack stack) {
        if (!isFuel(stack)) {
            return 0;
        }
        ItemStack slot = items.get(0);
        if (!slot.isEmpty() && !ItemStack.isSameItemSameComponents(slot, stack)) {
            return 0;
        }
        int limit = getMaxStackSize(stack);
        int moved = Math.min(stack.getCount(), limit - slot.getCount());
        if (moved <= 0) {
            return 0;
        }
        if (slot.isEmpty()) {
            items.set(0, stack.copyWithCount(moved));
        } else {
            slot.grow(moved);
        }
        setChanged();
        return moved;
    }

    // ---- the slot ---------------------------------------------------------------------------------

    @Override
    public int getContainerSize() {
        return 1;
    }

    @Override
    public boolean isEmpty() {
        return items.get(0).isEmpty();
    }

    @Override
    public ItemStack getItem(int slot) {
        return slot == 0 ? items.get(0) : ItemStack.EMPTY;
    }

    @Override
    public ItemStack removeItem(int slot, int amount) {
        ItemStack removed = ContainerHelper.removeItem(items, slot, amount);
        if (!removed.isEmpty()) {
            setChanged();
        }
        return removed;
    }

    @Override
    public ItemStack removeItemNoUpdate(int slot) {
        return ContainerHelper.takeItem(items, slot);
    }

    @Override
    public void setItem(int slot, ItemStack stack) {
        if (slot != 0) {
            return;
        }
        items.set(0, stack);
        stack.limitSize(getMaxStackSize(stack));
        setChanged();
    }

    /** Fuel only: what a hopper or a pipe may put in. */
    @Override
    public boolean canPlaceItem(int slot, ItemStack stack) {
        return slot == 0 && isFuel(stack);
    }

    @Override
    public boolean stillValid(Player player) {
        return Container.stillValidBlockEntity(this, player);
    }

    @Override
    public void clearContent() {
        items.clear();
    }

    @Override
    public int[] getSlotsForFace(Direction side) {
        return SLOTS;
    }

    @Override
    public boolean canPlaceItemThroughFace(int slot, ItemStack stack, @Nullable Direction side) {
        return canPlaceItem(slot, stack);
    }

    /** From below, and only what fuel left behind (a bucket): fuel stays in, as in a furnace. */
    @Override
    public boolean canTakeItemThroughFace(int slot, ItemStack stack, Direction side) {
        return side == Direction.DOWN && !isFuel(stack);
    }

    // ---- save -------------------------------------------------------------------------------------

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        tag.putInt("DataVersion", DATA_VERSION);
        ContainerHelper.saveAllItems(tag, items, registries);
        tag.putInt("BurnTicks", burnTicks);
        tag.putInt("BurnTotal", burnTotal);
        tag.putInt("Energy", energy);
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        items.clear();
        ContainerHelper.loadAllItems(tag, items, registries);
        burnTicks = Math.max(0, tag.getInt("BurnTicks"));
        burnTotal = Math.max(burnTicks, tag.getInt("BurnTotal"));
        energy = Math.max(0, tag.getInt("Energy"));
    }

    /** The generator's own FE storage: what it made and has not pushed yet. Extract-only. */
    private final class Output implements IEnergyStorage {

        @Override
        public int receiveEnergy(int toReceive, boolean simulate) {
            return 0;
        }

        @Override
        public int extractEnergy(int toExtract, boolean simulate) {
            int given = Math.max(0, Math.min(toExtract, energy));
            if (!simulate && given > 0) {
                energy -= given;
                fePushed += given;
                setChanged();
            }
            return given;
        }

        @Override
        public int getEnergyStored() {
            return energy;
        }

        @Override
        public int getMaxEnergyStored() {
            return Math.max(energy, RanCraftConfig.siteGeneratorFePerTick());
        }

        @Override
        public boolean canExtract() {
            return true;
        }

        @Override
        public boolean canReceive() {
            return false;
        }
    }
}
