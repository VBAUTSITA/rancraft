package dev.rancraft.registry;

import dev.rancraft.RanCraft;
import dev.rancraft.item.FieldTestMeterItem;
import dev.rancraft.item.LinkToolItem;
import dev.rancraft.item.NetworkLocatorItem;
import dev.rancraft.item.ProximityScannerItem;
import dev.rancraft.item.RfLensItem;
import dev.rancraft.item.StorageTerminalItem;
import dev.rancraft.item.WidebandRadioUnitItem;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

public final class ModItems {

    private ModItems() {
    }

    /**
     * Every entry here, block items included, has a crafting recipe in {@code data/rancraft/recipe/}
     * (named after the item), an advancement that unlocks it in the recipe book under
     * {@code data/rancraft/advancement/recipes/}, and a place in {@code ModCreativeTabs}. Phase 3 slice
     * 16 (§3C.6); {@code RecipeGameTests} checks all three for every entry, so a new item fails the game
     * tests until it has them.
     *
     * <p>Game abstraction, stated plainly: the recipes are progression, not a bill of materials. Their
     * ingredients are thematic (copper for RF, a compass in the Locator, a sculk sensor in the
     * Scanner) and their tiers pace the game (the RF Lens is deliberately cheap and early, §3C.1); no
     * real radio is built from these parts.
     */
    public static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(RanCraft.MOD_ID);

    public static final DeferredItem<FieldTestMeterItem> FIELD_TEST_METER = ITEMS.registerItem(
            "field_test_meter",
            FieldTestMeterItem::new,
            new Item.Properties().stacksTo(1));

    public static final DeferredItem<RfLensItem> RF_LENS = ITEMS.registerItem(
            "rf_lens",
            RfLensItem::new,
            new Item.Properties().stacksTo(1));

    /** Phase 3 slice 5, §3A.6. Cellular positioning, not GPS: see {@link NetworkLocatorItem}. */
    public static final DeferredItem<NetworkLocatorItem> NETWORK_LOCATOR = ITEMS.registerItem(
            "network_locator",
            NetworkLocatorItem::new,
            new Item.Properties().stacksTo(1));

    /**
     * Phase 3 slice 10, §3C.1: raises a Sector Antenna's radio to tier 3 (band_3500). Consumed when
     * fitted; breaking the antenna drops it. Stacks like any part.
     */
    public static final DeferredItem<WidebandRadioUnitItem> WIDEBAND_RADIO_UNIT = ITEMS.registerItem(
            "wideband_radio_unit",
            WidebandRadioUnitItem::new,
            new Item.Properties());

    public static final DeferredItem<BlockItem> SIGNAL_MAST = ITEMS.registerSimpleBlockItem(ModBlocks.SIGNAL_MAST);

    public static final DeferredItem<BlockItem> SECTOR_ANTENNA =
            ITEMS.registerSimpleBlockItem(ModBlocks.SECTOR_ANTENNA);

    /** Phase 3 slice 9, §3B.4. */
    public static final DeferredItem<BlockItem> RADIO_LINK_TRANSMITTER =
            ITEMS.registerSimpleBlockItem(ModBlocks.RADIO_LINK_TRANSMITTER);

    public static final DeferredItem<BlockItem> RADIO_LINK_RECEIVER =
            ITEMS.registerSimpleBlockItem(ModBlocks.RADIO_LINK_RECEIVER);

    /** Phase 3 slice 12, §3C.2. */
    public static final DeferredItem<BlockItem> CORE_SITE = ITEMS.registerSimpleBlockItem(ModBlocks.CORE_SITE);

    public static final DeferredItem<BlockItem> BACKHAUL_DISH = ITEMS.registerSimpleBlockItem(ModBlocks.BACKHAUL_DISH);

    /** Phase 3 slice 15, §3C.5. */
    public static final DeferredItem<BlockItem> SITE_GENERATOR = ITEMS.registerSimpleBlockItem(ModBlocks.SITE_GENERATOR);

    /** Phase 3 slice 12, §3C.2: pairs two Backhaul Dishes. */
    public static final DeferredItem<LinkToolItem> LINK_TOOL = ITEMS.registerItem(
            "link_tool",
            LinkToolItem::new,
            new Item.Properties().stacksTo(1));

    /**
     * Phase 3 slice 13, §3C.3: opens a chest or barrel near a Core Site over the mobile network. A
     * {@code SignalDevice} (GOOD, tier 2).
     */
    public static final DeferredItem<StorageTerminalItem> STORAGE_TERMINAL = ITEMS.registerItem(
            "storage_terminal",
            StorageTerminalItem::new,
            new Item.Properties().stacksTo(1));

    /**
     * Phase 3 slice 14, §3C.4: lists hostile mobs on the HUD while held, with GOOD service on a tier-3
     * band (band_3500). A {@code SignalDevice}; the list is not RF sensing (see the item).
     */
    public static final DeferredItem<ProximityScannerItem> PROXIMITY_SCANNER = ITEMS.registerItem(
            "proximity_scanner",
            ProximityScannerItem::new,
            new Item.Properties().stacksTo(1));

    public static void register(IEventBus modBus) {
        ITEMS.register(modBus);
    }
}
