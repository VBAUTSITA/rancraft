package dev.rancraft.rf;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
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

    // ---- Phase 3 slice 4: a candidate armed before an evaluation pause ----------------------

    /** The player ticker's cadence at the default config, and its stale-candidate threshold. */
    private static final long INTERVAL = 20L;

    /**
     * One evaluation the way {@code SignalTicker} runs it while a candidate is armed (the cache is
     * skipped then): resume the stored state, select, store the result with its tick.
     */
    private static Selection evaluateAt(
            ReceiverStateStore<String> store, List<CellSample> cells, long tick) {
        ReceiverState previous = store.resume("player", tick, INTERVAL);
        Selection selection = CellSelector.select(previous, cells, tick, DEFAULTS);
        store.put("player", selection.state(), tick);
        return selection;
    }

    private static ReceiverStateStore<String> storeCampedOnA() {
        ReceiverStateStore<String> store = new ReceiverStateStore<>();
        store.put("player", campedOnA(), 0L);
        return store;
    }

    @Test
    @DisplayName("S4. Evaluated every interval, the handover still fires at exactly TTT: the fix delays nothing")
    void continuousEvaluationKeepsTheCandidate() {
        ReceiverStateStore<String> store = storeCampedOnA();
        List<CellSample> cells = ranked(TestCells.sample(CELL_A, -80.0), TestCells.sample(CELL_B, -75.0));

        Selection armed = evaluateAt(store, cells, 20L);
        assertEquals(20L, armed.state().candidateSinceTick());
        Selection held = evaluateAt(store, cells, 40L);
        assertFalse(held.handedOver());
        assertEquals(20L, held.state().candidateSinceTick(), "a gap of one interval keeps the clock running");

        Selection fires = evaluateAt(store, cells, 60L);
        assertTrue(fires.handedOver(), "20 + 40 = 60, as before the fix");
        assertEquals(CELL_B, fires.servingCellId());
    }

    @Test
    @DisplayName("S4. A candidate armed before a pause is re-armed when evaluation resumes, so TTT is observed again")
    void pauseReArmsTheCandidate() {
        ReceiverStateStore<String> store = storeCampedOnA();
        List<CellSample> cells = ranked(TestCells.sample(CELL_A, -80.0), TestCells.sample(CELL_B, -75.0));

        evaluateAt(store, cells, 20L);
        assertTrue(store.get("player").hasCandidate(), "fixture: armed at 20");
        // The player puts the meter away at tick 30: nothing evaluates them until tick 200.

        // The bug this pins: the frozen state alone would hand over on the first evaluation back.
        Selection frozen = CellSelector.select(store.get("player"), cells, 200L, DEFAULTS);
        assertTrue(frozen.handedOver(), "fixture: without the fix the stale 180-tick timer fires at once");

        Selection resumed = evaluateAt(store, cells, 200L);
        assertFalse(resumed.handedOver(), "the gap was not observed, so it cannot count towards TTT");
        assertEquals(CELL_A, resumed.servingCellId());
        assertEquals(CELL_B, resumed.state().candidateCellId(), "still qualifies, so it is re-armed");
        assertEquals(200L, resumed.state().candidateSinceTick(), "the clock restarts where evaluation resumed");

        assertFalse(evaluateAt(store, cells, 220L).handedOver());
        Selection fires = evaluateAt(store, cells, 240L);
        assertTrue(fires.handedOver(), "200 + 40 = 240: one full TTT after resuming");
        assertEquals(1, fires.state().handoverCount());
    }

    @Test
    @DisplayName("S4. A pause after which the neighbour no longer qualifies leaves no candidate and no handover")
    void pauseWithNeighbourGoneClearsTheCandidate() {
        ReceiverStateStore<String> store = storeCampedOnA();
        evaluateAt(store, ranked(TestCells.sample(CELL_A, -80.0), TestCells.sample(CELL_B, -75.0)), 20L);

        Selection resumed = evaluateAt(store,
                ranked(TestCells.sample(CELL_A, -80.0), TestCells.sample(CELL_B, -79.0)), 300L);
        assertFalse(resumed.handedOver());
        assertFalse(resumed.state().hasCandidate());
        assertEquals(0, resumed.state().handoverCount());
    }

    @Test
    @DisplayName("S4. The threshold: a gap of one interval keeps the candidate, one tick more drops it; serving cell and tally survive")
    void staleThresholdBoundary() {
        ReceiverState armed = new ReceiverState(CELL_A, 5L, CELL_B, 100L, 7);

        assertSame(armed, CellSelector.expireStaleCandidate(armed, 100L, 120L, INTERVAL), "gap 20 = interval");
        ReceiverState dropped = CellSelector.expireStaleCandidate(armed, 100L, 121L, INTERVAL);
        assertFalse(dropped.hasCandidate(), "gap 21 > interval: at least one scheduled evaluation was missed");
        assertEquals(CELL_A, dropped.servingCellId());
        assertEquals(5L, dropped.servingSinceTick());
        assertEquals(7, dropped.handoverCount(), "a pause is not a handover and not an outage");

        assertFalse(CellSelector.expireStaleCandidate(armed, 100L, 99L, INTERVAL).hasCandidate(),
                "a clock that ran backwards cannot vouch for the timer");
        assertSame(armed, CellSelector.expireStaleCandidate(armed, CellSelector.NEVER_EVALUATED, 10_000L, INTERVAL),
                "no recorded evaluation: nothing to measure the gap from, so the state is kept");

        ReceiverState unarmed = armed.withoutCandidate();
        assertSame(unarmed, CellSelector.expireStaleCandidate(unarmed, 100L, 10_000L, INTERVAL));
    }

    @Test
    @DisplayName("S4. The store records when each state was produced and forgets it with the state")
    void storeTracksTheEvaluationTick() {
        ReceiverStateStore<String> store = new ReceiverStateStore<>();
        assertEquals(CellSelector.NEVER_EVALUATED, store.lastEvaluatedTick("player"));

        store.put("player", ReceiverState.NONE.withCandidate(CELL_B, 90L), 100L);
        assertEquals(100L, store.lastEvaluatedTick("player"));
        assertTrue(store.resume("player", 120L, INTERVAL).hasCandidate());
        assertFalse(store.resume("player", 121L, INTERVAL).hasCandidate());
        assertTrue(store.get("player").hasCandidate(), "resume only reads; the evaluation stores its result");

        store.put("player", ReceiverState.NONE.withCandidate(CELL_B, 90L));
        assertEquals(CellSelector.NEVER_EVALUATED, store.lastEvaluatedTick("player"),
                "a put without a tick must not keep an older evaluation's tick");

        store.select("player", ranked(TestCells.sample(CELL_A, -80.0)), 500L, DEFAULTS);
        assertEquals(500L, store.lastEvaluatedTick("player"), "select records the tick it ran at");

        store.clear("player");
        assertEquals(CellSelector.NEVER_EVALUATED, store.lastEvaluatedTick("player"));
        store.put("other", ReceiverState.NONE, 1L);
        store.clearAll();
        assertEquals(CellSelector.NEVER_EVALUATED, store.lastEvaluatedTick("other"));
        assertEquals(0, store.size());
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
