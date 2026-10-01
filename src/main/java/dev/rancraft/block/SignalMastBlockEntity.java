package dev.rancraft.block;

import dev.rancraft.RanCraftConfig;
import dev.rancraft.registry.ModBlockEntities;
import dev.rancraft.util.ColumnScan;
import dev.rancraft.world.MastColumnCensus;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;

/**
 * The omnidirectional Signal Mast: the cheap early-game site.
 *
 * <p>Unchanged in behaviour from Phase 1 -- 360 degrees horizontal, 6 dBi, no usable azimuth. It
 * now runs through the same pattern code as a sector antenna rather than a separate branch in
 * {@code RfEngine}, but because a 360-degree cell short-circuits to {@code OmniPattern} the numbers
 * it produces are identical to Phase 1's.
 *
 * <p>All state, persistence and migration live in {@link AntennaBlockEntity}; the defaults there
 * are already the mast's.
 *
 * <p><b>Mast columns (Phase 3 slice 6, §3B.1).</b> Stacked masts are one site (see
 * {@link SignalMastBlock}). Every mast keeps its own entity, but only the column's base registers a
 * cell: {@link #isTransmitting()} is false for structure and for a mounting pole, and
 * {@link #radiatingPoint()} is the column top's. Nothing persisted changes, so there is no
 * {@code DATA_VERSION} bump: a saved column works out its shape again from the blocks on load.
 *
 * <p>PCI planning waits until a mast is a base: a mast placed on top of a column is structure and
 * gets no plan (and no log line). When a structure mast becomes a base while loaded (the base below
 * it was broken, or the column split), it gets a fresh plan, as a newly placed mast would. That is a
 * known behaviour, not a bug (NOTES.md, slice 6): the new base is a different cell with a new id, so
 * its old entity's saved PCI (if any) is not the column's.
 *
 * <p>On the client the RF Lens draws a column's lobe at the radiating point the server worked out
 * with its own {@code maxMastHeight}, sent in the update tag ({@code RadiatingY}, Phase 3B review
 * fix): that config is COMMON, which NeoForge does not sync, so the client's own cap may differ.
 */
public class SignalMastBlockEntity extends AntennaBlockEntity {

    /**
     * Whether the last server-side refresh found this mast to be structure. Not persisted: it only
     * detects a promotion to base while the chunk stays loaded. Starts false, so a base loaded from
     * disk keeps its saved PCI.
     */
    private boolean seenAsStructure;

    /** Set when saved data arrived ({@link #loadAdditional}); only such a column is counted in the census. */
    private boolean loadedFromSave;

    public SignalMastBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.SIGNAL_MAST.get(), pos, state);
    }

    /**
     * Whether this mast is its column's base (the lowest mast). Without a level (never in play) it
     * counts as a column of one.
     */
    public boolean isColumnBase() {
        return level == null || MastColumn.isBase(level, getBlockPos());
    }

    /**
     * Whether this mast owns a cell by the shape of its column: the base, with no sector antenna on
     * top. Power is not considered. The RF Lens draws a lobe only for such a mast, greyed when the
     * server says it is off the air.
     */
    public boolean ownsColumnCell() {
        return level == null || MastColumn.ownsCell(level, getBlockPos());
    }

    /**
     * For a column's base: just above the top of the column's signal part ({@code top.above()}). For
     * a single mast that is {@code pos.above()}, as in Phase 2. For structure (never registered) the
     * block above, which nothing uses.
     *
     * <p>On the client (the RF Lens) the height is the server's, from the update tag
     * ({@link #toldRadiatingY()}), whenever it fits the column the client sees
     * ({@link ColumnScan#reportedRadiatingY}): the server applies its own {@code maxMastHeight}, which
     * is not synced. Only before the server has said, or for the moment a block change has reached
     * the client ahead of the base's update, does the client fall back to its own cap.
     */
    @Override
    public BlockPos radiatingPoint() {
        BlockPos pos = getBlockPos();
        if (level == null || !MastColumn.isBase(level, pos)) {
            return super.radiatingPoint();
        }
        ColumnScan.Bounds column = MastColumn.bounds(level, pos);
        if (column == null) {
            return super.radiatingPoint();
        }
        int y = level.isClientSide()
                ? ColumnScan.reportedRadiatingY(column, toldRadiatingY())
                : column.radiatingY();
        return new BlockPos(pos.getX(), y, pos.getZ());
    }

    /**
     * Only a column's base transmits, and not when a sector antenna sits on top (a mounting pole).
     * With {@code requireRedstone} on, the column needs any of its masts powered.
     */
    @Override
    public boolean isTransmitting() {
        if (level == null) {
            return super.isTransmitting();
        }
        BlockPos pos = getBlockPos();
        if (!MastColumn.isBase(level, pos)) {
            return false;
        }
        ColumnScan.Bounds column = MastColumn.bounds(level, pos);
        if (column == null || MastColumn.mountingPole(level, pos, column)) {
            return false;
        }
        if (!RanCraftConfig.REQUIRE_REDSTONE.get()) {
            return true;
        }
        return MastColumn.powered(level, pos, column);
    }

    /** A mast is planned only once it owns its column's cell; see the class javadoc. */
    @Override
    protected boolean readyForPciPlan() {
        return isColumnBase();
    }

    /**
     * Registers the cell if this mast is a transmitting base, unregisters it otherwise. A structure
     * mast that has just become a base gets a fresh PCI plan first.
     *
     * <p><b>Only a promotion plans here</b> (Phase 3B review fix). Every other pending plan (a fresh
     * placement, a migrated Phase 1 mast whose saved PCI is 0) waits for {@link #onLoad}, as before
     * slice 6: this method also runs from {@code ChunkEvent.Load}, before the antennas in chunks loaded
     * later in the same batch have registered, while NeoForge runs {@code onLoad} afterwards
     * ({@code Level.tickBlockEntities}), once the whole batch is in the registry. Until then such a mast
     * registers with its placeholder PCI, as a sector antenna does. A promotion happens on a block
     * change next to a loaded column, never during a chunk load, so its plan sees the neighbourhood.
     */
    @Override
    public void refreshRegistration() {
        if (level instanceof ServerLevel) {
            boolean base = isColumnBase();
            boolean promoted = base && seenAsStructure;
            seenAsStructure = !base;
            if (promoted) {
                // The mast (or masts) under it went. A fresh plan, as for a new mast; it plans, then
                // refreshes again (not promoted any more) to register the result.
                needsPciAssignment = true;
                assignPciIfNeeded();
                if (!needsPciAssignment) {
                    return;
                }
            }
        }
        super.refreshRegistration();
    }

    @Override
    public void onLoad() {
        super.onLoad();
        if (loadedFromSave && level instanceof ServerLevel serverLevel && isColumnBase()) {
            ColumnScan.Bounds column = MastColumn.bounds(serverLevel, getBlockPos());
            if (column != null) {
                MastColumnCensus.noteLoaded(serverLevel, getBlockPos(), column.height(),
                        MastColumn.mountingPole(serverLevel, getBlockPos(), column));
            }
        }
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        loadedFromSave = true;
    }
}
