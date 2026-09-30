package dev.rancraft.block;

import com.mojang.serialization.MapCodec;
import dev.rancraft.device.RadioLinkNetwork;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

/**
 * {@code rancraft:radio_link_transmitter} (Phase 3 slice 9, §3B.4): sends its redstone input over the
 * network to every receiver on its address, about once a second
 * ({@link RadioLinkTransmitterBlockEntity}). It emits no redstone itself. It reads its input only on
 * its turn; a neighbour change only marks the input to be read again ({@link #neighborChanged}). When it
 * is broken it tells the network ({@link #onRemove}).
 */
public class RadioLinkTransmitterBlock extends RadioLinkBlock {

    public static final MapCodec<RadioLinkTransmitterBlock> CODEC = simpleCodec(RadioLinkTransmitterBlock::new);

    public RadioLinkTransmitterBlock(Properties properties) {
        super(properties);
    }

    @Override
    protected MapCodec<? extends BaseEntityBlock> codec() {
        return CODEC;
    }

    @Override
    public @Nullable BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new RadioLinkTransmitterBlockEntity(pos, state);
    }

    /**
     * A neighbour changed, which is how any change of the redstone power reaching this block announces
     * itself (a redstone lamp relies on the same): the entity reads its input again on its next turn.
     */
    @Override
    protected void neighborChanged(BlockState state, Level level, BlockPos pos, Block neighborBlock, BlockPos neighborPos,
                                   boolean movedByPiston) {
        super.neighborChanged(state, level, pos, neighborBlock, neighborPos, movedByPiston);
        if (!level.isClientSide() && level.getBlockEntity(pos) instanceof RadioLinkTransmitterBlockEntity transmitter) {
            transmitter.neighbourChanged();
        }
    }

    /**
     * Broken or replaced (not unloaded: a chunk unload does not call this): the receivers on its address
     * forget it at once ({@link RadioLinkNetwork#transmitterGone}). Read before {@code super} removes
     * the block entity.
     */
    @Override
    protected void onRemove(BlockState state, Level level, BlockPos pos, BlockState newState, boolean movedByPiston) {
        if (!state.is(newState.getBlock()) && level instanceof ServerLevel serverLevel
                && level.getBlockEntity(pos) instanceof RadioLinkTransmitterBlockEntity transmitter) {
            RadioLinkNetwork.of(serverLevel).transmitterGone(pos.asLong(), transmitter.address());
        }
        super.onRemove(state, level, pos, newState, movedByPiston);
    }

    /**
     * Redstone dust next to it bends into it from any side, and dust powers the blocks it points into,
     * so a line of dust ending beside the transmitter powers it. Vanilla bends dust only towards signal
     * sources (a redstone lamp does not attract dust either); this is NeoForge's
     * {@code IBlockExtension.canConnectRedstone}. The answer never depends on the world, so no shape
     * update is owed.
     */
    @Override
    public boolean canConnectRedstone(BlockState state, BlockGetter level, BlockPos pos, @Nullable Direction direction) {
        return direction != null;
    }
}
