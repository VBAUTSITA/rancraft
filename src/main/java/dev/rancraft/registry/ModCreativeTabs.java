package dev.rancraft.registry;

import dev.rancraft.RanCraft;
import java.util.function.Supplier;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredRegister;

public final class ModCreativeTabs {

    private ModCreativeTabs() {
    }

    public static final DeferredRegister<CreativeModeTab> CREATIVE_MODE_TABS =
            DeferredRegister.create(Registries.CREATIVE_MODE_TAB, RanCraft.MOD_ID);

    public static final Supplier<CreativeModeTab> RANCRAFT = CREATIVE_MODE_TABS.register(
            "rancraft",
            () -> CreativeModeTab.builder()
                    .title(Component.translatable("itemGroup.rancraft"))
                    .icon(() -> new ItemStack(ModItems.FIELD_TEST_METER.get()))
                    .displayItems((params, output) -> {
                        output.accept(ModItems.SIGNAL_MAST.get());
                        output.accept(ModItems.SECTOR_ANTENNA.get());
                        output.accept(ModItems.FIELD_TEST_METER.get());
                        output.accept(ModItems.RF_LENS.get());
                    })
                    .build());

    public static void register(IEventBus modBus) {
        CREATIVE_MODE_TABS.register(modBus);
    }
}
