package dev.rancraft.block;

import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jetbrains.annotations.Nullable;

/**
 * A radio site.
 *
 * <p><b>Mast columns (Phase 3 slice 6, §3B.1).</b> A vertical run of contiguous masts is one site.
 * The lowest mast (the base) owns the cell, so {@code cellId} is the base's position and extending
 * the tower upward keeps the cell's id and its PCI. The cell radiates from just above the top mast
 * (at most {@code maxMastHeight} masts up); every other mast is structure and never registers. A
 * sector antenna directly on the top mast makes the column a mounting pole: the column goes quiet
 * and the sector is its own cell, as before. The rule itself is the pure {@code util.ColumnScan};
 * {@link MastColumn} applies it to the world. A single mast is a column of one and behaves exactly
 * as in Phase 2: it radiates from {@code pos.above()}.
 *
 * <p>Nothing may assume the radiating point equals the block position (or the block above it): for
 * a column it is the top's.
 *
 * <p><b>Honest labels (NOTES.md, slice 6).</b> A real tower is a steel structure that carries one
 * antenna system per sector; here the tower is built from the same block that radiates, and only the
 * base's block entity carries the cell's configuration. The mounting-pole rule is a game rule: a real
 * pole can carry an omni and sectors together, while here a sector on top replaces the column's omni,
 * so one column is never more than one cell.
 *
 * <p>{@code POWERED} is tracked per mast unconditionally, but only gates transmission when
 * {@code requireRedstone} is enabled. It defaults off so a freshly placed mast just works. With it
 * on, a column transmits while any of its masts is powered.
 */
public class SignalMastBlock extends BaseEntityBlock {

    public static final MapCodec<SignalMastBlock> CODEC = simpleCodec(SignalMastBlock::new);
    public static final BooleanProperty POWERED = BlockStateProperties.POWERED;

    private static final VoxelShape SHAPE = Block.box(5.0, 0.0, 5.0, 11.0, 16.0, 11.0);

    public SignalMastBlock(Properties properties) {
        super(properties);
        registerDefaultState(stateDefinition.any().setValue(POWERED, Boolean.FALSE));
    }

    @Override
    protected MapCodec<? extends BaseEntityBlock> codec() {
        return CODEC;
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(POWERED);
    }

    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return SHAPE;
    }

    @Override
    protected RenderShape getRenderShape(BlockState state) {
        return RenderShape.MODEL;
    }

    @Override
    public @Nullable BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new SignalMastBlockEntity(pos, state);
    }

    @Override
    public @Nullable BlockState getStateForPlacement(BlockPlaceContext context) {
        boolean powered = context.getLevel().hasNeighborSignal(context.getClickedPos());
        return defaultBlockState().setValue(POWERED, powered);
    }

    /**
     * A redstone change. Updates this mast's {@code POWERED}, then re-scans the column: with
     * {@code requireRedstone} on, any powered mast puts the whole column on the air, and only the
     * base registers.
     */
    @Override
    protected void neighborChanged(BlockState state, Level level, BlockPos pos,
                                   Block neighborBlock, BlockPos neighborPos, boolean movedByPiston) {
        if (!(level instanceof ServerLevel serverLevel)) {
            return;
        }
        boolean powered = level.hasNeighborSignal(pos);
        if (powered != state.getValue(POWERED)) {
            level.setBlock(pos, state.setValue(POWERED, powered), Block.UPDATE_CLIENTS);
            MastColumn.refresh(serverLevel, pos);
        }
    }

    /**
     * The block above or below changed: a mast added or removed (the column grew, shrank, split or
     * merged; its base may have changed) or a sector antenna put on or taken off the top. Re-scans the
     * column and refreshes this mast and the column's base only (§3B.1). The state itself never
     * changes shape here.
     *
     * <p>Called on both sides and during world generation ({@code LevelAccessor} is then a
     * {@code WorldGenRegion}); only a live server level registers anything. A chunk load re-scans
     * every antenna anyway ({@code SignalTicker.onChunkLoad}), so a change that skipped shape
     * updates is caught there.
     */
    @Override
    protected BlockState updateShape(BlockState state, Direction direction, BlockState neighborState,
                                     LevelAccessor level, BlockPos pos, BlockPos neighborPos) {
        if (direction.getAxis() == Direction.Axis.Y && level instanceof ServerLevel serverLevel) {
            MastColumn.refresh(serverLevel, pos);
        }
        return state;
    }
}
