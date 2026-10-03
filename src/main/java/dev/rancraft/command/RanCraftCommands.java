package dev.rancraft.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import dev.rancraft.RanCraft;
import dev.rancraft.RanCraftConfig;
import dev.rancraft.block.AntennaBlockEntity;
import dev.rancraft.block.SectorAntennaBlockEntity;
import dev.rancraft.rf.BackhaulGraph.BackhaulState;
import dev.rancraft.rf.CellParams;
import dev.rancraft.rf.PciConflict;
import dev.rancraft.rf.PciPlanner;
import dev.rancraft.rf.PowerModel;
import dev.rancraft.world.BackhaulNetwork;
import dev.rancraft.world.SitePower;
import dev.rancraft.world.SiteRegistry;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

/**
 * {@code /rancraft pci check [radius]} -- lists every PCI collision, confusion and mod-3 conflict
 * among the sites near the command source.
 *
 * <p>A thin wrapper over {@link PciPlanner}, which is pure and unit-tested. Nothing here decides
 * what a conflict is; it only formats.
 *
 * <p>{@code /rancraft backhaul status [radius]} (Phase 3 slice 12, §3C.2) -- lists the cells near the
 * source that are off the air for want of backhaul, the LIMITED cells, and every microwave link near
 * it with its RSL, margin, Fresnel state and rain loss, as the server's backhaul network last worked
 * them out ({@link BackhaulNetwork}). It formats; it judges nothing.
 *
 * <p>{@code /rancraft power status [radius]} (Phase 3 slice 15, §3C.5) -- lists the loaded cells near
 * the source with their draw (FE/t), their buffer, whether power keeps them on or off the air, and
 * how many receivers each served in the served-receivers window (the Phase 4 seam), as
 * {@link SitePower} keeps them. It formats; it judges nothing.
 */
@EventBusSubscriber(modid = RanCraft.MOD_ID)
public final class RanCraftCommands {

    private RanCraftCommands() {
    }

    private static final double DEFAULT_RADIUS_BLOCKS = 500.0;
    private static final double MIN_RADIUS = 1.0;
    private static final double MAX_RADIUS = 8192.0;

    /** Enough to be useful in chat without flooding it; the total is always reported. */
    private static final int MAX_LINES = 20;

    @SubscribeEvent
    public static void onRegisterCommands(RegisterCommandsEvent event) {
        register(event.getDispatcher());
    }

    private static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        LiteralArgumentBuilder<CommandSourceStack> pciCheck = Commands.literal("check")
                .executes(context -> checkPci(context, DEFAULT_RADIUS_BLOCKS))
                .then(Commands.argument("radius", DoubleArgumentType.doubleArg(MIN_RADIUS, MAX_RADIUS))
                        .executes(context -> checkPci(
                                context, DoubleArgumentType.getDouble(context, "radius"))));

        LiteralArgumentBuilder<CommandSourceStack> backhaulStatus = Commands.literal("status")
                .executes(context -> backhaulStatus(context, DEFAULT_RADIUS_BLOCKS))
                .then(Commands.argument("radius", DoubleArgumentType.doubleArg(MIN_RADIUS, MAX_RADIUS))
                        .executes(context -> backhaulStatus(
                                context, DoubleArgumentType.getDouble(context, "radius"))));

        LiteralArgumentBuilder<CommandSourceStack> powerStatus = Commands.literal("status")
                .executes(context -> powerStatus(context, DEFAULT_RADIUS_BLOCKS))
                .then(Commands.argument("radius", DoubleArgumentType.doubleArg(MIN_RADIUS, MAX_RADIUS))
                        .executes(context -> powerStatus(
                                context, DoubleArgumentType.getDouble(context, "radius"))));

