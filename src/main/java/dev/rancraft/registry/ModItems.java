package dev.rancraft.registry;

import dev.rancraft.RanCraft;
import dev.rancraft.item.FieldTestMeterItem;
import dev.rancraft.item.NetworkLocatorItem;
import dev.rancraft.item.RfLensItem;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

public final class ModItems {

    private ModItems() {
    }

    public static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(RanCraft.MOD_ID);

    public static final DeferredItem<FieldTestMeterItem> FIELD_TEST_METER = ITEMS.registerItem(
            "field_test_meter",
            FieldTestMeterItem::new,
            new Item.Properties().stacksTo(1));

    public static final DeferredItem<RfLensItem> RF_LENS = ITEMS.registerItem(
            "rf_lens",
            RfLensItem::new,
            new Item.Properties().stacksTo(1));

    /**
     * Phase 3 slice 5, §3A.6. Cellular positioning, not GPS: see {@link NetworkLocatorItem}. No
     * recipe yet; recipes are §3C.6, so it is creative-only like everything else for now.
     */
    public static final DeferredItem<NetworkLocatorItem> NETWORK_LOCATOR = ITEMS.registerItem(
            "network_locator",
            NetworkLocatorItem::new,
            new Item.Properties().stacksTo(1));

    public static final DeferredItem<BlockItem> SIGNAL_MAST = ITEMS.registerSimpleBlockItem(ModBlocks.SIGNAL_MAST);

    public static final DeferredItem<BlockItem> SECTOR_ANTENNA =
            ITEMS.registerSimpleBlockItem(ModBlocks.SECTOR_ANTENNA);

    public static void register(IEventBus modBus) {
        ITEMS.register(modBus);
    }
}
