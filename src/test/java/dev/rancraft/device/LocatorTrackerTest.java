package dev.rancraft.device;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.rancraft.device.LocatorTracker.Reading;
import dev.rancraft.net.SignalSamplePayload;
import dev.rancraft.rf.Band;
import dev.rancraft.rf.BandTable;
import dev.rancraft.rf.CellSample;
import dev.rancraft.rf.LocatorFix;
import dev.rancraft.rf.RangeMeasurement;
import dev.rancraft.rf.ReceiverState;
import dev.rancraft.rf.RfConfig;
import dev.rancraft.rf.ServiceLevel;
import dev.rancraft.rf.SignalSample;
import dev.rancraft.rf.SurfaceProbe;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.SplittableRandom;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The Network Locator's server step (Phase 3 slice 5, §3A.6): it ranges the evaluation's full cell
 * list, solves, reuses the fix on a replay, keeps the previous fix for "likely", and never needs
 * more of the world than the ground height.
 */
class LocatorTrackerTest {

    private static final Band B900 = new Band("band_900", 900.0, 3.5, 1.0, -110.0, 1, 10.0);
    private static final Band B1800 = new Band("band_1800", 1800.0, 3.5, 1.0, -110.0, 2, 20.0);
    private static final Band B3500 = new Band("band_3500", 3500.0, 3.5, 1.0, -110.0, 3, 100.0);
    private static final BandTable BANDS = BandTable.of(B900, B1800, B3500);
    private static final RfConfig CONFIG = RfConfig.DEFAULTS;

    /** Flat ground: feet at y = 70, so the assumed eye is at 71.62, exactly where the receiver is. */
    private static final int SURFACE = 70;
    private static final double EYE = SURFACE + 1.62;

    /** Flat ground that counts how often the solver looks. */
    private static final class CountingGround implements SurfaceProbe {
        int lookups;

        @Override
        public int surfaceY(int x, int z) {
            lookups++;
            return SURFACE;
        }
    }

    /** One heard cell, its true distance measured from the receiver's eye to its radiating centre. */
    private static CellSample cell(long id, int x, int y, int z, double rsrpDbm, String bandId,
                                   double rxX, double rxZ, double obstructionDb) {
        double dx = x + 0.5 - rxX;
        double dy = y + 0.5 - EYE;
        double dz = z + 0.5 - rxZ;
        return new CellSample(id, x, y, z, rsrpDbm, Math.sqrt(dx * dx + dy * dy + dz * dz), 90.0, obstructionDb,
                bandId, (int) (id % 504), 6.0, 0.0, 0.0);
    }

    private static SignalSample sample(long tick, List<CellSample> cells) {
        return new SignalSample(cells, tick, cells.isEmpty() ? ReceiverState.NO_CELL : cells.get(0).cellId(),
                12.0, -95.0, -104.0, ServiceLevel.GOOD, 0);
    }

    /** Six cells round the receiver, strongest first, all on the given band. */
    private static List<CellSample> ring(String bandId, double rxX, double rxZ) {
        int[][] sites = {{60, 20}, {-50, 45}, {-30, -70}, {80, -60}, {-90, -10}, {10, 95}};
        List<CellSample> cells = new ArrayList<>();
        for (int i = 0; i < sites.length; i++) {
            cells.add(cell(100 + i, sites[i][0], 90, sites[i][1], -70.0 - 3 * i, bandId, rxX, rxZ, 0.0));
        }
        return cells;
    }

    @Test
    @DisplayName("ranges the evaluation's full cell list, not the four cells the sample payload carries")
    void usesTheFullCellList() {
        List<CellSample> cells = ring("band_3500", 5.3, -7.8);
        Reading reading = LocatorTracker.update(null, sample(1_000, cells), BANDS, CONFIG, new CountingGround(), 1_000);

        assertTrue(cells.size() > SignalSamplePayload.MAX_CELLS);
        assertEquals(6, reading.used().size(), "every usable cell, strongest first");
        assertEquals(100L, reading.used().get(0).cellId());
        LocatorFix.Fix fix = assertInstanceOf(LocatorFix.Fix.class, reading.fix());
        assertEquals(6, fix.cellsUsed());
        assertEquals(5.3, fix.x(), 3.0, "within a few blocks with 3 m ranging");
        assertEquals(-7.8, fix.z(), 3.0);
        assertEquals(1_000, reading.confirmedTick());
    }

