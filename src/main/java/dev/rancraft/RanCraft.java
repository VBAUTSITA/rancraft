package dev.rancraft;

import com.mojang.logging.LogUtils;
import dev.rancraft.data.RfDataLoader;
import dev.rancraft.registry.ModBlockEntities;
import dev.rancraft.registry.ModBlocks;
import dev.rancraft.registry.ModCreativeTabs;
import dev.rancraft.registry.ModDataComponents;
import dev.rancraft.registry.ModItems;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.neoforge.event.AddReloadListenerEvent;
import net.neoforged.neoforge.event.TagsUpdatedEvent;
import org.slf4j.Logger;

@Mod(RanCraft.MOD_ID)
public final class RanCraft {

    public static final String MOD_ID = "rancraft";
    public static final Logger LOGGER = LogUtils.getLogger();

    public RanCraft(IEventBus modBus, ModContainer container) {
        ModBlocks.register(modBus);
        ModItems.register(modBus);
        ModBlockEntities.register(modBus);
        ModDataComponents.register(modBus);
        ModCreativeTabs.register(modBus);

        container.registerConfig(ModConfig.Type.COMMON, RanCraftConfig.SPEC);
        // RF Vision Step 3a: the drive-test trail's own knobs. Only loaded on a physical client.
        container.registerConfig(ModConfig.Type.CLIENT, RanCraftConfig.CLIENT_SPEC);

        LOGGER.info("RANCraft initialised");
    }

    /** Game-bus handlers that wire the data layer to the reload cycle. */
    @EventBusSubscriber(modid = MOD_ID)
    public static final class DataEvents {

        private DataEvents() {
        }

        @SubscribeEvent
        public static void onAddReloadListener(AddReloadListenerEvent event) {
            event.addListener(new RfDataLoader());
        }

        /**
         * Block tags are not bound while reload listeners run, so the resolved material table is
         * dropped here and rebuilt on next use, once tags are actually queryable.
         */
        @SubscribeEvent
        public static void onTagsUpdated(TagsUpdatedEvent event) {
            RfDataLoader.invalidateMaterials();
        }
    }
}
