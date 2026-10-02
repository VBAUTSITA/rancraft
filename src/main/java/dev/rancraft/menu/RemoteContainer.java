package dev.rancraft.menu;

import java.util.Objects;
import net.minecraft.world.Container;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

/**
 * A chest or barrel seen through the Storage Terminal (Phase 3 slice 13, §3C.3): every slot, stack
 * limit and change goes straight to the real container, so hoppers, comparators and a player standing
 * at the chest all see the same items.
 *
 * <p>Three things are deliberately <em>not</em> passed on:
 * <ul>
 *   <li>{@link #startOpen} and {@link #stopOpen}: a remote session does not lift the lid. They would
 *       also break the chest. Vanilla's {@code ContainerOpenersCounter} counts openers by looking for
 *       players within a few blocks whose menu holds this chest; five ticks after a remote open it would
 *       find none and reset the count to 0, and the remote close would then take it to −1, after which
 *       the next player to open the chest by hand would not open the lid (checked in the 1.21.1
 *       sources). So no lid, no sound, no {@code CONTAINER_OPEN} game event for a sculk sensor, and no
 *       trapped-chest signal: the storage is read over the network, not opened.</li>
 *   <li>{@link #stillValid}: the real container's check is "within 4 blocks of the player", which a
 *       remote session never is. {@link RemoteContainerMenu#stillValid} decides instead.</li>
 * </ul>
 */
final class RemoteContainer implements Container {

    private final Container storage;

    RemoteContainer(Container storage) {
        this.storage = Objects.requireNonNull(storage, "storage");
    }

    /** The real container behind the session. */
    Container storage() {
        return storage;
    }

    @Override
    public int getContainerSize() {
        return storage.getContainerSize();
    }

    @Override
    public boolean isEmpty() {
        return storage.isEmpty();
    }

    @Override
    public ItemStack getItem(int slot) {
        return storage.getItem(slot);
    }

    @Override
    public ItemStack removeItem(int slot, int amount) {
        return storage.removeItem(slot, amount);
    }

    @Override
    public ItemStack removeItemNoUpdate(int slot) {
        return storage.removeItemNoUpdate(slot);
    }

    @Override
    public void setItem(int slot, ItemStack stack) {
        storage.setItem(slot, stack);
    }

    @Override
    public int getMaxStackSize() {
        return storage.getMaxStackSize();
    }

    @Override
    public int getMaxStackSize(ItemStack stack) {
        return storage.getMaxStackSize(stack);
    }

    @Override
    public void setChanged() {
        storage.setChanged();
    }

    /** Always true: the menu judges the session ({@link RemoteContainerMenu#stillValid}). */
    @Override
    public boolean stillValid(Player player) {
        return true;
    }

    /** Not passed on: a remote session does not open the lid (see the class comment). */
    @Override
    public void startOpen(Player player) {
    }

    /** Not passed on, as {@link #startOpen}. */
    @Override
    public void stopOpen(Player player) {
    }

    @Override
    public boolean canPlaceItem(int slot, ItemStack stack) {
        return storage.canPlaceItem(slot, stack);
    }

    @Override
    public boolean canTakeItem(Container target, int slot, ItemStack stack) {
        return storage.canTakeItem(target, slot, stack);
    }

    @Override
    public void clearContent() {
        storage.clearContent();
    }
}
