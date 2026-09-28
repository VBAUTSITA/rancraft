package dev.rancraft.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import dev.rancraft.RanCraft;
import dev.rancraft.RanCraftConfig;
import dev.rancraft.rf.CellParams;
import dev.rancraft.rf.PciConflict;
import dev.rancraft.rf.PciPlanner;
import dev.rancraft.world.SiteRegistry;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
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

        dispatcher.register(Commands.literal("rancraft")
                .requires(source -> source.hasPermission(2))
                .then(Commands.literal("pci").then(pciCheck)));
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

        if (total > MAX_LINES) {
            int hidden = total - MAX_LINES;
            source.sendSuccess(() -> Component
                    .literal("  ... and " + hidden + " more")
                    .withStyle(ChatFormatting.GRAY), false);
        }

        return total;
    }

    private static String format(double radius) {
        return String.valueOf((long) radius);
    }
}
