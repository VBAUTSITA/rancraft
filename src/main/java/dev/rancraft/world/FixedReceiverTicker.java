package dev.rancraft.world;

import dev.rancraft.RanCraft;
import dev.rancraft.RanCraftConfig;
import dev.rancraft.data.RfDataLoader;
import dev.rancraft.device.DeviceContext;
import dev.rancraft.device.FixedDevice;
import dev.rancraft.rf.BandTable;
import dev.rancraft.rf.CellParams;
import dev.rancraft.rf.DeviceRequirement;
import dev.rancraft.rf.ReceiverState;
import dev.rancraft.rf.RfConfig;
import dev.rancraft.rf.RfEngine;
import dev.rancraft.rf.SignalSample;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.function.LongSupplier;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/**
 * Evaluates fixed receivers, the device blocks of {@link FixedReceiverRegistry} (Phase 3 slice 8,
 * §3B.3), and hands each sample to its {@link FixedDevice}.
 *
 * <p><b>Same engine, same rules as a player.</b> A fixed receiver is evaluated at the centre of its
 * block with the same {@link RfEngine} call, candidate query, handover state machine and dependency
 * set as {@link SignalTicker} uses for a player's eye. Devices never compute RF: they get the sample.
 *
 * <h2>Scheduling</h2>
 * The {@link CoverageSurveyor} pattern: the tick's {@link RanCraftConfig#fixedReceiverTickBudgetMs()}
 * is spent round-robin, in batches of {@value #BATCH_RECEIVERS} receivers with the clock read between
 * batches, so a tick overshoots its budget by at most one batch; the first batch always runs, so the
 * ticker makes progress however small the budget. Which dimension goes first rotates every tick, and
 * within a dimension each tick resumes where the last one stopped, so over budget nobody is always
 * last in line. A receiver is due at most once per {@code evaluationIntervalTicks}: after it is served
 * at tick {@code t} it is next due at {@code t + interval}, so a late service moves its next one later
 * too, never closer ({@link #runTick}). A new receiver's first turn is staggered over the interval by
 * its position ({@link #staggerTicks}), as players are by entity id.
 *
 * <h2>Replays: why a quiet receiver is free</h2>
 * A fixed receiver never moves. So while the site registry version is unchanged and no bin its last
 * evaluation depends on was bumped ({@link RegionEpochs}, Phase 3 slice 7: the bins along the rays it
 * marched), a fresh evaluation would give exactly the same result, and the cached sample is replayed
 * to the device instead ({@link #canReplay}). In steady state that is the only cost: one epoch check
 * and one dispatch. An armed handover candidate skips the cache, as for a player, so the
 * time-to-trigger keeps running.
 *
 * <h2>Stale handover candidates</h2>
 * A player is evaluated exactly once per interval, so the player ticker reads a longer gap as a pause
 * and drops an armed candidate ({@link SignalTicker#staleCandidateGapTicks}). This ticker cannot
 * promise that: over budget a receiver waits. Its threshold is therefore the receiver's own scheduled
 * gap plus how late it is being served ({@link #staleCandidateGapTicks(int, long)}), so its own
 * lateness is never read as a pause.
 */
@EventBusSubscriber(modid = RanCraft.MOD_ID)
public final class FixedReceiverTicker {

    private FixedReceiverTicker() {
    }

    /**
     * Receivers served between clock checks. Measured in a live level (NOTES.md, slice 8): a warm
     * fresh evaluation of one short ray costs 20-47 µs, a replay about 2 µs with its dispatch, a clock
     * read about 45 ns. So a tick overshoots its budget by at most four fresh evaluations (up to about
     * 190 µs; eight overshot by up to 690 µs in the first runs), and reading the clock every four
     * replays costs about half a percent of them.
     */
    static final int BATCH_RECEIVERS = 4;

    private static final long FAILURE_LOG_INTERVAL_MILLIS = 30_000L;

