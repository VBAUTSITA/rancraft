package dev.rancraft.block;

import com.mojang.serialization.MapCodec;
import dev.rancraft.item.LinkToolItem;
import dev.rancraft.rf.BackhaulGraph.BackhaulState;
import dev.rancraft.world.BackhaulNetwork;
import java.util.List;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.ItemInteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import org.jetbrains.annotations.Nullable;

/**
 * The Backhaul Dish (Phase 3 slice 12, §3C.2): one end of a point-to-point microwave hop. Paired with
 * another dish by the Link Tool; a dish within {@code siteRadiusBlocks} (horizontally) of a cell's base
 * serves that cell, and one within {@code fiberRadiusBlocks} of a Core Site is on fiber.
 *
 * <p>Use (with anything but a Link Tool) shows the hop as the server last measured it: the far dish,
 * the state, RSL, margin, Fresnel state and rain loss, and how this dish reaches the core. Every figure
 * is the server's; the client computes nothing. A Link Tool goes straight to the item
 * ({@link #useItemOn}), so the status line does not appear instead of the pairing.
 *
 * <p>The entity ({@link BackhaulDishBlockEntity}) holds the partner and keeps the dimension's backhaul
 * network told; breaking the dish unpairs its partner.
 */
public class BackhaulDishBlock extends BaseEntityBlock {

    public static final MapCodec<BackhaulDishBlock> CODEC = simpleCodec(BackhaulDishBlock::new);

    public BackhaulDishBlock(Properties properties) {
        super(properties);
    }

    @Override
    protected MapCodec<? extends BaseEntityBlock> codec() {
        return CODEC;
    }

    @Override
    protected RenderShape getRenderShape(BlockState state) {
        return RenderShape.MODEL;
    }

    @Override
    public @Nullable BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new BackhaulDishBlockEntity(pos, state);
    }

    @Override
    protected ItemInteractionResult useItemOn(ItemStack stack, BlockState state, Level level, BlockPos pos,
                                              Player player, InteractionHand hand, BlockHitResult hit) {
        // The Link Tool pairs in its own useOn; SKIP goes straight on to it.
        if (stack.getItem() instanceof LinkToolItem) {
            return ItemInteractionResult.SKIP_DEFAULT_BLOCK_INTERACTION;
        }
        return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
    }

    /** Use: the hop's last measurement, in chat (it is too long for the action bar). */
    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player,
                                               BlockHitResult hitResult) {
        if (level instanceof ServerLevel serverLevel) {
            player.displayClientMessage(status(serverLevel, pos), false);
        }
        return InteractionResult.sidedSuccess(level.isClientSide());
    }

    /** The dish's status line, from the server's last recompute. */
    static Component status(ServerLevel level, BlockPos pos) {
        BackhaulNetwork network = BackhaulNetwork.of(level);
        BackhaulState reach = network.dishStateOf(pos);
        String reachText = reach == null ? "not judged yet" : reach.name();
        BlockPos partner = network.partnerOf(pos);
        if (partner == null) {
            return Component.translatable("rancraft.backhaul_dish.unpaired", reachText);
        }
        BackhaulNetwork.HopStatus hop = network.hopOf(pos);
        String where = partner.getX() + ", " + partner.getY() + ", " + partner.getZ();
        if (hop == null) {
            return Component.translatable("rancraft.backhaul_dish.unmeasured", where, reachText);
        }
        ChatFormatting colour = switch (hop.budget().state()) {
            case UP -> ChatFormatting.GREEN;
            case DEGRADED -> ChatFormatting.GOLD;
            case DOWN -> ChatFormatting.RED;
        };
        return Component.translatable("rancraft.backhaul_dish.hop", where,
                        Component.literal(hop.budget().describe()).withStyle(colour),
                        hop.weather().name(), reachText);
    }

    @Override
    public void appendHoverText(ItemStack stack, Item.TooltipContext context, List<Component> tooltip, TooltipFlag flag) {
        super.appendHoverText(stack, context, tooltip, flag);
        tooltip.add(Component.translatable("block.rancraft.backhaul_dish.tooltip").withStyle(ChatFormatting.GRAY));
        tooltip.add(Component.translatable("block.rancraft.backhaul_dish.tooltip.site").withStyle(ChatFormatting.GRAY));
        tooltip.add(Component.translatable("block.rancraft.backhaul_dish.tooltip.use").withStyle(ChatFormatting.DARK_GRAY));
    }
}
