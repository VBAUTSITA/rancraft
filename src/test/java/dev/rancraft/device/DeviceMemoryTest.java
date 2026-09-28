package dev.rancraft.device;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.rancraft.rf.SignalSample;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Per-player device state ({@link DeviceMemory}) and the replay guard built on it
 * ({@link ReplayGuard}): what §3A.3 means by "devices must be idempotent on
 * {@code sample.timestampTick()}" and "add per-device server state to forget()". Pure Java; no game.
 */
class DeviceMemoryTest {

    private final UUID alice = UUID.randomUUID();
    private final UUID bob = UUID.randomUUID();

    @Test
    @DisplayName("each store keeps one value per player; put returns the previous one")
    void storesPerPlayer() {
        DeviceMemory<String> memory = DeviceMemory.create("test.values");
        assertNull(memory.get(alice));
        assertNull(memory.put(alice, "one"));
        assertEquals("one", memory.put(alice, "two"));
        memory.put(bob, "bob's");
        assertEquals("two", memory.get(alice));
        assertEquals(2, memory.size());
        assertEquals("test.values", memory.name());
        assertThrows(NullPointerException.class, () -> memory.put(alice, null), "absence means nothing remembered");
    }

    @Test
    @DisplayName("forget drops one player from every store at once, and is safe for a stranger")
    void forgetSpansEveryStore() {
        DeviceMemory<String> fixes = DeviceMemory.create("test.fixes");
        DeviceMemory<Integer> counts = DeviceMemory.create("test.counts");
        fixes.put(alice, "fix");
        counts.put(alice, 3);
        counts.put(bob, 4);

        DeviceMemory.forget(alice);
        DeviceMemory.forget(UUID.randomUUID());

        assertNull(fixes.get(alice));
        assertNull(counts.get(alice));
        assertEquals(4, counts.get(bob), "another player's state survives");
    }

    @Test
    @DisplayName("clearAll (server stop) empties every store")
    void clearAllEmptiesEverything() {
        DeviceMemory<String> memory = DeviceMemory.create("test.clear");
        memory.put(alice, "a");
        memory.put(bob, "b");
        DeviceMemory.clearAll();
        assertEquals(0, memory.size());
    }

    private static SignalSample sampleAt(long tick) {
        return SignalSample.empty(tick, 0);
    }

    @Test
    @DisplayName("a replayed sample (same timestampTick) is not a first sighting; the next evaluation is")
    void replayIsNotAFirstSighting() {
        ReplayGuard guard = new ReplayGuard("test.guard");
        assertTrue(guard.firstSighting(alice, sampleAt(1_000L)));
        assertFalse(guard.firstSighting(alice, sampleAt(1_000L)), "cached replay, one interval later");
        assertFalse(guard.firstSighting(alice, 1_000L), "and again");
        assertTrue(guard.firstSighting(alice, sampleAt(1_020L)), "a fresh evaluation");
        assertTrue(guard.firstSighting(bob, sampleAt(1_020L)), "players are independent");
    }

    @Test
    @DisplayName("after forget (respawn, dimension change, logout) any sample is a first sighting again")
    void forgetResetsTheGuard() {
        ReplayGuard guard = new ReplayGuard("test.guard.forget");
        assertTrue(guard.firstSighting(alice, 2_000L));
        DeviceMemory.forget(alice);
        assertTrue(guard.firstSighting(alice, 2_000L),
                "a forgotten player has no history to replay against");
    }

    @Test
    @DisplayName("/tick freeze: two fresh evaluations share a timestampTick, and the guard (keyed on it, as §3A.3 says) acts once")
    void frozenGameTimeLooksLikeAReplay() {
        ReplayGuard guard = new ReplayGuard("test.guard.freeze");
        // Game time stands still, the player walks: two different evaluations, the same tick.
        SignalSample first = SignalSample.empty(4_000L, 0);
        SignalSample second = SignalSample.empty(4_000L, 1);
        assertTrue(guard.firstSighting(alice, first));
        assertFalse(guard.firstSighting(alice, second),
                "documented in ReplayGuard: once-per-evaluation state freezes with game time");
        assertTrue(guard.firstSighting(alice, SignalSample.empty(4_001L, 1)), "time moves again");
    }

    @Test
    @DisplayName("two guards (two device types) do not share memory")
    void guardsAreIndependent() {
        ReplayGuard locator = new ReplayGuard("test.guard.locator");
        ReplayGuard scanner = new ReplayGuard("test.guard.scanner");
        assertTrue(locator.firstSighting(alice, 3_000L));
        assertTrue(scanner.firstSighting(alice, 3_000L));
        assertFalse(locator.firstSighting(alice, 3_000L));
    }
}
