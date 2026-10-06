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
        assertTrue(memory.hear(TX_A, true, 0L));
        assertTrue(memory.output());
        assertFalse(memory.hear(TX_A, true, 0L), "the same state again changes nothing");
        assertTrue(memory.hear(TX_A, false, 0L));
        assertFalse(memory.output());
        assertFalse(memory.hear(TX_A, false, 0L));
    }

    @Test
    @DisplayName("a lost message leaves the old state: stale, never toggled")
    void lostMessageIsStale() {
        RadioLinkMemory memory = new RadioLinkMemory();
        memory.hear(TX_A, true, 0L);
        // The transmitter goes unpowered, but its next three messages are lost: hear() is not called.
        assertTrue(memory.output(), "still the last delivered state");
        memory.hear(TX_A, false, 0L);
        assertFalse(memory.output(), "the first message that gets through updates it");
    }

    @Test
    @DisplayName("any transmitter last heard powered keeps the output on")
    void anyPowered() {
        RadioLinkMemory memory = new RadioLinkMemory();
        memory.hear(TX_A, true, 0L);
        memory.hear(TX_B, true, 0L);
        memory.hear(TX_A, false, 0L);
        assertTrue(memory.output(), "B is still powered");
        assertEquals(1, memory.knownOnCount());
        assertTrue(memory.knowsOn(TX_B) && !memory.knowsOn(TX_A));
        memory.hear(TX_B, false, 0L);
        assertFalse(memory.output());
    }

    @Test
    @DisplayName("forget drops one transmitter, clear drops all")
    void forgetAndClear() {
        RadioLinkMemory memory = new RadioLinkMemory();
        memory.hear(TX_A, true, 0L);
        memory.hear(TX_B, true, 0L);
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
        memory.hear(TX_A, true, 0L);
        memory.hear(TX_B, true, 0L);
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
        memory.hear(TX_A, true, 0L);
        memory.hear(TX_B, true, 0L);
        long[] saved = memory.toArray();
        Arrays.sort(saved);
        assertArrayEquals(new long[] {TX_A, TX_B}, saved);

        RadioLinkMemory loaded = new RadioLinkMemory();
        loaded.hear(TX_C, true, 0L);
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
        assertFalse(memory.hear(TX_A, true, 0L), "already on: the output does not change");
        assertEquals(1, memory.unverifiedCount(), "but A is verified now");
        assertTrue(memory.forget(TX_B));
        assertEquals(0, memory.unverifiedCount(), "B is gone, so nothing is left to check");
        memory.load(new long[] {TX_A});
        assertTrue(memory.hear(TX_A, false, 0L), "an unpowered message from it turns it off");
        assertEquals(0, memory.unverifiedCount());
        assertFalse(memory.output());
    }

    // ---- the timeout (row 16d) --------------------------------------------------------------------

    @Test
    @DisplayName("a transmitter not heard from for the timeout is forgotten; one heard since is kept")
    void silentTransmitterTimesOut() {
        RadioLinkMemory memory = new RadioLinkMemory();
        memory.hear(TX_A, true, 1_000L);
        memory.hear(TX_B, true, 1_000L);
        memory.hear(TX_B, true, 1_500L);
        assertFalse(memory.expire(2_199L, 1_200), "A is 1,199 ticks silent: kept");
        assertTrue(memory.expire(2_200L, 1_200), "A is 1,200 ticks silent: forgotten");
        assertFalse(memory.knowsOn(TX_A));
        assertTrue(memory.knowsOn(TX_B) && memory.output(), "B was heard at 1,500: still on");
        assertTrue(memory.expire(2_700L, 1_200), "B, 1,200 ticks after its last message");
        assertFalse(memory.output());
        assertFalse(memory.expire(9_000L, 1_200), "nothing left to forget");
    }

    @Test
    @DisplayName("0 is no timeout")
    void zeroNeverTimesOut() {
        RadioLinkMemory memory = new RadioLinkMemory();
        memory.hear(TX_A, true, 0L);
        assertFalse(memory.expire(Long.MAX_VALUE / 2, 0));
        assertTrue(memory.output());
    }

    @Test
    @DisplayName("an entry loaded from a save starts its clock at the first turn after loading")
    void loadedEntriesStartTheirClockAtTheFirstTurn() {
        RadioLinkMemory memory = new RadioLinkMemory();
        memory.load(new long[] {TX_A, TX_B});
        assertFalse(memory.expire(50_000L, 1_200), "first turn after loading, however late: the clock starts");
        assertTrue(memory.output());
        memory.hear(TX_B, true, 50_600L);
        assertFalse(memory.expire(51_199L, 1_200));
        assertTrue(memory.expire(51_200L, 1_200), "A, never heard since loading, times out 1,200 ticks later");
        assertFalse(memory.knowsOn(TX_A));
        assertTrue(memory.knowsOn(TX_B));
        assertEquals(0, memory.unverifiedCount(), "A left the unverified set with it; B was verified by its message");
    }
}
