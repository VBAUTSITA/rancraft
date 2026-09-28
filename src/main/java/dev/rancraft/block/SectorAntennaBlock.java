package dev.rancraft.block;

import com.mojang.serialization.MapCodec;
import dev.rancraft.RanCraftConfig;
import dev.rancraft.data.RfDataLoader;
import dev.rancraft.net.OpenAntennaConfigPayload;
import dev.rancraft.rf.PciConflict;
import dev.rancraft.rf.PciPlanner;
import dev.rancraft.world.SiteRegistry;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.ItemInteractionResult;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.block.state.properties.DirectionProperty;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;
import net.neoforged.neoforge.network.PacketDistributor;
import org.jetbrains.annotations.Nullable;

/**
 * A directional radio site.
 *
 * <p>Like the Signal Mast, the radiating point is {@code pos.above()}. Unlike the mast, it has a
 * facing, and that facing seeds {@code azimuthDeg} on placement so the antenna points somewhere
 * sensible before the player opens the configuration screen.
 *
 * <p>Right-clicking with an empty hand opens that screen. Any other interaction falls through, so
 * the antenna does not swallow block placement or tool use.
 */
public class SectorAntennaBlock extends BaseEntityBlock {

    public static final MapCodec<SectorAntennaBlock> CODEC = simpleCodec(SectorAntennaBlock::new);

    public static final DirectionProperty FACING = HorizontalDirectionalBlock.FACING;
    public static final BooleanProperty POWERED = BlockStateProperties.POWERED;

    /** A panel: wide across its face, thin front to back, mounted high on the block. */
    private static final VoxelShape SHAPE_NS = Block.box(3.0, 0.0, 6.0, 13.0, 16.0, 10.0);
    private static final VoxelShape SHAPE_EW = Block.box(6.0, 0.0, 3.0, 10.0, 16.0, 13.0);

    public SectorAntennaBlock(Properties properties) {
        super(properties);
        registerDefaultState(stateDefinition.any()
                .setValue(FACING, Direction.NORTH)
                .setValue(POWERED, Boolean.FALSE));
    }

    @Override
    protected MapCodec<? extends BaseEntityBlock> codec() {
        return CODEC;
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING, POWERED);
    }

    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        Direction facing = state.getValue(FACING);
        return facing.getAxis() == Direction.Axis.Z ? SHAPE_NS : SHAPE_EW;
    }

    @Override
    protected RenderShape getRenderShape(BlockState state) {
        return RenderShape.MODEL;
    }

    @Override
    public @Nullable BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new SectorAntennaBlockEntity(pos, state);
    }

    @Override
    public @Nullable BlockState getStateForPlacement(BlockPlaceContext context) {
        // The panel faces away from the player, like a sign or a furnace front.
        return defaultBlockState()
                .setValue(FACING, context.getHorizontalDirection().getOpposite())
                .setValue(POWERED, context.getLevel().hasNeighborSignal(context.getClickedPos()));
    }

    @Override
    public void setPlacedBy(Level level, BlockPos pos, BlockState state, @Nullable LivingEntity placer, ItemStack stack) {
        super.setPlacedBy(level, pos, state, placer, stack);
        if (!level.isClientSide()
                && level.getBlockEntity(pos) instanceof SectorAntennaBlockEntity antenna) {
            antenna.setAzimuthFromFacing(state.getValue(FACING));
            antenna.refreshRegistration();
        }
    }

    @Override
    protected ItemInteractionResult useItemOn(ItemStack stack, BlockState state, Level level, BlockPos pos,
                                              Player player, InteractionHand hand, BlockHitResult hit) {
        // Only an empty hand opens the screen, so the antenna never swallows block placement or
        // tool use. Falling through sends us to useWithoutItem when the hand is empty.
        return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos,
                                               Player player, BlockHitResult hit) {
        if (level.isClientSide()) {
            // The client waits to be told; the server owns the values and the conflict list.
            return InteractionResult.SUCCESS;
        }
        if (!(level.getBlockEntity(pos) instanceof AntennaBlockEntity antenna)
                || !(player instanceof ServerPlayer serverPlayer)) {
            return InteractionResult.PASS;
        }

        PacketDistributor.sendToPlayer(serverPlayer, openPayloadFor(antenna, pos, level));
        return InteractionResult.CONSUME;
    }

    /** Snapshots the antenna plus the band list and its current PCI conflicts, for the screen. */
    private static OpenAntennaConfigPayload openPayloadFor(
            AntennaBlockEntity antenna, BlockPos pos, Level level) {

        List<String> bands = List.copyOf(RfDataLoader.bands().all().keySet());

        List<String> conflicts = List.of();
        if (level instanceof ServerLevel serverLevel) {
            var params = RanCraftConfig.snapshot().pciParams();
            BlockPos point = antenna.radiatingPoint();
            var neighbours = SiteRegistry.of(serverLevel)
                    .near(point.getX(), point.getY(), point.getZ(), params.planningRadiusBlocks());
            conflicts = PciPlanner.findConflictsFor(antenna.cellId(), neighbours, params).stream()
                    .map(PciConflict::describe)
                    .toList();
        }

        return new OpenAntennaConfigPayload(
                pos, antenna.bandId(), antenna.txPowerDbm(), antenna.gainDbi(),
                antenna.azimuthDeg(), antenna.tiltDeg(),
                antenna.hBeamwidthDeg(), antenna.vBeamwidthDeg(), antenna.pci(),
                bands, conflicts);
    }

    @Override
    protected void neighborChanged(BlockState state, Level level, BlockPos pos,
                                   Block neighborBlock, BlockPos neighborPos, boolean movedByPiston) {
        if (level.isClientSide()) {
            return;
        }
        boolean powered = level.hasNeighborSignal(pos);
        if (powered != state.getValue(POWERED)) {
            level.setBlock(pos, state.setValue(POWERED, powered), Block.UPDATE_CLIENTS);
            if (level.getBlockEntity(pos) instanceof SectorAntennaBlockEntity antenna) {
                antenna.refreshRegistration();
            }
        }
    }
}
