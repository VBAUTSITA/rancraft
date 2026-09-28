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
 *
 * <p><b>Phase 3 slice 4:</b> the store also remembers <em>when</em> each state was produced
 * ({@link #put(Object, ReceiverState, long)}), so {@link #resume} can drop a handover candidate
 * that went stale while the receiver was not being evaluated. See
 * {@link CellSelector#expireStaleCandidate}.
 */
public final class ReceiverStateStore<K> {

    private final Map<K, ReceiverState> states = new ConcurrentHashMap<>();
    private final Map<K, Long> evaluatedAt = new ConcurrentHashMap<>();

    public ReceiverState get(K key) {
        return states.getOrDefault(key, ReceiverState.NONE);
    }

    /**
     * Stores a state with no record of when it was produced, so {@link #resume} will never expire
     * its candidate. Evaluations should use {@link #put(Object, ReceiverState, long)}.
     */
    public void put(K key, ReceiverState state) {
        states.put(key, state);
        evaluatedAt.remove(key);
    }

    /** Stores the state an evaluation at {@code tick} produced, and remembers that tick. */
    public void put(K key, ReceiverState state, long tick) {
        states.put(key, state);
        evaluatedAt.put(key, tick);
    }

    /**
     * The tick of the evaluation that produced this receiver's state, or
     * {@link CellSelector#NEVER_EVALUATED} if none was recorded.
     */
    public long lastEvaluatedTick(K key) {
        return evaluatedAt.getOrDefault(key, CellSelector.NEVER_EVALUATED);
    }

    /**
     * The state to carry into an evaluation at {@code tick}: {@link #get} with an armed candidate
     * dropped if more than {@code maxGapTicks} passed since the last recorded evaluation
     * ({@link CellSelector#expireStaleCandidate}). Reads only; the evaluation stores its result.
     */
    public ReceiverState resume(K key, long tick, long maxGapTicks) {
        return CellSelector.expireStaleCandidate(get(key), lastEvaluatedTick(key), tick, maxGapTicks);
    }

    /** Forgets one receiver. Safe to call for a key that was never present. */
    public void clear(K key) {
        states.remove(key);
        evaluatedAt.remove(key);
    }

    public void clearAll() {
        states.clear();
        evaluatedAt.clear();
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
        put(key, selection.state(), tick);
        return selection;
    }
}
