package dev.rancraft.rf;

import java.util.List;

/**
 * Picks which cell serves a receiver, with A3-style handover hysteresis.
 *
 * <p>Modelled on the 3GPP A3 event ("neighbour becomes offset better than serving") plus a
 * time-to-trigger: the neighbour must be better by {@code handoverHysteresisDb} <em>and</em> stay
 * that way for {@code timeToTriggerTicks} before the receiver moves. Both halves are real; without
 * the timer a receiver ping-pongs across a boundary, and the handover counter in the HUD exists
 * precisely so a player can see that happening instead of guessing.
 *
 * <p>Recovery from an outage deliberately skips both: if the serving cell has vanished or dropped
 * below the sensitivity floor there is nothing to be loyal to, and making a player wait two extra
 * seconds for service they could already have would be wrong.
 *
 * <p>Stateless by design -- the caller owns the {@link ReceiverState}.
 */
public final class CellSelector {

    private CellSelector() {
    }

    public record SelectionParams(double handoverHysteresisDb, int timeToTriggerTicks) {
        public static final SelectionParams DEFAULTS = new SelectionParams(3.0, 40);
    }

    /**
     * @param state       the state to carry forward, never null; use {@link ReceiverState#NONE}.
     * @param handedOver  true only on the tick a real handover fired, for logging and the HUD.
     */
    public record Selection(ReceiverState state, CellSample serving, boolean handedOver) {

        public boolean hasServing() {
            return serving != null;
        }

        public long servingCellId() {
            return serving != null ? serving.cellId() : ReceiverState.NO_CELL;
        }
    }

    /**
     * @param cellsByRsrpDesc every cell above the sensitivity floor, strongest first -- exactly
     *                        what {@link RfEngine#evaluate} returns.
     */
    public static Selection select(
            ReceiverState previous,
            List<CellSample> cellsByRsrpDesc,
            long tick,
            SelectionParams params) {

        if (cellsByRsrpDesc.isEmpty()) {
            // Total outage. Forget everything except the tally, so the next cell in range is taken
            // immediately rather than being held off by a stale candidate timer.
            return new Selection(
                    new ReceiverState(ReceiverState.NO_CELL, tick, ReceiverState.NO_CELL, 0L, previous.handoverCount()),
                    null,
                    false);
        }

        CellSample strongest = cellsByRsrpDesc.get(0);
        CellSample serving = previous.hasServing() ? find(cellsByRsrpDesc, previous.servingCellId()) : null;

        // 1. No serving cell, or it is gone. RfEngine has already dropped anything below the
        //    floor, so "not in the list" covers both the broken-mast and the faded-out cases.
        if (serving == null) {
            return new Selection(previous.reselected(strongest.cellId(), tick), strongest, false);
        }

        // 2. The best alternative to what we are on.
        CellSample neighbour = strongestOtherThan(cellsByRsrpDesc, serving.cellId());
        if (neighbour == null) {
            return new Selection(previous.withoutCandidate(), serving, false);
        }

        boolean qualifies = neighbour.rsrpDbm() > serving.rsrpDbm() + params.handoverHysteresisDb();
        if (!qualifies) {
            // 3. The A3 condition stopped holding: the candidate timer resets rather than pausing,
            //    so a neighbour that flickers in and out never accumulates its way to a handover.
            return new Selection(previous.withoutCandidate(), serving, false);
        }

        if (previous.hasCandidate() && previous.candidateCellId() == neighbour.cellId()) {
            long heldTicks = tick - previous.candidateSinceTick();
            if (heldTicks >= params.timeToTriggerTicks()) {
                return new Selection(previous.handedOverTo(neighbour.cellId(), tick), neighbour, true);
            }
            return new Selection(previous, serving, false);
        }

        // A different neighbour (or the first qualifying one): start its timer now.
        return new Selection(previous.withCandidate(neighbour.cellId(), tick), serving, false);
    }

    /** "This receiver has no recorded evaluation": {@link #expireStaleCandidate} then keeps the state. */
    public static final long NEVER_EVALUATED = Long.MIN_VALUE;

    /**
     * The state to carry into an evaluation at {@code tick}, with an armed candidate dropped when
     * the receiver was not evaluated for longer than {@code maxGapTicks}.
     *
     * <p><b>Why.</b> Time-to-trigger is a tick difference ({@code tick - candidateSinceTick}), which
     * only means "the A3 condition held all along" if the condition was <em>looked at</em> all
     * along. A receiver that stops being evaluated (a player who puts every device away, or
     * switches the lens to a preset that needs no evaluation) freezes its state, armed candidate
     * included. Without this check, the first evaluation after the pause sees a huge
     * {@code heldTicks} and hands over at once, although nobody observed the neighbour staying
     * better through the gap. Dropping the candidate makes that evaluation re-arm it instead
     * (if the neighbour still qualifies), so the handover fires one full time-to-trigger after
     * evaluation resumed, exactly as if the receiver had just walked into the boundary.
     *
     * <p>The serving cell and the handover tally are kept ({@link ReceiverState#withoutCandidate}):
     * the pause is not an outage and not a handover.
     *
     * <p>A negative gap (the clock went backwards, which the game clock never does) cannot vouch
     * for the timer either, so it drops the candidate too.
     *
     * @param lastEvaluatedTick the tick of the evaluation that produced {@code previous}, or
     *                          {@link #NEVER_EVALUATED} when unknown (the state is then kept).
     * @param maxGapTicks       the longest gap between two evaluations that still counts as
     *                          continuous observation. The caller derives it from its own cadence;
     *                          the player ticker uses one evaluation interval.
     */
    public static ReceiverState expireStaleCandidate(
            ReceiverState previous, long lastEvaluatedTick, long tick, long maxGapTicks) {

        if (!previous.hasCandidate() || lastEvaluatedTick == NEVER_EVALUATED) {
            return previous;
        }
        long gapTicks = tick - lastEvaluatedTick;
        return gapTicks < 0 || gapTicks > maxGapTicks ? previous.withoutCandidate() : previous;
    }

    private static CellSample find(List<CellSample> cells, long cellId) {
        for (CellSample cell : cells) {
            if (cell.cellId() == cellId) {
                return cell;
            }
        }
        return null;
    }

    /** The list is already sorted by RSRP descending, so the first non-serving entry is the best. */
    private static CellSample strongestOtherThan(List<CellSample> cells, long cellId) {
        for (CellSample cell : cells) {
            if (cell.cellId() != cellId) {
                return cell;
            }
        }
        return null;
    }
}
