package dev.rancraft.world;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import dev.rancraft.rf.Band;
import dev.rancraft.rf.BandTable;
import dev.rancraft.rf.CoverageSurvey;
import dev.rancraft.rf.RfConfig;
import dev.rancraft.world.CoverageSurveyor.Job;
import dev.rancraft.world.CoverageSurveyor.Reason;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The restart policy of a coverage job. Only {@link Job} is loaded here -- its restart logic takes
 * plain values -- so this runs headless like the {@code rf} tests. The dimension check lives in the
 * tick handler and is not covered.
 */
class CoverageSurveyorTest {

    /** 8 points of 4 blocks: a 32-block span, so half a span is 16 and a quarter is 8. */
    private static final int STEP = 4;
    private static final int SIZE = 8;

    private static final long STARTED = 1_000L;
    private static final long EPOCH = 5L;
    private static final long SITES = 7L;
    private static final int INTERVAL = 100;
    private static final long AFTER_INTERVAL = STARTED + INTERVAL;
    private static final long SOON = STARTED + 1;

    private static Job job(String bandFilter, boolean finished) {
        CoverageSurvey survey = new CoverageSurvey(
                CoverageSurvey.centredOn(0.0, 0.0, STEP, SIZE, bandFilter),
                List.of(), BandTable.of(Band.DEFAULT_900), RfConfig.DEFAULTS);
        Job job = new Job(null, survey, STARTED, EPOCH, SITES);
        if (finished) {
            job.finish(STARTED);
        }
        return job;
    }

    private static Job job(boolean finished) {
        return job("", finished);
    }

    private static Reason check(Job job, double x, double z, long epoch, long sites, long now) {
        return job.restartReason("", STEP, SIZE, x, z, epoch, sites, now, INTERVAL);
    }

    @Test
    @DisplayName("fixture: the grid is centred on the origin with a 32-block span")
    void fixture() {
        CoverageSurvey.Spec spec = job(false).survey.spec();
        assertEquals(0.0, spec.centreX());
        assertEquals(0.0, spec.centreZ());
        assertEquals(32.0, spec.spanBlocks());
    }

    @Test
    @DisplayName("nothing changed: keep the job, finished or not")
    void unchangedKeeps() {
        assertNull(check(job(false), 0, 0, EPOCH, SITES, AFTER_INTERVAL));
        assertNull(check(job(true), 0, 0, EPOCH, SITES, AFTER_INTERVAL));
        assertNull(check(job(true), 3, -5, EPOCH, SITES, AFTER_INTERVAL * 10));
    }

    @Test
    @DisplayName("a band filter change restarts at once, mid-survey and inside the interval")
    void bandChangeRestartsAtOnce() {
        assertEquals(Reason.BAND, job(false).restartReason(
                "band_1800", STEP, SIZE, 0, 0, EPOCH, SITES, SOON, INTERVAL));
        assertEquals(Reason.BAND, job("band_1800", true).restartReason(
                "", STEP, SIZE, 0, 0, EPOCH, SITES, SOON, INTERVAL));
    }

    @Test
    @DisplayName("a grid size or step change restarts at once")
    void gridChangeRestartsAtOnce() {
        assertEquals(Reason.GRID, job(false).restartReason("", STEP, SIZE * 2, 0, 0, EPOCH, SITES, SOON, INTERVAL));
        assertEquals(Reason.GRID, job(false).restartReason("", STEP / 2, SIZE, 0, 0, EPOCH, SITES, SOON, INTERVAL));
    }

    @Test
    @DisplayName("walking more than half a span from the centre restarts at once, mid-survey and inside the interval")
    void walkingOffRestartsAtOnce() {
        assertEquals(Reason.WALKED_OFF, check(job(false), 16.1, 0, EPOCH, SITES, SOON));
        assertEquals(Reason.WALKED_OFF, check(job(true), 0, -16.1, EPOCH, SITES, SOON));
        assertNull(check(job(false), 16.0, 0, EPOCH, SITES, SOON), "exactly half a span is not more than half");
    }

    @Test
    @DisplayName("distance is Euclidean, so a diagonal walk restarts before the wearer leaves the painted square")
    void distanceIsEuclidean() {
        // 12 blocks each way is inside the 16-block half-square, but 17 blocks from the centre.
        assertEquals(Reason.WALKED_OFF, check(job(false), 12, 12, EPOCH, SITES, SOON));
    }

    @Test
    @DisplayName("an unfinished survey of the right place is allowed to finish, however stale")
    void unfinishedIgnoresStaleness() {
        assertNull(check(job(false), 10, 0, EPOCH + 1, SITES + 1, AFTER_INTERVAL * 10));
    }

    @Test
    @DisplayName("a finished survey is not redone for staleness before the minimum interval")
    void finishedWaitsForInterval() {
        Job job = job(true);
        assertNull(check(job, 10, 0, EPOCH + 1, SITES + 1, AFTER_INTERVAL - 1));
        assertEquals(Reason.MOVED, check(job, 10, 0, EPOCH + 1, SITES + 1, AFTER_INTERVAL));
    }

    @Test
    @DisplayName("the interval runs from when the survey was sent, so a survey longer than the interval is not redone at once")
    void intervalRunsFromSend() {
        Job job = job(false);
        long sent = STARTED + 3 * INTERVAL; // took three intervals to finish (many wearers sharing the budget)
        job.finish(sent);
        assertEquals(sent, job.finishedTick);

        assertNull(check(job, 0, 0, EPOCH + 1, SITES, sent + 1),
                "stale on arrival, but it has not been up for a full interval yet");
        assertNull(check(job, 0, 0, EPOCH + 1, SITES, sent + INTERVAL - 1));
        assertEquals(Reason.BLOCKS, check(job, 0, 0, EPOCH + 1, SITES, sent + INTERVAL));
    }

    @Test
    @DisplayName("once finished and past the interval: moved, blocks and sites each trigger a redo")
    void stalenessTriggers() {
        assertEquals(Reason.MOVED, check(job(true), 8.5, 0, EPOCH, SITES, AFTER_INTERVAL));
        assertNull(check(job(true), 8.0, 0, EPOCH, SITES, AFTER_INTERVAL), "exactly a quarter span is not more");
        assertEquals(Reason.BLOCKS, check(job(true), 0, 0, EPOCH + 1, SITES, AFTER_INTERVAL));
        assertEquals(Reason.SITES, check(job(true), 0, 0, EPOCH, SITES + 1, AFTER_INTERVAL));
    }

    @Test
    @DisplayName("a zero minimum interval redoes a stale survey on the tick it finished")
    void zeroInterval() {
        assertEquals(Reason.BLOCKS, job(true).restartReason("", STEP, SIZE, 0, 0, EPOCH + 1, SITES, STARTED, 0));
    }
}
