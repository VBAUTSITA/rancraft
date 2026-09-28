package dev.rancraft.rf;

import java.util.List;
import java.util.Optional;

/**
 * The full result of one evaluation.
 *
 * <p>{@code cells} is sorted by RSRP descending and may be empty. Note that from Phase 2 onward
 * <b>the strongest cell is not necessarily the serving cell</b>: handover hysteresis can keep a
 * receiver camped on a slightly weaker cell to stop it ping-ponging at a boundary. Read
 * {@link #serving()}, which honours {@code servingCellId}, rather than assuming index 0.
 *
 * @param servingCellId   {@link ReceiverState#NO_CELL} when out of service.
 * @param sinrDb          raw and unclamped; the HUD clamps it for display.
 * @param interferenceDbm {@code -Infinity} when nothing is interfering.
 */
public record SignalSample(
        List<CellSample> cells,
        long timestampTick,
        // ---- Phase 2 ----
        long servingCellId,
        double sinrDb,
        double interferenceDbm,
        double noiseDbm,
        ServiceLevel serviceLevel,
        int handoverCount
) {

    public SignalSample {
        cells = List.copyOf(cells);
    }

    public static SignalSample empty(long timestampTick) {
        return empty(timestampTick, 0);
    }

    /** An out-of-service sample that preserves the receiver's running handover tally. */
    public static SignalSample empty(long timestampTick, int handoverCount) {
        return new SignalSample(
                List.of(), timestampTick,
                ReceiverState.NO_CELL,
                Double.NEGATIVE_INFINITY, Double.NEGATIVE_INFINITY, 0.0,
                ServiceLevel.NONE, handoverCount);
    }

    /** The cell actually serving this receiver, which hysteresis may hold below the strongest. */
    public Optional<CellSample> serving() {
        if (cells.isEmpty()) {
            return Optional.empty();
        }
        for (CellSample cell : cells) {
            if (cell.cellId() == servingCellId) {
                return Optional.of(cell);
            }
        }
        return Optional.empty();
    }

    /** The strongest cell, regardless of which one is serving. */
    public Optional<CellSample> strongest() {
        return cells.isEmpty() ? Optional.empty() : Optional.of(cells.get(0));
    }

    /** How many evaluated cells share the serving cell's band and are therefore co-channel. */
    public int coChannelCount() {
        return serving().map(serving -> (int) cells.stream()
                .filter(cell -> cell.cellId() != serving.cellId())
                .filter(cell -> cell.bandId().equals(serving.bandId()))
                .count()).orElse(0);
    }

    public boolean isNoService() {
        return cells.isEmpty() || serving().isEmpty() || serviceLevel == ServiceLevel.NONE;
    }

    public double displaySinrDb() {
        return Math.clamp(sinrDb, SinrCalculator.DISPLAY_MIN_DB, SinrCalculator.DISPLAY_MAX_DB);
    }
}
