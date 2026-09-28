package dev.rancraft.rf;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

/**
 * A drive-test log: every measurement the server sent, where it was taken, and what happened there.
 *
 * <p>Real radio optimisation starts from a drive test rather than a prediction: walk the area, log
 * every sample with its position, then read the log for problems. This is that log. It stores values
 * exactly as the server measured them and derives only <em>events</em> by comparing consecutive
 * samples; it never computes a radio value itself, so it cannot break the rule that the server is
 * authoritative.
 *
 * <p>Pure: no Minecraft, unit-tested headless. Not thread-safe; the client feeds and reads it on its
 * main thread, which is also the render thread.
 */
public final class DriveTestLog {

    /** One hour at the default 1 Hz sample rate. */
    public static final int DEFAULT_CAPACITY = 3_600;

    /**
     * Below this movement, in blocks, a sample with the same cell and level replaces the previous one
     * rather than being appended. Without it, standing still for a minute stacks sixty identical
     * markers in one voxel.
     */
    public static final double STATIONARY_EPSILON_BLOCKS = 0.5;

    /** What changed between the previous sample and this one. */
    public enum Event {
        /** Nothing a diagnosis cares about. */
        NONE,
        /** The server's handover counter went up: an A3 handover fired between the two samples. */
        HANDOVER,
        /**
         * The serving cell changed <em>without</em> a handover. In RANCraft that is recovery from an
         * outage, or the first cell after the handover counter was reset by a respawn, a dimension
         * change or a relog. Kept separate from handovers because a real engineer would too.
         */
        RESELECTION,
        /** Service was lost: the previous sample had a serving cell and this one has none. */
        OUTAGE
    }

    /**
     * One server measurement, exactly as received.
     *
     * @param tick          the server game time of the evaluation. A cached replay carries the
     *                      tick of the evaluation it replays.
     * @param x             where the server evaluated the receiver. In RANCraft that is the player's
     *                      <em>eye</em>, so {@code y} is eye height (feet + 1.62 standing), not the
     *                      ground, as a real scanner's antenna sits above the road.
     * @param servingCellId {@link ReceiverState#NO_CELL} when there is no serving cell.
     * @param pci           meaningless when there is no serving cell.
     * @param sinrDb        raw, unclamped; {@code -Infinity} or NaN when there is no service.
     * @param cellCount     how many cells the server heard at this point.
     */
    public record Sample(
            long tick,
            double x, double y, double z,
            long servingCellId, int pci, String bandId,
            double rsrpDbm, double sinrDb, ServiceLevel level,
            int handoverCount, int cellCount
    ) {
        public Sample {
            bandId = bandId == null ? "" : bandId;
            level = level == null ? ServiceLevel.NONE : level;
        }

        public boolean hasServing() {
            return servingCellId != ReceiverState.NO_CELL;
        }
    }

    public record Entry(Sample sample, Event event) {
    }

    private final int capacity;
    private final Deque<Entry> entries;

    public DriveTestLog() {
        this(DEFAULT_CAPACITY);
    }

    public DriveTestLog(int capacity) {
        if (capacity < 1) {
            throw new IllegalArgumentException("capacity must be at least 1, was " + capacity);
        }
        this.capacity = capacity;
        this.entries = new ArrayDeque<>(Math.min(capacity, 1024));
    }

    /**
     * Stores a sample and returns the entry it became.
     *
     * <p>The sample either replaces the previous entry (stationary, same cell, same level, and
     * neither sample marks an event) or is appended, evicting the oldest entry once the log is full.
     * A replace never swallows an event: if the previous entry marks a handover, the new sample is
     * appended after it so the handover marker stays where it happened.
     *
     * <p>A sample identical to the previous one is the server replaying a cached evaluation (it does
     * that while the receiver stands still and nothing changed). It carries no new information, so it
     * is ignored and the previous entry is returned. Without this, standing still on the spot where a
     * handover fired would log the handover sample twice: once as the event, once as its replay.
     */
    public Entry record(Sample sample) {
        Entry previous = entries.peekLast();
        if (previous != null && previous.sample().equals(sample)) {
            return previous;
        }
        Event event = classify(previous == null ? null : previous.sample(), sample);
        Entry entry = new Entry(sample, event);

        if (previous != null && isStationaryRepeat(previous, entry)) {
            entries.pollLast();
            entries.addLast(entry);
            return entry;
        }

        if (entries.size() >= capacity) {
            entries.pollFirst();
        }
        entries.addLast(entry);
        return entry;
    }

    /** Oldest first. A snapshot: later recording does not change the returned list. */
    public List<Entry> entries() {
        return List.copyOf(entries);
    }

    /**
     * Visits every entry, oldest first, without copying. For the per-frame trail renderer, which
     * would otherwise allocate a snapshot of up to {@link #capacity()} entries every frame. The action
     * must not record into or clear this log.
     */
    public void forEach(Consumer<? super Entry> action) {
        for (Entry entry : entries) {
            action.accept(entry);
        }
    }

