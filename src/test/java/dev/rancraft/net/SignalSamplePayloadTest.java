package dev.rancraft.net;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.rancraft.rf.CellSample;
import dev.rancraft.rf.DriveTestLog;
import dev.rancraft.rf.ReceiverState;
import dev.rancraft.rf.ServiceLevel;
import dev.rancraft.rf.SignalSample;
import io.netty.buffer.Unpooled;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.network.FriendlyByteBuf;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The signal sample on the wire, v3 (RF Vision Step 3a): the evaluation point and the heard-cell
 * count survive the trip, the serving cell survives the four-cell cut, and the drive-test sample is
 * a straight copy of what the server sent. Only a Netty buffer is touched, so this runs headless.
 */
class SignalSamplePayloadTest {

    /** A cell with a distinct id, RSRP and PCI; everything else fixed. */
    private static CellSample cell(long id, double rsrpDbm, int pci) {
        return new CellSample(id, (int) id, 70, 0, rsrpDbm, 40.0, 90.0, 0.0,
                "band_900", pci, 6.0, 0.0, 0.0);
    }

    /** {@code n} cells, strongest first, 0.5 dB apart: all inside a 3 dB handover hysteresis. */
    private static List<CellSample> cells(int n) {
        List<CellSample> cells = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            cells.add(cell(100L + i, -80.0 - 0.5 * i, i));
        }
        return cells;
    }

    private static SignalSample sample(List<CellSample> cells, long servingCellId) {
        return new SignalSample(cells, 1234L, servingCellId, -3.5, -78.0, -104.0, ServiceLevel.NONE, 2);
    }

    private static SignalSamplePayload roundTrip(SignalSamplePayload payload) {
        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
        SignalSamplePayload.STREAM_CODEC.encode(buf, payload);
        SignalSamplePayload decoded = SignalSamplePayload.STREAM_CODEC.decode(buf);
        assertEquals(0, buf.readableBytes(), "bytes left unread: the reader and writer disagree");
        return decoded;
    }

    @Test
    @DisplayName("v3 round-trips the evaluation point and the heard-cell count")
    void roundTripsV3Fields() {
        SignalSamplePayload payload = SignalSamplePayload.of(
                sample(cells(6), 100L), "band_900", 900.0, 1.0, "", 12.25, 71.62, -3.5);

        SignalSamplePayload decoded = roundTrip(payload);

        assertEquals(4, SignalSamplePayload.VERSION, "slice 12 appended the backhaul cap");
        assertEquals(SignalSamplePayload.VERSION, decoded.version());
        assertEquals(12.25, decoded.rxX());
        assertEquals(71.62, decoded.rxY());
        assertEquals(-3.5, decoded.rxZ());
        assertEquals(6, decoded.cellsHeard(), "the full count, not the four carried");
        assertEquals(SignalSamplePayload.MAX_CELLS, decoded.cells().size());
        assertEquals(payload, decoded);
    }

    @Test
    @DisplayName("an out-of-service payload keeps its evaluation point too")
    void emptyRoundTrips() {
        SignalSamplePayload decoded = roundTrip(SignalSamplePayload.empty(55L, 3, 1.0, 2.0, 3.0));
        assertTrue(decoded.hasReceiverPosition());
        assertEquals(0, decoded.cellsHeard());
        assertFalse(decoded.hasServing());
        assertEquals(3, decoded.handoverCount());
    }

    @Test
    @DisplayName("the client's placeholder has no evaluation point, so it can never be logged")
    void placeholderHasNoPosition() {
        assertFalse(SignalSamplePayload.empty(0L, 0).hasReceiverPosition());
    }

    @Test
    @DisplayName("a serving cell held below the fourth strongest takes the last slot instead of vanishing")
    void servingCellSurvivesTheCut() {
        List<CellSample> heard = cells(6);
        long servingId = heard.get(5).cellId();   // camped on the weakest, inside the hysteresis

        SignalSamplePayload payload = SignalSamplePayload.of(
                sample(heard, servingId), "band_900", 900.0, 1.0, "", 0.0, 64.0, 0.0);

        assertEquals(SignalSamplePayload.MAX_CELLS, payload.cells().size());
        assertNotNull(payload.serving(), "the HUD would have drawn NO SERVICE here before Step 3a");
        assertEquals(servingId, payload.cells().get(SignalSamplePayload.MAX_CELLS - 1).cellId());
        assertEquals(heard.subList(0, SignalSamplePayload.MAX_CELLS - 1),
                payload.cells().subList(0, SignalSamplePayload.MAX_CELLS - 1),
                "the three strongest keep their places, strongest first");
    }

    @Test
    @DisplayName("the cut is unchanged when the serving cell is already among the strongest four")
    void cutUnchangedWhenServingIsTopFour() {
        List<CellSample> heard = cells(6);
        assertEquals(heard.subList(0, 4), SignalSamplePayload.topCells(heard, heard.get(2).cellId(), 4));
        assertEquals(heard.subList(0, 4), SignalSamplePayload.topCells(heard, ReceiverState.NO_CELL, 4));
        List<CellSample> few = cells(3);
        assertSame(few, SignalSamplePayload.topCells(few, few.get(0).cellId(), 4));
    }

    @Test
    @DisplayName("the drive-test sample copies the server's values: serving PCI, RSRP, band, level, count")
    void driveTestSampleCopiesServerValues() {
        List<CellSample> heard = cells(6);
        CellSample serving = heard.get(1);
        SignalSamplePayload payload = SignalSamplePayload.of(
                sample(heard, serving.cellId()), "band_900", 900.0, 1.0, "", 5.5, 70.0, -9.25);

        DriveTestLog.Sample logged = payload.toDriveTestSample();

        assertEquals(1234L, logged.tick());
        assertEquals(5.5, logged.x());
        assertEquals(70.0, logged.y());
        assertEquals(-9.25, logged.z());
        assertEquals(serving.cellId(), logged.servingCellId());
        assertEquals(serving.pci(), logged.pci());
        assertEquals("band_900", logged.bandId());
        assertEquals(serving.rsrpDbm(), logged.rsrpDbm());
        assertEquals(-3.5, logged.sinrDb());
        assertEquals(ServiceLevel.NONE, logged.level(), "drowned in interference, yet still served");
        assertTrue(logged.hasServing());
        assertEquals(2, logged.handoverCount());
        assertEquals(6, logged.cellCount());
    }

    @Test
    @DisplayName("v4 (slice 12): the backhaul cap round-trips; no cap by default; the meter's note")
    void backhaulCap() {
        SignalSamplePayload payload = SignalSamplePayload.of(
                sample(cells(3), 100L), "band_900", 900.0, 1.0, "", 1.0, 64.0, 2.0);
        assertEquals(ServiceLevel.EXCELLENT, payload.serviceCap(), "of() caps nothing: the ticker sets the cap");
        assertEquals("", payload.backhaulNote());
        assertSame(payload, payload.withServiceCap(ServiceLevel.EXCELLENT), "no change, no copy");
        assertSame(payload, payload.withServiceCap(null), "null is no cap");

        SignalSamplePayload limited = payload.withServiceCap(ServiceLevel.FAIR);
        assertEquals("BH: LIMITED (capped FAIR)", limited.backhaulNote(), "§3C.2's wording");
        SignalSamplePayload decoded = roundTrip(limited);
        assertEquals(ServiceLevel.FAIR, decoded.serviceCap());
        assertEquals(limited, decoded);
        // Every radio figure is the same payload's: the cap is a note beside them.
        assertEquals(payload, limited.withServiceCap(ServiceLevel.EXCELLENT));
        assertEquals(payload.serviceLevel(), limited.serviceLevel());
        assertEquals(payload.sinrDb(), limited.sinrDb());
        assertEquals(payload.cells(), limited.cells());

        assertEquals("BH: NONE (no backhaul)", payload.withServiceCap(ServiceLevel.NONE).backhaulNote());
        assertEquals(ServiceLevel.NONE, roundTrip(payload.withServiceCap(ServiceLevel.NONE)).serviceCap());
        // No serving cell: nothing to cap, no note.
        SignalSamplePayload empty = SignalSamplePayload.empty(55L, 3, 1.0, 2.0, 3.0);
        assertEquals(ServiceLevel.EXCELLENT, roundTrip(empty).serviceCap());
        assertEquals("", empty.withServiceCap(ServiceLevel.FAIR).backhaulNote());
    }

    @Test
    @DisplayName("with no serving cell the drive-test sample is NO SERVICE with no RSRP")
    void driveTestSampleWithoutService() {
        DriveTestLog.Sample logged = SignalSamplePayload.empty(40L, 1, 1.0, 65.0, 2.0).toDriveTestSample();
        assertFalse(logged.hasServing());
        assertEquals(ServiceLevel.NONE, logged.level());
        assertTrue(Double.isNaN(logged.rsrpDbm()));
        assertEquals("", logged.bandId());
        assertEquals(0, logged.cellCount());
    }
}
