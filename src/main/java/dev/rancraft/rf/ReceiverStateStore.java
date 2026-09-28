package dev.rancraft.rf;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Per-receiver handover state, keyed by whatever identifies a receiver.
 *
 * <p>Generic in the key on purpose. Phase 2 keys players by {@code UUID}; Phase 3 keys fixed
 * devices by block position, and must not need a second copy of this class to do it.
 *
 * <p><b>This map is a server leak if nobody clears it.</b> Every entry corresponds to a receiver
 * that existed at some point, and a long-running server accumulates one per player who ever logged
 * in. The owner must call {@link #clear(Object)} on logout, on dimension change and on death --
 * the last two because a receiver that teleports has no business carrying a candidate timer for a
 * cell that is now thousands of blocks away.
 */
public final class ReceiverStateStore<K> {

    private final Map<K, ReceiverState> states = new ConcurrentHashMap<>();

    public ReceiverState get(K key) {
        return states.getOrDefault(key, ReceiverState.NONE);
    }

    public void put(K key, ReceiverState state) {
        states.put(key, state);
    }

    /** Forgets one receiver. Safe to call for a key that was never present. */
    public void clear(K key) {
        states.remove(key);
    }

    public void clearAll() {
        states.clear();
    }

    public int size() {
        return states.size();
    }

    /** Runs the selection rule for one receiver and stores the result in one call. */
    public CellSelector.Selection select(
            K key,
            java.util.List<CellSample> cellsByRsrpDesc,
            long tick,
            CellSelector.SelectionParams params) {

        CellSelector.Selection selection = CellSelector.select(get(key), cellsByRsrpDesc, tick, params);
        put(key, selection.state());
        return selection;
    }
}