    /** Rotates the dimension served first each tick. Server thread only. */
    private static int roundRobin;

    private static long statTicks;
    private static long statNanos;
    private static long statMaxTickNanos;
    private static long statSetupNanos;
    private static long statServeNanos;
    private static long statServed;
    private static long statEvaluations;
    private static long statReplays;
    private static long statSkipped;
    private static long statFailures;
    private static long lastFailureLogMillis;

    /**
     * A fixed receiver's last evaluation, replayed while nothing it depends on has changed.
     * Server memory only; never sent or saved.
     *
     * @param sample       the evaluation, full cell list included.
     * @param dependencies the epochs of its dependency bins when it ran
     *                     ({@code RfEngine.Evaluation.dependencyBins}).
     * @param siteVersion  the site registry version when it ran.
     */
    record Cached(SignalSample sample, RegionEpochs.Snapshot dependencies, long siteVersion) {
    }

    /**
     * Counters since the server started (or {@link #resetStats}), for measuring the ticker at
     * runtime. A tick is counted only when at least one receiver is registered.
     *
     * @param ticks        ticks the ticker ran.
     * @param nanos        wall-clock time spent in them, in all (the handler, from reading the config
     *                     to the last dispatch).
     * @param maxTickNanos the slowest of them.
     * @param setupNanos   the part of {@code nanos} spent before the round robin: finding the
     *                     dimensions with receivers, the config snapshot.
     * @param serveNanos   the part of {@code nanos} spent serving receivers (evaluating or replaying
     *                     and dispatching); the rest is the scan for due receivers.
     * @param served       receivers served (each is an evaluation, a replay or a skip).
     * @param evaluations  fresh evaluations.
     * @param replays      cached samples replayed.
     * @param skipped      receivers whose chunk was not loaded as FULL (not evaluated, not dispatched).
     * @param failures     devices whose {@code onSample} threw.
     */
    public record Stats(long ticks, long nanos, long maxTickNanos, long setupNanos, long serveNanos, long served,
                        long evaluations, long replays, long skipped, long failures) {
    }

    public static Stats stats() {
        return new Stats(statTicks, statNanos, statMaxTickNanos, statSetupNanos, statServeNanos, statServed,
                statEvaluations, statReplays, statSkipped, statFailures);
    }

    /** Zeroes the counters, {@code maxTickNanos} included. */
    public static void resetStats() {
        statTicks = 0;
        statNanos = 0;
        statMaxTickNanos = 0;
        statSetupNanos = 0;
        statServeNanos = 0;
        statServed = 0;
        statEvaluations = 0;
        statReplays = 0;
        statSkipped = 0;
        statFailures = 0;
    }

    // ---- pure rules -------------------------------------------------------------------------------

    /**
     * Whether a receiver's cached sample is replayed instead of evaluated. Pure, pinned by
     * {@code FixedReceiverTickerTest}. All must hold: caching enabled; no handover candidate armed
     * (replaying would freeze the time-to-trigger clock); an entry exists; the site registry version is
     * the one it was evaluated under; and no dependency bin was bumped since. There is no move check:
     * a fixed receiver never moves.
     *
     * @param candidateArmed the <em>stored</em> handover state has a candidate.
     */
    static boolean canReplay(
            Cached cached, boolean cachingEnabled, boolean candidateArmed, RegionEpochs epochs, long siteVersion) {
        return cachingEnabled
                && !candidateArmed
                && cached != null
                && cached.siteVersion() == siteVersion
                && epochs.unchanged(cached.dependencies());
    }

