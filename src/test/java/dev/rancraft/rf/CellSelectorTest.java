package dev.rancraft.rf;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.rancraft.rf.CellSelector.Selection;
import dev.rancraft.rf.CellSelector.SelectionParams;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Phase 2 tests 11-14: cell selection and handover hysteresis. */
class CellSelectorTest {

    private static final SelectionParams DEFAULTS = SelectionParams.DEFAULTS;
    private static final long CELL_A = 100L;
    private static final long CELL_B = 200L;

    /** Strongest first, as {@link RfEngine#evaluate} guarantees. */
    private static List<CellSample> ranked(CellSample... cells) {
        return List.of(cells).stream()
                .sorted((a, b) -> Double.compare(b.rsrpDbm(), a.rsrpDbm()))
                .toList();
    }

    /** Camps on cell A so the hysteresis tests start from a settled state. */
    private static ReceiverState campedOnA() {
        Selection first = CellSelector.select(
                ReceiverState.NONE,
                ranked(TestCells.sample(CELL_A, -80.0), TestCells.sample(CELL_B, -90.0)),
                0L,
                DEFAULTS);
        assertEquals(CELL_A, first.servingCellId());
        return first.state();
    }

    // ---- Test 11 ---------------------------------------------------------------------------

    @Test
    @DisplayName("11. A neighbour only 2 dB stronger never hands over, however long it holds")
    void belowHysteresisNeverTriggers() {
        ReceiverState state = campedOnA();
        List<CellSample> cells = ranked(TestCells.sample(CELL_A, -80.0), TestCells.sample(CELL_B, -78.0));

        for (long tick = 1; tick <= 200; tick++) {
            Selection selection = CellSelector.select(state, cells, tick, DEFAULTS);
            state = selection.state();
            assertFalse(selection.handedOver(), "handover at tick " + tick);
            assertEquals(CELL_A, selection.servingCellId());
        }

        assertEquals(0, state.handoverCount());
        assertFalse(state.hasCandidate(), "2 dB never qualifies, so no candidate is ever armed");
    }

    // ---- Test 12 ---------------------------------------------------------------------------

    @Test
    @DisplayName("12. A neighbour 5 dB stronger hands over at 40 ticks, not at 39")
    void timeToTriggerIsExact() {
        ReceiverState state = campedOnA();
        List<CellSample> cells = ranked(TestCells.sample(CELL_A, -80.0), TestCells.sample(CELL_B, -75.0));

        // Tick 1 arms the candidate; the clock starts here.
        Selection armed = CellSelector.select(state, cells, 1L, DEFAULTS);
        state = armed.state();
        assertFalse(armed.handedOver());
        assertTrue(state.hasCandidate());
        assertEquals(CELL_B, state.candidateCellId());
        assertEquals(1L, state.candidateSinceTick());

        for (long tick = 2; tick <= 39; tick++) {
            Selection selection = CellSelector.select(state, cells, tick, DEFAULTS);
            state = selection.state();
            assertFalse(selection.handedOver(), "premature handover at tick " + tick);
            assertEquals(CELL_A, selection.servingCellId());
        }

        // 39 ticks held: still not enough.
        Selection justShort = CellSelector.select(state, cells, 1L + 39L, DEFAULTS);
        assertFalse(justShort.handedOver());
        assertEquals(CELL_A, justShort.servingCellId());

        // 40 ticks held: fires.
        Selection fires = CellSelector.select(justShort.state(), cells, 1L + 40L, DEFAULTS);
        assertTrue(fires.handedOver());
        assertEquals(CELL_B, fires.servingCellId());
        assertEquals(1, fires.state().handoverCount());
        assertFalse(fires.state().hasCandidate(), "the candidate is consumed by the handover");
    }

    // ---- Test 13 ---------------------------------------------------------------------------

    @Test
    @DisplayName("13. Losing the serving cell reselects immediately, ignoring hysteresis and TTT")
    void outageReselectsImmediately() {
        ReceiverState state = campedOnA();

        // RfEngine drops sub-floor cells, so a faded or broken serving cell is simply absent.
        List<CellSample> onlyB = ranked(TestCells.sample(CELL_B, -95.0));
        Selection selection = CellSelector.select(state, onlyB, 1L, DEFAULTS);

        assertEquals(CELL_B, selection.servingCellId());
        assertFalse(selection.handedOver(), "recovery from outage is not a handover");
        assertEquals(0, selection.state().handoverCount(),
                "an outage must not inflate the ping-pong counter the player is reading");
    }

