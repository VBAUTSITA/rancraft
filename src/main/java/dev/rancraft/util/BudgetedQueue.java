package dev.rancraft.util;

import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.function.Consumer;
import java.util.function.LongSupplier;

/**
 * A queue of work items done a few per server tick, under a time budget. Phase 3C review, finding 2:
 * the backhaul recompute marches its dirty microwave hops through one of these, so a recompute that
 * must re-measure many long hops at once (a block moving in a region bin they all cross, chunks loading
 * along a long link, the first recompute after a server start) spreads them over ticks instead of
 * spending tens of milliseconds in one.
 *
 * <p>The rule is the one the fixed-receiver ticker uses: the clock is read before each item, and the
 * drain stops once the budget has passed since the tick's start; the first item of a drain may be taken
 * whatever the clock says, so the caller makes progress however small the budget (or however much of
 * a shared budget others used). A drain therefore overruns its budget by at most one item: the one that
 * started inside the budget, or the one forced first.
 *
 * <p>Items are taken oldest first; an item already queued is not queued twice (a set in insertion
 * order). Pure (no game types). Not thread-safe: the server thread only.
 *
 * @param <T> the item; equal items are the same work.
 */
public final class BudgetedQueue<T> {

    private final LinkedHashSet<T> items = new LinkedHashSet<>();

    /** Queues an item at the back. False (and nothing moves) when it is already queued. */
    public boolean add(T item) {
        return items.add(item);
    }

    /** Takes an item out without doing it. False when it was not queued. */
    public boolean remove(T item) {
        return items.remove(item);
    }

    public boolean contains(T item) {
        return items.contains(item);
    }

    public int size() {
        return items.size();
    }

    public boolean isEmpty() {
        return items.isEmpty();
    }

    public void clear() {
        items.clear();
    }

    /**
     * Hands items, oldest first, to {@code work} while the budget lasts. Before each item the clock is
     * read, and the drain stops once {@code clock - startNanos >= budgetNanos}; with {@code atLeastOne}
     * the first item is handed over without that check. Each item leaves the queue before {@code work}
     * runs, so {@code work} may queue it (or others) again: those wait for the next drain.
     *
     * @param clock       nanoseconds, as {@link System#nanoTime()} (differences only).
     * @param startNanos  the clock when the budget started (the tick's start, when several callers share
     *                    one budget).
     * @param budgetNanos the budget; {@link Long#MAX_VALUE} for none (the queue is drained).
     * @param atLeastOne  take the first item even when the budget is already spent.
     * @return how many items were handed to {@code work}.
     */
    public int drain(LongSupplier clock, long startNanos, long budgetNanos, boolean atLeastOne,
                     Consumer<? super T> work) {
        if (budgetNanos < 0) {
            throw new IllegalArgumentException("budgetNanos must be zero or positive: " + budgetNanos);
        }
        int done = 0;
        int toTake = items.size();
        while (done < toTake && !items.isEmpty()) {
            if ((done > 0 || !atLeastOne) && clock.getAsLong() - startNanos >= budgetNanos) {
                break;
            }
            Iterator<T> oldest = items.iterator();
            T item = oldest.next();
            oldest.remove();
            work.accept(item);
            done++;
        }
        return done;
    }
}
