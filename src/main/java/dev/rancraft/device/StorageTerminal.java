package dev.rancraft.device;

import dev.rancraft.RanCraftConfig;
import dev.rancraft.menu.RemoteContainerMenu;
import dev.rancraft.registry.ModDataComponents;
import dev.rancraft.rf.BackhaulGraph;
import dev.rancraft.rf.DeviceRequirement;
import dev.rancraft.rf.ServiceLevel;
import dev.rancraft.world.BackhaulNetwork;
import java.util.List;
import java.util.Locale;
import java.util.OptionalInt;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.RandomizableContainer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.entity.BarrelBlockEntity;
import net.minecraft.world.level.block.entity.BaseContainerBlockEntity;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.ChestType;
import net.minecraft.world.level.chunk.LevelChunk;
import org.jetbrains.annotations.Nullable;

/**
 * The Wireless Storage Terminal's server side (Phase 3 slice 13, §3C.3): binding, opening, and the
 * session rule the open menu checks. The item is {@code item.StorageTerminalItem}; the menu is
 * {@link RemoteContainerMenu}.
 *
 * <p><b>What it is.</b> A thin client: it opens a chest or barrel that sits next to a Core Site, over the
 * mobile network. <b>Game abstraction, labelled (NOTES.md, slice 13):</b> the chest stands in for
 * network-attached storage in the data centre, so it must be within {@code fiberRadiusBlocks} of a Core
 * Site, the same implicit-fiber rule the backhaul graph uses for cells and dishes
 * ({@link BackhaulGraph.Topology#onFiber}). It is not "any chest anywhere".
 *
 * <p><b>What it needs from the network:</b> {@link #REQUIREMENT}, GOOD service on a band of capacity
 * tier 2 or higher, from the cell serving the player. That is the verdict the ticker computes once per
 * evaluation with the serving cell's backhaul cap applied (§3C.2), so under a backhaul-limited cell
 * (capped at FAIR) the terminal stops while a Radio Link (POOR) keeps working. <b>Game abstraction,
 * labelled:</b> "GOOD on tier 2" stands in for the throughput a remote file session needs; there is no
 * traffic or throughput model (Phase 4).
 *
 * <p><b>Devices never compute RF.</b> {@link #onSample} keeps the last verdict per player
 * ({@link TerminalLink}, in a {@link DeviceMemory}, forgotten on logout, dimension change and respawn);
 * opening a session and keeping it open both read that record and nothing else.
 *
 * <p><b>Never loads a chunk.</b> The storage is read through {@code getChunkNow}, which returns only a
 * chunk already FULL; a storage whose chunk (or whose other half's chunk, for a double chest) is not
 * loaded is "storage unreachable". <b>Game abstraction, labelled:</b> real network storage is always
 * on; here, keeping the server's cost bounded, an unloaded data centre cannot be reached.
 */
public final class StorageTerminal {

    /** §3C.3: GOOD service, on a band of capacity tier 2 or higher (band_1800, band_3500). */
    public static final DeviceRequirement REQUIREMENT = new DeviceRequirement(ServiceLevel.GOOD, 2);

    /** A double chest. */
    public static final int LARGE_SLOTS = 54;
    /** A single chest or a barrel. */
    public static final int SMALL_SLOTS = 27;

    private static final String KEY = "rancraft.storage_terminal.";

    private static final DeviceMemory<TerminalLink> LINKS = DeviceMemory.create("storage_terminal.link");

    private StorageTerminal() {
    }

    // ---- the device -------------------------------------------------------------------------------

    /**
     * Keeps the verdict of this dispatch. Idempotent on replays by construction: it overwrites one
     * record and counts nothing, so a replay only renews it (with the cap and game time of now).
     */
    public static void onSample(ServerPlayer player, DeviceContext ctx) {
        LINKS.put(player.getUUID(), TerminalLink.of(ctx));
    }

    /** The terminal's last verdict for this player, or {@code null}. */
    public static @Nullable TerminalLink linkOf(UUID player) {
        return LINKS.get(player);
    }

