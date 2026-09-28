package dev.rancraft.net;

import dev.rancraft.RanCraft;
import dev.rancraft.data.RfDataLoader;
import dev.rancraft.item.LensLayers;
import dev.rancraft.item.LensSettings;
import dev.rancraft.item.RfLensItem;
import dev.rancraft.registry.ModDataComponents;
import dev.rancraft.rf.Band;
import dev.rancraft.rf.BandTable;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

/**
 * Client to server: one press of an RF Lens key.
 *
 * <p>The packet names an action, never a value. The server works out the next band or layer preset
 * from the lens the player is actually wearing and the bands it actually has loaded, so a client
 * cannot set a filter to a band that does not exist, or edit a lens it is not wearing. The new
 * settings reach the client the ordinary way, through the item's synced data component.
 *
 * @param action {@link #CYCLE_BAND} or {@link #CYCLE_LAYERS}; anything else is ignored.
 */
public record LensControlPayload(int action) implements CustomPacketPayload {

    /** All bands, then each loaded band by ascending frequency, then back to all. */
    public static final int CYCLE_BAND = 0;

    /** {@link LensLayers#ALL} -> ANTENNAS -> LINKS -> COVERAGE -> TRAIL -> ALL. */
    public static final int CYCLE_LAYERS = 1;

    public static final CustomPacketPayload.Type<LensControlPayload> TYPE =
            new CustomPacketPayload.Type<>(
                    ResourceLocation.fromNamespaceAndPath(RanCraft.MOD_ID, "lens_control"));

    public static final StreamCodec<FriendlyByteBuf, LensControlPayload> STREAM_CODEC =
            StreamCodec.of(
                    (buf, payload) -> buf.writeVarInt(payload.action),
                    buf -> new LensControlPayload(buf.readVarInt()));

    /**
     * Applies one key press, on the server thread.
     *
     * <p>Not wearing a lens is not an error worth a warning: the key can be pressed in the moment
     * the lens comes off, with the packet already in flight. It is logged at debug and dropped.
     */
    public static void handle(ServerPlayer player, LensControlPayload payload) {
        ItemStack lens = RfLensItem.wornBy(player);
        if (lens == null) {
            RanCraft.LOGGER.debug("RANCraft ignored lens action {} from {}: no RF Lens worn",
                    payload.action, player.getGameProfile().getName());
            return;
        }

        LensSettings current = RfLensItem.settingsOf(lens);
        LensSettings next = switch (payload.action) {
            case CYCLE_BAND -> current.withBandFilter(nextBandFilter(current.bandFilter(), RfDataLoader.bands()));
            case CYCLE_LAYERS -> current.withLayers(current.layers().next());
            default -> null;
        };
        if (next == null) {
            RanCraft.LOGGER.debug("RANCraft ignored unknown lens action {} from {}",
                    payload.action, player.getGameProfile().getName());
            return;
        }

        lens.set(ModDataComponents.LENS_SETTINGS.get(), next);
        player.displayClientMessage(
                Component.translatable("rancraft.lens.status", layersComponent(next), bandComponent(next)),
                true);
    }

    /**
     * The filter after {@code current}: {@code ""} (all bands) first, then every loaded band id in
     * ascending frequency (ties by id, so the order is stable across reloads), then back to
     * {@code ""}. A filter naming a band that is no longer loaded -- a datapack removed it -- goes
     * back to {@code ""} rather than jumping to an arbitrary neighbour.
     */
    static String nextBandFilter(String current, BandTable bands) {
        List<Band> sorted = new ArrayList<>(bands.all().values());
        sorted.sort(Comparator.comparingDouble(Band::frequencyMhz).thenComparing(Band::id));

        List<String> order = new ArrayList<>(sorted.size() + 1);
        order.add(LensSettings.ALL_BANDS);
        for (Band band : sorted) {
            order.add(band.id());
        }

        int at = order.indexOf(current == null ? LensSettings.ALL_BANDS : current);
        return at < 0 ? LensSettings.ALL_BANDS : order.get((at + 1) % order.size());
    }

    private static Component layersComponent(LensSettings settings) {
        return Component.translatable(settings.layers().translationKey());
    }

    /** Band ids are data-pack names rather than translation keys, so they are shown as they are. */
    private static Component bandComponent(LensSettings settings) {
        return settings.showsAllBands()
                ? Component.translatable("rancraft.lens.band.all")
                : Component.literal(settings.bandFilter());
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
