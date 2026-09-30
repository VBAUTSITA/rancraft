package dev.rancraft.block;

import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import org.jetbrains.annotations.Nullable;

/**
 * {@code rancraft:radio_link_receiver} (Phase 3 slice 9, §3B.4): outputs redstone 15 on every side while
 * {@code POWERED}, like a redstone block (weak power only; it does not power the block behind a solid
 * block it touches). {@code POWERED} is the receiver's output, set only by the server from the messages
 * that got through ({@link RadioLinkReceiverBlockEntity}); it is saved with the block, so a reloaded
 * receiver keeps its output.
 */
public class RadioLinkReceiverBlock extends RadioLinkBlock {

    public static final MapCodec<RadioLinkReceiverBlock> CODEC = simpleCodec(RadioLinkReceiverBlock::new);
    public static final BooleanProperty POWERED = BlockStateProperties.POWERED;

    public RadioLinkReceiverBlock(Properties properties) {
        super(properties);
        registerDefaultState(defaultBlockState().setValue(POWERED, Boolean.FALSE));
    }

    @Override
    protected MapCodec<? extends BaseEntityBlock> codec() {
        return CODEC;
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        super.createBlockStateDefinition(builder);
        builder.add(POWERED);
    }

    @Override
    public @Nullable BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new RadioLinkReceiverBlockEntity(pos, state);
    }

    @Override
    protected boolean isSignalSource(BlockState state) {
        return true;
    }

    @Override
    protected int getSignal(BlockState state, BlockGetter level, BlockPos pos, Direction direction) {
        return state.getValue(POWERED) ? 15 : 0;
    }
}
