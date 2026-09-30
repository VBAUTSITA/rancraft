package dev.rancraft.block;

import dev.rancraft.RanCraftConfig;
import dev.rancraft.util.ColumnScan;
import java.util.function.IntPredicate;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

/**
 * {@link ColumnScan} applied to a world: which Signal Masts form one tower. Phase 3 slice 6 (§3B.1).
 *
 * <p>Read through {@link BlockGetter}, so the server (registration) and the client (the RF Lens) use
 * one rule on the same block states. A column is public world data, like the blocks themselves, so
 * the client working it out does not break the VISION rule: it decides where a declared antenna
 * <em>is</em>, never what anyone receives from it.
 *
 * <p>Every read is a {@code getBlockState} in one x/z column, which a chunk holds whole, so a scan
 * never reaches into a neighbouring chunk. During {@code ChunkEvent.Load} NeoForge lets the loading
 * chunk be read without waiting on its own future ({@code GenerationChunkHolder.currentlyLoading}),
 * which is why the load-time refresh may scan.
 */
public final class MastColumn {

    private MastColumn() {
    }

    /** Whether a mast stands at a height, in the x/z column of {@code pos}. */
    private static IntPredicate masts(BlockGetter level, BlockPos pos) {
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        int x = pos.getX();
        int z = pos.getZ();
        return y -> level.getBlockState(cursor.set(x, y, z)).getBlock() instanceof SignalMastBlock;
    }

    /** The column through the mast at {@code pos}, or {@code null} if no mast stands there. */
    public static @Nullable ColumnScan.Bounds bounds(BlockGetter level, BlockPos pos) {
        IntPredicate isMast = masts(level, pos);
        if (!isMast.test(pos.getY())) {
            return null;
        }
        return ColumnScan.bounds(pos.getY(), isMast, RanCraftConfig.maxMastHeight());
    }

    /** Whether the mast at {@code pos} is its column's base (two lookups, whatever the height). */
    public static boolean isBase(BlockGetter level, BlockPos pos) {
        return ColumnScan.isBase(pos.getY(), masts(level, pos));
    }

    /** The base of a column found from any of its masts. */
    public static BlockPos basePos(BlockPos anyMast, ColumnScan.Bounds column) {
        return new BlockPos(anyMast.getX(), column.baseY(), anyMast.getZ());
    }

    /**
     * Whether a sector antenna sits directly on the column's highest mast. Then the column is a
     * mounting pole and does not transmit; the sector is its own cell, as before.
     */
    public static boolean mountingPole(BlockGetter level, BlockPos anyMast, ColumnScan.Bounds column) {
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        int x = anyMast.getX();
        int z = anyMast.getZ();
        return ColumnScan.mountingPole(column,
                y -> level.getBlockState(cursor.set(x, y, z)).getBlock() instanceof SectorAntennaBlock);
    }

    /**
     * §3B.1's power gate: the column is powered if any mast in it receives a redstone signal. Each
     * mast keeps its own {@code POWERED} state up to date in {@code neighborChanged}. A mast is not a
     * full block, so it does not conduct redstone to the next one; the column-wide rule is this.
     */
    public static boolean powered(BlockGetter level, BlockPos anyMast, ColumnScan.Bounds column) {
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        int x = anyMast.getX();
        int z = anyMast.getZ();
        return ColumnScan.any(column, y -> {
            BlockState state = level.getBlockState(cursor.set(x, y, z));
            return state.getBlock() instanceof SignalMastBlock && state.getValue(SignalMastBlock.POWERED);
        });
    }

    /**
     * Whether the mast at {@code pos} owns a cell by the shape of its column: it is the base and no
     * sector antenna sits on top. Power is not considered; on the client that is the server's
     * {@code OnAir} flag. The RF Lens draws a lobe for such a mast only.
     */
    public static boolean ownsCell(BlockGetter level, BlockPos pos) {
        if (!isBase(level, pos)) {
            return false;
        }
        ColumnScan.Bounds column = bounds(level, pos);
        return column != null && !mountingPole(level, pos, column);
    }

    /**
     * The server's re-scan after a block next to the mast at {@code pos} changed (§3B.1). Refreshes
     * this mast (so a base that just became structure, because a mast was placed under it,
     * unregisters) and its column's base (which registers, moves its radiating point, or goes off the
     * air). No other mast in the column is touched: structure never registers.
     */
    public static void refresh(ServerLevel level, BlockPos pos) {
        if (level.getBlockEntity(pos) instanceof SignalMastBlockEntity self) {
            self.refreshRegistration();
        }
        ColumnScan.Bounds column = bounds(level, pos);
        if (column == null || column.baseY() == pos.getY()) {
            return;
        }
        if (level.getBlockEntity(basePos(pos, column)) instanceof SignalMastBlockEntity base) {
            base.refreshRegistration();
        }
    }
}
