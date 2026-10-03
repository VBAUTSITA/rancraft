package dev.rancraft.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.function.LongSupplier;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Phase 3C review, finding 2: the per-tick budget the backhaul recompute marches its hops under. A fake
 * clock in which each item costs a known time stands in for the marches.
 */
class BudgetedQueueTest {

    /** A clock that each item of work advances by its own cost. */
    private static final class FakeClock implements LongSupplier {

        long now;
        int reads;

        @Override
        public long getAsLong() {
            reads++;
            return now;
        }
    }

    private static BudgetedQueue<Integer> queueOf(int count) {
        BudgetedQueue<Integer> queue = new BudgetedQueue<>();
        for (int i = 0; i < count; i++) {
            queue.add(i);
        }
        return queue;
    }

    @Test
    @DisplayName("Items go oldest first, and one already queued is not queued twice")
    void orderAndNoDuplicates() {
        BudgetedQueue<Integer> queue = queueOf(3);
        assertFalse(queue.add(1), "1 is already queued");
        assertTrue(queue.add(7));
        assertEquals(4, queue.size());
        List<Integer> done = new ArrayList<>();
        assertEquals(4, queue.drain(() -> 0L, 0L, Long.MAX_VALUE, false, done::add));
        assertEquals(List.of(0, 1, 2, 7), done);
        assertTrue(queue.isEmpty());
        assertTrue(queue.add(1), "drained: it can be queued again");
        assertTrue(queue.remove(1));
        assertFalse(queue.remove(1));
        assertFalse(queue.contains(1));
    }

    @Test
    @DisplayName("Within budget: items are taken while the budget lasts, and a drain overruns it by at most one item")
    void stopsAtTheBudget() {
        FakeClock clock = new FakeClock();
        BudgetedQueue<Integer> queue = queueOf(20);
        long budget = 250;
        // Each item costs 100: started at 0, 100 and 200 (inside the budget); the clock reads 300 before the 4th.
        int done = queue.drain(clock, 0L, budget, true, item -> clock.now += 100);
        assertEquals(3, done);
        assertEquals(300, clock.now, "overran the 250 budget by less than one item");
        assertEquals(17, queue.size());

        // An uneven load: whatever the costs, no item starts after the budget is spent.
        long[] costs = {30, 400, 10, 10};
        BudgetedQueue<Integer> uneven = queueOf(4);
        clock.now = 1_000;
        long start = clock.now;
        List<Long> startedAt = new ArrayList<>();
        uneven.drain(clock, start, budget, true, item -> {
            startedAt.add(clock.now - start);
            clock.now += costs[item];
        });
        assertEquals(List.of(0L, 30L), startedAt, "the 400 started at 30, inside; nothing started after 430");
        assertEquals(2, uneven.size());
    }

    @Test
    @DisplayName("Progress whatever the budget: the first item is taken even when the budget is spent, unless the caller says not")
    void atLeastOne() {
        FakeClock clock = new FakeClock();
        clock.now = 5_000;
        BudgetedQueue<Integer> queue = queueOf(3);
        assertEquals(1, queue.drain(clock, 0L, 100L, true, item -> clock.now += 50), "budget long spent: one item");
        assertEquals(0, queue.drain(clock, 0L, 100L, false, item -> clock.now += 50), "and none without atLeastOne");
        assertEquals(1, queue.drain(clock, 0L, 0L, true, item -> clock.now += 50), "a zero budget: one item");
        assertEquals(1, queue.size());
        // Ten ticks with a budget smaller than any item still drain ten items.
        BudgetedQueue<Integer> slow = queueOf(10);
        int ticks = 0;
        while (!slow.isEmpty()) {
            long start = clock.now;
            assertEquals(1, slow.drain(clock, start, 10L, true, item -> clock.now += 100));
            ticks++;
        }
        assertEquals(10, ticks);
    }

    @Test
    @DisplayName("No budget (Long.MAX_VALUE) drains the queue; an empty queue does nothing and reads no clock")
    void unboundedAndEmpty() {
        FakeClock clock = new FakeClock();
        BudgetedQueue<Integer> queue = queueOf(50);
        assertEquals(50, queue.drain(clock, 0L, Long.MAX_VALUE, false, item -> clock.now += 1_000_000_000L));
        assertTrue(queue.isEmpty());
        int reads = clock.reads;
        assertEquals(0, queue.drain(clock, 0L, 0L, true, item -> {
            throw new AssertionError("nothing to do");
        }));
        assertEquals(reads, clock.reads);
        assertThrows(IllegalArgumentException.class, () -> queue.drain(clock, 0L, -1L, true, item -> { }));
    }

    @Test
    @DisplayName("An item queued again by its own work waits for the next drain")
    void requeuedItemsWait() {
        BudgetedQueue<Integer> queue = queueOf(2);
        List<Integer> done = new ArrayList<>();
        assertEquals(2, queue.drain(() -> 0L, 0L, Long.MAX_VALUE, true, item -> {
            done.add(item);
            queue.add(item + 10);
        }));
        assertEquals(List.of(0, 1), done);
        assertEquals(2, queue.size(), "10 and 11 wait");
        queue.clear();
        assertTrue(queue.isEmpty());
    }
}
