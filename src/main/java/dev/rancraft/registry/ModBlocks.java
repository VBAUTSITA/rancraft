package dev.rancraft.registry;

import dev.rancraft.RanCraft;
import dev.rancraft.block.RadioLinkReceiverBlock;
import dev.rancraft.block.RadioLinkTransmitterBlock;
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

    /** Phase 3 slice 9, §3B.4: sends its redstone input over the network. */
    public static final DeferredBlock<RadioLinkTransmitterBlock> RADIO_LINK_TRANSMITTER = BLOCKS.registerBlock(
            "radio_link_transmitter",
            RadioLinkTransmitterBlock::new,
            BlockBehaviour.Properties.of()
                    .mapColor(MapColor.COLOR_ORANGE)
                    .strength(3.0F, 6.0F)
                    .requiresCorrectToolForDrops()
                    .sound(SoundType.COPPER));

    /** Phase 3 slice 9, §3B.4: outputs redstone while a transmitter on its address is powered. */
    public static final DeferredBlock<RadioLinkReceiverBlock> RADIO_LINK_RECEIVER = BLOCKS.registerBlock(
            "radio_link_receiver",
            RadioLinkReceiverBlock::new,
            BlockBehaviour.Properties.of()
                    .mapColor(MapColor.COLOR_ORANGE)
                    .strength(3.0F, 6.0F)
                    .requiresCorrectToolForDrops()
                    .sound(SoundType.COPPER));

    public static void register(IEventBus modBus) {
        BLOCKS.register(modBus);
    }
}
