package dev.rancraft.rf;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.rancraft.rf.DriveTestLog.Entry;
import dev.rancraft.rf.DriveTestLog.Event;
import dev.rancraft.rf.DriveTestLog.Sample;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** RF Vision Step 3a: the drive-test log. */
class DriveTestLogTest {

    private static final long CELL_A = 100L;
    private static final long CELL_B = 200L;
    private static final long NONE = ReceiverState.NO_CELL;

    /** A sample at (x, 64, 0), strong and clean on the given cell. */
    private static Sample at(long tick, double x, long cell, int handovers) {
        return at(tick, x, cell, handovers, ServiceLevel.EXCELLENT);
    }

    private static Sample at(long tick, double x, long cell, int handovers, ServiceLevel level) {
        boolean serving = cell != NONE;
        return new Sample(tick, x, 64.0, 0.0,
                cell, serving ? 7 : 0, serving ? "band_900" : "",
                serving ? -80.0 : Double.NaN, serving ? 25.0 : Double.NEGATIVE_INFINITY,
                serving ? level : ServiceLevel.NONE, handovers, serving ? 2 : 0);
    }

    // ---- event classification -------------------------------------------------------------

    @Test
    @DisplayName("The first sample marks no event: there is nothing to compare it against")
    void firstSampleIsNone() {
        DriveTestLog log = new DriveTestLog(10);
        assertEquals(Event.NONE, log.record(at(0, 0, CELL_A, 0)).event());
    }

    @Test
    @DisplayName("A handover is read off the server's counter, not guessed from a cell change")
    void handoverFromCounter() {
        DriveTestLog log = new DriveTestLog(10);
        log.record(at(0, 0, CELL_A, 0));
        assertEquals(Event.HANDOVER, log.record(at(20, 5, CELL_B, 1)).event());
    }

    @Test
    @DisplayName("A cell change without the counter moving is a reselection, not a handover")
    void cellChangeWithoutCounterIsReselection() {
        DriveTestLog log = new DriveTestLog(10);
        log.record(at(0, 0, CELL_A, 3));
        assertEquals(Event.RESELECTION, log.record(at(20, 5, CELL_B, 3)).event());
    }

    @Test
    @DisplayName("Losing the serving cell is an outage; getting one back is a reselection")
    void outageAndRecovery() {
        DriveTestLog log = new DriveTestLog(10);
        log.record(at(0, 0, CELL_A, 0));
        assertEquals(Event.OUTAGE, log.record(at(20, 5, NONE, 0)).event());
        assertEquals(Event.NONE, log.record(at(40, 10, NONE, 0)).event(),
                "staying in an outage is not a second outage");
        assertEquals(Event.RESELECTION, log.record(at(60, 15, CELL_A, 0)).event(),
                "recovery from outage is a reselection, exactly as CellSelector treats it");
    }

    @Test
    @DisplayName("A counter that goes down was reset by a respawn or relog and is not a handover")
    void counterResetIsNotAHandover() {
        DriveTestLog log = new DriveTestLog(10);
        log.record(at(0, 0, CELL_A, 5));
        assertEquals(Event.NONE, log.record(at(20, 5, CELL_A, 0)).event());

        DriveTestLog other = new DriveTestLog(10);
        other.record(at(0, 0, CELL_A, 5));
        assertEquals(Event.RESELECTION, other.record(at(20, 5, CELL_B, 0)).event(),
                "reset plus a new cell is a reselection");
    }

    @Test
    @DisplayName("A handover to the same cell id still counts: the counter is authoritative")
    void counterWinsEvenWithoutCellChange() {
        // Cannot happen while the log sees every evaluation (a handover always changes the cell),
        // but if the server ever says it handed over, believe it.
        DriveTestLog log = new DriveTestLog(10);
        log.record(at(0, 0, CELL_A, 0));
        assertEquals(Event.HANDOVER, log.record(at(20, 5, CELL_A, 1)).event());
    }

