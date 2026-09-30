package dev.rancraft.block;

import dev.rancraft.device.FixedDevice;
import dev.rancraft.world.FixedReceiverRegistry;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Base class for a device block's entity (Phase 3 slice 8, §3B.3): registers it as a fixed receiver
 * when it joins a chunk or loads, and unregisters it when it is removed. The Radio Link (§3B.4)
 * extends it.
 *
 * <p>These are the block entity's own lifecycle paths; {@link FixedReceiverRegistry} handles the chunk
 * load and unload events itself, for any block entity that is a {@link FixedDevice}. Registration is
 * keyed by position and unregistration is by identity, so a replaced entity's late {@code setRemoved}
 * never drops its successor.
 *
 * <ul>
 *   <li>{@link #clearRemoved()} (slice 9): {@code LevelChunk.setBlockEntity} calls it, after
 *       {@code setLevel}, on every block entity it puts into a chunk: a block placed (by a player, a
 *       command, a structure) and every entity of a chunk loading from disk. It is the only caller in
 *       1.21.1. This registers a device the moment it exists, even in a dimension whose block entities
 *       are not ticking (no player, no forced chunk, over 300 ticks), where {@code onLoad} is deferred
 *       and no chunk event fires for a chunk that is already loaded. The block's {@code onPlace} cannot
 *       do it: {@code LevelChunk.setBlockState} calls {@code onPlace} before it creates the entity.
 *   <li>{@link #onLoad()}: NeoForge's deferred load hook, run at the dimension's next block-entity tick.
 *       Idempotent after the above; kept so a device added some other way still registers.
 *   <li>{@link #setRemoved()}: the block broken or replaced, and also every entity of an unloading chunk
 *       ({@code ServerLevel.unload} → {@code LevelChunk.clearAllBlockEntities}, after the chunk unload
 *       event), so a device stops on unload by two paths.
 * </ul>
 */
public abstract class FixedDeviceBlockEntity extends BlockEntity implements FixedDevice {

    protected FixedDeviceBlockEntity(BlockEntityType<?> type, BlockPos pos, BlockState state) {
        super(type, pos, state);
    }

    /**
     * Put into a chunk (see the class javadoc). On the client, or with no level yet, registers nothing.
     * A chunk still being promoted to FULL may register its devices here a moment before its load event
     * does; the ticker skips a receiver whose chunk is not FULL, and the unload paths drop it.
     */
    @Override
    public void clearRemoved() {
        super.clearRemoved();
        FixedReceiverRegistry.hostLoaded(this);
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