    /** The storage this terminal is bound to, or {@code null}. */
    public static @Nullable GlobalPos targetOf(ItemStack stack) {
        return stack.get(ModDataComponents.STORAGE_TERMINAL_TARGET.get());
    }

    // ---- binding ----------------------------------------------------------------------------------

    /** A chest (a trapped chest too) or a barrel: the containers §3C.3 lets the terminal bind. */
    public static boolean isStorage(@Nullable BlockEntity entity) {
        return entity instanceof ChestBlockEntity || entity instanceof BarrelBlockEntity;
    }

    /**
     * Sneak + use on a block: binds the terminal to it if it is a chest or barrel within
     * {@code fiberRadiusBlocks} of a Core Site (dimension and position, a data component on the stack).
     * Anything else is refused and the binding kept. Server side.
     */
    public static InteractionResult bind(Player player, ItemStack stack, ServerLevel level, BlockPos pos) {
        BlockEntity entity = level.getBlockEntity(pos);
        if (!isStorage(entity)) {
            tell(player, Component.translatable(KEY + "bind_not_storage"));
            return InteractionResult.CONSUME;
        }
        Component name = ((BaseContainerBlockEntity) entity).getDisplayName();
        BlockPos core = nearestCore(level, pos);
        if (core == null) {
            tell(player, Component.translatable(KEY + "bind_no_core", blocks(RanCraftConfig.fiberRadiusBlocks()), name));
            return InteractionResult.CONSUME;
        }
        stack.set(ModDataComponents.STORAGE_TERMINAL_TARGET.get(), GlobalPos.of(level.dimension(), pos.immutable()));
        tell(player, Component.translatable(KEY + "bound", name, xyz(pos),
                blocks(Math.sqrt(horizontalDistanceSq(core, pos)))));
        return InteractionResult.CONSUME;
    }

    // ---- opening ----------------------------------------------------------------------------------

    /**
     * Use: opens the bound storage as a {@link RemoteContainerMenu} if the terminal's last verdict is OK,
     * the storage is in this dimension, near a Core Site, in a FULL chunk and still a 27- or 54-slot
     * chest or barrel. Otherwise tells the player why and opens nothing. Server side.
     *
     * @return whether a menu opened.
     */
    public static boolean open(ServerPlayer player, ItemStack stack) {
        GlobalPos bound = targetOf(stack);
        if (bound == null) {
            return refuse(player, problem("not_bound"));
        }
        ServerLevel level = player.serverLevel();
        if (!bound.dimension().equals(level.dimension())) {
            return refuse(player, problem("other_dimension", bound.dimension().location().toString()));
        }
        Component link = linkProblem(player.getUUID(), level.getGameTime());
        if (link != null) {
            return refuse(player, link);
        }
        BlockPos pos = bound.pos();
        BlockPos core = nearestCore(level, pos);
        if (core == null) {
            return refuse(player, noCore(pos));
        }
        Resolved resolved = resolve(level, pos);
        if (resolved.problem() != null) {
            return refuse(player, resolved.problem());
        }
        Storage storage = resolved.storage();
        // A locked container needs its key, as vanilla's own chest asks (canOpen tells the player).
        for (BaseContainerBlockEntity entity : storage.entities()) {
            if (!entity.canOpen(player)) {
                return false;
            }
        }
        for (BaseContainerBlockEntity entity : storage.entities()) {
            if (entity instanceof RandomizableContainer loot) {
                loot.unpackLootTable(player);
            }
        }
        Component title = Component.translatable("container.rancraft.storage_terminal", storage.name());
        OptionalInt opened = player.openMenu(new SimpleMenuProvider(
                (containerId, inventory, owner) -> new RemoteContainerMenu(containerId, inventory, storage.container(),
                        storage.rows(), level, pos, core, storage.entities()),
                title));
        return opened.isPresent();
    }

    /** The container behind a bound position, with the block entities that make it up. */
    public record Storage(Container container, List<BaseContainerBlockEntity> entities, int rows, Component name) {
    }