    @Test
    @DisplayName("Handovers the log never saw land as one HANDOVER on the next sample, so the server sends every evaluation")
    void unseenHandoversLandOnTheNextSample() {
        // A walk A -> B -> A with handovers at x = 100 and x = 200, and the wearer switching views
        // at x = 300. Before the slice 0 gate fix a LINKS-only lens was evaluated but not sent the
        // sample, so the log got only the two ends of the walk.
        DriveTestLog gap = new DriveTestLog(10);
        gap.record(at(0, 0, CELL_A, 0));
        Entry resumed = gap.record(at(6_000, 300, CELL_A, 2));
        assertEquals(Event.HANDOVER, resumed.event(), "a false pillar at the switch point");
        assertEquals(1, gap.events(Event.HANDOVER).size(), "and neither real handover is marked");

        // Every evaluation sent: each handover is marked where it fired, and nothing at the switch.
        DriveTestLog full = new DriveTestLog(10);
        full.record(at(0, 0, CELL_A, 0));
        full.record(at(2_000, 100, CELL_B, 1));
        full.record(at(4_000, 200, CELL_A, 2));
        assertEquals(Event.NONE, full.record(at(6_000, 300, CELL_A, 2)).event());
        List<Entry> handovers = full.events(Event.HANDOVER);
        assertEquals(2, handovers.size());
        assertEquals(100.0, handovers.get(0).sample().x());
        assertEquals(200.0, handovers.get(1).sample().x());
    }

    // ---- stationary de-duplication ----------------------------------------------------------

    @Test
    @DisplayName("Standing still on the same cell and level replaces instead of stacking markers")
    void stationaryReplaces() {
        DriveTestLog log = new DriveTestLog(10);
        log.record(at(0, 0.0, CELL_A, 0));
        log.record(at(20, 0.1, CELL_A, 0));
        log.record(at(40, 0.2, CELL_A, 0));

        assertEquals(1, log.size());
        assertEquals(40L, log.entries().get(0).sample().tick(), "the newest sample is the one kept");
    }

    @Test
    @DisplayName("Moving at least half a block appends")
    void movingAppends() {
        DriveTestLog log = new DriveTestLog(10);
        log.record(at(0, 0.0, CELL_A, 0));
        log.record(at(20, 0.5, CELL_A, 0));
        assertEquals(2, log.size());
    }

    /**
     * Phase 3A review, round 1: "stationary" is measured from where the still period began, not from
     * the previous (already replaced) sample. Walking at 4.317 blocks/s with a 2-tick interval moves
     * 0.43 blocks per sample; measured sample to sample, every one replaced the last and a 100-block
     * walk collapsed into one entry. From the anchor, every second sample is 0.8 blocks on and
     * appends: 126 entries.
     */
    @Test
    @DisplayName("A slow walk (0.4 blocks per sample) still leaves a trail: distance is measured from where the still period began")
    void slowWalkStillAppends() {
        DriveTestLog log = new DriveTestLog(3_600);
        for (int i = 0; i <= 250; i++) {
            log.record(at(2L * i, 0.4 * i, CELL_A, 0));
        }
        assertTrue(log.size() >= 80, "entries after a 100-block walk: " + log.size());
        assertEquals(126, log.size(), "every second sample is at least half a block from the anchor");
        assertEquals(0.4, log.entries().get(0).sample().x(), 1e-9, "the first still period keeps its newest sample");
        assertEquals(100.0, log.entries().get(log.size() - 1).sample().x(), 1e-9);
    }

    @Test
    @DisplayName("Sixty samples at one point (rising ticks) are still one entry, holding the newest")
    void longStandStillIsOneEntry() {
        DriveTestLog log = new DriveTestLog(3_600);
        for (int i = 0; i < 60; i++) {
            log.record(at(20L * i, 3.0, CELL_A, 0));
        }
        assertEquals(1, log.size());
        assertEquals(20L * 59, log.entries().get(0).sample().tick());

        // After a clear the next sample starts a new still period on its own.
        log.clear();
        log.record(at(2_000, 50.0, CELL_A, 0));
        log.record(at(2_020, 50.3, CELL_A, 0));
        assertEquals(1, log.size());
        log.record(at(2_040, 50.6, CELL_A, 0));
        assertEquals(2, log.size(), "0.6 from where this still period began");
    }

