package dev.rancraft.world;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.rancraft.device.DeviceContext;
import dev.rancraft.device.FixedDevice;
import dev.rancraft.rf.DeviceRequirement;
import dev.rancraft.rf.ReceiverState;
import dev.rancraft.rf.SignalSample;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Phase 3 slice 8 (§3B.3): the fixed receiver registry, headless. Registration by position, removal
 * by identity or by chunk (the unload path), handover state forgotten with the receiver, and the
 * round-robin cursor the ticker resumes from. The lifecycle hooks that call it run in game
 * ({@code FixedReceiverGameTests}).
 */
class FixedReceiverRegistryTest {

    /** A device that does nothing: the registry only cares about identity. */
    static final class NullDevice implements FixedDevice {
        @Override
        public DeviceRequirement requirement() {
            return DeviceRequirement.NONE;
        }

        @Override
        public void onSample(ServerLevel level, BlockPos pos, DeviceContext ctx) {
        }
    }

    private final FixedReceiverRegistry registry = new FixedReceiverRegistry();

    private static long key(int x, int y, int z) {
        return BlockPos.asLong(x, y, z);
    }

    private List<Long> lap() {
        List<Long> seen = new ArrayList<>();
        for (int i = 0; i < registry.size(); i++) {
            seen.add(registry.advance().key);
        }
        return seen;
    }

    @Test
    @DisplayName("register by position: the same device twice is one receiver; queries see it")
    void registerIsIdempotent() {
        FixedDevice device = new NullDevice();
        BlockPos pos = new BlockPos(10, 64, -3);
        assertTrue(registry.register(pos, device));
        assertFalse(registry.register(pos, device), "onLoad and the chunk load both register it");
        assertEquals(1, registry.size());
        assertTrue(registry.contains(pos));
        assertSame(device, registry.deviceAt(pos));
        assertNull(registry.deviceAt(pos.above()));
        assertEquals(1, registry.countInChunk(0, -1), "z = -3 is in chunk -1");
        assertThrows(IllegalArgumentException.class, () -> registry.register(pos.above(), null));
    }

    @Test
    @DisplayName("a different device at a registered position replaces it and keeps the position's schedule, state and cache")
    void replacementKeepsThePositionsState() {
        FixedDevice old = new NullDevice();
        FixedDevice successor = new NullDevice();
        long key = key(1, 2, 3);
        registry.register(key, old);
        FixedReceiverRegistry.Entry entry = registry.entry(key);
        registry.setDueOf(key, 77L);
        entry.cached = new FixedReceiverTicker.Cached(SignalSample.empty(5L), RegionEpochs.Snapshot.NONE, 3L);
        registry.states.put(key, ReceiverState.NONE.reselected(9L, 5L), 5L);

        assertFalse(registry.register(key, successor), "not a new receiver");
        assertSame(entry, registry.entry(key));
        assertSame(successor, entry.device);
        assertEquals(77L, registry.dueOf(key));
        assertEquals(9L, registry.states.get(key).servingCellId());

        assertFalse(registry.unregister(key, old), "the old entity's late setRemoved does not drop its successor");
        assertEquals(1, registry.size());
        assertTrue(registry.unregister(key, successor));
        assertEquals(0, registry.size());
    }

    @Test
    @DisplayName("unregister is by identity and forgets the handover state")
    void unregisterByIdentity() {
        FixedDevice device = new NullDevice();
        long key = key(-40, 70, 12);
        registry.register(key, device);
        registry.states.put(key, ReceiverState.NONE.reselected(1L, 0L).withCandidate(2L, 10L), 10L);

        assertFalse(registry.unregister(key, new NullDevice()), "another device's removal leaves it");
        assertTrue(registry.states.get(key).hasCandidate());
        assertTrue(registry.unregister(key, device));
        assertFalse(registry.unregister(key, device), "twice is harmless");
        assertEquals(0, registry.size());
        assertSame(ReceiverState.NONE, registry.states.get(key), "a receiver that comes back starts afresh");
        assertEquals(0, registry.states.size(), "no leak");
        assertEquals(0, registry.countInChunk(-3, 0));
    }