    /** {@link #storage} when the storage can be reached; otherwise {@link #problem}, as the player is told it. */
    public record Resolved(@Nullable Storage storage, @Nullable Component problem) {
    }

    /**
     * The storage at {@code pos}, read without loading a chunk: {@code getChunkNow} for its chunk and,
     * for a double chest, for its other half's chunk too. Only once both are FULL does vanilla's own
     * pairing rule ({@link ChestBlock#getContainer}, which reads the neighbour through the level) run, so
     * it never loads one either. A double chest whose other half is not a matching chest is a single
     * chest, as vanilla treats it. The lid is ignored ({@code ignoreBlocked}): nothing is lifted.
     */
    public static Resolved resolve(ServerLevel level, BlockPos pos) {
        LevelChunk chunk = level.getChunkSource().getChunkNow(pos.getX() >> 4, pos.getZ() >> 4);
        if (chunk == null) {
            return new Resolved(null, unloaded(pos));
        }
        BlockEntity entity = chunk.getBlockEntity(pos);
        if (!isStorage(entity)) {
            return new Resolved(null, notStorage(pos));
        }
        BaseContainerBlockEntity first = (BaseContainerBlockEntity) entity;
        Container container = first;
        List<BaseContainerBlockEntity> entities = List.of(first);
        Component name = first.getDisplayName();

        BlockState state = chunk.getBlockState(pos);
        if (entity instanceof ChestBlockEntity && state.getBlock() instanceof ChestBlock chestBlock
                && state.hasProperty(ChestBlock.TYPE) && state.getValue(ChestBlock.TYPE) != ChestType.SINGLE) {
            BlockPos otherPos = pos.relative(ChestBlock.getConnectedDirection(state));
            LevelChunk otherChunk = level.getChunkSource().getChunkNow(otherPos.getX() >> 4, otherPos.getZ() >> 4);
            if (otherChunk == null) {
                return new Resolved(null, unloaded(otherPos));
            }
            Container combined = ChestBlock.getContainer(chestBlock, state, level, pos, true);
            if (combined != null && combined.getContainerSize() == LARGE_SLOTS
                    && otherChunk.getBlockEntity(otherPos) instanceof ChestBlockEntity other) {
                container = combined;
                entities = List.of(first, other);
                name = first.hasCustomName() ? first.getDisplayName()
                        : other.hasCustomName() ? other.getDisplayName()
                        : Component.translatable("container.chestDouble");
            }
        }
        int rows = rowsFor(container.getContainerSize());
        if (rows == 0) {
            return new Resolved(null, notStorage(pos));
        }
        return new Resolved(new Storage(container, entities, rows, name), null);
    }

    // ---- the rules --------------------------------------------------------------------------------

    /** Chest-menu rows for a container of this size: 3 for 27 slots, 6 for 54, 0 for anything else. */
    public static int rowsFor(int containerSize) {
        return switch (containerSize) {
            case SMALL_SLOTS -> 3;
            case LARGE_SLOTS -> 6;
            default -> 0;
        };
    }

    /**
     * The nearest of {@code cores} (packed positions) on fiber from {@code target}: within
     * {@code radius} horizontally, inclusive, height ignored, by the backhaul graph's own fiber rule. Ties
     * go to the first listed. {@code null} when none is.
     */
    public static @Nullable BlockPos nearestCore(long[] cores, BlockPos target, double radius) {
        BackhaulGraph.Topology fiber = new BackhaulGraph.Topology(radius, 0.0);
        BackhaulGraph.Node storage = node(target);
        BlockPos best = null;
        double bestSq = Double.POSITIVE_INFINITY;
        for (long packed : cores) {
            BlockPos core = BlockPos.of(packed);
            if (!fiber.onFiber(storage, node(core))) {
                continue;
            }
            double sq = horizontalDistanceSq(core, target);
            if (sq < bestSq) {
                bestSq = sq;
                best = core;
            }
        }
        return best;
    }

