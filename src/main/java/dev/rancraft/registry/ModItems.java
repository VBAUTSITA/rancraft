package dev.rancraft.registry;

import dev.rancraft.RanCraft;
import dev.rancraft.item.FieldTestMeterItem;
import dev.rancraft.item.LinkToolItem;
import dev.rancraft.item.NetworkLocatorItem;
import dev.rancraft.item.RfLensItem;
import dev.rancraft.item.WidebandRadioUnitItem;
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

    /**
     * Phase 3 slice 10, §3C.1: raises a Sector Antenna's radio to tier 3 (band_3500). Consumed when
     * fitted; breaking the antenna drops it. Stacks like any part. No recipe yet (§3C.6, slice 16).
     */
    public static final DeferredItem<WidebandRadioUnitItem> WIDEBAND_RADIO_UNIT = ITEMS.registerItem(
            "wideband_radio_unit",
            WidebandRadioUnitItem::new,
            new Item.Properties());

    public static final DeferredItem<BlockItem> SIGNAL_MAST = ITEMS.registerSimpleBlockItem(ModBlocks.SIGNAL_MAST);

    public static final DeferredItem<BlockItem> SECTOR_ANTENNA =
            ITEMS.registerSimpleBlockItem(ModBlocks.SECTOR_ANTENNA);

    /** Phase 3 slice 9, §3B.4. No recipe yet (§3C.6, slice 16): creative-only for now. */
    public static final DeferredItem<BlockItem> RADIO_LINK_TRANSMITTER =
            ITEMS.registerSimpleBlockItem(ModBlocks.RADIO_LINK_TRANSMITTER);

    public static final DeferredItem<BlockItem> RADIO_LINK_RECEIVER =
            ITEMS.registerSimpleBlockItem(ModBlocks.RADIO_LINK_RECEIVER);

    /** Phase 3 slice 12, §3C.2. No recipe yet (§3C.6, slice 16): creative-only for now. */
    public static final DeferredItem<BlockItem> CORE_SITE = ITEMS.registerSimpleBlockItem(ModBlocks.CORE_SITE);

    public static final DeferredItem<BlockItem> BACKHAUL_DISH = ITEMS.registerSimpleBlockItem(ModBlocks.BACKHAUL_DISH);

    /** Phase 3 slice 12, §3C.2: pairs two Backhaul Dishes. No recipe yet (§3C.6, slice 16). */
    public static final DeferredItem<LinkToolItem> LINK_TOOL = ITEMS.registerItem(
            "link_tool",
            LinkToolItem::new,
            new Item.Properties().stacksTo(1));

    public static void register(IEventBus modBus) {
        ITEMS.register(modBus);
    }
}
