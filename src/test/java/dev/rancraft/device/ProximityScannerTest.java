package dev.rancraft.device;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.rancraft.net.ScannerPayload;
import dev.rancraft.net.ScannerPayload.Status;
import dev.rancraft.rf.BackhaulGraph;
import dev.rancraft.rf.Band;
import dev.rancraft.rf.BandTable;
import dev.rancraft.rf.CellSample;
import dev.rancraft.rf.DeviceRequirement;
import dev.rancraft.rf.DeviceRequirement.Verdict;
import dev.rancraft.rf.RfConfig;
import dev.rancraft.rf.ServiceLevel;
import dev.rancraft.rf.SignalSample;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The Proximity Scanner's rules (Phase 3 slice 14, §3C.4), headless: its requirement, the status a
 * dispatch gives (each reason told apart, by the Storage Terminal's rule), what a refusal sends, and
 * which held scanner sends. The list itself is {@code ProximityScanTest}; the live level (real mobs, the
 * ticker's dispatch, what reaches the player) is {@code ProximityScannerGameTests}.
 */
class ProximityScannerTest {

    private static final BandTable BANDS = BandTable.of(
            Band.DEFAULT_900,
            new Band("band_1800", 1800.0, 3.6, 1.1, -108.0, 2),
            new Band("band_3500", 3500.0, 4.0, 1.8, -106.0, 3));

    private static SignalSample served(String bandId, ServiceLevel level) {
        CellSample cell = new CellSample(7L, 0, 70, 0, -75.0, 30.0, 80.0, 0.0, bandId, 4, 15.0, 0.0, 0.0);
        return new SignalSample(List.of(cell), 100L, 7L, 18.0, -100.0, -108.0, level, 0);
    }

    /** As the ticker builds it: the verdict for the scanner's requirement, with the cap. */
    private static DeviceContext dispatched(SignalSample sample, ServiceLevel cap) {
        Verdict verdict = ProximityScanner.REQUIREMENT.check(sample, BANDS, cap);
        return new DeviceContext(sample, verdict, BANDS, RfConfig.DEFAULTS, null, 120L, cap);
    }

    @Test
    @DisplayName("§3C.4: GOOD on a band of tier 3, which of the four bands only band_3500 is")
    void requirement() {
        assertEquals(new DeviceRequirement(ServiceLevel.GOOD, 3), ProximityScanner.REQUIREMENT);
        assertEquals(3, BANDS.getOrFallback("band_3500").capacityTier());
        assertEquals(2, BANDS.getOrFallback("band_1800").capacityTier());
    }

    @Test
    @DisplayName("each verdict gets its own status: OK, needs tier 3, weak signal, backhaul limited, no service")
    void statuses() {
        assertEquals(Status.OK, ProximityScanner.statusOf(dispatched(served("band_3500", ServiceLevel.GOOD), ServiceLevel.EXCELLENT)));
        assertEquals(Status.LOW_TIER, ProximityScanner.statusOf(dispatched(served("band_1800", ServiceLevel.EXCELLENT), ServiceLevel.EXCELLENT)),
                "band_1800 at EXCELLENT: the signal is fine, the band is tier 2");
        assertEquals(Status.WEAK_SIGNAL, ProximityScanner.statusOf(dispatched(served("band_3500", ServiceLevel.FAIR), ServiceLevel.EXCELLENT)));
        assertEquals(Status.WEAK_SIGNAL, ProximityScanner.statusOf(dispatched(served("band_1800", ServiceLevel.FAIR), ServiceLevel.EXCELLENT)),
                "the signal is judged before the band (DeviceRequirement's order)");
        assertEquals(Status.BACKHAUL_LIMITED, ProximityScanner.statusOf(
                        dispatched(served("band_3500", ServiceLevel.EXCELLENT), BackhaulGraph.LIMITED_SERVICE_CAP)),
                "the radio would do; only the FAIR cap fails it");
        assertEquals(Status.WEAK_SIGNAL, ProximityScanner.statusOf(
                        dispatched(served("band_3500", ServiceLevel.POOR), BackhaulGraph.LIMITED_SERVICE_CAP)),
                "capped and short of GOOD on its own: fix the signal first");
        assertEquals(Status.NO_SERVICE, ProximityScanner.statusOf(dispatched(SignalSample.empty(100L), ServiceLevel.EXCELLENT)));
    }

    @Test
    @DisplayName("a refusal sends its reason with the server's values and no list (no level is touched)")
    void refusalPayload() {
        ScannerPayload lowTier = ProximityScanner.payloadFor(null,
                dispatched(served("band_1800", ServiceLevel.EXCELLENT), ServiceLevel.EXCELLENT), 24.0);
        assertEquals(Status.LOW_TIER, lowTier.status());
        assertEquals(ServiceLevel.GOOD, lowTier.neededLevel());
        assertEquals(3, lowTier.neededTier());
        assertEquals("band_1800", lowTier.servingBandId());
        assertEquals(2, lowTier.servingBandTier());
        assertEquals(ServiceLevel.EXCELLENT, lowTier.radioLevel());
        assertEquals(24.0f, lowTier.rangeBlocks());
        assertTrue(lowTier.contacts().isEmpty());

        ScannerPayload limited = ProximityScanner.payloadFor(null,
                dispatched(served("band_3500", ServiceLevel.EXCELLENT), BackhaulGraph.LIMITED_SERVICE_CAP), 24.0);
        assertEquals(Status.BACKHAUL_LIMITED, limited.status());
        assertEquals(ServiceLevel.EXCELLENT, limited.radioLevel(), "the radio's own level, uncapped");
        assertEquals(ServiceLevel.FAIR, limited.serviceCap());

        ScannerPayload none = ProximityScanner.payloadFor(null, dispatched(SignalSample.empty(100L), ServiceLevel.EXCELLENT), 24.0);
        assertEquals(Status.NO_SERVICE, none.status());
        assertEquals("", none.servingBandId());
        assertEquals(0, none.servingBandTier());
    }

    @Test
    @DisplayName("one held scanner sends: the main hand's, else the offhand's; never one in the hotbar")
    void whichScannerSends() {
        assertTrue(ProximityScanner.sendsPayload(true, true, true), "the main hand's");
        assertFalse(ProximityScanner.sendsPayload(true, false, true), "the offhand's, with another in the main hand");
        assertTrue(ProximityScanner.sendsPayload(true, false, false), "the offhand's, alone");
        assertFalse(ProximityScanner.sendsPayload(false, false, false), "the hotbar's");
        assertFalse(ProximityScanner.sendsPayload(false, false, true), "the hotbar's, with one in the main hand");
    }
}
