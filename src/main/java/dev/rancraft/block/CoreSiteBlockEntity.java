package dev.rancraft.block;

import dev.rancraft.registry.ModBlockEntities;
import dev.rancraft.world.BackhaulNetwork;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

/**
 * The Core Site's entity (Phase 3 slice 12, §3C.2). It holds nothing; it exists for its lifecycle,
 * which tells the dimension's {@link BackhaulNetwork} that a core is here.
 *
 * <ul>
 *   <li>{@link #clearRemoved()}: the entity joined a chunk (placed, or its chunk loaded), so the core is
 *       known even in a dimension whose block entities are not ticking ({@code FixedDeviceBlockEntity}
 *       has the details). {@link #onLoad()} repeats it, idempotently.</li>
 *   <li>{@link #setRemoved()}: the core leaves the network when the block is broken or replaced. Not
 *       when its chunk unloads ({@link #onChunkUnloaded()} comes first then): the backhaul topology is
 *       saved and an unloaded core still feeds its fiber (the network's class javadoc).</li>
 * </ul>
 */
public class CoreSiteBlockEntity extends BlockEntity {

    private boolean unloading;

    public CoreSiteBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.CORE_SITE.get(), pos, state);
    }

    @Override
    public void clearRemoved() {
        super.clearRemoved();
        announce();
    }

    @Override
    public void onLoad() {
        super.onLoad();
        announce();
    }

    private void announce() {
        if (level instanceof ServerLevel serverLevel) {
            unloading = false;
            BackhaulNetwork.of(serverLevel).coreLoaded(getBlockPos());
        }
    }

    @Override
    public void onChunkUnloaded() {
        super.onChunkUnloaded();
        unloading = true;
    }

    @Override
    public void setRemoved() {
        if (!unloading && level instanceof ServerLevel serverLevel) {
            BackhaulNetwork.of(serverLevel).coreRemoved(getBlockPos());
        }
        super.setRemoved();
    }
}
