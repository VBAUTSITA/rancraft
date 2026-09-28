package dev.rancraft.rf;

/**
 * What one receiver remembers between samples.
 *
 * <p>Phase 1 re-picked the strongest cell every second, which made the meter flicker whenever two
 * cells were within noise of each other at a boundary. Phase 2 keeps this state so that a handover
 * has to be earned: a neighbour must be meaningfully better <em>and</em> stay better.
 *
 * <p>Immutable. {@link CellSelector} returns a new instance rather than mutating, so the selection
 * rule is a pure function and testable without a server.
 */
public record ReceiverState(
        long servingCellId,
        long servingSinceTick,
        long candidateCellId,
        long candidateSinceTick,
        int handoverCount
) {

    /** Sentinel for "no cell". Cell ids are derived from block positions and can legitimately be 0. */
    public static final long NO_CELL = Long.MIN_VALUE;

    public static final ReceiverState NONE =
            new ReceiverState(NO_CELL, 0L, NO_CELL, 0L, 0);

    public boolean hasServing() {
        return servingCellId != NO_CELL;
    }

    public boolean hasCandidate() {
        return candidateCellId != NO_CELL;
    }

    /** Drops any pending candidate but keeps the serving cell and the handover tally. */
    public ReceiverState withoutCandidate() {
        return hasCandidate()
                ? new ReceiverState(servingCellId, servingSinceTick, NO_CELL, 0L, handoverCount)
                : this;
    }

    public ReceiverState withCandidate(long cellId, long tick) {
        return new ReceiverState(servingCellId, servingSinceTick, cellId, tick, handoverCount);
    }

    /** Selection without a handover: used on recovery from outage, so the tally does not move. */
    public ReceiverState reselected(long cellId, long tick) {
        return new ReceiverState(cellId, tick, NO_CELL, 0L, handoverCount);
    }

    /** A real handover: the candidate is promoted and the tally increments. */
    public ReceiverState handedOverTo(long cellId, long tick) {
        return new ReceiverState(cellId, tick, NO_CELL, 0L, handoverCount + 1);
    }
}
