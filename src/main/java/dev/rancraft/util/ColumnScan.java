package dev.rancraft.util;

import java.util.function.IntPredicate;

/**
 * Where a column of stacked Signal Masts starts and ends. Phase 3 slice 6 (§3B.1).
 *
 * <p>A vertical run of contiguous masts is one site. Its lowest mast, the <b>base</b>, owns the cell
 * (so extending the tower upward keeps the cell's id and its PCI); the cell radiates from just above
 * the <b>top</b> of the signal part; every other mast in the run is structure and never transmits.
 * The signal part is at most {@code maxHeight} masts tall: masts above that are still structure (and
 * still part of the column) but no longer raise the radiating point.
 *
 * <p>Not RF: this only answers "which blocks form one tower", so it lives in {@code util}. Pure (the
 * column is read through an {@link IntPredicate} over y, "is there a mast at this height in this x/z
 * column"), so it is unit-tested with a fake predicate and the server and the client (the RF Lens)
 * apply exactly the same rule to the same public world data.
 *
 * <p>Honest note, also in NOTES.md: the propagation model has no antenna-height term (Okumura-Hata
 * has one; log-distance does not). A taller column helps only by lifting the radiating point clear
 * of obstruction and, for a sector on its own mast, through the vertical pattern.
 */
public final class ColumnScan {

    private ColumnScan() {
    }

    /**
     * The longest walk in either direction, in blocks. More than any world is tall (1.21 caps a
     * dimension at 4064 blocks), so it never shortens a real column; it only guarantees that a
     * predicate which never says "no" cannot hang the scan.
     */
    public static final int MAX_WALK = 4096;

    /** A radiating height nobody has reported: the client has not been told the server's value. */
    public static final int UNKNOWN_Y = Integer.MIN_VALUE;

    /**
     * One column.
     *
     * @param baseY    the lowest mast: the one that owns the cell.
     * @param topY     the highest mast of the signal part: at most {@code maxHeight - 1} above the
     *                 base. The cell radiates from {@link #radiatingY()}.
     * @param highestY the highest mast of the whole run, structure above the cap included. Equal to
     *                 {@code topY} unless the column is taller than the cap. Whatever sits directly
     *                 above it (a sector antenna, for example) is on top of the column.
     */
    public record Bounds(int baseY, int topY, int highestY) {

        /** {@code top.above()}: where the column's cell radiates from. */
        public int radiatingY() {
            return topY + 1;
        }

        /** Masts in the whole run, structure above the cap included. */
        public int height() {
            return highestY - baseY + 1;
        }

        /** Masts in the signal part: {@code min(height, maxHeight)}. */
        public int signalHeight() {
            return topY - baseY + 1;
        }

        /** Whether masts above the cap are structure only. */
        public boolean capped() {
            return highestY > topY;
        }
    }

    /**
     * The column through {@code y}.
     *
     * <p>Walks down from {@code y} to the lowest contiguous mast (the base), then up from there to the
     * highest one. The signal part is the lowest {@code maxHeight} masts. A gap ends a column: the
     * masts above a gap are a column of their own with their own base.
     *
     * @param y         a height at which {@code isMast} is true.
     * @param isMast    whether a mast stands at a height, in this x/z column.
     * @param maxHeight the cap on the signal part ({@code maxMastHeight}); values below 1 count as 1.
     * @throws IllegalArgumentException if there is no mast at {@code y}.
     */
    public static Bounds bounds(int y, IntPredicate isMast, int maxHeight) {
        if (!isMast.test(y)) {
            throw new IllegalArgumentException("no mast at y=" + y);
        }
        int base = y;
        for (int steps = 0; steps < MAX_WALK && isMast.test(base - 1); steps++) {
            base--;
        }
        int highest = base;
        for (int steps = 0; steps < MAX_WALK && isMast.test(highest + 1); steps++) {
            highest++;
        }
        int cap = Math.max(1, maxHeight);
        // In long arithmetic: base + cap - 1 can overflow an int for a huge cap.
        int top = (int) Math.min((long) highest, (long) base + cap - 1L);
        return new Bounds(base, top, highest);
    }

    /**
     * Where a column radiates from, given the height another party (the server) reported for it: the
     * reported height if it can belong to this column, otherwise this column's own
     * {@link Bounds#radiatingY()}. Phase 3B review fix.
     *
     * <p>Why: the signal part's cap ({@code maxMastHeight}) is a COMMON config, which NeoForge does not
     * sync, so a client may scan the same blocks with a different cap than the server registered the
     * cell with. The server's height is the true one. It can belong to the column when it lies above
     * the base and at most one block above the highest mast ({@code (baseY, highestY + 1]}); any cap
     * gives a height in that range. Outside it, the report is about a column that has since changed
     * (a block change can reach the client before the base's new update tag), and the local scan of
     * the blocks the client sees is the better guess until the next report arrives.
     *
     * @param column    this column, as scanned locally.
     * @param reportedY the reported radiating height, or {@link #UNKNOWN_Y} if none was reported.
     */
    public static int reportedRadiatingY(Bounds column, int reportedY) {
        if (reportedY != UNKNOWN_Y && reportedY > column.baseY() && reportedY <= (long) column.highestY() + 1L) {
            return reportedY;
        }
        return column.radiatingY();
    }

    /**
     * Whether the mast at {@code y} is its column's base: a mast with no mast directly under it. One
     * lookup, so a caller can skip the structure masts of a tall column without scanning each one.
     */
    public static boolean isBase(int y, IntPredicate isMast) {
        return isMast.test(y) && !isMast.test(y - 1);
    }

    /**
     * Whether an antenna sits directly on top of the column (above its highest mast, not above the
     * cap). §3B.1: then the column is a mounting pole and does not transmit; the antenna on top is
     * its own cell.
     */
    public static boolean mountingPole(Bounds column, IntPredicate isMountedAntenna) {
        return isMountedAntenna.test(column.highestY() + 1);
    }

    /**
     * Whether {@code test} holds for any mast of the column, structure above the cap included. §3B.1's
     * power gate: the column is powered if any mast in it receives a redstone signal.
     */
    public static boolean any(Bounds column, IntPredicate test) {
        for (int y = column.baseY(); y <= column.highestY(); y++) {
            if (test.test(y)) {
                return true;
            }
        }
        return false;
    }
}
