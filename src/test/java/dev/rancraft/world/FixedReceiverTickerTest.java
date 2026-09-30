package dev.rancraft.world;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.rancraft.rf.BinTraversal;
import dev.rancraft.rf.ReceiverState;
import dev.rancraft.rf.ReceiverStateStore;
import dev.rancraft.rf.SignalSample;
import dev.rancraft.world.FixedReceiverRegistryTest.NullDevice;
import dev.rancraft.world.FixedReceiverTicker.Cached;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Phase 3 slice 8 (§3B.3): the fixed-receiver ticker's rules, headless. The replay decision, the
 * stale-candidate threshold derived from the ticker's own schedule (the slice 4 follow-up), the
 * stagger, and the budgeted round robin (batches, clock read between batches, rotating start, at
 * most once per interval) driven with a fake clock. The tick handler itself runs in game
 * ({@code FixedReceiverGameTests}).
 */
class FixedReceiverTickerTest {

    private static final int INTERVAL = 20;
    private static final long SITES = 4L;
    private static final long BIN_A = BinTraversal.key(0, 0);
    private static final long BIN_B = BinTraversal.key(1, 0);
    private static final long OTHER_BIN = BinTraversal.key(-7, 3);

    private final RegionEpochs epochs = new RegionEpochs();

    private Cached cached() {
        return new Cached(SignalSample.empty(100L), epochs.snapshot(new long[] {BIN_A, BIN_B}), SITES);
    }

    // ---- canReplay --------------------------------------------------------------------------------

    @Test
    @DisplayName("replay: caching on, no armed candidate, same antennas, no dependency bin bumped")
    void replaysWhenQuiet() {
        Cached cached = cached();
        assertTrue(FixedReceiverTicker.canReplay(cached, true, false, epochs, SITES));
        epochs.bumpBin(OTHER_BIN);
        epochs.bumpBlock(5_000, -5_000);
        assertTrue(FixedReceiverTicker.canReplay(cached, true, false, epochs, SITES),
                "a block change outside its bins is not a reason to re-evaluate");
    }

    @Test
    @DisplayName("no replay: caching off, a candidate armed, no entry, another site version, or a dependency bin bumped")
    void eachConditionBlocksTheReplay() {
        Cached cached = cached();
        assertFalse(FixedReceiverTicker.canReplay(cached, false, false, epochs, SITES), "caching off");
        assertFalse(FixedReceiverTicker.canReplay(cached, true, true, epochs, SITES),
                "an armed candidate: replaying would freeze the time-to-trigger");
        assertFalse(FixedReceiverTicker.canReplay(null, true, false, epochs, SITES), "nothing cached yet");
        assertFalse(FixedReceiverTicker.canReplay(cached, true, false, epochs, SITES + 1),
                "an antenna was added, removed or reconfigured");
        epochs.bumpBin(BIN_B);
        assertFalse(FixedReceiverTicker.canReplay(cached, true, false, epochs, SITES), "a block changed on a ray");
    }

    @Test
    @DisplayName("an evaluation that marched nothing depends on no block")
    void nothingMarchedNothingDepended() {
        Cached none = new Cached(SignalSample.empty(1L), RegionEpochs.Snapshot.NONE, SITES);
        epochs.bumpBin(BIN_A);
        assertTrue(FixedReceiverTicker.canReplay(none, true, false, epochs, SITES));
    }

    // ---- the stale-candidate threshold ------------------------------------------------------------

    @Test
    @DisplayName("stale threshold: the receiver's interval plus how late the budget made it")
    void staleThresholdIsIntervalPlusLag() {
        assertEquals(20L, FixedReceiverTicker.staleCandidateGapTicks(20, 0));
        assertEquals(27L, FixedReceiverTicker.staleCandidateGapTicks(20, 7));
        assertEquals(1L, FixedReceiverTicker.staleCandidateGapTicks(0, 0), "never below one tick");
        assertEquals(20L, FixedReceiverTicker.staleCandidateGapTicks(20, -3), "a negative lag counts as none");
        assertEquals(SignalTicker.staleCandidateGapTicks(20), FixedReceiverTicker.staleCandidateGapTicks(20, 0),
                "on time, it is the player ticker's threshold");
    }

