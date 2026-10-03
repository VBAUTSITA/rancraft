package dev.rancraft.util;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Phase 3 slice 15 (§3C.5): the served-receivers seam counts distinct receivers per cell in a window. */
class ServedReceiversTest {

    private static final long CELL_A = 11L;
    private static final long CELL_B = 22L;
    private static final long WINDOW = 6000L;

    @Test
    @DisplayName("Repeats count once; distinct receivers each count")
    void distinct() {
        ServedReceivers<String> served = new ServedReceivers<>();
        for (long tick = 0; tick < 100; tick += 20) {
            served.served(CELL_A, "alice", tick);
        }
        served.served(CELL_A, "bob", 50);
        assertEquals(2, served.count(CELL_A, 100, WINDOW));
        assertEquals(0, served.count(CELL_B, 100, WINDOW));
    }

    @Test
    @DisplayName("Receivers last served before the window no longer count")
    void window() {
        ServedReceivers<Long> served = new ServedReceivers<>();
        served.served(CELL_A, 1L, 0);
        served.served(CELL_A, 2L, 5000);
        assertEquals(2, served.count(CELL_A, 5999, WINDOW));
        assertEquals(1, served.count(CELL_A, 6000, WINDOW), "served at 0 is out at 6000 with a 6000-tick window");
        assertEquals(0, served.count(CELL_A, 11_000, WINDOW));
    }

    @Test
    @DisplayName("A receiver that crossed a boundary counts for both cells in the window")
    void bothCells() {
        ServedReceivers<String> served = new ServedReceivers<>();
        served.served(CELL_A, "alice", 100);
        served.served(CELL_B, "alice", 200);
        assertEquals(1, served.count(CELL_A, 300, WINDOW));
        assertEquals(1, served.count(CELL_B, 300, WINDOW));
    }

    @Test
    @DisplayName("An out-of-order record keeps the latest tick")
    void keepsLatest() {
        ServedReceivers<String> served = new ServedReceivers<>();
        served.served(CELL_A, "alice", 7000);
        served.served(CELL_A, "alice", 10);
        assertEquals(1, served.count(CELL_A, 7000, WINDOW));
    }

    @Test
    @DisplayName("A receiver reports again when its cell changes or its report is an interval old")
    void due() {
        assertEquals(true, ServedReceivers.due(CELL_A, 0, CELL_B, 1, 400), "another cell");
        assertEquals(false, ServedReceivers.due(CELL_A, 0, CELL_A, 399, 400), "same cell, 399 ticks");
        assertEquals(true, ServedReceivers.due(CELL_A, 0, CELL_A, 400, 400), "same cell, 400 ticks");
        assertEquals(true, ServedReceivers.due(CELL_A, Long.MIN_VALUE / 2, CELL_A, 0, 400), "never reported");
    }

    @Test
    @DisplayName("Throttled reports keep a continuously served receiver counted in any window above the interval")
    void throttledReportsKeepCounting() {
        ServedReceivers<String> served = new ServedReceivers<>();
        long lastCell = -1;
        long lastTick = Long.MIN_VALUE / 2;
        for (long tick = 0; tick < 10_000; tick += 20) {
            if (ServedReceivers.due(lastCell, lastTick, CELL_A, tick, 400)) {
                served.served(CELL_A, "alice", tick);
                lastCell = CELL_A;
                lastTick = tick;
            }
            assertEquals(1, served.count(CELL_A, tick, 1200), "still served at " + tick);
        }
    }

    @Test
    @DisplayName("Prune forgets old records and empty cells")
    void prune() {
        ServedReceivers<String> served = new ServedReceivers<>();
        served.served(CELL_A, "alice", 0);
        served.served(CELL_B, "bob", 0);
        served.served(CELL_B, "carol", 5000);
        assertEquals(3, served.records());
        served.prune(6000, WINDOW);
        assertEquals(1, served.cells());
        assertEquals(1, served.records());
        assertEquals(1, served.count(CELL_B, 6000, WINDOW));
        served.clear();
        assertEquals(0, served.cells());
    }
}
