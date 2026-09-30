package dev.rancraft.device;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.rancraft.device.RadioLinkMemory.Presence;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Phase 3 slice 9 (§3B.4): a receiver's output rule. A lost message is a stale state, not a toggle. */
class RadioLinkMemoryTest {

    private static final long TX_A = 1_000L;
    private static final long TX_B = 2_000L;
    private static final long TX_C = 3_000L;

    @Test
    @DisplayName("outputs 15 once a delivered message says powered, 0 once one says unpowered")
    void followsDeliveredMessages() {
        RadioLinkMemory memory = new RadioLinkMemory();
        assertFalse(memory.output(), "nothing heard yet");
        assertTrue(memory.hear(TX_A, true));
        assertTrue(memory.output());
        assertFalse(memory.hear(TX_A, true), "the same state again changes nothing");
        assertTrue(memory.hear(TX_A, false));
        assertFalse(memory.output());
        assertFalse(memory.hear(TX_A, false));
    }

    @Test
    @DisplayName("a lost message leaves the old state: stale, never toggled")
    void lostMessageIsStale() {
        RadioLinkMemory memory = new RadioLinkMemory();
        memory.hear(TX_A, true);
        // The transmitter goes unpowered, but its next three messages are lost: hear() is not called.
        assertTrue(memory.output(), "still the last delivered state");
        memory.hear(TX_A, false);
        assertFalse(memory.output(), "the first message that gets through updates it");
    }

    @Test
    @DisplayName("any transmitter last heard powered keeps the output on")
    void anyPowered() {
        RadioLinkMemory memory = new RadioLinkMemory();
        memory.hear(TX_A, true);
        memory.hear(TX_B, true);
        memory.hear(TX_A, false);
        assertTrue(memory.output(), "B is still powered");
        assertEquals(1, memory.knownOnCount());
        assertTrue(memory.knowsOn(TX_B) && !memory.knowsOn(TX_A));
        memory.hear(TX_B, false);
        assertFalse(memory.output());
    }

    @Test
    @DisplayName("forget drops one transmitter, clear drops all")
    void forgetAndClear() {
        RadioLinkMemory memory = new RadioLinkMemory();
        memory.hear(TX_A, true);
        memory.hear(TX_B, true);
        assertTrue(memory.forget(TX_B));
        assertFalse(memory.forget(TX_B), "already forgotten");
        assertTrue(memory.output(), "A remains");
        assertTrue(memory.clear());
        assertFalse(memory.output());
        assertFalse(memory.clear(), "already empty");
    }

    @Test
    @DisplayName("what it hears itself is verified: in steady state a turn looks nothing up")
    void heardIsVerified() {
        RadioLinkMemory memory = new RadioLinkMemory();
        memory.hear(TX_A, true);
        memory.hear(TX_B, true);
        assertEquals(0, memory.unverifiedCount());
        List<Long> lookedUp = new ArrayList<>();
        assertFalse(memory.verify(key -> {
            lookedUp.add(key);
            return Presence.GONE;
        }));
        assertTrue(lookedUp.isEmpty(), "nothing unverified, so no lookup");
        assertEquals(2, memory.knownOnCount());
    }

    @Test
    @DisplayName("saved and loaded: the same transmitters and output, all unverified until looked up")
    void saveAndLoad() {
        RadioLinkMemory memory = new RadioLinkMemory();
        memory.hear(TX_A, true);
        memory.hear(TX_B, true);
        long[] saved = memory.toArray();
        Arrays.sort(saved);
        assertArrayEquals(new long[] {TX_A, TX_B}, saved);

        RadioLinkMemory loaded = new RadioLinkMemory();
        loaded.hear(TX_C, true);
        loaded.load(saved);
        assertEquals(2, loaded.knownOnCount(), "load replaces, it does not merge");
        assertTrue(loaded.output(), "a reloaded receiver keeps its output");
        assertEquals(2, loaded.unverifiedCount(), "a reloaded receiver checks everything it remembers");
        loaded.load(new long[0]);
        assertFalse(loaded.output());
        assertEquals(0, loaded.unverifiedCount());
    }

    @Test
    @DisplayName("verify: GONE is forgotten, PRESENT confirmed, UNKNOWN (chunk not loaded) kept and asked again")
    void verifyAfterLoad() {
        RadioLinkMemory memory = new RadioLinkMemory();
        memory.load(new long[] {TX_A, TX_B, TX_C});
        List<Long> lookedUp = new ArrayList<>();
        boolean changed = memory.verify(key -> {
            lookedUp.add(key);
            return key == TX_A ? Presence.GONE : key == TX_B ? Presence.PRESENT : Presence.UNKNOWN;
        });
        assertTrue(changed, "A was gone");
        assertEquals(3, lookedUp.size());
        assertEquals(2, memory.knownOnCount(), "B and C remain");
        assertFalse(memory.knowsOn(TX_A));
        assertEquals(1, memory.unverifiedCount(), "only C, whose chunk was not loaded");

        lookedUp.clear();
        assertFalse(memory.verify(key -> {
            lookedUp.add(key);
            return Presence.PRESENT;
        }), "C is there after all: nothing forgotten");
        assertEquals(List.of(TX_C), lookedUp, "only the unverified one is looked up");
        assertEquals(0, memory.unverifiedCount());
        assertTrue(memory.output());
    }

    @Test
    @DisplayName("hearing or forgetting an unverified transmitter settles it without a lookup")
    void hearingVerifies() {
        RadioLinkMemory memory = new RadioLinkMemory();
        memory.load(new long[] {TX_A, TX_B});
        assertFalse(memory.hear(TX_A, true), "already on: the output does not change");
        assertEquals(1, memory.unverifiedCount(), "but A is verified now");
        assertTrue(memory.forget(TX_B));
        assertEquals(0, memory.unverifiedCount(), "B is gone, so nothing is left to check");
        memory.load(new long[] {TX_A});
        assertTrue(memory.hear(TX_A, false), "an unpowered message from it turns it off");
        assertEquals(0, memory.unverifiedCount());
        assertFalse(memory.output());
    }
}
