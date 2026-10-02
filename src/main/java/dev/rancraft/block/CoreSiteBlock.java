package dev.rancraft.block;

import com.mojang.serialization.MapCodec;
import dev.rancraft.RanCraftConfig;
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
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import org.jetbrains.annotations.Nullable;

/**
 * The Core Site (Phase 3 slice 12, §3C.2): the core network, the data centre every cell's traffic has
 * to reach. A cell whose column base, or a Backhaul Dish, is within {@code fiberRadiusBlocks}
 * (horizontally) of one is on fiber.
 *
 * <p><b>Game abstraction, labelled (§6, "implicit fiber by radius"):</b> nothing is laid. Being near a
 * core is being on fiber; real fiber is trenched or strung on poles, and has its own cuts and capacity
 * ({@code rf.BackhaulGraph}).
 *
 * <p>Use shows the fiber radius in the action bar. The entity ({@link CoreSiteBlockEntity}) tells the
 * dimension's backhaul network that a core is here.
 */
public class CoreSiteBlock extends BaseEntityBlock {

    public static final MapCodec<CoreSiteBlock> CODEC = simpleCodec(CoreSiteBlock::new);

    public CoreSiteBlock(Properties properties) {
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
        return new CoreSiteBlockEntity(pos, state);
    }

    /** Use: the fiber radius, from the server's config. */
    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player,
                                               BlockHitResult hitResult) {
        if (!level.isClientSide()) {
            player.displayClientMessage(Component.translatable("rancraft.core_site.status",
                    String.valueOf((long) RanCraftConfig.fiberRadiusBlocks())), true);
        }
        return InteractionResult.sidedSuccess(level.isClientSide());
    }

    @Override
    public void appendHoverText(ItemStack stack, Item.TooltipContext context, List<Component> tooltip, TooltipFlag flag) {
        super.appendHoverText(stack, context, tooltip, flag);
        tooltip.add(Component.translatable("block.rancraft.core_site.tooltip").withStyle(ChatFormatting.GRAY));
        tooltip.add(Component.translatable("block.rancraft.core_site.tooltip.fiber").withStyle(ChatFormatting.DARK_GRAY));
    }
}
