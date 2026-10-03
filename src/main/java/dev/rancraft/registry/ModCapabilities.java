package dev.rancraft.registry;

import dev.rancraft.RanCraft;
import dev.rancraft.block.AntennaBlockEntity;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.capabilities.RegisterCapabilitiesEvent;
import net.neoforged.neoforge.items.wrapper.SidedInvWrapper;

/**
 * Block capabilities (Phase 3 slice 15, §3C.5), registered on the mod bus.
 *
 * <ul>
 *   <li>{@code Capabilities.EnergyStorage.BLOCK} on Signal Masts and Sector Antennas, every side: the
 *       cell's buffer, receive-only ({@link AntennaBlockEntity#energyStorage()}). Any mast of a column
 *       feeds its base.</li>
 *   <li>{@code Capabilities.EnergyStorage.BLOCK} on the Site Generator: its output not yet pushed,
 *       extract-only, so another mod's cable may pull it.</li>
 *   <li>{@code Capabilities.ItemHandler.BLOCK} on the Site Generator: its fuel slot through
 *       {@link SidedInvWrapper}, the way NeoForge exposes a vanilla furnace (vanilla hoppers use the
 *       slot as a {@code WorldlyContainer} either way).</li>
 * </ul>
 *
 * <p>Each provider returns the entity's own object (or, for the item handler, a wrapper over the
 * entity), so it never goes stale; NeoForge invalidates capability caches when a block entity is
 * placed, removed, loaded or unloaded.
 */
@EventBusSubscriber(modid = RanCraft.MOD_ID, bus = EventBusSubscriber.Bus.MOD)
public final class ModCapabilities {

    private ModCapabilities() {
    }

    @SubscribeEvent
    public static void register(RegisterCapabilitiesEvent event) {
        event.registerBlockEntity(Capabilities.EnergyStorage.BLOCK, ModBlockEntities.SIGNAL_MAST.get(),
                (antenna, side) -> antenna.energyStorage());
        event.registerBlockEntity(Capabilities.EnergyStorage.BLOCK, ModBlockEntities.SECTOR_ANTENNA.get(),
                (antenna, side) -> antenna.energyStorage());
        event.registerBlockEntity(Capabilities.EnergyStorage.BLOCK, ModBlockEntities.SITE_GENERATOR.get(),
                (generator, side) -> generator.energyStorage());
        event.registerBlockEntity(Capabilities.ItemHandler.BLOCK, ModBlockEntities.SITE_GENERATOR.get(),
                SidedInvWrapper::new);
    }
}
