package dev.rancraft.net;

import dev.rancraft.RanCraft;
import dev.rancraft.client.ClientAntennaConfig;
import dev.rancraft.client.ClientDriveTest;
import dev.rancraft.client.ClientLensState;
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
     * </ul>
     */
    private static final String PROTOCOL_VERSION = "4";

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
