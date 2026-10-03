package dev.rancraft.client;

import dev.rancraft.RanCraft;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;

/**
 * Keeps the client readouts from outliving the connection, or the life, they belong to.
 *
 * <p>The drive-test log ({@link ClientDriveTest}) goes on logging out, with everything else: it is
 * keyed only by dimension, so a trail kept past a disconnect would be drawn, classified against and
 * exported with the next world's trail under the same dimension names. It is deliberately
 * <em>not</em> cleared on {@link #onClone} (respawn, dimension change): within one session its
 * per-dimension split already keeps a portal trip from smearing trails together, and a record of a
 * walk should survive a death or a portal.
 *
 * <p>The Network Locator's reading also goes whenever no Locator is held ({@link #onClientTick}).
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
        ClientLocatorState.clear();
        ClientScannerState.clear();
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
        ClientLocatorState.clear();
        ClientScannerState.clear();
        ClientLensState.clear();
        CoverageRenderer.clearCache();
    }

    /**
     * The Locator's payload is sent only while it is held ({@code NetworkLocator.sendsPayload}), so a
     * reading kept after the Locator leaves the hand is from where the player was, not where they
     * are. Drop it, so re-selecting the Locator shows "no reading yet" until the first payload sent
     * after it is held again, instead of an old fix (and its rings) drawn as current. Phase 3A
     * review, round 1.
     *
     * <p>Here, not in the HUD layer: the layer returns early under F1 ({@code hideGui}), and
     * {@link LocatorRenderer} would still draw the old rings. The learned send cadence is kept
     * ({@link ClientLocatorState#putAway()}).
     *
     * <p>Phase 3 slice 14: the same for the Proximity Scanner, whose list is of mobs near where the
     * player was when it was last held ({@link ClientScannerState#putAway()}).
     */
    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null || LocatorHudOverlay.heldLocator(player) == null) {
            ClientLocatorState.putAway();
        }
        if (player == null || ScannerHudOverlay.heldScanner(player) == null) {
            ClientScannerState.putAway();
        }
    }
}