    @Test
    @DisplayName("a budget overrun is not read as a pause (the player threshold would drop the candidate); a skipped turn is")
    void overrunKeepsTheCandidateSkipDropsIt() {
        ReceiverStateStore<Long> store = new ReceiverStateStore<>();
        long key = BlockPos.asLong(3, 64, 3);
        ReceiverState armed = ReceiverState.NONE.reselected(1L, 0L).withCandidate(2L, 100L);
        store.put(key, armed, 100L);

        // Served at 100, due at 120, served 7 ticks late at 127.
        assertTrue(store.resume(key, 127L, FixedReceiverTicker.staleCandidateGapTicks(INTERVAL, 7)).hasCandidate(),
                "the ticker's own lateness keeps the candidate: the handover is not delayed");
        assertFalse(store.resume(key, 127L, SignalTicker.staleCandidateGapTicks(INTERVAL)).hasCandidate(),
                "the player ticker's threshold would have dropped it (the slice 4 follow-up)");

        // Its turn at 120 was skipped (chunk not FULL) and rescheduled to 140, served on time there.
        assertFalse(store.resume(key, 140L, FixedReceiverTicker.staleCandidateGapTicks(INTERVAL, 0)).hasCandidate(),
                "a skipped turn is a real pause in observation: the candidate is dropped");
    }

    @Test
    @DisplayName("the derivation holds on the real scheduler: every gap between services is exactly interval + lag")
    void gapsMatchTheThreshold() {
        FixedReceiverRegistry registry = new FixedReceiverRegistry();
        for (int i = 0; i < 60; i++) {
            registry.register(BlockPos.asLong(i, 70, -i), new NullDevice());
        }
        Random random = new Random(8L);
        Map<Long, Long> lastServed = new HashMap<>();
        int[] checked = {0};
        long[] time = {0L};
        for (long tick = 0; tick < 400; tick++) {
            // A random budget: one to seven batches' worth of fake nanoseconds this tick.
            long deadline = time[0] + 1 + random.nextInt(7) * FixedReceiverTicker.BATCH_RECEIVERS * 10L;
            long now = tick;
            FixedReceiverTicker.runTick(List.of(registry), 0, now, INTERVAL, () -> time[0], deadline,
                    (lane, entry, lag) -> {
                        time[0] += 10;
                        Long previous = lastServed.put(entry.key, now);
                        if (previous != null) {
                            assertEquals(FixedReceiverTicker.staleCandidateGapTicks(INTERVAL, lag), now - previous,
                                    "gap since the last service");
                            checked[0]++;
                        }
                    });
        }
        assertTrue(checked[0] > 600, "most receivers were served many times: " + checked[0]);
    }

    // ---- the stagger ------------------------------------------------------------------------------

    @Test
    @DisplayName("stagger: in [0, interval), deterministic, and spreads a block of devices over the interval")
    void staggerSpreads() {
        int[] perTick = new int[INTERVAL];
        for (int x = 0; x < 20; x++) {
            for (int y = 60; y < 70; y++) {
                long key = BlockPos.asLong(x, y, 5);
                int offset = FixedReceiverTicker.staggerTicks(key, INTERVAL);
                assertTrue(offset >= 0 && offset < INTERVAL);
                assertEquals(offset, FixedReceiverTicker.staggerTicks(key, INTERVAL));
                perTick[offset]++;
            }
        }
        for (int count : perTick) {
            assertTrue(count >= 1 && count <= 25, "200 devices over 20 ticks, about 10 each: " + count);
        }
        assertEquals(0, FixedReceiverTicker.staggerTicks(123L, 1));
        assertEquals(0, FixedReceiverTicker.staggerTicks(123L, 0), "a nonsense interval counts as 1");
        assertTrue(FixedReceiverTicker.staggerTicks(Long.MIN_VALUE, 7) >= 0);
    }

    // ---- the round robin --------------------------------------------------------------------------

    /** Serves every due receiver: the clock never reaches the deadline. */
    private static final long NO_DEADLINE = Long.MAX_VALUE;

