package dev.rancraft.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.rancraft.net.LocatorFixPayload;
import dev.rancraft.rf.LocatorFix;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The client's Locator reading (Phase 3A review, round 1): the stale limit is learned from the gaps
 * between payloads, as the lens's links are, instead of a fixed 5 s; putting the Locator away drops
 * the reading but keeps the learned cadence; a full clear forgets both.
 */
class ClientLocatorStateTest {

    /** A realistic wall clock, so 0 (the "never received" sentinel) never occurs as a time. */
    private static final long T0 = 1_700_000_000_000L;

    private static final LocatorFixPayload PAYLOAD = new LocatorFixPayload(LocatorFixPayload.VERSION, 100L,
            new LocatorFix.NoSignal(), List.of(), 1.0, 0.0, "", 6.0, -100.0, Optional.empty());

    @BeforeEach
    @AfterEach
    void reset() {
        ClientLocatorState.clear();
    }

    @Test
    @DisplayName("a 10 s send interval (evaluationIntervalTicks 200) gives a 25 s limit: no blink between payloads")
    void learnsATenSecondCadence() {
        assertTrue(ClientLocatorState.isStaleAt(T0), "nothing received yet");
        ClientLocatorState.acceptAt(PAYLOAD, T0);
        assertEquals(5_000L, ClientLocatorState.staleAfterMillis(), "one payload: the 5 s floor");
        ClientLocatorState.acceptAt(PAYLOAD, T0 + 10_000L);

        assertEquals(25_000L, ClientLocatorState.staleAfterMillis(), "2.5 x the 10 s gap");
        assertFalse(ClientLocatorState.isStaleAt(T0 + 10_000L + 6_000L), "the old fixed 5 s would blank it here");
        assertFalse(ClientLocatorState.isStaleAt(T0 + 10_000L + 25_000L));
        assertTrue(ClientLocatorState.isStaleAt(T0 + 10_000L + 25_001L), "the server stopped sending");
        assertSame(PAYLOAD, ClientLocatorState.latest());
    }

    @Test
    @DisplayName("the default 1 s cadence keeps the 5 s floor; the limit never exceeds 30 s, and a longer gap is not learned")
    void floorAndCap() {
        ClientLocatorState.acceptAt(PAYLOAD, T0);
        ClientLocatorState.acceptAt(PAYLOAD, T0 + 1_000L);
        assertEquals(5_000L, ClientLocatorState.staleAfterMillis());

        ClientLocatorState.acceptAt(PAYLOAD, T0 + 21_000L);
        assertEquals(30_000L, ClientLocatorState.staleAfterMillis(), "2.5 x 20 s, capped");

        ClientLocatorState.acceptAt(PAYLOAD, T0 + 61_000L);
        assertEquals(30_000L, ClientLocatorState.staleAfterMillis(), "a 40 s gap is an outage, not the cadence");
    }

    @Test
    @DisplayName("putting the Locator away drops the reading at once but keeps the learned cadence")
    void putAwayKeepsTheCadence() {
        ClientLocatorState.acceptAt(PAYLOAD, T0);
        ClientLocatorState.acceptAt(PAYLOAD, T0 + 10_000L);

        ClientLocatorState.putAway();
        assertNull(ClientLocatorState.latest());
        assertTrue(ClientLocatorState.isStaleAt(T0 + 10_001L), "an old fix is never shown as current");
        assertEquals(25_000L, ClientLocatorState.staleAfterMillis());

        // Taken back 5 s later: the first payload after that is current for the learned limit, and
        // the put-away gap (15 s) is not learned as the cadence.
        ClientLocatorState.acceptAt(PAYLOAD, T0 + 25_000L);
        assertEquals(25_000L, ClientLocatorState.staleAfterMillis());
        assertFalse(ClientLocatorState.isStaleAt(T0 + 25_000L + 9_000L));
    }

    @Test
    @DisplayName("a full clear (logout, respawn, dimension change) forgets the reading and the cadence")
    void clearForgetsEverything() {
        ClientLocatorState.acceptAt(PAYLOAD, T0);
        ClientLocatorState.acceptAt(PAYLOAD, T0 + 10_000L);
        ClientLocatorState.clear();
        assertNull(ClientLocatorState.latest());
        assertTrue(ClientLocatorState.isStaleAt(T0 + 10_001L));
        assertEquals(5_000L, ClientLocatorState.staleAfterMillis());
    }
}