    @Test
    @DisplayName("at most locatorMaxCells cells (the strongest) go in, and each gets a ring")
    void capsAtMaxCells() {
        List<CellSample> cells = new ArrayList<>(ring("band_1800", 0, 0));
        cells.addAll(ring("band_900", 0, 0).stream()
                .map(c -> cell(c.cellId() + 50, c.x() + 3, c.y(), c.z() - 4, c.rsrpDbm() - 20, "band_900", 0, 0, 0))
                .toList());
        Reading reading = LocatorTracker.update(null, sample(5, cells), BANDS, CONFIG, new CountingGround(), 5);
        assertEquals(CONFIG.locatorMaxCells(), reading.used().size());
        assertEquals(List.of(100L, 101L, 102L, 103L, 104L, 105L, 150L, 151L),
                reading.used().stream().map(RangeMeasurement::cellId).toList());
    }

    @Test
    @DisplayName("a cell below locatorMinRsrpDbm is not ranged and gets no ring")
    void weakCellsAreLeftOut() {
        List<CellSample> cells = new ArrayList<>(ring("band_1800", 0, 0).subList(0, 3));
        cells.add(cell(900, 200, 90, 200, -100.5, "band_3500", 0, 0, 0));
        Reading reading = LocatorTracker.update(null, sample(5, cells), BANDS, CONFIG, new CountingGround(), 5);
        assertEquals(3, reading.used().size());
        assertFalse(reading.used().stream().anyMatch(r -> r.cellId() == 900L));
        assertEquals("band_1800", reading.bestResolutionBandId(), "the weak band_3500 cell does not count");
    }

    @Test
    @DisplayName("no usable cell: NO SIGNAL, no rings, no resolution to report")
    void noSignal() {
        Reading reading = LocatorTracker.update(null, SignalSample.empty(7), BANDS, CONFIG, new CountingGround(), 7);
        assertInstanceOf(LocatorFix.NoSignal.class, reading.fix());
        assertTrue(reading.used().isEmpty());
        assertEquals(0.0, reading.bestResolutionMeters());
        assertEquals("", reading.bestResolutionBandId());
    }

    @Test
    @DisplayName("best resolution names the finest band among the cells used (c / BW in metres)")
    void bestResolution() {
        List<CellSample> cells = new ArrayList<>(ring("band_900", 0, 0).subList(0, 3));
        cells.add(cell(300, 40, 90, 40, -95, "band_1800", 0, 0, 0));
        Reading mixed = LocatorTracker.update(null, sample(5, cells), BANDS, CONFIG, new CountingGround(), 5);
        assertEquals("band_1800", mixed.bestResolutionBandId());
        assertEquals(299.792458 / 20.0, mixed.bestResolutionMeters(), 1e-9);

        cells.add(cell(400, -40, 90, 40, -96, "band_3500", 0, 0, 0));
        Reading wide = LocatorTracker.update(null, sample(6, cells), BANDS, CONFIG, new CountingGround(), 6);
        assertEquals("band_3500", wide.bestResolutionBandId());
        assertEquals(2.99792458, wide.bestResolutionMeters(), 1e-9);
    }

    @Test
    @DisplayName("a cached replay reuses the stored fix: nothing solved, the ground not even read, only the confirmation moves")
    void replayReusesTheFix() {
        SignalSample evaluated = sample(2_000, ring("band_1800", 5.3, -7.8));
        CountingGround ground = new CountingGround();
        Reading first = LocatorTracker.update(null, evaluated, BANDS, CONFIG, ground, 2_000);
        int lookupsForTheFix = ground.lookups;
        assertTrue(lookupsForTheFix > 0);

        Reading replayed = LocatorTracker.update(first, evaluated, BANDS, CONFIG, ground, 2_020);
        assertSame(first.fix(), replayed.fix());
        assertEquals(first.used(), replayed.used());
        assertEquals(2_020, replayed.confirmedTick());
        assertEquals(lookupsForTheFix, ground.lookups, "a replay reads nothing");

        // An equal sample (a copy) is the same measurement: also a replay.
        SignalSample copy = new SignalSample(evaluated.cells(), evaluated.timestampTick(), evaluated.servingCellId(),
                evaluated.sinrDb(), evaluated.interferenceDbm(), evaluated.noiseDbm(), evaluated.serviceLevel(),
                evaluated.handoverCount());
        assertSame(first.fix(), LocatorTracker.update(replayed, copy, BANDS, CONFIG, ground, 2_040).fix());

        // Two Locators on one player: the second dispatch of the same evaluation is a replay too.
        assertEquals(2_020, LocatorTracker.update(replayed, evaluated, BANDS, CONFIG, ground, 2_020).confirmedTick());
    }

