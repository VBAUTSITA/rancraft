package dev.rancraft.block;

import dev.rancraft.registry.ModBlockEntities;
import net.minecraft.core.BlockPos;
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
 */
public class SignalMastBlockEntity extends AntennaBlockEntity {

    public SignalMastBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.SIGNAL_MAST.get(), pos, state);
    }
}
