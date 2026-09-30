package dev.rancraft.block;

import java.util.List;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.phys.BlockHitResult;

/**
 * What both Radio Link blocks share (Phase 3 slice 9, §3B.4): {@code LIT} while the block has service,
 * the address on use, and the tooltip.
 *
 * <p><b>Address on use.</b> Use cycles the address up, sneak + use cycles it down, and the action bar
 * shows the new value. Vanilla skips a block's use when the player sneaks with an item in either hand
 * ({@code ServerPlayerGameMode.useItemOn}), so sneak + use works with empty hands, as for any vanilla
 * block. The server changes the address; the client only swings the hand.
 *
 * <p><b>{@code LIT}</b> is set only by the server, from the device's last evaluation
 * ({@link RadioLinkBlockEntity#served()}), and reaches clients through vanilla block-state sync: the
 * client computes no service. Placed unlit; it lights at its first evaluation if it has service.
 */
public abstract class RadioLinkBlock extends BaseEntityBlock {

    public static final BooleanProperty LIT = BlockStateProperties.LIT;

    protected RadioLinkBlock(Properties properties) {
        super(properties);
        registerDefaultState(stateDefinition.any().setValue(LIT, Boolean.FALSE));
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(LIT);
    }

    @Override
    protected RenderShape getRenderShape(BlockState state) {
        return RenderShape.MODEL;
    }

    /** Use: address up. Sneak + use: address down. The action bar shows it. */
    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player,
                                               BlockHitResult hitResult) {
        if (!level.isClientSide() && level.getBlockEntity(pos) instanceof RadioLinkBlockEntity link) {
            int address = link.cycleAddress(player.isSecondaryUseActive() ? -1 : 1);
            player.displayClientMessage(Component.translatable("rancraft.radio_link.address", getName(), address), true);
        }
        return InteractionResult.sidedSuccess(level.isClientSide());
    }

    /** The role line, then what every Radio Link shares, including the update rate (§3B.4). */
    @Override
    public void appendHoverText(ItemStack stack, Item.TooltipContext context, List<Component> tooltip, TooltipFlag flag) {
        super.appendHoverText(stack, context, tooltip, flag);
        tooltip.add(Component.translatable(getDescriptionId() + ".tooltip").withStyle(ChatFormatting.GRAY));
        tooltip.add(Component.translatable("block.rancraft.radio_link.tooltip.rate").withStyle(ChatFormatting.GRAY));
        tooltip.add(Component.translatable("block.rancraft.radio_link.tooltip.service").withStyle(ChatFormatting.GRAY));
        tooltip.add(Component.translatable("block.rancraft.radio_link.tooltip.use").withStyle(ChatFormatting.DARK_GRAY));
    }
}