    @Test
    @DisplayName("confirmation never runs backwards")
    void confirmationIsMonotonic() {
        SignalSample evaluated = sample(100, ring("band_1800", 0, 0));
        Reading first = LocatorTracker.update(null, evaluated, BANDS, CONFIG, new CountingGround(), 140);
        assertEquals(140, first.confirmedAt(120).confirmedTick());
    }

    @Test
    @DisplayName("/tick freeze: a fresh evaluation with the same tick but other cells is solved, so the Locator follows the player")
    void frozenGameTimeStillFollows() {
        Reading here = LocatorTracker.update(null, sample(3_000, ring("band_3500", 0, 0)), BANDS, CONFIG,
                new CountingGround(), 3_000);
        Reading moved = LocatorTracker.update(here, sample(3_000, ring("band_3500", 40, 0)), BANDS, CONFIG,
                new CountingGround(), 3_000);
        LocatorFix.Fix before = assertInstanceOf(LocatorFix.Fix.class, here.fix());
        LocatorFix.Fix after = assertInstanceOf(LocatorFix.Fix.class, moved.fix());
        assertEquals(40.0, after.x() - before.x(), 4.0);
    }

    @Test
    @DisplayName("the previous fix marks the likely candidate when only two cells are left")
    void previousFixPicksLikely() {
        double rxX = 12.0;
        double rxZ = -20.0;
        List<CellSample> three = ring("band_3500", rxX, rxZ).subList(0, 3);
        SignalSample twoCells = sample(4_020, three.subList(0, 2));

        Reading withoutHistory = LocatorTracker.update(null, twoCells, BANDS, CONFIG, new CountingGround(), 4_020);
        LocatorFix.Ambiguous unknown = assertInstanceOf(LocatorFix.Ambiguous.class, withoutHistory.fix());
        assertEquals(LocatorFix.Ambiguous.NO_PREFERENCE, unknown.likely(), "no previous estimate: no preference");

        Reading fixed = LocatorTracker.update(null, sample(4_000, three), BANDS, CONFIG, new CountingGround(), 4_000);
        assertInstanceOf(LocatorFix.Fix.class, fixed.fix());
        Reading next = LocatorTracker.update(fixed, twoCells, BANDS, CONFIG, new CountingGround(), 4_020);
        LocatorFix.Ambiguous ambiguous = assertInstanceOf(LocatorFix.Ambiguous.class, next.fix());
        double toA = Math.hypot(ambiguous.ax() - rxX, ambiguous.az() - rxZ);
        double toB = Math.hypot(ambiguous.bx() - rxX, ambiguous.bz() - rxZ);
        assertEquals(toA < toB ? 0 : 1, ambiguous.likely(), "the candidate near the true position");
        assertEquals(2, next.used().size(), "both cells get a ring");
    }

    /**
     * Phase 3A review, round 1: the solver's {@code previous} counts only while the reading is fresh
     * (two intervals, the waypoint rule). An older one means the Locator was put away and the player
     * may be anywhere; choosing the candidate nearer that old place would be a coin flip drawn as a
     * preference, and the Ambiguous chain would keep it.
     */
    @Test
    @DisplayName("a previous fix older than two intervals (the Locator was put away) picks no likely candidate; up to two it still does")
    void stalePreviousPicksNothing() {
        double rxX = 12.0;
        double rxZ = -20.0;
        List<CellSample> three = ring("band_3500", rxX, rxZ).subList(0, 3);
        Reading fixed = LocatorTracker.update(null, sample(4_000, three), BANDS, CONFIG, new CountingGround(), 4_000);
        assertInstanceOf(LocatorFix.Fix.class, fixed.fix());
        assertEquals(40, LocatorTracker.freshForTicks(CONFIG.evaluationIntervalTicks()), "fixture: interval 20");

        for (long tick : new long[] {4_020, 4_040}) {
            Reading next = LocatorTracker.update(fixed, sample(tick, three.subList(0, 2)), BANDS, CONFIG,
                    new CountingGround(), tick);
            LocatorFix.Ambiguous ambiguous = assertInstanceOf(LocatorFix.Ambiguous.class, next.fix());
            double toA = Math.hypot(ambiguous.ax() - rxX, ambiguous.az() - rxZ);
            double toB = Math.hypot(ambiguous.bx() - rxX, ambiguous.bz() - rxZ);
            assertEquals(toA < toB ? 0 : 1, ambiguous.likely(), "fresh at tick " + tick + ": the candidate near the truth");
        }

        Reading stale = LocatorTracker.update(fixed, sample(4_041, three.subList(0, 2)), BANDS, CONFIG,
                new CountingGround(), 4_041);
        LocatorFix.Ambiguous ambiguous = assertInstanceOf(LocatorFix.Ambiguous.class, stale.fix());
        assertEquals(LocatorFix.Ambiguous.NO_PREFERENCE, ambiguous.likely(), "41 ticks old: no preference");
        assertEquals(2, stale.used().size());

        // A replay is still recognised whatever its age: that check uses the stored reading as is.
        assertSame(fixed.fix(), LocatorTracker.update(fixed, fixed.sample(), BANDS, CONFIG, new CountingGround(), 9_000).fix());
    }

