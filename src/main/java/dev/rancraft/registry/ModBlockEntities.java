package dev.rancraft.registry;

import dev.rancraft.RanCraft;
import dev.rancraft.block.BackhaulDishBlockEntity;
import dev.rancraft.block.CoreSiteBlockEntity;
import dev.rancraft.block.RadioLinkReceiverBlockEntity;
import dev.rancraft.block.RadioLinkTransmitterBlockEntity;
import dev.rancraft.block.SectorAntennaBlockEntity;
import dev.rancraft.block.SignalMastBlockEntity;
import dev.rancraft.block.SiteGeneratorBlockEntity;
import java.util.function.Supplier;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredRegister;

public final class ModBlockEntities {

    private ModBlockEntities() {
    }

    public static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITIES =
            DeferredRegister.create(Registries.BLOCK_ENTITY_TYPE, RanCraft.MOD_ID);

    public static final Supplier<BlockEntityType<SignalMastBlockEntity>> SIGNAL_MAST =
            BLOCK_ENTITIES.register("signal_mast", () -> BlockEntityType.Builder
                    .of(SignalMastBlockEntity::new, ModBlocks.SIGNAL_MAST.get())
                    .build(null));

    public static final Supplier<BlockEntityType<SectorAntennaBlockEntity>> SECTOR_ANTENNA =
            BLOCK_ENTITIES.register("sector_antenna", () -> BlockEntityType.Builder
                    .of(SectorAntennaBlockEntity::new, ModBlocks.SECTOR_ANTENNA.get())
                    .build(null));

    public static final Supplier<BlockEntityType<RadioLinkTransmitterBlockEntity>> RADIO_LINK_TRANSMITTER =
            BLOCK_ENTITIES.register("radio_link_transmitter", () -> BlockEntityType.Builder
                    .of(RadioLinkTransmitterBlockEntity::new, ModBlocks.RADIO_LINK_TRANSMITTER.get())
                    .build(null));

    public static final Supplier<BlockEntityType<RadioLinkReceiverBlockEntity>> RADIO_LINK_RECEIVER =
            BLOCK_ENTITIES.register("radio_link_receiver", () -> BlockEntityType.Builder
                    .of(RadioLinkReceiverBlockEntity::new, ModBlocks.RADIO_LINK_RECEIVER.get())
                    .build(null));

    public static final Supplier<BlockEntityType<CoreSiteBlockEntity>> CORE_SITE =
            BLOCK_ENTITIES.register("core_site", () -> BlockEntityType.Builder
                    .of(CoreSiteBlockEntity::new, ModBlocks.CORE_SITE.get())
                    .build(null));

    public static final Supplier<BlockEntityType<BackhaulDishBlockEntity>> BACKHAUL_DISH =
            BLOCK_ENTITIES.register("backhaul_dish", () -> BlockEntityType.Builder
                    .of(BackhaulDishBlockEntity::new, ModBlocks.BACKHAUL_DISH.get())
                    .build(null));

    public static final Supplier<BlockEntityType<SiteGeneratorBlockEntity>> SITE_GENERATOR =
            BLOCK_ENTITIES.register("site_generator", () -> BlockEntityType.Builder
                    .of(SiteGeneratorBlockEntity::new, ModBlocks.SITE_GENERATOR.get())
                    .build(null));

    public static void register(IEventBus modBus) {
        BLOCK_ENTITIES.register(modBus);
    }
}