    @Test
    @DisplayName("Standing still but changing service level appends, so the change is not lost")
    void levelChangeAppends() {
        DriveTestLog log = new DriveTestLog(10);
        log.record(at(0, 0, CELL_A, 0, ServiceLevel.EXCELLENT));
        log.record(at(20, 0, CELL_A, 0, ServiceLevel.POOR));
        assertEquals(2, log.size());
    }

    @Test
    @DisplayName("A stationary repeat never swallows an event marker")
    void stationaryNeverSwallowsAnEvent() {
        DriveTestLog log = new DriveTestLog(10);
        log.record(at(0, 0, CELL_A, 0));
        log.record(at(20, 5, CELL_B, 1));      // handover, at x=5
        log.record(at(40, 5, CELL_B, 1));      // standing on the handover spot

        assertEquals(3, log.size(), "the handover entry must survive the stationary sample after it");
        assertEquals(1, log.events(Event.HANDOVER).size());
        assertEquals(20L, log.events(Event.HANDOVER).get(0).sample().tick());

        log.record(at(60, 5, CELL_B, 1));      // and further stationary samples do collapse
        assertEquals(3, log.size());
    }

    @Test
    @DisplayName("An exact replay of a cached sample adds nothing, even right after a handover")
    void exactReplayIsIgnored() {
        // The server replays its cached payload, same tick and all, while the receiver stands still.
        // Right after a handover the previous entry is an event, so a replay would otherwise be
        // appended as a second row at the same tick and place.
        DriveTestLog log = new DriveTestLog(10);
        log.record(at(0, 0, CELL_A, 0));
        Entry handover = log.record(at(20, 5, CELL_B, 1));

        Entry replay = log.record(at(20, 5, CELL_B, 1));

        assertSame(handover, replay, "the replay returns the entry it repeats");
        assertEquals(2, log.size());
        assertEquals(Event.HANDOVER, log.entries().get(1).event(), "the handover marker is untouched");
    }

    @Test
    @DisplayName("A replay of a no-service sample is ignored too: NaN fields still compare equal")
    void exactReplayWithNaNIsIgnored() {
        DriveTestLog log = new DriveTestLog(10);
        log.record(at(0, 0, CELL_A, 0));
        log.record(at(20, 5, NONE, 0));        // outage: NaN RSRP, -Infinity SINR
        log.record(at(20, 5, NONE, 0));
        assertEquals(2, log.size());
        assertEquals(1, log.events(Event.OUTAGE).size());
    }

    // ---- ring buffer ---------------------------------------------------------------------

    @Test
    @DisplayName("A full log evicts the oldest sample and keeps chronological order")
    void ringBufferEvictsOldest() {
        DriveTestLog log = new DriveTestLog(3);
        for (int i = 0; i < 5; i++) {
            log.record(at(i * 20L, i * 10.0, CELL_A, 0));
        }
        List<Entry> entries = log.entries();
        assertEquals(3, entries.size());
        assertEquals(40L, entries.get(0).sample().tick());
        assertEquals(80L, entries.get(2).sample().tick());
    }

    @Test
    @DisplayName("entries() is a snapshot that later recording does not change")
    void entriesIsASnapshot() {
        DriveTestLog log = new DriveTestLog(10);
        log.record(at(0, 0, CELL_A, 0));
        List<Entry> snapshot = log.entries();
        log.record(at(20, 10, CELL_A, 0));
        assertEquals(1, snapshot.size());
        assertThrows(UnsupportedOperationException.class, () -> snapshot.add(snapshot.get(0)));
    }

    @Test
    @DisplayName("forEach() visits the same entries as entries(), oldest first")
    void forEachMatchesEntries() {
        DriveTestLog log = new DriveTestLog(3);
        for (int i = 0; i < 5; i++) {
            log.record(at(i * 20L, i * 10.0, CELL_A, 0));
        }
        List<Entry> visited = new ArrayList<>();
        log.forEach(visited::add);
        assertEquals(log.entries(), visited);
    }