        dispatcher.register(Commands.literal("rancraft")
                .requires(source -> source.hasPermission(2))
                .then(Commands.literal("pci").then(pciCheck))
                .then(Commands.literal("backhaul").then(backhaulStatus))
                .then(Commands.literal("power").then(powerStatus)));
    }

    private static int checkPci(CommandContext<CommandSourceStack> context, double radius) {
        CommandSourceStack source = context.getSource();
        ServerLevel level = source.getLevel();
        Vec3 origin = source.getPosition();

        Collection<CellParams> nearby = SiteRegistry.of(level)
                .near(origin.x, origin.y, origin.z, radius);

        List<PciConflict> conflicts =
                PciPlanner.findConflicts(nearby, RanCraftConfig.snapshot().pciParams());

        if (conflicts.isEmpty()) {
            source.sendSuccess(() -> Component
                    .translatable("commands.rancraft.pci.none", format(radius))
                    .withStyle(ChatFormatting.GREEN), false);
            return nearby.size();
        }

        // Errors first, so a collision is never buried under a page of mod-3 warnings.
        conflicts.sort(Comparator
                .comparingInt((PciConflict conflict) -> conflict.severity().ordinal())
                .thenComparingDouble(PciConflict::separationBlocks));

        int total = conflicts.size();
        source.sendSuccess(() -> Component
                .translatable("commands.rancraft.pci.header", total, format(radius))
                .withStyle(ChatFormatting.YELLOW), false);

        for (PciConflict conflict : conflicts.subList(0, Math.min(total, MAX_LINES))) {
            ChatFormatting colour = conflict.severity() == PciConflict.Severity.ERROR
                    ? ChatFormatting.RED
                    : ChatFormatting.GOLD;
            String line = String.format("  [%d, %d, %d] %s",
                    conflict.ax(), conflict.ay(), conflict.az(), conflict.describe());
            source.sendSuccess(() -> Component.literal(line).withStyle(colour), false);
        }

        more(source, total);
        return total;
    }

    private static String format(double radius) {
        return String.valueOf((long) radius);
    }

    // ---- /rancraft backhaul status ----------------------------------------------------------------

    /**
     * The header (the flag, counts, the weather and the age of the measurement), then three lists, each
     * nearest first and capped at {@value #MAX_LINES} lines: cells with no backhaul (off the air when
     * {@code requireBackhaul} is on), LIMITED cells, and the links. Cells are filtered by their base,
     * links by their nearest point to the source. Returns the number of entries listed.
     */
    private static int backhaulStatus(CommandContext<CommandSourceStack> context, double radius) {
        CommandSourceStack source = context.getSource();
        ServerLevel level = source.getLevel();
        Vec3 origin = source.getPosition();
        BackhaulNetwork network = BackhaulNetwork.peek(level);
        if (network == null || !network.solved()) {
            source.sendSuccess(() -> Component.translatable("commands.rancraft.backhaul.not_solved")
                    .withStyle(ChatFormatting.GRAY), false);
            return 0;
        }
        boolean required = RanCraftConfig.requireBackhaul();
        double radiusSq = radius * radius;

        List<BackhaulNetwork.CellStatus> none = new ArrayList<>();
        List<BackhaulNetwork.CellStatus> limited = new ArrayList<>();
        for (BackhaulNetwork.CellStatus cell : network.cells()) {
            if (cell.base().distToCenterSqr(origin) > radiusSq) {
                continue;
            }
            if (cell.state() == BackhaulState.NONE) {
                none.add(cell);
            } else if (cell.state() == BackhaulState.LIMITED) {
                limited.add(cell);
            }
        }
        Comparator<BackhaulNetwork.CellStatus> nearest =
                Comparator.comparingDouble(cell -> cell.base().distToCenterSqr(origin));
        none.sort(nearest);
        limited.sort(nearest);

        List<BackhaulNetwork.HopStatus> allLinks = network.hops();
        List<BackhaulNetwork.HopStatus> links = new ArrayList<>();
        for (BackhaulNetwork.HopStatus hop : allLinks) {
            if (BackhaulNetwork.distanceSqToHop(origin.x, origin.y, origin.z, hop) <= radiusSq) {
                links.add(hop);
            }
        }
        links.sort(Comparator.comparingDouble(
                hop -> BackhaulNetwork.distanceSqToHop(origin.x, origin.y, origin.z, hop)));

        String weather = level.isThundering() ? "thunder" : level.isRaining() ? "rain" : "clear";
        long age = Math.max(0L, level.getGameTime() - network.lastRecomputeTick());
        int linkCount = allLinks.size();
        source.sendSuccess(() -> Component.translatable("commands.rancraft.backhaul.header",
                format(radius), required ? "ON" : "OFF", network.coreCount(), network.dishCount(),
                linkCount, weather, age).withStyle(ChatFormatting.YELLOW), false);

        source.sendSuccess(() -> Component.translatable(
                required ? "commands.rancraft.backhaul.off_air" : "commands.rancraft.backhaul.no_backhaul",
                none.size()).withStyle(ChatFormatting.WHITE), false);
        cellLines(source, level, none, ChatFormatting.RED);

        source.sendSuccess(() -> Component.translatable(
                required ? "commands.rancraft.backhaul.limited" : "commands.rancraft.backhaul.limited_off",
                limited.size()).withStyle(ChatFormatting.WHITE), false);
        cellLines(source, level, limited, ChatFormatting.GOLD);

        source.sendSuccess(() -> Component.translatable("commands.rancraft.backhaul.links", links.size())
                .withStyle(ChatFormatting.WHITE), false);
        if (links.isEmpty()) {
            source.sendSuccess(() -> Component.translatable("commands.rancraft.backhaul.none")
                    .withStyle(ChatFormatting.GRAY), false);
        }
        for (BackhaulNetwork.HopStatus hop : links.subList(0, Math.min(links.size(), MAX_LINES))) {
            ChatFormatting colour = switch (hop.budget().state()) {
                case UP -> ChatFormatting.GREEN;
                case DEGRADED -> ChatFormatting.GOLD;
                case DOWN -> ChatFormatting.RED;
            };
            String line = "  " + xyz(hop.a()) + " <-> " + xyz(hop.b()) + ": " + hop.budget().describe()
                    + ", weather " + hop.weather().name().toLowerCase(Locale.ROOT);
            source.sendSuccess(() -> Component.literal(line).withStyle(colour), false);
        }
        more(source, links.size());
        return none.size() + limited.size() + links.size();
    }

    /** One line per cell, nearest first, with "(unloaded)" for a cell whose chunk is not loaded. */
    private static void cellLines(CommandSourceStack source, ServerLevel level,
                                  List<BackhaulNetwork.CellStatus> cells, ChatFormatting colour) {
        if (cells.isEmpty()) {
            source.sendSuccess(() -> Component.translatable("commands.rancraft.backhaul.none")
                    .withStyle(ChatFormatting.GRAY), false);
            return;
        }
        for (BackhaulNetwork.CellStatus cell : cells.subList(0, Math.min(cells.size(), MAX_LINES))) {
            BlockPos base = cell.base();
            boolean loaded = level.getChunkSource().getChunkNow(
                    SectionPos.blockToSectionCoord(base.getX()), SectionPos.blockToSectionCoord(base.getZ())) != null;
            String line = "  cell at " + xyz(base) + (loaded ? "" : " (unloaded)");
            source.sendSuccess(() -> Component.literal(line).withStyle(colour), false);
        }
        more(source, cells.size());
    }

    // ---- /rancraft power status -------------------------------------------------------------------

    /**
     * The header (the flag, the buffer, the restart level, the count and the served window), then one
     * line per loaded cell within the radius, nearest first, capped at {@value #MAX_LINES}: kind, Tx
     * power, draw, buffer, state and receivers served in the window. Returns the number of cells.
     */
    private static int powerStatus(CommandContext<CommandSourceStack> context, double radius) {
        CommandSourceStack source = context.getSource();
        ServerLevel level = source.getLevel();
        Vec3 origin = source.getPosition();
        double radiusSq = radius * radius;
        SitePower power = SitePower.of(level);
        boolean required = RanCraftConfig.requirePower();
        int capacity = RanCraftConfig.powerBufferFe();
        double restartFraction = RanCraftConfig.powerRestartFraction();
        PowerModel model = RanCraftConfig.powerModel();
        long now = level.getGameTime();

        List<AntennaBlockEntity> cells = new ArrayList<>();
        for (AntennaBlockEntity cell : power.cells()) {
            if (!cell.isRemoved() && cell.getBlockPos().distToCenterSqr(origin) <= radiusSq) {
                cells.add(cell);
            }
        }
        cells.sort(Comparator.comparingDouble(cell -> cell.getBlockPos().distToCenterSqr(origin)));

        long windowMinutes = RanCraftConfig.servedWindowTicks() / (60L * 20L);
        int count = cells.size();
        source.sendSuccess(() -> Component.translatable("commands.rancraft.power.header",
                format(radius), required ? "ON" : "OFF", capacity, Math.round(restartFraction * 100.0),
                count, windowMinutes).withStyle(ChatFormatting.YELLOW), false);
        if (cells.isEmpty()) {
            source.sendSuccess(() -> Component.translatable("commands.rancraft.power.none")
                    .withStyle(ChatFormatting.GRAY), false);
            return 0;
        }
        for (AntennaBlockEntity cell : cells.subList(0, Math.min(count, MAX_LINES))) {
            int stored = cell.energyBuffer().stored();
            String state;
            ChatFormatting colour;
            if (!cell.onAir()) {
                boolean outOfEnergy = required && !cell.energyBuffer().on();
                state = outOfEnergy
                        ? "OFF: out of energy (back above " + (long) Math.floor(restartFraction * capacity) + " FE)"
                        : "off the air (not for power)";
                colour = outOfEnergy ? ChatFormatting.RED : ChatFormatting.GRAY;
            } else {
                state = required ? "on the air" : "on the air (requirePower is off: no draw)";
                colour = ChatFormatting.GREEN;
            }
            String line = String.format(Locale.ROOT, "  %s %s %.0f dBm: %.2f FE/t, %d/%d FE (%d%%), %s, served %d",
                    xyz(cell.getBlockPos()), cell instanceof SectorAntennaBlockEntity ? "sector" : "mast",
                    cell.txPowerDbm(), cell.fePerTick(model), stored, capacity,
                    Math.round(100.0 * stored / capacity), state, power.servedCount(cell.cellId(), now));
            source.sendSuccess(() -> Component.literal(line).withStyle(colour), false);
        }
        more(source, count);
        return count;
    }

    private static void more(CommandSourceStack source, int total) {
        if (total > MAX_LINES) {
            int hidden = total - MAX_LINES;
            source.sendSuccess(() -> Component
                    .literal("  ... and " + hidden + " more")
                    .withStyle(ChatFormatting.GRAY), false);
        }
    }

    private static String xyz(BlockPos pos) {
        return "[" + pos.getX() + ", " + pos.getY() + ", " + pos.getZ() + "]";
    }
}
