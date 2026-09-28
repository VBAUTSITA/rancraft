package dev.rancraft.client;

import dev.rancraft.RanCraft;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;

/** Keeps the client readouts from outliving the connection, or the life, they belong to. */
@EventBusSubscriber(modid = RanCraft.MOD_ID, value = Dist.CLIENT)
public final class ClientEvents {

    private ClientEvents() {
    }

    @SubscribeEvent
    public static void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        ClientSignalState.clear();
        ClientLensState.clear();
        LensRenderer.clearCache();
        CoverageRenderer.clearCache();
    }

    /**
     * Fires on respawn and on dimension change. The lens state goes too: a coverage survey of the
     * old dimension must not be painted over the new one. The server drops its survey job on the
     * same events and starts a fresh one, so the painting comes back on its own.
     */
    @SubscribeEvent
    public static void onClone(ClientPlayerNetworkEvent.Clone event) {
        ClientSignalState.clear();
        ClientLensState.clear();
        CoverageRenderer.clearCache();
    }
}
