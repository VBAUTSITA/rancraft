package dev.rancraft.registry;

import com.mojang.serialization.Codec;
import dev.rancraft.RanCraft;
import dev.rancraft.item.LensSettings;
import dev.rancraft.item.LocatorWaypoints;
import java.util.function.Supplier;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.network.codec.ByteBufCodecs;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredRegister;

/**
 * Item-side state lives in data components, not NBT.
 *
 * <p>Phase 1 only needs the HUD mode flag. Phase 2 will add band selection and a locked-cell
 * component here, which is why this class exists at all rather than the flag being inlined.
 */
public final class ModDataComponents {

    private ModDataComponents() {
    }

    public static final DeferredRegister.DataComponents DATA_COMPONENTS =
            DeferredRegister.createDataComponents(RanCraft.MOD_ID);

    /** True when the Field Test Meter should draw the detailed readout instead of the compact one. */
    public static final Supplier<DataComponentType<Boolean>> METER_DETAILED =
            DATA_COMPONENTS.registerComponentType("meter_detailed", builder -> builder
                    .persistent(Codec.BOOL)
                    .networkSynchronized(ByteBufCodecs.BOOL));

    /**
     * What the RF Lens is currently drawing. Carries the band filter that multi-band vision will
     * use, so adding it later is a renderer change rather than a protocol change. See VISION.md.
     */
    public static final Supplier<DataComponentType<LensSettings>> LENS_SETTINGS =
            DATA_COMPONENTS.registerComponentType("lens_settings", builder -> builder
                    .persistent(LensSettings.CODEC)
                    .networkSynchronized(LensSettings.STREAM_CODEC));

    /**
     * The Network Locator's saved waypoints and which one is selected (Phase 3 slice 5, §3A.6).
     * Each is an estimate the Locator reported, never the true position. Synced so the HUD can show
     * distance and bearing to the selected one; the stream codec rejects more than 8 entries.
     */
    public static final Supplier<DataComponentType<LocatorWaypoints>> LOCATOR_WAYPOINTS =
            DATA_COMPONENTS.registerComponentType("locator_waypoints", builder -> builder
                    .persistent(LocatorWaypoints.CODEC)
                    .networkSynchronized(LocatorWaypoints.STREAM_CODEC));

    public static void register(IEventBus modBus) {
        DATA_COMPONENTS.register(modBus);
    }
}
