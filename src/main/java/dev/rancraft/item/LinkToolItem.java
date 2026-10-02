package dev.rancraft.item;

import dev.rancraft.RanCraftConfig;
import dev.rancraft.block.BackhaulDishBlockEntity;
import dev.rancraft.registry.ModDataComponents;
import dev.rancraft.world.BackhaulNetwork;
import java.util.List;
import java.util.Locale;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;

/**
 * The Link Tool (Phase 3 slice 12, §3C.2): use it on one Backhaul Dish, then on another, to pair them
 * into a microwave hop. Both dishes store each other's position. Sneak + use on a dish unpairs it.
 *
 * <p><b>Game abstraction, labelled (§6, "automatic dish alignment"):</b> pairing is the whole job. Both
 * dishes are then taken as perfectly aimed at each other ({@code BackhaulDishBlockEntity}); a real crew
 * aligns the two by hand, a fraction of a degree at a time, watching the received level.
 *
 * <p>The first dish is kept on the tool (a data component: dimension and position) until the second
 * use, so the far dish may be a kilometre away and the first one's chunk unloaded by then: the pairing
 * is the dimension's backhaul network's record ({@link BackhaulNetwork#pair}). The two must be in one
 * dimension, and no further apart than {@code maxEvaluationRangeBlocks}, the range the server marches
 * any radio path over.
 *
 * <p>The server pairs; the client only swings the hand when the clicked block is a dish.
 */
public class LinkToolItem extends Item {

    public LinkToolItem(Properties properties) {
        super(properties);
    }

    @Override
    public InteractionResult useOn(UseOnContext context) {
        Level level = context.getLevel();
        BlockPos pos = context.getClickedPos();
        if (!(level.getBlockEntity(pos) instanceof BackhaulDishBlockEntity)) {
            return InteractionResult.PASS;
        }
        if (!(level instanceof ServerLevel serverLevel)) {
            return InteractionResult.SUCCESS;
        }
        Player player = context.getPlayer();
        ItemStack stack = context.getItemInHand();
        BackhaulNetwork network = BackhaulNetwork.of(serverLevel);

        if (player != null && player.isSecondaryUseActive()) {
            boolean unpaired = network.unpair(serverLevel, pos);
            tell(player, Component.translatable(unpaired ? "rancraft.link_tool.unpaired" : "rancraft.link_tool.not_paired"));
            return InteractionResult.CONSUME;
        }

        GlobalPos here = GlobalPos.of(serverLevel.dimension(), pos.immutable());
        GlobalPos source = stack.get(ModDataComponents.LINK_TOOL_SOURCE.get());
        if (source == null) {
            stack.set(ModDataComponents.LINK_TOOL_SOURCE.get(), here);
            tell(player, Component.translatable("rancraft.link_tool.selected", xyz(pos)));
            return InteractionResult.CONSUME;
        }
        if (!source.dimension().equals(serverLevel.dimension())) {
            stack.set(ModDataComponents.LINK_TOOL_SOURCE.get(), here);
            tell(player, Component.translatable("rancraft.link_tool.other_dimension", xyz(pos)));
            return InteractionResult.CONSUME;
        }
        BlockPos first = source.pos();
        if (first.equals(pos)) {
            tell(player, Component.translatable("rancraft.link_tool.same"));
            return InteractionResult.CONSUME;
        }
        if (!network.isDish(first)) {
            stack.set(ModDataComponents.LINK_TOOL_SOURCE.get(), here);
            tell(player, Component.translatable("rancraft.link_tool.gone", xyz(first), xyz(pos)));
            return InteractionResult.CONSUME;
        }
        double length = Math.sqrt(BackhaulNetwork.distanceSq(first.asLong(), pos.asLong()));
        double limit = RanCraftConfig.snapshot().maxEvaluationRangeBlocks();
        if (length > limit) {
            tell(player, Component.translatable("rancraft.link_tool.too_far",
                    format(length), format(limit)));
            return InteractionResult.CONSUME;
        }
        network.pair(serverLevel, first, pos);
        stack.remove(ModDataComponents.LINK_TOOL_SOURCE.get());
        double meters = length * RanCraftConfig.snapshot().metersPerBlock();
        tell(player, Component.translatable("rancraft.link_tool.paired", xyz(first), xyz(pos), format(meters),
                String.valueOf(Math.max(1, RanCraftConfig.backhaulRecomputeTicks() / 20))));
        return InteractionResult.CONSUME;
    }

    private static void tell(Player player, Component message) {
        if (player != null) {
            player.displayClientMessage(message, true);
        }
    }

    private static String xyz(BlockPos pos) {
        return pos.getX() + ", " + pos.getY() + ", " + pos.getZ();
    }

    private static String format(double value) {
        return String.format(Locale.ROOT, "%.0f", value);
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> tooltip, TooltipFlag flag) {
        super.appendHoverText(stack, context, tooltip, flag);
        tooltip.add(Component.translatable("item.rancraft.link_tool.tooltip.use").withStyle(ChatFormatting.GRAY));
        tooltip.add(Component.translatable("item.rancraft.link_tool.tooltip.unpair").withStyle(ChatFormatting.GRAY));
        tooltip.add(Component.translatable("item.rancraft.link_tool.tooltip.alignment").withStyle(ChatFormatting.DARK_GRAY));
        GlobalPos source = stack.get(ModDataComponents.LINK_TOOL_SOURCE.get());
        if (source != null) {
            tooltip.add(Component.translatable("item.rancraft.link_tool.tooltip.selected", xyz(source.pos()))
                    .withStyle(ChatFormatting.AQUA));
        }
    }

    /** A first dish is selected: the tool glints, as a lodestone compass does. */
    @Override
    public boolean isFoil(ItemStack stack) {
        return stack.has(ModDataComponents.LINK_TOOL_SOURCE.get()) || super.isFoil(stack);
    }
}
