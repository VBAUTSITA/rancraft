package dev.rancraft.menu;

import dev.rancraft.device.StorageTerminal;
import java.util.List;
import java.util.Objects;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.level.block.entity.BaseContainerBlockEntity;
import org.jetbrains.annotations.Nullable;

/**
 * A chest or barrel opened from the Wireless Storage Terminal (Phase 3 slice 13, §3C.3): vanilla's
 * {@link ChestMenu} over the real container, with a session rule in place of "within reach".
 *
 * <p><b>Server only.</b> It uses vanilla's {@link MenuType#GENERIC_9x3} and {@link MenuType#GENERIC_9x6},
 * so the client opens its ordinary chest screen with an ordinary {@code ChestMenu}; the slot layout is
 * the same, and nothing new is registered or sent. The client decides nothing about the session.
 *
 * <p><b>{@link #stillValid} (run by {@code ServerPlayer.tick} every tick, which closes the menu when it
 * fails)</b> holds while all of these hold:
 * <ol>
 *   <li>the player is still in the storage's dimension;</li>
 *   <li>the terminal's <b>last verdict</b> for this player is OK and at most two evaluation intervals old
 *       ({@code device.TerminalLink}). That is the one the ticker computed for the terminal it carries;
 *       nothing is evaluated here, every tick or otherwise (§3C.3). It is the backhaul-capped verdict, so
 *       a cell turning backhaul-limited ends the session just as a fading signal does;</li>
 *   <li>a Core Site is still within {@code fiberRadiusBlocks} of the storage (network-attached storage
 *       in the data centre);</li>
 *   <li>every block entity behind the container is still there and its chunk still FULL (never loaded
 *       here: a chunk that leaves FULL ends the session instead).</li>
 * </ol>
 * The first one to fail is kept and told to the player when the menu closes: "connection lost (signal
 * too weak: FAIR, needs GOOD)". That is the lesson of §3C.3: losing service closes the menu, the way a
 * download stops.
 */
public class RemoteContainerMenu extends ChestMenu {

    private final ServerLevel level;
    private final BlockPos target;
    private final List<BaseContainerBlockEntity> storage;
    private BlockPos core;
    private @Nullable Component lost;

    /**
     * @param container the real container: the chest, the barrel, or vanilla's {@code CompoundContainer}
     *                  of a double chest. 27 or 54 slots.
     * @param rows      3 or 6, matching the container.
     * @param target    the position the terminal is bound to (one half of a double chest).
     * @param core      the Core Site found within {@code fiberRadiusBlocks} of {@code target} at open.
     * @param storage   every block entity behind {@code container} (two for a double chest).
     */
    public RemoteContainerMenu(int containerId, Inventory inventory, Container container, int rows,
                               ServerLevel level, BlockPos target, BlockPos core,
                               List<? extends BaseContainerBlockEntity> storage) {
        super(menuType(rows), containerId, inventory, new RemoteContainer(container), rows);
        this.level = Objects.requireNonNull(level, "level");
        this.target = target.immutable();
        this.core = core.immutable();
        this.storage = List.copyOf(storage);
        if (this.storage.isEmpty()) {
            throw new IllegalArgumentException("a remote container needs the block entity behind it");
        }
    }

    private static MenuType<ChestMenu> menuType(int rows) {
        return switch (rows) {
            case 3 -> MenuType.GENERIC_9x3;
            case 6 -> MenuType.GENERIC_9x6;
            default -> throw new IllegalArgumentException("a remote container has 3 or 6 rows, not " + rows);
        };
    }

    /** The real container behind the session (not the remote view the slots use). */
    public Container storage() {
        return ((RemoteContainer) getContainer()).storage();
    }

    public BlockPos target() {
        return target;
    }

    /** The Core Site the session goes through now (another one within reach takes over if it goes). */
    public BlockPos core() {
        return core;
    }

    /** Why the session ended, once {@link #stillValid} has failed; {@code null} while it holds. */
    public @Nullable Component lost() {
        return lost;
    }

    @Override
    public boolean stillValid(Player player) {
        if (lost == null) {
            lost = check(player);
        }
        return lost == null;
    }

    /** The first rule of the session that fails, as the player is told it; {@code null} when all hold. */
    private @Nullable Component check(Player player) {
        if (player.level() != level) {
            return StorageTerminal.otherDimension(level);
        }
        Component link = StorageTerminal.linkProblem(player.getUUID(), level.getGameTime());
        if (link != null) {
            return link;
        }
        if (!StorageTerminal.coreServes(level, core, target)) {
            BlockPos other = StorageTerminal.nearestCore(level, target);
            if (other == null) {
                return StorageTerminal.noCore(target);
            }
            core = other;
        }
        for (BaseContainerBlockEntity entity : storage) {
            BlockPos pos = entity.getBlockPos();
            if (level.getChunkSource().getChunkNow(pos.getX() >> 4, pos.getZ() >> 4) == null) {
                return StorageTerminal.unloaded(pos);
            }
            if (entity.isRemoved()) {
                return StorageTerminal.notStorage(pos);
            }
        }
        return null;
    }

    @Override
    public void removed(Player player) {
        super.removed(player);
        if (lost != null && player instanceof ServerPlayer serverPlayer) {
            serverPlayer.displayClientMessage(StorageTerminal.connectionLost(lost), true);
        }
    }
}