    /** Only the entries that mark this event, oldest first. */
    public List<Entry> events(Event type) {
        List<Entry> matching = new ArrayList<>();
        for (Entry entry : entries) {
            if (entry.event() == type) {
                matching.add(entry);
            }
        }
        return matching;
    }

    public int size() {
        return entries.size();
    }

    public int capacity() {
        return capacity;
    }

    public boolean isEmpty() {
        return entries.isEmpty();
    }

    /** Forgets everything. The next sample is compared against nothing and so marks no event. */
    public void clear() {
        entries.clear();
    }

    // ---- classification -----------------------------------------------------

    /**
     * Decides what happened between two consecutive samples.
     *
     * <p>A handover is read off the server's own counter rather than guessed from a change of
     * serving cell, because the two are genuinely different: the counter only moves when the A3
     * condition held for the whole time-to-trigger, while a cell change can also be recovery from an
     * outage. A counter that goes <em>down</em> was reset (respawn, dimension change, relog) and is
     * not a handover.
     */
    static Event classify(Sample previous, Sample current) {
        if (previous == null) {
            return Event.NONE;
        }
        if (!current.hasServing()) {
            return previous.hasServing() ? Event.OUTAGE : Event.NONE;
        }
        if (current.handoverCount() > previous.handoverCount()) {
            return Event.HANDOVER;
        }
        if (current.servingCellId() != previous.servingCellId()) {
            return Event.RESELECTION;
        }
        return Event.NONE;
    }

    private static boolean isStationaryRepeat(Entry previous, Entry current) {
        if (previous.event() != Event.NONE || current.event() != Event.NONE) {
            return false;
        }
        Sample a = previous.sample();
        Sample b = current.sample();
        if (a.servingCellId() != b.servingCellId() || a.level() != b.level()) {
            return false;
        }
        double dx = a.x() - b.x();
        double dy = a.y() - b.y();
        double dz = a.z() - b.z();
        return dx * dx + dy * dy + dz * dz < STATIONARY_EPSILON_BLOCKS * STATIONARY_EPSILON_BLOCKS;
    }

    // ---- CSV export -----------------------------------------------------------

    /**
     * Column order of the export. The shape of a TEMS or Nemo drive-test export, cut down to what the
     * model actually has.
     */
    public static final String CSV_HEADER =
            "tick,x,y,z,serving_cell_id,pci,band,rsrp_dbm,sinr_db,service_level,handover_count,cells,event";

    /** RFC 4180 line ending. */
    public static final String CSV_NEWLINE = "\r\n";

    /** The whole log as RFC 4180 CSV, header first. */
    public String toCsv() {
        StringBuilder out = new StringBuilder(64 + entries.size() * 96);
        out.append(CSV_HEADER).append(CSV_NEWLINE);
        for (Entry entry : entries) {
            out.append(csvRow(entry)).append(CSV_NEWLINE);
        }
        return out.toString();
    }

    /**
     * One row, without a line ending.
     *
     * <p>Every number is formatted with {@link Locale#ROOT}. On a comma-decimal locale (Spanish,
     * German, French...) the default {@code String.format} writes {@code -82,4}, which silently turns
     * one column into two in every row. Fields with no meaning for a no-service sample are left empty
     * rather than written as a sentinel that a spreadsheet would happily average.
     */
    public static String csvRow(Entry entry) {
        Sample s = entry.sample();
        boolean serving = s.hasServing();

        StringBuilder row = new StringBuilder(96);
        row.append(s.tick()).append(',');
        row.append(fixed(s.x(), 2)).append(',');
        row.append(fixed(s.y(), 2)).append(',');
        row.append(fixed(s.z(), 2)).append(',');
        row.append(serving ? Long.toString(s.servingCellId()) : "").append(',');
        row.append(serving ? Integer.toString(s.pci()) : "").append(',');
        row.append(serving ? quote(s.bandId()) : "").append(',');
        row.append(serving ? fixed(s.rsrpDbm(), 1) : "").append(',');
        row.append(fixed(s.sinrDb(), 1)).append(',');
        row.append(s.level().name()).append(',');
        row.append(s.handoverCount()).append(',');
        row.append(s.cellCount()).append(',');
        row.append(entry.event().name());
        return row.toString();
    }

    /** Fixed-point in the root locale, or an empty field for NaN and infinities. */
    private static String fixed(double value, int decimals) {
        if (!Double.isFinite(value)) {
            return "";
        }
        return String.format(Locale.ROOT, "%." + decimals + "f", value);
    }

    /** RFC 4180 quoting: only when needed, with embedded quotes doubled. */
    static String quote(String field) {
        if (field.indexOf(',') < 0 && field.indexOf('"') < 0
                && field.indexOf('\n') < 0 && field.indexOf('\r') < 0) {
            return field;
        }
        return '"' + field.replace("\"", "\"\"") + '"';
    }
}
