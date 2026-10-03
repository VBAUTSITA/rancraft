package dev.rancraft.registry;

import dev.rancraft.RanCraft;
import dev.rancraft.block.BackhaulDishBlock;
import dev.rancraft.block.CoreSiteBlock;
import dev.rancraft.block.RadioLinkReceiverBlock;
import dev.rancraft.block.RadioLinkTransmitterBlock;
import dev.rancraft.block.SectorAntennaBlock;
import dev.rancraft.block.SignalMastBlock;
import dev.rancraft.block.SiteGeneratorBlock;
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

    /** Phase 3 slice 12, §3C.2: the core network; cells and dishes near it are on fiber. */
    public static final DeferredBlock<CoreSiteBlock> CORE_SITE = BLOCKS.registerBlock(
            "core_site",
            CoreSiteBlock::new,
            BlockBehaviour.Properties.of()
                    .mapColor(MapColor.METAL)
                    .strength(5.0F, 6.0F)
                    .requiresCorrectToolForDrops()
                    .sound(SoundType.METAL));

    /** Phase 3 slice 12, §3C.2: one end of a point-to-point microwave backhaul hop. */
    public static final DeferredBlock<BackhaulDishBlock> BACKHAUL_DISH = BLOCKS.registerBlock(
            "backhaul_dish",
            BackhaulDishBlock::new,
            BlockBehaviour.Properties.of()
                    .mapColor(MapColor.METAL)
                    .strength(3.0F, 6.0F)
                    .requiresCorrectToolForDrops()
                    .sound(SoundType.METAL));

    /** Phase 3 slice 15, §3C.5: burns furnace fuel and feeds the FE receivers next to it. */
    public static final DeferredBlock<SiteGeneratorBlock> SITE_GENERATOR = BLOCKS.registerBlock(
            "site_generator",
            SiteGeneratorBlock::new,
            BlockBehaviour.Properties.of()
                    .mapColor(MapColor.STONE)
                    .strength(3.5F, 6.0F)
                    .requiresCorrectToolForDrops()
                    .sound(SoundType.METAL)
                    .lightLevel(state -> state.getValue(SiteGeneratorBlock.LIT) ? SiteGeneratorBlock.LIT_LIGHT : 0));

    public static void register(IEventBus modBus) {
        BLOCKS.register(modBus);
    }
}
