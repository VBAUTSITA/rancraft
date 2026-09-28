package dev.rancraft.client;

import com.mojang.blaze3d.platform.InputConstants;
import dev.rancraft.RanCraft;
import dev.rancraft.item.RfLensItem;
import dev.rancraft.net.LensControlPayload;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.player.LocalPlayer;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.client.settings.KeyConflictContext;
import net.neoforged.neoforge.network.PacketDistributor;
import org.lwjgl.glfw.GLFW;

/**
 * The RF Lens's two key bindings: cycle the band filter (default B) and cycle the layer presets
 * (default V).
 *
 * <p>Keys rather than right-click, because the lens is used while it is on your head, where
 * right-click cannot reach it. A press sends only the <em>action</em> to the server
 * ({@link LensControlPayload}); the server works out the next band or preset from the lens it can
 * see you wearing, writes it onto the stack, and confirms on the action bar. The new settings come
 * back through the item's ordinary data-component sync. Nothing is changed client-side first, so
 * the client can never disagree with the server about what the lens shows.
 *
 * <p>Both listeners live here: FML routes {@link RegisterKeyMappingsEvent} to the mod bus and
 * {@link ClientTickEvent.Post} to the game bus on its own, by event type.
 */
@EventBusSubscriber(modid = RanCraft.MOD_ID, value = Dist.CLIENT)
public final class LensKeys {

    private LensKeys() {
    }

    public static final String CATEGORY = "key.categories.rancraft";

    /**
     * The mappings, in a holder so they are built when the registration event first asks for
     * them rather than when this subscriber class is loaded during mod construction.
     *
     * <p>In-game only: the keys mean nothing with a screen open, and saying so lets NeoForge keep
     * them from clashing with GUI bindings on the same key.
     */
    static final class Bindings {

        private Bindings() {
        }

        static final KeyMapping CYCLE_BAND = new KeyMapping(
                "key.rancraft.lens_band",
                KeyConflictContext.IN_GAME,
                InputConstants.Type.KEYSYM,
                GLFW.GLFW_KEY_B,
                CATEGORY);

        static final KeyMapping CYCLE_LAYERS = new KeyMapping(
                "key.rancraft.lens_layers",
                KeyConflictContext.IN_GAME,
                InputConstants.Type.KEYSYM,
                GLFW.GLFW_KEY_V,
                CATEGORY);
    }

    /**
     * Presses acted on per key per tick. Far above any human rate; it only stops a stuck or
     * scripted key from queueing a burst of packets.
     */
    private static final int MAX_PRESSES_PER_TICK = 4;

    /** Set once the mappings are registered; ticks before that have nothing to read. */
    private static volatile boolean registered;

    @SubscribeEvent
    public static void onRegisterKeyMappings(RegisterKeyMappingsEvent event) {
        event.register(Bindings.CYCLE_BAND);
        event.register(Bindings.CYCLE_LAYERS);
        registered = true;
    }

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        if (!registered) {
            return;
        }
        boolean canSend = canSend();
        drain(Bindings.CYCLE_BAND, LensControlPayload.CYCLE_BAND, canSend);
        drain(Bindings.CYCLE_LAYERS, LensControlPayload.CYCLE_LAYERS, canSend);
    }

    /**
     * Presses are always consumed, even when they cannot be acted on, so a press made without a
     * lens on does not fire later when one is put on.
     */
    private static void drain(KeyMapping key, int action, boolean canSend) {
        int sent = 0;
        while (key.consumeClick()) {
            if (canSend && sent < MAX_PRESSES_PER_TICK) {
                PacketDistributor.sendToServer(new LensControlPayload(action));
                sent++;
            }
        }
    }

    /**
     * Wearing a lens, connected, and talking to a server that has the lens channel. Without a lens
     * the keys do nothing and say nothing: they may share a key with another mod's binding, and a
     * message on every press of that would be noise.
     */
    private static boolean canSend() {
        Minecraft minecraft = Minecraft.getInstance();
        LocalPlayer player = minecraft.player;
        ClientPacketListener connection = minecraft.getConnection();
        return player != null
                && connection != null
                && RfLensItem.wornBy(player) != null
                && connection.hasChannel(LensControlPayload.TYPE);
    }
}
