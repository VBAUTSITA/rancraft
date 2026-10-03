package dev.rancraft.util;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

/**
 * How many distinct receivers each cell served in a recent window. Phase 3 slice 15 (§3C.5).
 *
 * <p><b>A seam, not a feature.</b> A real network saves energy by putting idle cells to sleep
 * (discontinuous transmission, cell switch-off at night) and needs to know which cells nobody is
 * using. Phase 4 can build that on this counter. Nothing in Phase 3 reads it except the status command
 * ({@code /rancraft power status}): no cell sleeps, no draw changes.
 *
 * <p>A receiver is <em>served</em> by a cell when the server evaluates it and that cell is its serving
 * cell (a player carrying a device or wearing a lens, or a fixed device such as a Radio Link). A
 * receiver served by two cells within the window (it walked across a boundary) counts for both:
 * both were in use. Repeats within the window count once.
 *
 * <p>Pure (no game types; the receiver key is whatever identifies one: a player's UUID, a fixed
 * device's packed position). Not thread-safe: the server thread only.
 *
 * @param <R> the receiver key; equal keys are the same receiver.
 */
public final class ServedReceivers<R> {

    /** Cell id to (receiver to the tick it was last served by that cell). */
    private final Map<Long, Map<R, Long>> byCell = new HashMap<>();

    /**
     * Whether a receiver that last reported {@code lastCell} at {@code lastTick} should report again,
     * now served by {@code cell} at {@code tick}: when the cell changed, or the last report is at least
     * {@code interval} ticks old. A receiver served continuously then always has a record younger than
     * {@code interval}, so with a window longer than that it is never dropped while served; one that
     * stops being served drops out between {@code window - interval} and {@code window} after its last
     * service. Lets a caller with many receivers skip the map on most turns.
     */
    public static boolean due(long lastCell, long lastTick, long cell, long tick, long interval) {
        return cell != lastCell || tick - lastTick >= interval;
    }

    /** Records that {@code cellId} served {@code receiver} at {@code tick}. */
    public void served(long cellId, R receiver, long tick) {
        byCell.computeIfAbsent(cellId, id -> new HashMap<>()).merge(receiver, tick, Math::max);
    }

    /**
     * Distinct receivers {@code cellId} served in the window ending at {@code now}: those last served
     * at a tick {@code > now - windowTicks}.
     */
    public int count(long cellId, long now, long windowTicks) {
        Map<R, Long> receivers = byCell.get(cellId);
        if (receivers == null) {
            return 0;
        }
        long since = now - windowTicks;
        int count = 0;
        for (long tick : receivers.values()) {
            if (tick > since) {
                count++;
            }
        }
        return count;
    }

    /** Forgets every record older than the window, and every cell left with none. */
    public void prune(long now, long windowTicks) {
        long since = now - windowTicks;
        Iterator<Map<R, Long>> cells = byCell.values().iterator();
        while (cells.hasNext()) {
            Map<R, Long> receivers = cells.next();
            receivers.values().removeIf(tick -> tick <= since);
            if (receivers.isEmpty()) {
                cells.remove();
            }
        }
    }

    /** Cells with at least one record (pruned or not). */
    public int cells() {
        return byCell.size();
    }

    /** Records kept, over all cells (pruned or not). */
    public int records() {
        int records = 0;
        for (Map<R, Long> receivers : byCell.values()) {
            records += receivers.size();
        }
        return records;
    }

    public void clear() {
        byCell.clear();
    }
}