    @Test
    @DisplayName("the chunk unload path drops every receiver in that chunk column, by position, and nothing else")
    void unregisterChunkIsByPosition() {
        // Chunk (0, 0) spans blocks 0..15; chunk (-1, 0) spans -16..-1.
        long a = key(0, 64, 0);
        long b = key(15, -60, 15);
        long c = key(15, 300, 15);
        long west = key(-1, 64, 0);
        long east = key(16, 64, 0);
        for (long k : new long[] {a, west, b, east, c}) {
            registry.register(k, new NullDevice());
            registry.states.put(k, ReceiverState.NONE.reselected(k, 0L), 0L);
        }
        assertEquals(3, registry.countInChunk(0, 0));

        assertEquals(3, registry.unregisterChunk(0, 0));
        assertEquals(2, registry.size());
        assertNull(registry.entry(a));
        assertNull(registry.entry(b));
        assertNull(registry.entry(c));
        assertSame(ReceiverState.NONE, registry.states.get(b), "their handover state is forgotten");
        assertTrue(registry.entry(west) != null && registry.entry(east) != null, "the neighbours stay");
        assertEquals(2, registry.states.size());
        assertEquals(0, registry.unregisterChunk(0, 0), "an empty chunk: nothing to do");
        assertEquals(1, registry.unregisterChunk(-1, 0));
        assertEquals(List.of(east), lap());
    }

    @Test
    @DisplayName("due ticks stay with their receivers when others are removed and when the array grows")
    void dueTicksFollowTheirReceivers() {
        List<FixedDevice> devices = new ArrayList<>();
        for (int i = 0; i < 40; i++) {
            FixedDevice device = new NullDevice();
            devices.add(device);
            registry.register(receiver(i), device); // 40 > the array's first 16: it grows twice
            assertEquals(FixedReceiverRegistry.UNSCHEDULED, registry.dueOf(receiver(i)), "new: not scheduled yet");
            registry.setDueOf(receiver(i), 1_000L + i);
        }
        for (int i = 0; i < 40; i += 4) {
            registry.unregister(receiver(i), devices.get(i));
        }
        // Chunk (0, 1) holds i % 3 == 1: 13 receivers, 3 of them (4, 16, 28) already gone.
        assertEquals(10, registry.unregisterChunk(0, 1));
        assertEquals(20, registry.size());
        for (int i = 0; i < 40; i++) {
            if (registry.entry(receiver(i)) != null) {
                assertEquals(1_000L + i, registry.dueOf(receiver(i)), "receiver " + i);
            }
        }
    }

    /** Receiver {@code i}: chunk x 0, chunk z {@code i % 3}. */
    private static long receiver(int i) {
        return key(i % 16, 64 + i / 16, 16 * (i % 3));
    }

    @Test
    @DisplayName("round robin: advance cycles in registration order and resumes where it stopped")
    void advanceCycles() {
        assertNull(registry.advance(), "empty");
        long[] keys = {key(0, 0, 0), key(1, 0, 0), key(2, 0, 0), key(3, 0, 0)};
        for (long k : keys) {
            registry.register(k, new NullDevice());
        }
        assertEquals(keys[0], registry.advance().key);
        assertEquals(keys[1], registry.advance().key);
        assertEquals(List.of(keys[2], keys[3], keys[0], keys[1]), lap(), "the next lap starts at the cursor");
    }

    @Test
    @DisplayName("round robin: removals keep the cursor on the same next receiver")
    void removalsKeepTheCursor() {
        FixedDevice[] devices = new FixedDevice[5];
        long[] keys = new long[5];
        for (int i = 0; i < 5; i++) {
            keys[i] = key(i, 0, 100);
            devices[i] = new NullDevice();
            registry.register(keys[i], devices[i]);
        }
        registry.advance();
        registry.advance();
        registry.advance(); // next is keys[3]

        registry.unregister(keys[0], devices[0]); // before the cursor
        assertEquals(keys[3], registry.advance().key, "a removal behind the cursor does not skip anyone");

        registry.unregister(keys[4], devices[4]); // the next one
        assertEquals(keys[1], registry.advance().key, "removing the next receiver moves on to the one after (wraps)");

        registry.unregister(keys[1], devices[1]);
        registry.unregister(keys[2], devices[2]);
        assertEquals(List.of(keys[3]), lap());
        registry.unregister(keys[3], devices[3]);
        assertNull(registry.advance());
        registry.register(keys[0], devices[0]);
        assertEquals(keys[0], registry.advance().key, "a fresh start after emptying");
    }
}
