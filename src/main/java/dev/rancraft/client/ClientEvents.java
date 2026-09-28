package dev.rancraft.client;

import dev.rancraft.RanCraft;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;

/**
 * Keeps the client readouts from outliving the connection, or the life, they belong to.
 *
 * <p>The drive-test log ({@link ClientDriveTest}) goes on logging out, with everything else: it is
 * keyed only by dimension, so a trail kept past a disconnect would be drawn, classified against and
 * exported with the next world's trail under the same dimension names. It is deliberately
 * <em>not</em> cleared on {@link #onClone} (respawn, dimension change): within one session its
 * per-dimension split already keeps a portal trip from smearing trails together, and a record of a
 * walk should survive a death or a portal.
 */
@EventBusSubscriber(modid = RanCraft.MOD_ID, value = Dist.CLIENT)
public final class ClientEvents {

    private ClientEvents() {
    }

    /**
     * Leaving a world. NeoForge also fires this before every join (a new singleplayer world, a
     * server, a server transfer: each goes through {@code Minecraft.disconnect}), so a session
     * always starts with nothing from the last one.
     */
    @SubscribeEvent
    public static void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        ClientSignalState.clear();
        ClientLensState.clear();
        LensRenderer.clearCache();
        CoverageRenderer.clearCache();
        ClientDriveTest.clear();
    }

    /**
     * Fires on respawn and on dimension change. The lens state goes too: a coverage survey of the
     * old dimension must not be painted over the new one. The server drops its survey job on the
     * same events and starts a fresh one, so the painting comes back on its own. The drive-test log
     * stays (see the class comment).
     */
    @SubscribeEvent
    public static void onClone(ClientPlayerNetworkEvent.Clone event) {
        ClientSignalState.clear();
        ClientLensState.clear();
        CoverageRenderer.clearCache();
    }
}
