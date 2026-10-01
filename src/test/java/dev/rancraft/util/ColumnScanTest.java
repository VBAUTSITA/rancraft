package dev.rancraft.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.BitSet;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntPredicate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Mast columns (§3B.1, §3B tests): single block, 9-stack, gap in the column, antenna on top, height
 * cap. The world is a fake predicate over y.
 */
class ColumnScanTest {

    private static final int CAP = 64;

    /** Masts at the given heights; heights may be negative (below y = 0 in 1.18+ worlds). */
    private static IntPredicate masts(int... ys) {
        BitSet set = new BitSet();
        int offset = 1000;
        for (int y : ys) {
            set.set(y + offset);
        }
        return y -> y + offset >= 0 && set.get(y + offset);
    }

    /** A contiguous run from {@code from} to {@code to} inclusive. */
    private static IntPredicate run(int from, int to) {
        return y -> y >= from && y <= to;
    }

    @Test
    @DisplayName("a single mast is its own base and top; it radiates from the block above")
    void singleBlock() {
        ColumnScan.Bounds column = ColumnScan.bounds(70, masts(70), CAP);
        assertEquals(new ColumnScan.Bounds(70, 70, 70), column);
        assertEquals(71, column.radiatingY());
        assertEquals(1, column.height());
        assertEquals(1, column.signalHeight());
        assertFalse(column.capped());
        assertTrue(ColumnScan.isBase(70, masts(70)));
    }

    @Test
    @DisplayName("a 9-stack is one column seen from every mast in it: base 64, top 72, radiating at 73")
    void nineStack() {
        IntPredicate nine = run(64, 72);
        for (int y = 64; y <= 72; y++) {
            ColumnScan.Bounds column = ColumnScan.bounds(y, nine, CAP);
            assertEquals(new ColumnScan.Bounds(64, 72, 72), column, "seen from y=" + y);
            assertEquals(73, column.radiatingY());
            assertEquals(9, column.height());
            assertEquals(y == 64, ColumnScan.isBase(y, nine), "only the lowest mast is the base, y=" + y);
        }
    }

    @Test
    @DisplayName("extending a column upward keeps its base (so the cell keeps its id) and lifts the radiating point")
    void extendingKeepsTheBase() {
        ColumnScan.Bounds before = ColumnScan.bounds(64, run(64, 72), CAP);
        ColumnScan.Bounds after = ColumnScan.bounds(64, run(64, 73), CAP);
        assertEquals(before.baseY(), after.baseY());
        assertEquals(before.radiatingY() + 1, after.radiatingY());
    }

    @Test
    @DisplayName("a gap splits the column: the masts above the gap are a column with their own base")
    void gapInTheColumn() {
        // 60..63, gap at 64, 65..68.
        IntPredicate split = masts(60, 61, 62, 63, 65, 66, 67, 68);
        ColumnScan.Bounds lower = ColumnScan.bounds(62, split, CAP);
        ColumnScan.Bounds upper = ColumnScan.bounds(67, split, CAP);
        assertEquals(new ColumnScan.Bounds(60, 63, 63), lower);
        assertEquals(new ColumnScan.Bounds(65, 68, 68), upper);
        assertEquals(64, lower.radiatingY(), "the lower column radiates into the gap");
        assertTrue(ColumnScan.isBase(60, split));
        assertTrue(ColumnScan.isBase(65, split), "the first mast above the gap is a base");
        assertFalse(ColumnScan.isBase(66, split));
    }

    @Test
    @DisplayName("breaking the base promotes the next mast up: same top, new base")
    void breakingTheBasePromotesTheNextMast() {
        ColumnScan.Bounds column = ColumnScan.bounds(70, run(65, 72), CAP);
        assertEquals(65, column.baseY());
        ColumnScan.Bounds afterBreak = ColumnScan.bounds(70, run(66, 72), CAP);
        assertEquals(66, afterBreak.baseY());
        assertEquals(column.radiatingY(), afterBreak.radiatingY());
    }

    @Test
    @DisplayName("an antenna on top is not part of the column and makes it a mounting pole; one beside or inside it does not")
    void antennaOnTop() {
        IntPredicate column = run(64, 72);
        IntPredicate sectorAt73 = y -> y == 73;
        ColumnScan.Bounds bounds = ColumnScan.bounds(64, column, CAP);
        assertEquals(new ColumnScan.Bounds(64, 72, 72), bounds, "the antenna does not extend the column");
        assertTrue(ColumnScan.mountingPole(bounds, sectorAt73));

        assertFalse(ColumnScan.mountingPole(bounds, y -> y == 74), "one block higher is not on top");
        assertFalse(ColumnScan.mountingPole(bounds, y -> false), "nothing on top");
        // A single mast under a sector is a pole too.
        assertTrue(ColumnScan.mountingPole(ColumnScan.bounds(5, masts(5), CAP), y -> y == 6));
    }

    @Test
    @DisplayName("height cap: the signal part is the lowest maxHeight masts; the rest is structure and the pole test looks above all of it")
    void heightCap() {
        IntPredicate seventy = run(0, 69);
        ColumnScan.Bounds column = ColumnScan.bounds(35, seventy, 64);
        assertEquals(new ColumnScan.Bounds(0, 63, 69), column);
        assertEquals(64, column.radiatingY(), "radiates from above the 64th mast");
        assertEquals(70, column.height());
        assertEquals(64, column.signalHeight());
        assertTrue(column.capped());

        // An antenna above the capped top but inside the structure is not "on top"; above the highest mast it is.
        assertFalse(ColumnScan.mountingPole(column, y -> y == 64));
        assertTrue(ColumnScan.mountingPole(column, y -> y == 70));

        // Exactly at the cap: not capped.
        ColumnScan.Bounds exact = ColumnScan.bounds(10, run(0, 63), 64);
        assertEquals(new ColumnScan.Bounds(0, 63, 63), exact);
        assertFalse(exact.capped());

        // Cap 1: every column radiates from just above its base.
        assertEquals(new ColumnScan.Bounds(0, 0, 69), ColumnScan.bounds(50, seventy, 1));
        // A nonsense cap counts as 1 rather than putting the radiating point inside the base.
        assertEquals(new ColumnScan.Bounds(0, 0, 69), ColumnScan.bounds(50, seventy, 0));
        assertEquals(new ColumnScan.Bounds(0, 0, 69), ColumnScan.bounds(50, seventy, -5));
        // A huge cap does not overflow.
        assertEquals(new ColumnScan.Bounds(0, 69, 69), ColumnScan.bounds(50, seventy, Integer.MAX_VALUE));
    }