    /**
     * The longest gap, in game ticks, since a fixed receiver's last evaluation that still counts as
     * continuous observation of an armed handover candidate. A longer gap drops the candidate before
     * selection ({@link dev.rancraft.rf.CellSelector#expireStaleCandidate}).
     *
     * <p><b>Derived from this ticker's own schedule, not the player ticker's</b> (a slice 4
     * follow-up). A receiver served at server tick {@code s} is next due at {@code s + interval} and
     * served at {@code s + interval + lag}, where {@code lag} is how late the budget made it. That whole
     * gap is the ticker's doing, not a pause in observation, so the threshold is {@code interval + lag}:
     * reading it as a pause would drop the candidate at every budget overrun and delay the handover by a
     * time-to-trigger each time. Game time advances at most one tick per server tick (and not at all
     * under {@code /tick freeze}), so the game-time gap never exceeds the server-tick gap.
     *
     * <p><b>What still counts as a pause:</b> a receiver whose chunk was not loaded as FULL when its turn
     * came is skipped and rescheduled one interval on, not evaluated. Its next service then has a small
     * lag but a gap of two intervals or more, and the candidate is dropped, exactly as for a player who
     * put the meter away. (Unloading the chunk itself unregisters the receiver and forgets its state.)
     *
     * <p><b>Honest limit (NOTES.md, slice 8):</b> an overloaded ticker samples a receiver less often, and
     * the time-to-trigger is judged on the evaluations it could afford. A neighbour that dipped and
     * recovered between two late evaluations is not seen to dip. A real UE measures on its own
     * schedule; here the server's budget sets the sampling rate.
     *
     * <p>A live change of {@code evaluationIntervalTicks} can drop a candidate once (the gap was
     * scheduled with the old interval), delaying that handover by at most one time-to-trigger, never
     * making it early: the same accepted exception as the player ticker's.
     *
     * @param interval the evaluation interval, already clamped to at least 1.
     * @param lagTicks server ticks between the receiver becoming due and being served now.
     */
    static long staleCandidateGapTicks(int interval, long lagTicks) {
        return Math.max(1, interval) + Math.max(0L, lagTicks);
    }

    /**
     * A new receiver's offset into the interval: a well-mixed hash of its position, so a chunk full of
     * devices loading at once spreads over the interval instead of landing on one tick. Pure.
     *
     * @return a value in {@code [0, interval)}.
     */
    static int staggerTicks(long key, int interval) {
        long h = key;
        h ^= h >>> 33;
        h *= 0xff51afd7ed558ccdL;
        h ^= h >>> 33;
        h *= 0xc4ceb9fe1a85ec53L;
        h ^= h >>> 33;
        return (int) Math.floorMod(h, (long) Math.max(1, interval));
    }

    // ---- the round robin --------------------------------------------------------------------------

    /** What serving one due receiver does: the tick handler evaluates or replays it; tests record it. */
    @FunctionalInterface
    interface Service {
        /**
         * @param lane     index of the receiver's dimension in the list handed to {@link #runTick}.
         * @param entry    the receiver; already rescheduled one interval on.
         * @param lagTicks server ticks it waited past its due tick.
         */
        void serve(int lane, FixedReceiverRegistry.Entry entry, long lagTicks);
    }

