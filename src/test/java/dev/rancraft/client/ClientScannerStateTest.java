package dev.rancraft.client;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.rancraft.net.ScannerPayload;
import dev.rancraft.net.ScannerPayload.Status;
import dev.rancraft.rf.DeviceRequirement;
import dev.rancraft.rf.ServiceLevel;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The client's Proximity Scanner reading (Phase 3 slice 14), by the Network Locator's rules: stale once
 * the server stops sending (the limit learned from the cadence), dropped the moment the scanner leaves the
 * hand with the cadence kept, and both forgotten on a full clear.
 */
class ClientScannerStateTest {

    /** A realistic wall clock, so 0 (the "never received" sentinel) never occurs as a time. */
    private static final long T0 = 1_700_000_000_000L;

    private static final ScannerPayload PAYLOAD = ScannerPayload.of(Status.OK, new DeviceRequirement(ServiceLevel.GOOD, 3),
            ServiceLevel.GOOD, ServiceLevel.EXCELLENT, "band_3500", 3, 24.0, List.of());

    @BeforeEach
    @AfterEach
    void reset() {
        ClientScannerState.clear();
    }

    @Test
    @DisplayName("a 10 s cadence is learned: current for 25 s, stale after")
    void learnsTheCadence() {
        assertTrue(ClientScannerState.isStaleAt(T0), "nothing received yet");
        ClientScannerState.acceptAt(PAYLOAD, T0);
        assertFalse(ClientScannerState.isStaleAt(T0 + 5_000L));
        assertTrue(ClientScannerState.isStaleAt(T0 + 5_001L), "one payload: the 5 s floor");

        ClientScannerState.acceptAt(PAYLOAD, T0 + 10_000L);
        assertFalse(ClientScannerState.isStaleAt(T0 + 10_000L + 25_000L));
        assertTrue(ClientScannerState.isStaleAt(T0 + 10_000L + 25_001L), "the server stopped sending");
        assertSame(PAYLOAD, ClientScannerState.latest());
    }

    @Test
    @DisplayName("put away: the list is dropped at once, the cadence kept for the next payload")
    void putAwayKeepsTheCadence() {
        ClientScannerState.acceptAt(PAYLOAD, T0);
        ClientScannerState.acceptAt(PAYLOAD, T0 + 10_000L);
        ClientScannerState.putAway();
        assertNull(ClientScannerState.latest());
        assertTrue(ClientScannerState.isStaleAt(T0 + 10_001L), "a list of mobs near where you were is never current");

        ClientScannerState.acceptAt(PAYLOAD, T0 + 25_000L);
        assertFalse(ClientScannerState.isStaleAt(T0 + 25_000L + 9_000L), "the learned 25 s limit, not the 5 s floor");
    }

    @Test
    @DisplayName("a full clear forgets the reading and the cadence")
    void clearForgetsEverything() {
        ClientScannerState.acceptAt(PAYLOAD, T0);
        ClientScannerState.acceptAt(PAYLOAD, T0 + 10_000L);
        ClientScannerState.clear();
        assertNull(ClientScannerState.latest());
        ClientScannerState.acceptAt(PAYLOAD, T0 + 20_000L);
        assertTrue(ClientScannerState.isStaleAt(T0 + 20_000L + 5_001L), "back to the 5 s floor");
    }
}
