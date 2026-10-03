package dev.rancraft.block;

import com.mojang.serialization.MapCodec;
import dev.rancraft.RanCraftConfig;
import dev.rancraft.registry.ModBlockEntities;
import java.util.List;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.Containers;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.ItemInteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.phys.BlockHitResult;
import org.jetbrains.annotations.Nullable;

/**
 * The Site Generator (Phase 3 slice 15, §3C.5): burns furnace fuel and feeds the FE receivers next to
 * it, such as an antenna's buffer (any mast of a column feeds the column). The entity
 * ({@link SiteGeneratorBlockEntity}) does the work; this block is its controls.
 *
 * <ul>
 *   <li><b>Use with fuel:</b> as much of the stack as fits goes into the fuel slot (kept in creative).</li>
 *   <li><b>Use</b> (without fuel): the state in the action bar: burning or idle and why, the fuel
 *       slot, the output.</li>
 *   <li><b>Sneak + use with an empty hand:</b> takes the fuel slot out. Vanilla skips a block's use
 *       when the player sneaks with an item in either hand, so this needs empty hands, as on a Radio
 *       Link.</li>
 *   <li>Hoppers fill the slot from any side and take a lava bucket's bucket out from below.</li>
 * </ul>
 *
 * <p>{@code LIT} is set only by the server, while it burns (held a second, so a part-load generator
 * does not flicker), and reaches clients through vanilla block-state sync: the generator's own public
 * state, like a lit furnace. No screen: the slot is the only thing to set (NOTES.md, slice 15).
 */
public class SiteGeneratorBlock extends BaseEntityBlock {

    public static final MapCodec<SiteGeneratorBlock> CODEC = simpleCodec(SiteGeneratorBlock::new);
    public static final BooleanProperty LIT = BlockStateProperties.LIT;

    /** Light while it burns: a furnace's 13. */
    public static final int LIT_LIGHT = 13;

    public SiteGeneratorBlock(Properties properties) {
        super(properties);
        registerDefaultState(stateDefinition.any().setValue(LIT, Boolean.FALSE));
    }

    @Override
    protected MapCodec<? extends BaseEntityBlock> codec() {
        return CODEC;
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(LIT);
    }

    @Override
    protected RenderShape getRenderShape(BlockState state) {
        return RenderShape.MODEL;
    }

    @Override
    public @Nullable BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new SiteGeneratorBlockEntity(pos, state);
    }

    /** Server only: the client computes nothing. */
    @Nullable
    @Override
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state, BlockEntityType<T> type) {
        return level.isClientSide()
                ? null
                : createTickerHelper(type, ModBlockEntities.SITE_GENERATOR.get(), SiteGeneratorBlockEntity::serverTick);
    }

    /** Fuel in hand: into the slot. Anything else: the default use (the status line). */
    @Override
    protected ItemInteractionResult useItemOn(ItemStack stack, BlockState state, Level level, BlockPos pos,
                                              Player player, InteractionHand hand, BlockHitResult hitResult) {
        if (!SiteGeneratorBlockEntity.isFuel(stack)) {
            return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
        }
        if (!level.isClientSide() && level.getBlockEntity(pos) instanceof SiteGeneratorBlockEntity generator) {
            int moved = generator.insertFuel(stack);
            if (moved > 0 && !player.hasInfiniteMaterials()) {
                stack.shrink(moved);
            }
            player.displayClientMessage(Component.translatable("rancraft.site_generator.fuelled",
                    fuelText(generator.fuel())), true);
        }
        return ItemInteractionResult.sidedSuccess(level.isClientSide());
    }

    /** Use: the state. Sneak + use (empty hands): the fuel slot comes out. */
    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player,
                                               BlockHitResult hitResult) {
        if (!level.isClientSide() && level.getBlockEntity(pos) instanceof SiteGeneratorBlockEntity generator) {
            if (player.isSecondaryUseActive()) {
                ItemStack taken = generator.removeItemNoUpdate(0);
                generator.setChanged();
                if (!taken.isEmpty()) {
                    player.getInventory().placeItemBackInInventory(taken);
                }
                player.displayClientMessage(Component.translatable("rancraft.site_generator.taken",
                        fuelText(taken)), true);
            } else {
                player.displayClientMessage(status(generator, level.getGameTime()), true);
            }
        }
        return InteractionResult.sidedSuccess(level.isClientSide());
    }

    /** The action-bar status: burning (time left on this fuel, output) or idle and why; the fuel slot. */
    static Component status(SiteGeneratorBlockEntity generator, long gameTime) {
        Component fuel = fuelText(generator.fuel());
        if (generator.lit(gameTime)) {
            return Component.translatable("rancraft.site_generator.burning",
                    String.valueOf(generator.burnTicks() / 20), String.valueOf(RanCraftConfig.siteGeneratorFePerTick()),
                    fuel);
        }
        String reason;
        if (generator.burnTicks() <= 0 && !SiteGeneratorBlockEntity.isFuel(generator.fuel())) {
            reason = "rancraft.site_generator.reason.no_fuel";
        } else if (!RanCraftConfig.requirePower()) {
            reason = "rancraft.site_generator.reason.power_off";
        } else {
            reason = "rancraft.site_generator.reason.no_taker";
        }
        return Component.translatable("rancraft.site_generator.idle", Component.translatable(reason), fuel);
    }

    private static Component fuelText(ItemStack stack) {
        return stack.isEmpty()
                ? Component.translatable("rancraft.site_generator.fuel.empty")
                : Component.translatable("rancraft.site_generator.fuel", stack.getCount(), stack.getHoverName());
    }

    /** Broken or replaced by another block: the fuel slot drops, as a furnace's does. */
    @Override
    protected void onRemove(BlockState state, Level level, BlockPos pos, BlockState newState, boolean movedByPiston) {
        Containers.dropContentsOnDestroy(state, newState, level, pos);
        super.onRemove(state, level, pos, newState, movedByPiston);
    }

    @Override
    public void appendHoverText(ItemStack stack, Item.TooltipContext context, List<Component> tooltip, TooltipFlag flag) {
        super.appendHoverText(stack, context, tooltip, flag);
        tooltip.add(Component.translatable("block.rancraft.site_generator.tooltip").withStyle(ChatFormatting.GRAY));
        tooltip.add(Component.translatable("block.rancraft.site_generator.tooltip.burn").withStyle(ChatFormatting.GRAY));
        tooltip.add(Component.translatable("block.rancraft.site_generator.tooltip.use").withStyle(ChatFormatting.DARK_GRAY));
    }
}
