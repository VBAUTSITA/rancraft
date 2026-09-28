package dev.rancraft.client;

import dev.rancraft.RanCraft;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;

/**
 * Keeps the client readouts from outliving the connection, or the life, they belong to.
 *
 * <p>The drive-test log ({@link ClientDriveTest}) is deliberately <em>not</em> cleared here. It is a
 * record of a walk, not a live readout: it is kept per dimension so a portal trip does not smear
 * one dimension's trail over another, and it survives a relog until the player runs
 * {@code /rancraftc drivetest clear}. VISION_STEP3.md does not ask for it to be cleared; see NOTES.md.
 */
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
