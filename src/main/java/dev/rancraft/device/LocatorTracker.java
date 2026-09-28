package dev.rancraft.device;

import dev.rancraft.rf.Band;
import dev.rancraft.rf.BandTable;
import dev.rancraft.rf.LocatorFix;
import dev.rancraft.rf.LocatorParams;
import dev.rancraft.rf.LocatorSolver;
import dev.rancraft.rf.RangeMeasurement;
import dev.rancraft.rf.Ranging;
import dev.rancraft.rf.RfConfig;
import dev.rancraft.rf.SignalSample;
import dev.rancraft.rf.SurfaceProbe;
import java.util.List;
import java.util.Objects;

/**
 * The Network Locator's server-side step for one receiver, with no Minecraft types, so it is
 * unit-tested headless. Phase 3 slice 5, §3A.6. The game side ({@link NetworkLocator}) supplies the
 * level-backed {@link SurfaceProbe}, keeps the {@link Reading} per player and sends the payload.
 *
 * <p><b>It computes no RF.</b> Its only input about the signal is the {@link SignalSample} the ticker
 * already produced: the evaluation's <em>full</em> cell list (up to {@code maxCellsEvaluated}, not
 * the four cells the sample payload carries). There is no {@code WorldProbe} anywhere in its
 * signature, so it cannot march a ray; carrying the Locator costs no evaluation. Its only look at the
 * world is the ground height for altitude aiding ({@link SurfaceProbe}), which never loads a chunk.
 *
 * <p><b>Replays.</b> While the player stands still, the ticker hands over its cached sample again,
 * the very same object, with the same {@code timestampTick}. That is recognised here
 * ({@link #isReplay}) and the stored fix is reused rather than solved again: the same measurement
 * gives the same fix, and re-solving would feed the fix back to itself as its own "previous"
 * estimate. Only {@link Reading#confirmedTick()} moves, recording that the Locator reported this fix
 * again. <b>Keyed on the whole sample, not on its tick alone</b> (a deliberate refinement of
 * §3A.3's "idempotent on {@code sample.timestampTick()}", recorded in NOTES.md): under
 * {@code /tick freeze} game time stops, so fresh evaluations of a walking player share a tick; they
 * differ in their cells, so the Locator still follows the player. A replay is always equal to what
 * it replays, tick included, so every replay the tick key would catch is caught here too.
 *
 * <p>A live change of a locator setting (say {@code locatorMaxHdop}) applies from the next fresh
 * evaluation; a player standing still keeps the fix worked out under the old one, as the cached
 * sample itself already keeps the band table it was evaluated with.
 */
public final class LocatorTracker {

    private LocatorTracker() {
    }

    /**
     * What the Locator last worked out for one receiver.
     *
     * @param sample               the evaluation the fix came from. Kept to recognise a replay of it.
     * @param fix                  the solver's answer ({@link LocatorSolver#solve}).
     * @param used                 the ranges that went into it, strongest first: the Locator's rings.
     * @param bestResolutionMeters the finest timing resolution among {@code used}, {@code c / BW} in
     *                             metres (0 when none was used): what the HUD's "best res" names.
     * @param bestResolutionBandId the band that resolution belongs to ({@code ""} when none).
     * @param confirmedTick        game time of the latest dispatch that reported this fix: the
     *                             evaluation's own tick, or later once replays have confirmed it.
     */
    public record Reading(
            SignalSample sample,
            LocatorFix fix,
            List<RangeMeasurement> used,
            double bestResolutionMeters,
            String bestResolutionBandId,
            long confirmedTick) {

        public Reading {
            Objects.requireNonNull(sample, "sample");
            Objects.requireNonNull(fix, "fix");
            used = List.copyOf(used);
            bestResolutionBandId = bestResolutionBandId == null ? "" : bestResolutionBandId;
        }

        /** The same reading, reported again at {@code tick}. */
        public Reading confirmedAt(long tick) {
            return new Reading(sample, fix, used, bestResolutionMeters, bestResolutionBandId,
                    Math.max(confirmedTick, tick));
        }
    }

    /** Whether {@code sample} is the one {@code previous} was worked out from, handed over again. */
    public static boolean isReplay(Reading previous, SignalSample sample) {
        return previous != null && (previous.sample() == sample || previous.sample().equals(sample));
    }

    /**
     * One dispatch: a replay reuses the stored fix, anything else is ranged and solved afresh with
     * the previous fix as the solver's {@code previous} (which picks the likely candidate of an
     * ambiguous answer).
     *
     * @param previous     what this receiver's Locator last worked out, or {@code null}.
     * @param dispatchTick the game time of this dispatch ({@code DeviceContext.tick()}).
     */
    public static Reading update(Reading previous, SignalSample sample, BandTable bands, RfConfig config,
                                 SurfaceProbe ground, long dispatchTick) {
        Objects.requireNonNull(sample, "sample");
        if (isReplay(previous, sample)) {
            return previous.confirmedAt(dispatchTick);
        }

        LocatorParams params = config.locatorParams();
        List<RangeMeasurement> ranges = Ranging.measure(sample.cells(), bands, config);
        List<RangeMeasurement> used = LocatorSolver.cellsUsed(ranges, params);
        LocatorFix fix = LocatorSolver.solve(ranges, ground, previous == null ? null : previous.fix(), params);

        double bestMeters = 0.0;
        String bestBand = "";
        for (RangeMeasurement range : used) {
            Band band = bands.getOrFallback(range.bandId());
            double meters = Ranging.resolutionMeters(band.bandwidthMhz());
            if (bestBand.isEmpty() || meters < bestMeters) {
                bestMeters = meters;
                bestBand = range.bandId();
            }
        }
        return new Reading(sample, fix, used, bestMeters, bestBand, dispatchTick);
    }

    /**
     * How long, in game ticks, a reading still counts as the Locator's <em>current</em> answer, for
     * saving a waypoint: two evaluation intervals. A Locator carried in a hand or the hotbar is
     * dispatched every interval (fresh or replayed), so its reading is at most one interval old; the
     * second interval is slack for a live change of {@code evaluationIntervalTicks}, which can
     * stretch one gap. An older reading means the Locator was put away and the player may have moved
     * since, so a waypoint saved from it would not even be the estimate of where they stand.
     */
    public static long freshForTicks(int evaluationIntervalTicks) {
        return 2L * Math.max(1, evaluationIntervalTicks);
    }

    /**
     * Whether {@code reading} is still the Locator's current answer at game time {@code now}
     * ({@link #freshForTicks}). Game time never runs backwards; a reading stamped after {@code now}
     * is treated as stale rather than trusted.
     */
    public static boolean isFresh(Reading reading, long now, long maxAgeTicks) {
        if (reading == null) {
            return false;
        }
        long age = now - reading.confirmedTick();
        return age >= 0L && age <= maxAgeTicks;
    }
}