    @Test
    @DisplayName("fresh for two intervals: a reading kept while the Locator was put away is not the current answer")
    void freshness() {
        Reading reading = LocatorTracker.update(null, sample(1_000, ring("band_1800", 0, 0)), BANDS, CONFIG,
                new CountingGround(), 1_000);
        long maxAge = LocatorTracker.freshForTicks(20);
        assertEquals(40, maxAge);
        assertEquals(2, LocatorTracker.freshForTicks(0));
        assertTrue(LocatorTracker.isFresh(reading, 1_000, maxAge));
        assertTrue(LocatorTracker.isFresh(reading, 1_040, maxAge));
        assertFalse(LocatorTracker.isFresh(reading, 1_041, maxAge));
        assertFalse(LocatorTracker.isFresh(reading, 999, maxAge), "stamped in the future: not trusted");
        assertFalse(LocatorTracker.isFresh(null, 1_000, maxAge));
    }

    @Test
    @DisplayName("cost: ground lookups per fix stay within 7 runs x 16, and a fix is microseconds (measured)")
    void costPerFix() {
        SplittableRandom random = new SplittableRandom(55);
        String[] bandIds = {"band_900", "band_1800", "band_3500"};
        List<SignalSample> samples = new ArrayList<>();
        for (int s = 0; s < 2_000; s++) {
            double rxX = random.nextDouble(-300, 300);
            double rxZ = random.nextDouble(-300, 300);
            List<CellSample> cells = new ArrayList<>();
            for (int i = 0; i < 8; i++) {
                cells.add(cell(s * 10L + i, random.nextInt(-400, 400), random.nextInt(75, 120), random.nextInt(-400, 400),
                        -60.0 - 4 * i, bandIds[random.nextInt(3)], rxX, rxZ, random.nextDouble() < 0.3 ? 20.0 : 0.0));
            }
            samples.add(sample(s, cells));
        }

        CountingGround ground = new CountingGround();
        int maxLookups = 0;
        long totalLookups = 0;
        int fixes = 0;
        for (SignalSample sample : samples) {
            int before = ground.lookups;
            Reading reading = LocatorTracker.update(null, sample, BANDS, CONFIG, ground, sample.timestampTick());
            int lookups = ground.lookups - before;
            maxLookups = Math.max(maxLookups, lookups);
            totalLookups += lookups;
            if (reading.fix() instanceof LocatorFix.Fix) {
                fixes++;
            }
        }
        assertTrue(maxLookups <= 7 * 16 + 1, "lookups " + maxLookups);

        // Timing: JIT-warm, repeated; printed for NOTES.md. Loose bound: the whole server budget.
        long start = 0;
        int repetitions = 0;
        for (int round = 0; round < 6; round++) {
            if (round == 3) {
                start = System.nanoTime();
                repetitions = 0;
            }
            for (SignalSample sample : samples) {
                LocatorTracker.update(null, sample, BANDS, CONFIG, ground, sample.timestampTick());
                repetitions++;
            }
        }
        double microsPerFix = (System.nanoTime() - start) / 1_000.0 / repetitions;
        System.out.println(String.format(Locale.ROOT,
                "LocatorTrackerTest cost: %.1f us per 8-cell update (%d reps), ground lookups mean %.1f max %d, %d/%d FIX",
                microsPerFix, repetitions, totalLookups / (double) samples.size(), maxLookups, fixes, samples.size()));
        assertTrue(microsPerFix < 1_000.0, microsPerFix + " us per fix");
        assertNotEquals(0, fixes);
    }
}