    /**
     * The nearest Core Site of this dimension within {@code fiberRadiusBlocks} of {@code target}, loaded or
     * not (the dimension's backhaul network keeps every core). Creates no network where there is none.
     */
    public static @Nullable BlockPos nearestCore(ServerLevel level, BlockPos target) {
        BackhaulNetwork network = BackhaulNetwork.peek(level);
        return network == null ? null : nearestCore(network.cores(), target, RanCraftConfig.fiberRadiusBlocks());
    }

    /** Whether {@code core} is still a Core Site of this dimension and still within reach of {@code target}. */
    public static boolean coreServes(ServerLevel level, BlockPos core, BlockPos target) {
        BackhaulNetwork network = BackhaulNetwork.peek(level);
        return network != null && network.isCore(core)
                && new BackhaulGraph.Topology(RanCraftConfig.fiberRadiusBlocks(), 0.0).onFiber(node(target), node(core));
    }

    private static BackhaulGraph.Node node(BlockPos pos) {
        return new BackhaulGraph.Node(pos.asLong(), pos.getX(), pos.getZ());
    }

    private static double horizontalDistanceSq(BlockPos a, BlockPos b) {
        double dx = (double) a.getX() - b.getX();
        double dz = (double) a.getZ() - b.getZ();
        return dx * dx + dz * dz;
    }

    // ---- what the player is told ------------------------------------------------------------------

    /**
     * Why the terminal cannot carry a session for this player now, from its last verdict
     * ({@link TerminalLink#problemOf}), or {@code null} when it can.
     */
    public static @Nullable Component linkProblem(UUID player, long now) {
        TerminalLink link = LINKS.get(player);
        TerminalLink.Problem problem = TerminalLink.problemOf(link, REQUIREMENT, now,
                RanCraftConfig.evaluationIntervalTicks());
        return problem == null ? null : describe(problem, link);
    }

    /** The player's words for a {@link TerminalLink.Problem}; {@code link} may be null only for NO_READING. */
    public static Component describe(TerminalLink.Problem problem, @Nullable TerminalLink link) {
        String needed = REQUIREMENT.minServiceLevel().label();
        return switch (problem) {
            case NO_READING -> problem("no_reading");
            case NO_SERVICE -> problem("no_service");
            case WEAK_SIGNAL -> problem("weak_signal", link.radio().label(), needed);
            case BACKHAUL_LIMITED -> problem("backhaul_limited", link.serviceCap().label(), needed);
            case LOW_TIER -> problem("low_tier", REQUIREMENT.minCapacityTier(), String.valueOf(link.servingBandId()),
                    link.servingBandTier());
        };
    }

    /** The translation key of a problem's text: {@code rancraft.storage_terminal.problem.<name>}. */
    public static String problemKey(String name) {
        return KEY + "problem." + name;
    }

    private static Component problem(String name, Object... args) {
        return Component.translatable(problemKey(name), args);
    }

    public static Component otherDimension(ServerLevel storageLevel) {
        return problem("other_dimension", storageLevel.dimension().location().toString());
    }

    public static Component noCore(BlockPos storage) {
        return problem("no_core", blocks(RanCraftConfig.fiberRadiusBlocks()), xyz(storage));
    }

    public static Component unloaded(BlockPos pos) {
        return problem("unloaded", xyz(pos));
    }

    public static Component notStorage(BlockPos pos) {
        return problem("not_storage", xyz(pos));
    }

    /** "Storage Terminal: connection lost (...)", when a session ends. */
    public static Component connectionLost(Component why) {
        return Component.translatable(KEY + "lost", why);
    }

    private static boolean refuse(Player player, Component why) {
        tell(player, Component.translatable(KEY + "refused", why));
        return false;
    }

    private static void tell(Player player, Component message) {
        player.displayClientMessage(message, true);
    }

    private static String xyz(BlockPos pos) {
        return pos.getX() + ", " + pos.getY() + ", " + pos.getZ();
    }

    private static String blocks(double value) {
        return String.format(Locale.ROOT, "%.0f", value);
    }
}
