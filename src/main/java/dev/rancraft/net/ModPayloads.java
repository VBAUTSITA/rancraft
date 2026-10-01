package dev.rancraft.net;

import dev.rancraft.RanCraft;
import dev.rancraft.client.ClientAntennaConfig;
import dev.rancraft.client.ClientDriveTest;
import dev.rancraft.client.ClientLensState;
import dev.rancraft.client.ClientLocatorState;
import dev.rancraft.client.ClientSignalState;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

@EventBusSubscriber(modid = RanCraft.MOD_ID, bus = EventBusSubscriber.Bus.MOD)
public final class ModPayloads {

    private ModPayloads() {
    }

    /**
     * Bumped when the wire format changes; clients on a different version are rejected.
     *
     * <ul>
     *   <li><b>2</b> -- Phase 2: {@link SignalSamplePayload} gained SINR and the serving cell id, and
     *       the two antenna-configuration payloads are new.
     *   <li><b>3</b> -- RF Lens Step 2: {@link LensLinksPayload}, {@link CoverageSurveyPayload} and
     *       {@link LensControlPayload} are new. A Step 1 client would have no handler for the first
     *       two and no key bindings to send the third.
     *   <li><b>4</b> -- RF Vision Step 3a: {@link SignalSamplePayload} v3 appends the evaluation
     *       point and the heard-cell count. A Step 2 client would stop reading four fields early.
     *   <li><b>5</b> -- Phase 3 slice 5: {@link LocatorFixPayload} is new (the Network Locator), and
     *       the Locator's waypoint data component is synced. A slice 4 client has no handler for the
     *       payload and does not know the component.
     *   <li><b>6</b> -- Phase 3 slice 6: mast columns. The antennas' block-entity update tag appends
     *       {@code OnAir}, and the RF Lens now draws one lobe per column, at its top, working the
     *       column out from the blocks as the server does. No payload changed shape, but a slice 5
     *       client on a slice 6 server would draw a lobe on every stacked mast (and none greyed), and
     *       the reverse pairing would draw one lobe where nine cells transmit.
     *   <li><b>7</b> -- Phase 3B review: the antennas' update tag appends {@code RadiatingY}, the
     *       radiating height the server worked out with its own {@code maxMastHeight} (a COMMON config,
     *       not synced), and the lens draws a column's lobe there. A slice 6 client would draw it at its
     *       own cap's height.
     * </ul>
     */
    private static final String PROTOCOL_VERSION = "7";

    @SubscribeEvent
    public static void register(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar registrar = event.registrar(PROTOCOL_VERSION);

        // One sample feeds two views: the meter HUD's latest reading, and the drive-test log behind
        // the lens trail. Both classes are named only inside the handler, as below.
        registrar.playToClient(
                SignalSamplePayload.TYPE,
                SignalSamplePayload.STREAM_CODEC,
                (payload, context) -> context.enqueueWork(() -> {
                    ClientSignalState.accept(payload);
                    ClientDriveTest.accept(payload);
                }));

        // The lambda body is the only reference to the client-only class, and a dedicated server
        // never receives an S2C packet, so this never resolves ClientAntennaConfig server-side.
        registrar.playToClient(
                OpenAntennaConfigPayload.TYPE,
                OpenAntennaConfigPayload.STREAM_CODEC,
                (payload, context) -> context.enqueueWork(() -> ClientAntennaConfig.open(payload)));

        // RF Lens Step 2. Same pattern: the client state class is named only inside the handler.
        registrar.playToClient(
                LensLinksPayload.TYPE,
                LensLinksPayload.STREAM_CODEC,
                (payload, context) -> context.enqueueWork(() -> ClientLensState.acceptLinks(payload)));

        registrar.playToClient(
                CoverageSurveyPayload.TYPE,
                CoverageSurveyPayload.STREAM_CODEC,
                (payload, context) -> context.enqueueWork(() -> ClientLensState.acceptCoverage(payload)));

        // Phase 3 slice 5: the Network Locator's fix, sent only while a Locator is held. Same
        // pattern: the client state class is named only inside the handler.
        registrar.playToClient(
                LocatorFixPayload.TYPE,
                LocatorFixPayload.STREAM_CODEC,
                (payload, context) -> context.enqueueWork(() -> ClientLocatorState.accept(payload)));

        registrar.playToServer(
                UpdateCellParamsPayload.TYPE,
                UpdateCellParamsPayload.STREAM_CODEC,
                (payload, context) -> context.enqueueWork(() -> {
                    if (context.player() instanceof ServerPlayer player) {
                        UpdateCellParamsPayload.applyOn(player, payload);
                    }
                }));

        registrar.playToServer(
                LensControlPayload.TYPE,
                LensControlPayload.STREAM_CODEC,
                (payload, context) -> context.enqueueWork(() -> {
                    if (context.player() instanceof ServerPlayer player) {
                        LensControlPayload.handle(player, payload);
                    }
                }));
    }
}