    @Test
    @DisplayName("13b. A total outage clears the serving cell and any armed candidate")
    void totalOutageClearsState() {
        ReceiverState state = campedOnA();
        Selection selection = CellSelector.select(state, List.of(), 1L, DEFAULTS);

        assertNull(selection.serving());
        assertFalse(selection.state().hasServing());
        assertFalse(selection.state().hasCandidate());

        // The next cell to appear is taken at once, with no leftover timer holding it off.
        Selection recovered = CellSelector.select(
                selection.state(), ranked(TestCells.sample(CELL_B, -100.0)), 2L, DEFAULTS);
        assertEquals(CELL_B, recovered.servingCellId());
    }

    // ---- Test 14 ---------------------------------------------------------------------------

    @Test
    @DisplayName("14. A candidate that stops qualifying resets the timer rather than accumulating")
    void candidateTimerDoesNotAccumulate() {
        ReceiverState state = campedOnA();
        List<CellSample> qualifying = ranked(TestCells.sample(CELL_A, -80.0), TestCells.sample(CELL_B, -75.0));
        List<CellSample> notQualifying = ranked(TestCells.sample(CELL_A, -80.0), TestCells.sample(CELL_B, -79.0));

        // 30 ticks of qualifying: most of the way there.
        state = CellSelector.select(state, qualifying, 1L, DEFAULTS).state();
        state = CellSelector.select(state, qualifying, 30L, DEFAULTS).state();
        assertTrue(state.hasCandidate());
        assertEquals(1L, state.candidateSinceTick());

        // The neighbour fades back under the hysteresis: the candidate is dropped outright.
        state = CellSelector.select(state, notQualifying, 31L, DEFAULTS).state();
        assertFalse(state.hasCandidate(), "a lapsed candidate must be cleared, not paused");

        // It qualifies again at tick 32. If the timer had accumulated, 30 + 9 would fire at 41.
        state = CellSelector.select(state, qualifying, 32L, DEFAULTS).state();
        assertEquals(32L, state.candidateSinceTick(), "the clock restarts from scratch");

        Selection atOldDeadline = CellSelector.select(state, qualifying, 41L, DEFAULTS);
        assertFalse(atOldDeadline.handedOver(), "accumulated time must not carry over");

        Selection atNewDeadline = CellSelector.select(atOldDeadline.state(), qualifying, 72L, DEFAULTS);
        assertTrue(atNewDeadline.handedOver(), "32 + 40 = 72 is the real deadline");
    }

    @Test
    @DisplayName("14b. Switching to a different qualifying neighbour restarts the timer")
    void switchingCandidateRestartsTimer() {
        long cellC = 300L;
        ReceiverState state = campedOnA();

        state = CellSelector.select(state,
                ranked(TestCells.sample(CELL_A, -80.0), TestCells.sample(CELL_B, -75.0)), 1L, DEFAULTS).state();
        assertEquals(CELL_B, state.candidateCellId());

        // C is now the best neighbour instead of B.
        state = CellSelector.select(state,
                ranked(TestCells.sample(CELL_A, -80.0), TestCells.sample(CELL_B, -76.0),
                        TestCells.sample(cellC, -70.0)), 20L, DEFAULTS).state();
        assertEquals(cellC, state.candidateCellId());
        assertEquals(20L, state.candidateSinceTick());
    }

    // ---- Store -----------------------------------------------------------------------------

    @Test
    @DisplayName("The store keys receivers generically and forgets them on demand")
    void storeLifecycle() {
        ReceiverStateStore<String> store = new ReceiverStateStore<>();
        List<CellSample> cells = ranked(TestCells.sample(CELL_A, -80.0));

        assertEquals(ReceiverState.NONE, store.get("player-1"));

        Selection selection = store.select("player-1", cells, 0L, DEFAULTS);
        assertEquals(CELL_A, selection.servingCellId());
        assertEquals(1, store.size());
        assertEquals(CELL_A, store.get("player-1").servingCellId());

        store.clear("player-1");
        assertEquals(0, store.size(), "logout must not leave an entry behind");
        assertEquals(ReceiverState.NONE, store.get("player-1"));

        store.clear("never-seen");
        assertEquals(0, store.size());
    }
}