    @Test
    @DisplayName("clear() empties the log, and the next sample again marks no event")
    void clearResetsComparison() {
        DriveTestLog log = new DriveTestLog(10);
        log.record(at(0, 0, CELL_A, 0));
        log.clear();
        assertTrue(log.isEmpty());
        assertEquals(Event.NONE, log.record(at(20, 5, CELL_B, 4)).event(),
                "with nothing before it, even a jump in the counter is not a handover");
    }

    @Test
    @DisplayName("A capacity below one is rejected")
    void rejectsZeroCapacity() {
        assertThrows(IllegalArgumentException.class, () -> new DriveTestLog(0));
    }

    // ---- CSV -----------------------------------------------------------------------------

    @Test
    @DisplayName("CSV has the documented header and one CRLF-terminated row per entry")
    void csvShape() {
        DriveTestLog log = new DriveTestLog(10);
        log.record(at(0, 0, CELL_A, 0));
        log.record(at(20, 10, CELL_B, 1));

        String csv = log.toCsv();
        assertTrue(csv.startsWith(DriveTestLog.CSV_HEADER + "\r\n"));
        assertTrue(csv.endsWith("\r\n"));

        String[] lines = csv.split("\r\n");
        assertEquals(3, lines.length);
        int columns = DriveTestLog.CSV_HEADER.split(",", -1).length;
        for (String line : lines) {
            assertEquals(columns, line.split(",", -1).length, "column count drifted in: " + line);
        }
        assertTrue(lines[2].endsWith(",HANDOVER"));
    }

    @Test
    @DisplayName("Numbers use a dot even on a comma-decimal locale, so no column ever splits")
    void csvIgnoresDefaultLocale() {
        Locale original = Locale.getDefault();
        try {
            Locale.setDefault(Locale.forLanguageTag("es-PE"));
            DriveTestLog log = new DriveTestLog(10);
            log.record(new Sample(7, 12.345, 64.5, -3.25, CELL_A, 11, "band_1800",
                    -82.44, 9.46, ServiceLevel.FAIR, 2, 3));

            String row = DriveTestLog.csvRow(log.entries().get(0));
            assertEquals("7,12.35,64.50,-3.25,100,11,band_1800,-82.4,9.5,FAIR,2,3,NONE", row);
        } finally {
            Locale.setDefault(original);
        }
    }

    @Test
    @DisplayName("A no-service row leaves meaningless fields empty instead of writing sentinels")
    void csvNoServiceRow() {
        DriveTestLog log = new DriveTestLog(10);
        log.record(at(0, 0, CELL_A, 0));
        Entry outage = log.record(at(20, 5, NONE, 0));

        String row = DriveTestLog.csvRow(outage);
        assertEquals("20,5.00,64.00,0.00,,,,,,NONE,0,0,OUTAGE", row);
        assertFalse(row.contains("NaN"));
        assertFalse(row.contains("Infinity"));
    }

    @Test
    @DisplayName("Band ids are quoted per RFC 4180 only when they need it")
    void csvQuoting() {
        assertEquals("band_900", DriveTestLog.quote("band_900"));
        assertEquals("\"a,b\"", DriveTestLog.quote("a,b"));
        assertEquals("\"say \"\"hi\"\"\"", DriveTestLog.quote("say \"hi\""));
        assertEquals("\"line\nbreak\"", DriveTestLog.quote("line\nbreak"));
    }

    @Test
    @DisplayName("events() filters to one event type, oldest first")
    void eventsFilter() {
        DriveTestLog log = new DriveTestLog(10);
        log.record(at(0, 0, CELL_A, 0));
        log.record(at(20, 10, CELL_B, 1));
        log.record(at(40, 20, CELL_A, 2));
        log.record(at(60, 30, NONE, 2));

        assertEquals(2, log.events(Event.HANDOVER).size());
        assertEquals(1, log.events(Event.OUTAGE).size());
        assertEquals(0, log.events(Event.RESELECTION).size());
        assertEquals(20L, log.events(Event.HANDOVER).get(0).sample().tick());
    }
}
