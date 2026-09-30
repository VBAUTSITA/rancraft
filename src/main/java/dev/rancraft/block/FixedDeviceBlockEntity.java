package dev.rancraft.block;

import dev.rancraft.device.FixedDevice;
import dev.rancraft.world.FixedReceiverRegistry;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Base class for a device block's entity (Phase 3 slice 8, §3B.3): registers it as a fixed receiver
 * when it loads and unregisters it when it is removed. The Radio Link (§3B.4) extends it.
 *
 * <p>These are two of the four lifecycle paths; {@link FixedReceiverRegistry} handles the chunk load
 * and unload events itself, for any block entity that is a {@link FixedDevice}. Registration is keyed
 * by position and unregistration is by identity, so a replaced entity's late {@code setRemoved} never
 * drops its successor.
 *
 * <p>{@code setRemoved} also runs for every block entity of an unloading chunk
 * ({@code ServerLevel.unload} → {@code LevelChunk.clearAllBlockEntities}, after the chunk unload
 * event), so a device stops on unload by two paths. {@code onLoad} is deferred by NeoForge to the
 * dimension's next block-entity tick, which is why the chunk load event registers too.
 */
public abstract class FixedDeviceBlockEntity extends BlockEntity implements FixedDevice {

    protected FixedDeviceBlockEntity(BlockEntityType<?> type, BlockPos pos, BlockState state) {
        super(type, pos, state);
    }

    @Override
    public void onLoad() {
        super.onLoad();
        FixedReceiverRegistry.hostLoaded(this);
    }

    @Override
    public void setRemoved() {
        FixedReceiverRegistry.hostRemoved(this);
        super.setRemoved();
    }
}