    /**
     * One tick of the round robin over every dimension's receivers. MC-free, so the scheduling is
     * pinned headless ({@code FixedReceiverTickerTest}) with a fake clock.
     *
     * <ul>
     *   <li>Dimensions ("lanes") take turns one batch at a time, starting at {@code firstLane}.
     *   <li>Within a lane, receivers are looked at from where the lane's last tick stopped
     *       ({@link FixedReceiverRegistry#advanceIndex}), each at most once per tick (a lap). Looking
     *       reads only the registry's due-tick array; an entry is touched only when it is due.
     *   <li>A receiver is served when due ({@code dueTick <= now}); it is then next due at
     *       {@code now + interval}. A new one is first due at {@code now + staggerTicks}. A due tick
     *       further off than one interval (the interval was lowered live) is pulled in to
     *       {@code now + interval}.
     *   <li>Before every batch but the first, the clock is read; at or past {@code deadlineNanos} the
     *       tick ends and the rest wait, still due, for the next tick.
     * </ul>
     *
     * @return receivers served.
     */
    static int runTick(List<FixedReceiverRegistry> lanes, int firstLane, long now, int interval,
                       LongSupplier clock, long deadlineNanos, Service service) {

        int count = lanes.size();
        if (count == 0) {
            return 0;
        }
        int step = Math.max(1, interval);
        int[] looked = new int[count];
        boolean[] lapped = new boolean[count];
        int lappedCount = 0;
        int served = 0;
        boolean firstBatch = true;
        int lane = Math.floorMod(firstLane, count);

        while (lappedCount < count) {
            if (!lapped[lane]) {
                if (!firstBatch && clock.getAsLong() - deadlineNanos >= 0) {
                    break;
                }
                firstBatch = false;
                FixedReceiverRegistry registry = lanes.get(lane);
                // One lock per batch, not two per receiver looked at: a quiet tick looks at every
                // receiver and serves few. Reentrant, so a device may unregister itself meanwhile.
                synchronized (registry) {
                    int batch = 0;
                    while (batch < BATCH_RECEIVERS && looked[lane] < registry.sizeHeld()) {
                        int index = registry.advanceIndex();
                        looked[lane]++;
                        if (index < 0) {
                            break;
                        }
                        long due = registry.dueAt(index);
                        if (due == FixedReceiverRegistry.UNSCHEDULED) {
                            due = now + staggerTicks(registry.entryAt(index).key, step);
                            registry.setDueAt(index, due);
                        } else if (due > now + step) {
                            due = now + step;
                            registry.setDueAt(index, due);
                        }
                        if (due <= now) {
                            registry.setDueAt(index, now + step);
                            // May change the registry (a device unregistering itself): the index is
                            // not used after this call.
                            service.serve(lane, registry.entryAt(index), now - due);
                            batch++;
                            served++;
                        }
                    }
                    if (looked[lane] >= registry.sizeHeld()) {
                        lapped[lane] = true;
                        lappedCount++;
                    }
                }
            }
            lane = (lane + 1) % count;
        }
        return served;
    }

    // ---- the tick handler -------------------------------------------------------------------------

    /** One dimension's inputs for this tick, read once. */
    private static final class Lane {

        final ServerLevel level;
        final FixedReceiverRegistry registry;
        final RegionEpochs epochs;
        final SiteRegistry sites;
        final long gameTime;
        /** One probe per dimension per tick: it caches the last chunk read, server thread only. */
        private LevelWorldProbe probe;

        Lane(ServerLevel level, FixedReceiverRegistry registry) {
            this.level = level;
            this.registry = registry;
            this.epochs = RegionEpochs.of(level);
            this.sites = SiteRegistry.of(level);
            this.gameTime = level.getGameTime();
        }

        LevelWorldProbe probe() {
            if (probe == null) {
                probe = new LevelWorldProbe(level, RfDataLoader.materials());
            }
            return probe;
        }
    }

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        long started = System.nanoTime();
        MinecraftServer server = event.getServer();
        List<Lane> lanes = null;
        for (ServerLevel level : server.getAllLevels()) {
            FixedReceiverRegistry registry = FixedReceiverRegistry.existing(level);
            if (registry != null && registry.size() > 0) {
                if (lanes == null) {
                    lanes = new ArrayList<>(2);
                }
                lanes.add(new Lane(level, registry));
            }
        }
        if (lanes == null) {
            return;
        }

        RfConfig config = RanCraftConfig.snapshot();
        int interval = Math.max(1, config.evaluationIntervalTicks());
        BandTable bands = RfDataLoader.bands();
        long deadline = started + (long) (RanCraftConfig.fixedReceiverTickBudgetMs() * 1_000_000.0);

        List<FixedReceiverRegistry> registries = new ArrayList<>(lanes.size());
        for (Lane lane : lanes) {
            registries.add(lane.registry);
        }
        final List<Lane> tickLanes = lanes;
        statSetupNanos += System.nanoTime() - started;
        int served = runTick(registries, roundRobin++, server.getTickCount(), interval, System::nanoTime, deadline,
                (index, entry, lag) -> {
                    long serveStarted = System.nanoTime();
                    serve(tickLanes.get(index), entry, lag, config, interval, bands);
                    statServeNanos += System.nanoTime() - serveStarted;
                });