    private static FixedReceiverRegistry registryOf(int count, int z) {
        FixedReceiverRegistry registry = new FixedReceiverRegistry();
        for (int i = 0; i < count; i++) {
            registry.register(BlockPos.asLong(i, 64, z), new NullDevice());
        }
        return registry;
    }

    private static void allDueAt(FixedReceiverRegistry registry, long tick) {
        for (int i = 0; i < registry.size(); i++) {
            registry.setDueAt(registry.advanceIndex(), tick);
        }
    }

    @Test
    @DisplayName("within budget: each receiver first at its stagger, then exactly once per interval")
    void oncePerInterval() {
        FixedReceiverRegistry registry = registryOf(50, 0);
        Map<Long, List<Long>> served = new HashMap<>();
        for (long tick = 1_000; tick < 1_200; tick++) {
            long now = tick;
            FixedReceiverTicker.runTick(List.of(registry), 0, now, INTERVAL, () -> 0L, NO_DEADLINE,
                    (lane, entry, lag) -> {
                        assertEquals(0L, lag, "never late within budget");
                        served.computeIfAbsent(entry.key, k -> new ArrayList<>()).add(now);
                    });
        }
        assertEquals(50, served.size());
        for (Map.Entry<Long, List<Long>> entry : served.entrySet()) {
            List<Long> ticks = entry.getValue();
            assertEquals(1_000L + FixedReceiverTicker.staggerTicks(entry.getKey(), INTERVAL), ticks.get(0),
                    "first turn at the stagger");
            assertEquals(10, ticks.size(), "200 ticks, 20 per interval");
            for (int i = 1; i < ticks.size(); i++) {
                assertEquals(INTERVAL, ticks.get(i) - ticks.get(i - 1));
            }
        }
    }

    @Test
    @DisplayName("the first batch always runs, however small the budget")
    void firstBatchAlwaysRuns() {
        FixedReceiverRegistry registry = registryOf(30, 0);
        allDueAt(registry, 0L);
        int served = FixedReceiverTicker.runTick(List.of(registry), 0, 0L, INTERVAL, () -> 1_000L, 0L,
                (lane, entry, lag) -> { });
        assertEquals(FixedReceiverTicker.BATCH_RECEIVERS, served, "the deadline had passed before the tick");
    }