    @Test
    @DisplayName("the lens uses the server's radiating height under a different cap, and its own scan when unknown or stale")
    void reportedRadiatingHeight() {
        // 70 masts from y 0. The client's cap is 32; the server registered the cell with 64.
        ColumnScan.Bounds clientView = ColumnScan.bounds(0, run(0, 69), 32);
        assertEquals(32, clientView.radiatingY(), "the client's own cap would put the lobe above the 32nd mast");
        assertEquals(64, ColumnScan.reportedRadiatingY(clientView, 64), "the server's height wins");
        // The reverse: client cap 64, server cap 32.
        ColumnScan.Bounds wideClient = ColumnScan.bounds(0, run(0, 69), 64);
        assertEquals(32, ColumnScan.reportedRadiatingY(wideClient, 32));
        // Any cap gives a height in (base, highest + 1]: both ends are accepted.
        assertEquals(1, ColumnScan.reportedRadiatingY(clientView, 1), "cap 1: just above the base");
        assertEquals(70, ColumnScan.reportedRadiatingY(clientView, 70), "no cap reached: above the highest mast");

        // Not told yet: the client's own scan.
        assertEquals(32, ColumnScan.reportedRadiatingY(clientView, ColumnScan.UNKNOWN_Y));
        // A report that cannot belong to this column (the column changed and its new report has not
        // arrived): the local scan.
        assertEquals(32, ColumnScan.reportedRadiatingY(clientView, 0), "at the base: not this column");
        assertEquals(32, ColumnScan.reportedRadiatingY(clientView, -3), "below the base");
        assertEquals(32, ColumnScan.reportedRadiatingY(clientView, 71), "above the highest mast + 1");
        // A column that was cut down to three masts while the old report (73) is still in hand.
        ColumnScan.Bounds cut = ColumnScan.bounds(64, run(64, 66), CAP);
        assertEquals(67, ColumnScan.reportedRadiatingY(cut, 73));
        // Extreme heights do not overflow.
        ColumnScan.Bounds top = new ColumnScan.Bounds(0, 10, Integer.MAX_VALUE);
        assertEquals(Integer.MAX_VALUE, ColumnScan.reportedRadiatingY(top, Integer.MAX_VALUE));
    }

    @Test
    @DisplayName("negative heights work (1.18+ worlds reach y = -64)")
    void negativeHeights() {
        ColumnScan.Bounds column = ColumnScan.bounds(-60, run(-64, -56), CAP);
        assertEquals(new ColumnScan.Bounds(-64, -56, -56), column);
        assertEquals(-55, column.radiatingY());
    }

    @Test
    @DisplayName("power gate: the column is powered if any mast in it, structure above the cap included, has a signal")
    void anyMastPowersTheColumn() {
        ColumnScan.Bounds column = ColumnScan.bounds(0, run(0, 69), 64);
        assertFalse(ColumnScan.any(column, y -> false));
        assertTrue(ColumnScan.any(column, y -> y == 0), "the base");
        assertTrue(ColumnScan.any(column, y -> y == 35), "a structure mast in the middle");
        assertTrue(ColumnScan.any(column, y -> y == 69), "a structure mast above the cap");
        assertFalse(ColumnScan.any(column, y -> y == 70 || y == -1), "the blocks above and below are not the column");
    }

    @Test
    @DisplayName("no mast at y is a caller error, not a one-block column")
    void noMastIsAnError() {
        assertThrows(IllegalArgumentException.class, () -> ColumnScan.bounds(5, masts(4, 6), CAP));
        assertFalse(ColumnScan.isBase(5, masts(4, 6)));
    }

    @Test
    @DisplayName("a predicate that never says no cannot hang the scan")
    void walkIsBounded() {
        AtomicInteger calls = new AtomicInteger();
        ColumnScan.Bounds column = ColumnScan.bounds(0, y -> {
            calls.incrementAndGet();
            return true;
        }, CAP);
        assertEquals(-ColumnScan.MAX_WALK, column.baseY());
        assertEquals(0, column.highestY());
        assertEquals(column.baseY() + CAP - 1, column.topY());
        assertTrue(calls.get() <= 2 * ColumnScan.MAX_WALK + 3, "calls: " + calls.get());
    }

    @Test
    @DisplayName("cost: a column is read once down and once up, about one lookup per mast plus the two ends")
    void lookupCount() {
        AtomicInteger calls = new AtomicInteger();
        IntPredicate nine = run(64, 72);
        ColumnScan.bounds(68, y -> {
            calls.incrementAndGet();
            return nine.test(y);
        }, CAP);
        // 1 (y itself) + 5 down (67..63) + 9 up (65..73).
        assertEquals(15, calls.get());

        calls.set(0);
        ColumnScan.isBase(68, y -> {
            calls.incrementAndGet();
            return nine.test(y);
        });
        assertEquals(2, calls.get(), "isBase is two lookups whatever the column's height");
    }
}