        long elapsed = System.nanoTime() - started;
        statTicks++;
        statNanos += elapsed;
        statMaxTickNanos = Math.max(statMaxTickNanos, elapsed);
        statServed += served;
    }

    /**
     * Evaluates one due receiver, or replays its cached evaluation, then dispatches the sample to its
     * device. A receiver whose chunk is not loaded as FULL (on its way out, or a registration a missed
     * path left behind) is skipped: neither evaluated nor dispatched.
     */
    private static void serve(Lane lane, FixedReceiverRegistry.Entry entry, long lag, RfConfig config, int interval,
                              BandTable bands) {
        ServerLevel level = lane.level;
        long key = entry.key;
        int blockX = BlockPos.getX(key);
        int blockY = BlockPos.getY(key);
        int blockZ = BlockPos.getZ(key);
        if (!level.getChunkSource().hasChunk(blockX >> 4, blockZ >> 4)) {
            statSkipped++;
            return;
        }
        // Receiver position: the block centre (§3B.3). The march skips the receiver's own voxel.
        double x = blockX + 0.5;
        double y = blockY + 0.5;
        double z = blockZ + 0.5;
        FixedReceiverRegistry registry = lane.registry;
        long siteVersion = lane.sites.version();
        long gameTime = lane.gameTime;

        SignalSample sample;
        Cached cached = entry.cached;
        if (canReplay(cached, config.enableSampleCaching(), registry.states.get(key).hasCandidate(),
                lane.epochs, siteVersion)) {
            sample = cached.sample();
            statReplays++;
        } else {
            Collection<CellParams> candidates = lane.sites.near(x, y, z, config.maxEvaluationRangeBlocks());
            ReceiverState previous = registry.states.resume(key, gameTime, staleCandidateGapTicks(interval, lag));
            RfEngine.Evaluation evaluation = RfEngine.evaluate(
                    lane.probe(), x, y, z, candidates, bands, config, gameTime, previous);
            registry.states.put(key, evaluation.state(), gameTime);
            sample = evaluation.sample();
            // No block event can fire between the evaluation and this snapshot (one thread).
            entry.cached = new Cached(sample,
                    lane.epochs.snapshot(evaluation.dependencyBins(RegionEpochs.BIN_SIZE)), siteVersion);
            statEvaluations++;
        }
        dispatch(level, BlockPos.of(key), entry.device, sample, bands, config, gameTime);
    }

    /**
     * Hands the sample to the device with its verdict. A device that throws costs its own dispatch,
     * not the server: a device block in a spawn chunk that crashed the tick would make the world
     * unloadable. The failure is logged (at most every 30 s) and counted.
     */
    static void dispatch(ServerLevel level, BlockPos pos, FixedDevice device, SignalSample sample, BandTable bands,
                         RfConfig config, long tick) {
        try {
            DeviceRequirement.Verdict verdict = device.requirement().check(sample, bands);
            device.onSample(level, pos, new DeviceContext(sample, verdict, bands, config, level, tick));
        } catch (RuntimeException failure) {
            statFailures++;
            long now = System.currentTimeMillis();
            if (now - lastFailureLogMillis >= FAILURE_LOG_INTERVAL_MILLIS) {
                lastFailureLogMillis = now;
                RanCraft.LOGGER.error("RANCraft fixed device at {} in {} failed on its sample ({} failure(s) so far)",
                        pos, level.dimension().location(), statFailures, failure);
            }
        }
    }

    @SubscribeEvent
    public static void onServerStopped(ServerStoppedEvent event) {
        FixedReceiverRegistry.clearAll();
        roundRobin = 0;
        resetStats();
        lastFailureLogMillis = 0L;
    }
}