    @Test
    @DisplayName("over budget: the rest wait, still due, and are served next tick in round-robin order with their lag")
    void overBudgetResumesInOrder() {
        int batch = FixedReceiverTicker.BATCH_RECEIVERS;
        int count = 5 * batch;
        FixedReceiverRegistry registry = registryOf(count, 0);
        allDueAt(registry, 0L);
        List<Long> order = new ArrayList<>();
        Map<Long, Long> lags = new HashMap<>();
        long[] time = {0L};
        // Each serve costs 10 fake ns; the budget runs out inside the first batch.
        long budget = batch * 10L - 5L;
        for (long tick = 0; tick < 5; tick++) {
            long now = tick;
            int served = FixedReceiverTicker.runTick(List.of(registry), 0, now, INTERVAL, () -> time[0],
                    time[0] + budget, (lane, entry, lag) -> {
                        time[0] += 10;
                        order.add(entry.key);
                        lags.put(entry.key, lag);
                    });
            assertEquals(batch, served, "one batch per tick, tick " + tick);
        }
        List<Long> registration = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            registration.add(BlockPos.asLong(i, 64, 0));
        }
        assertEquals(registration, order, "every receiver once, in round-robin order, nobody starved");
        for (int i = 0; i < count; i++) {
            assertEquals((long) (i / batch), lags.get(registration.get(i)), "the lag is how many ticks it waited");
        }
        int more = FixedReceiverTicker.runTick(List.of(registry), 0, 5L, INTERVAL, () -> time[0], time[0] + budget,
                (lane, entry, lag) -> { });
        assertEquals(0, more, "and nobody is served twice within an interval");
    }

    @Test
    @DisplayName("the dimension served first rotates, so a busy one cannot starve another")
    void firstLaneRotates() {
        FixedReceiverRegistry overworld = registryOf(16, 0);
        FixedReceiverRegistry nether = registryOf(16, 1);
        allDueAt(overworld, 0L);
        allDueAt(nether, 0L);
        List<Integer> lanesServed = new ArrayList<>();
        long[] time = {0L};
        for (int tick = 0; tick < 2; tick++) {
            List<Integer> thisTick = new ArrayList<>();
            FixedReceiverTicker.runTick(List.of(overworld, nether), tick, tick, INTERVAL, () -> time[0], time[0] + 1,
                    (lane, entry, lag) -> {
                        time[0] += 10;
                        thisTick.add(lane);
                    });
            assertEquals(FixedReceiverTicker.BATCH_RECEIVERS, thisTick.size());
            assertTrue(thisTick.stream().allMatch(lane -> lane == thisTick.get(0)), "one batch, one lane");
            lanesServed.add(thisTick.get(0));
        }
        assertEquals(List.of(0, 1), lanesServed);

        // With room for everything, the lanes alternate batch by batch.
        allDueAt(overworld, 10L);
        allDueAt(nether, 10L);
        List<Integer> interleaved = new ArrayList<>();
        FixedReceiverTicker.runTick(List.of(overworld, nether), 1, 10L, INTERVAL, () -> 0L, NO_DEADLINE,
                (lane, entry, lag) -> interleaved.add(lane));
        assertEquals(32, interleaved.size());
        for (int i = 0; i < 32; i++) {
            int expectedLane = (i / FixedReceiverTicker.BATCH_RECEIVERS + 1) % 2;
            assertEquals(expectedLane, interleaved.get(i), "batch " + i / FixedReceiverTicker.BATCH_RECEIVERS);
        }
    }

    @Test
    @DisplayName("a device that unregisters receivers while being served does not break the tick or get anyone served twice")
    void removalDuringService() {
        FixedReceiverRegistry registry = new FixedReceiverRegistry();
        List<NullDevice> devices = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            NullDevice device = new NullDevice();
            devices.add(device);
            registry.register(BlockPos.asLong(i, 64, 0), device);
        }
        allDueAt(registry, 0L);
        List<Long> served = new ArrayList<>();
        FixedReceiverTicker.runTick(List.of(registry), 0, 0L, INTERVAL, () -> 0L, NO_DEADLINE, (lane, entry, lag) -> {
            served.add(entry.key);
            int x = BlockPos.getX(entry.key);
            if (x % 4 == 0 && x + 1 < 20) {
                // Breaks its east neighbour, which has not had its turn yet.
                registry.unregister(BlockPos.asLong(x + 1, 64, 0), devices.get(x + 1));
            }
        });
        assertEquals(15, registry.size());
        assertEquals(15, served.size(), "every remaining receiver once");
        assertEquals(served.size(), served.stream().distinct().count(), "none twice");
    }

    @Test
    @DisplayName("an interval lowered live pulls a far due tick in; a new receiver waits for its stagger")
    void schedulingEdges() {
        FixedReceiverRegistry registry = registryOf(1, 0);
        FixedReceiverRegistry.Entry entry = registry.advance();
        long stagger = FixedReceiverTicker.staggerTicks(entry.key, INTERVAL);
        int[] served = {0};
        for (long tick = 0; tick < stagger; tick++) {
            FixedReceiverTicker.runTick(List.of(registry), 0, tick, INTERVAL, () -> 0L, NO_DEADLINE,
                    (lane, e, lag) -> served[0]++);
        }
        assertEquals(0, served[0], "not before its stagger");
        FixedReceiverTicker.runTick(List.of(registry), 0, stagger, INTERVAL, () -> 0L, NO_DEADLINE,
                (lane, e, lag) -> served[0]++);
        assertEquals(1, served[0]);

        registry.setDueOf(entry.key, 5_000L); // scheduled under an interval of 200
        FixedReceiverTicker.runTick(List.of(registry), 0, 1_000L, INTERVAL, () -> 0L, NO_DEADLINE,
                (lane, e, lag) -> served[0]++);
        assertEquals(1_000L + INTERVAL, registry.dueOf(entry.key), "pulled in to one interval from now");
        assertEquals(1, served[0], "but not served early");
        assertEquals(0, FixedReceiverTicker.runTick(List.of(), 0, 0L, INTERVAL, () -> 0L, 0L,
                (lane, e, lag) -> served[0]++), "no dimensions, nothing to do");
    }
}
