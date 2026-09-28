package dev.rancraft.registry;

import dev.rancraft.RanCraft;
import dev.rancraft.block.SectorAntennaBlock;
import dev.rancraft.block.SignalMastBlock;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.MapColor;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredRegister;

public final class ModBlocks {

    private ModBlocks() {
    }

    public static final DeferredRegister.Blocks BLOCKS = DeferredRegister.createBlocks(RanCraft.MOD_ID);

    public static final DeferredBlock<SignalMastBlock> SIGNAL_MAST = BLOCKS.registerBlock(
            "signal_mast",
            SignalMastBlock::new,
            BlockBehaviour.Properties.of()
                    .mapColor(MapColor.METAL)
                    .strength(3.0F, 6.0F)
                    .requiresCorrectToolForDrops()
                    .sound(SoundType.METAL)
                    .noOcclusion());

    public static final DeferredBlock<SectorAntennaBlock> SECTOR_ANTENNA = BLOCKS.registerBlock(
            "sector_antenna",
            SectorAntennaBlock::new,
            BlockBehaviour.Properties.of()
                    .mapColor(MapColor.METAL)
                    .strength(3.0F, 6.0F)
                    .requiresCorrectToolForDrops()
                    .sound(SoundType.METAL)
                    .noOcclusion());

    public static void register(IEventBus modBus) {
        BLOCKS.register(modBus);
    }
}
