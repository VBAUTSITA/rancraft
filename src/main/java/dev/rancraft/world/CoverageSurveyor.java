package dev.rancraft.world;

import dev.rancraft.RanCraft;
import dev.rancraft.RanCraftConfig;
import dev.rancraft.data.RfDataLoader;
import dev.rancraft.item.LensSettings;
import dev.rancraft.item.RfLensItem;
import dev.rancraft.net.CoverageSurveyPayload;
import dev.rancraft.rf.CellParams;
import dev.rancraft.rf.CoverageSurvey;
import dev.rancraft.rf.RfConfig;
import dev.rancraft.rf.SurfaceProbe;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.Heightmap;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * Runs the best-server surveys behind RF Lens coverage painting (Step 2b), a slice per tick.
 *
 * <p>A survey is one full {@code RfEngine} evaluation per grid point: 2304 of them for the default
 * 48 x 48 grid, tens of milliseconds in all. Done at once that would stall the tick, and it cannot
 * move to a worker thread, because {@link LevelWorldProbe} reads live {@link ServerLevel} block
 * state, which is not thread-safe. So every survey runs <b>on the server thread, time-sliced</b>:
 * each tick spends at most {@link RanCraftConfig#coverageTickBudgetMs()} on surveys, shared by every
 * wearer, and the rest waits for the next tick. The cost is time to first paint, not tick time.
 *
 * <h2>Scheduling</h2>
 * One job per player. The budget is handed out round-robin in batches of {@value #BATCH_POINTS}
 * points, with the clock read between batches, so one tick overshoots its budget by at most one
 * batch. Which job goes first rotates every tick, so with more wearers than budget nobody is always
 * last in line.
 *
 * <h2>When a survey is redone</h2>
 * At once, abandoning any survey in progress, when the old one would be the wrong picture: no job
 * yet, another dimension, another band filter, another grid size, or the wearer has walked more
 * than half the painted span from its centre. After a survey is finished and sent, it is also
 * redone when it has merely gone stale -- the wearer moved more than a quarter span, a block
 * changed in the dimension ({@link SignalTicker#blockEpochOf}), or an antenna was added, removed or
 * reconfigured ({@link SiteRegistry#version()}) -- but no sooner than
 * {@link RanCraftConfig#coverageMinIntervalTicks()} after it was sent. The block epoch is
 * dimension-wide and moves on every placed or broken block, so on a busy server that floor is what
 * stops coverage from being resurveyed back to back. It runs from the send, not the start: a survey
 * that took longer than the interval (many wearers sharing the budget) would otherwise be stale
 * on arrival and redone on the very next tick, forever.
 *
 * <p>The client keeps drawing the last survey it received until the next one lands, so a redo never
 * blanks the painting.
 *
 * <h2>Where there is no painting</h2>
 * <ul>
 *   <li><b>A band filter naming no loaded band</b> is not surveyed: it could only paint NONE, and
 *       the filter is an unbounded item-component string, so it is never echoed back to the client.
 *   <li><b>Columns with no ground</b> -- the End's void, void worlds -- are left as holes rather
 *       than painted as a floor at the bottom of the world.
 *   <li><b>No coverage under a ceiling.</b> In a dimension with a roof (the Nether), the top-down
 *       heightmap finds the bedrock roof, not the floor a player stands on, so a survey would paint
 *       tiles on top of the roof where nobody can see them. Such dimensions are not surveyed at
 *       all. Finding the floor under a roof would need a downward scan per column and a choice of
 *       which cave floor counts; that is not modelled.
 * </ul>
 */
@EventBusSubscriber(modid = RanCraft.MOD_ID)
public final class CoverageSurveyor {

    private CoverageSurveyor() {
    }

    /**
     * Points surveyed between clock checks: small enough to stop close to the budget, large enough
     * that reading the clock is noise next to the work.
     */
    static final int BATCH_POINTS = 16;

    private static final Map<UUID, Job> JOBS = new ConcurrentHashMap<>();

    /** Rotates the first job served each tick. Server thread only. */
    private static int roundRobin;

    /** Why a job was (re)started. Logged at debug, so survey churn can be diagnosed. */
    enum Reason {
        /** No job yet: the wearer just switched coverage on. */
        NO_JOB,
        /** The wearer is in another dimension. */
        DIMENSION,
        /** The lens band filter changed. */
        BAND,
        /** The configured grid size or step changed. */
        GRID,
        /** The wearer is more than half a span from the centre: the painting no longer surrounds them. */
        WALKED_OFF,
        /** Finished, stale: the wearer moved more than a quarter span. */
        MOVED,
        /** Finished, stale: a block changed in the dimension. */
        BLOCKS,
        /** Finished, stale: an antenna was added, removed or reconfigured. */
        SITES
    }

    /**
     * One player's survey and what it was started against.
     *
     * <p>Package-private, with no Minecraft types in its restart logic, so the policy can be tested
     * headless. {@code dimension} is only compared by the tick handler.
     */
    static final class Job {

        final ResourceKey<Level> dimension;
        final CoverageSurvey survey;
        /** Server tick the job started. Diagnostic only: the stale floor runs from {@link #finishedTick}. */
        final long startedTick;
        final long blockEpoch;
        final long siteVersion;

        /** Sent to the client, or given up on after an error. Either way, no more work. */
        boolean finished;
        /** Server tick the job was sent or given up on; meaningful only once {@link #finished}. */
        long finishedTick;
        long spentNanos;

        Job(ResourceKey<Level> dimension, CoverageSurvey survey, long startedTick, long blockEpoch, long siteVersion) {
            this.dimension = dimension;
            this.survey = survey;
            this.startedTick = startedTick;
            this.blockEpoch = blockEpoch;
            this.siteVersion = siteVersion;
        }

        /** Marks the job sent (or given up on) at {@code tick}; the stale-restart floor runs from here. */
        void finish(long tick) {
            finished = true;
            finishedTick = tick;
        }

        /**
         * Why this job must be replaced, or {@code null} to keep it. Dimension is checked by the
         * caller; everything else is here.
         *
         * <p>Distance is horizontal and Euclidean from the grid centre. Euclidean is never smaller
         * than the per-axis distance, so "more than half a span" fires no later than the wearer
         * leaving the painted square.
         *
         * @param x                the wearer's x now.
         * @param z                the wearer's z now.
         * @param blockEpoch       {@link SignalTicker#blockEpochOf} now.
         * @param siteVersion      {@link SiteRegistry#version()} now.
         * @param now              server tick count now.
         * @param minIntervalTicks {@link RanCraftConfig#coverageMinIntervalTicks()}: the least ticks
         *                         between this job being sent (or given up on) and a stale
         *                         restart. Measured from the send, not the start, so a survey
         *                         that ran longer than the interval still stands for a full one.
         */
        Reason restartReason(String bandFilter, int step, int size, double x, double z,
                             long blockEpoch, long siteVersion, long now, int minIntervalTicks) {
            CoverageSurvey.Spec spec = survey.spec();
            if (!spec.bandFilter().equals(bandFilter)) {
                return Reason.BAND;
            }
            if (spec.step() != step || spec.size() != size) {
                return Reason.GRID;
            }
            double moved = Math.hypot(x - spec.centreX(), z - spec.centreZ());
            double span = spec.spanBlocks();
            if (moved > span / 2.0) {
                return Reason.WALKED_OFF;
            }
            // An unfinished survey of the right place is allowed to finish, even if the world has
            // moved on under it: restarting on every block change would never finish on a busy
            // server. Its staleness is caught below once it has been sent.
            if (!finished || now - finishedTick < minIntervalTicks) {
                return null;
            }
            if (moved > span / 4.0) {
                return Reason.MOVED;
            }
            if (blockEpoch != this.blockEpoch) {
                return Reason.BLOCKS;
            }
            if (siteVersion != this.siteVersion) {
                return Reason.SITES;
            }
            return null;
        }
    }

    /** A job with work left this tick, and the wearer it is for. */
    private record Active(ServerPlayer player, Job job) {
    }

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        MinecraftServer server = event.getServer();
        long now = server.getTickCount();

        List<Active> active = new ArrayList<>();
        Set<UUID> wearers = new HashSet<>();
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            LensSettings lens = coverageLensOf(player);
            if (lens == null) {
                continue;
            }
            if (player.serverLevel().dimensionType().hasCeiling()) {
                // Under a roof the heightmap finds the roof, not the floor: no painting here (see
                // the class comment). Not a wearer for this tick, so any old job is dropped below.
                continue;
            }
            UUID id = player.getUUID();
            wearers.add(id);
            Job job = currentJob(player, lens, now);
            if (!job.finished) {
                active.add(new Active(player, job));
            }
        }
        // Anyone not wearing a coverage lens this tick -- took it off, switched the layer off,
        // logged out, went under a ceiling -- loses their job.
        JOBS.keySet().retainAll(wearers);

        if (!active.isEmpty()) {
            runSlice(active, now);
        }
    }

    /**
     * The worn lens's settings when it is painting coverage, otherwise {@code null}.
     *
     * <p>A filter naming no loaded band gets no survey. It could only paint NONE everywhere, and the
     * filter is an item-component string with no length limit: sending it back in a payload is
     * exactly what must not happen to a string the encoder may refuse.
     */
    private static LensSettings coverageLensOf(ServerPlayer player) {
        ItemStack lens = RfLensItem.wornBy(player);
        if (lens == null) {
            return null;
        }
        LensSettings settings = RfLensItem.settingsOf(lens);
        if (!settings.showCoverage()) {
            return null;
        }
        if (!settings.showsAllBands() && RfDataLoader.bands().get(settings.bandFilter()).isEmpty()) {
            return null;
        }
        return settings;
    }

    /** The player's job, restarted first if it no longer fits. */
    private static Job currentJob(ServerPlayer player, LensSettings lens, long now) {
        ServerLevel level = player.serverLevel();
        int step = RanCraftConfig.coverageStepBlocks();
        int size = gridSize();
        long epoch = SignalTicker.blockEpochOf(level);
        long sites = SiteRegistry.of(level).version();

        Job job = JOBS.get(player.getUUID());
        Reason reason;
        if (job == null) {
            reason = Reason.NO_JOB;
        } else if (!job.dimension.equals(level.dimension())) {
            reason = Reason.DIMENSION;
        } else {
            reason = job.restartReason(
                    lens.bandFilter(), step, size, player.getX(), player.getZ(),
                    epoch, sites, now, RanCraftConfig.coverageMinIntervalTicks());
        }
        if (reason == null) {
            return job;
        }

        Job fresh = start(player, level, lens.bandFilter(), step, size, epoch, sites, now);
        JOBS.put(player.getUUID(), fresh);
        if (RanCraft.LOGGER.isDebugEnabled()) {
            RanCraft.LOGGER.debug("RANCraft coverage survey for {} ({}): {} points around ({}, {}), {} candidate cell(s)",
                    player.getGameProfile().getName(), reason, fresh.survey.total(),
                    (long) fresh.survey.spec().centreX(), (long) fresh.survey.spec().centreZ(),
                    fresh.survey.candidates().size());
        }
        return fresh;
    }

    /**
     * Snapshots everything the survey reads apart from the blocks: the network, the bands and the
     * config. An antenna changed mid-survey shows up in the next one ({@link Reason#SITES}).
     *
     * <p>Candidates are every cell within the engine's range of any grid point: the maximum range
     * plus three quarters of the span, which covers the half-diagonal (0.71 span) with a few blocks
     * spare for terrain above or below the wearer's eye.
     */
    private static Job start(ServerPlayer player, ServerLevel level, String bandFilter,
                             int step, int size, long epoch, long sites, long now) {
        RfConfig config = RanCraftConfig.snapshot();
        CoverageSurvey.Spec spec = CoverageSurvey.centredOn(player.getX(), player.getZ(), step, size, bandFilter);
        Collection<CellParams> candidates = SiteRegistry.of(level).near(
                spec.centreX(), player.getEyeY(), spec.centreZ(),
                config.maxEvaluationRangeBlocks() + spec.spanBlocks() * 0.75);
        CoverageSurvey survey = new CoverageSurvey(spec, candidates, RfDataLoader.bands(), config);
        return new Job(level.dimension(), survey, now, epoch, sites);
    }

    /** The configured side, never more than the payload can carry. */
    private static int gridSize() {
        return Math.min(RanCraftConfig.coverageGridSize(), CoverageSurveyPayload.MAX_SIZE);
    }

    /**
     * Spends this tick's budget on the unfinished jobs, one batch each in turn, until the budget is
     * gone or every job is done. The first batch always runs, so every tick with a wearer makes
     * progress however small the budget.
     */
    private static void runSlice(List<Active> active, long now) {
        long budgetNanos = (long) (RanCraftConfig.coverageTickBudgetMs() * 1_000_000.0);
        long deadline = System.nanoTime() + budgetNanos;

        int count = active.size();
        int next = Math.floorMod(roundRobin++, count);
        int unfinished = count;

        // One probe pair per job per tick. LevelWorldProbe carries a mutable cursor, which is fine
        // here: everything below runs on this one thread.
        LevelWorldProbe[] probes = new LevelWorldProbe[count];
        SurfaceProbe[] surfaces = new SurfaceProbe[count];

        while (unfinished > 0 && System.nanoTime() < deadline) {
            Active entry = active.get(next);
            Job job = entry.job();
            if (!job.finished) {
                if (probes[next] == null) {
                    ServerLevel level = entry.player().serverLevel();
                    probes[next] = new LevelWorldProbe(level, RfDataLoader.materials());
                    surfaces[next] = surfaceOf(level);
                }
                runBatch(entry, probes[next], surfaces[next], now);
                if (job.finished) {
                    unfinished--;
                }
            }
            next = (next + 1) % count;
        }
    }

    private static void runBatch(Active entry, LevelWorldProbe probe, SurfaceProbe surface, long now) {
        Job job = entry.job();
        long started = System.nanoTime();
        try {
            job.survey.step(probe, surface, BATCH_POINTS);
            job.spentNanos += System.nanoTime() - started;
            if (job.survey.isComplete()) {
                send(entry.player(), job, now);
            }
        } catch (RuntimeException failure) {
            // A coverage bug should cost the painting, not the server. The job is marked finished,
            // so it is retried only on the ordinary stale-survey triggers, at most once per
            // coverageMinIntervalTicks, rather than every tick.
            job.finish(now);
            RanCraft.LOGGER.error("RANCraft coverage survey for {} failed; painting skipped until it is next redone",
                    entry.player().getGameProfile().getName(), failure);
        }
    }

    private static void send(ServerPlayer player, Job job, long now) {
        CoverageSurvey.Result result = job.survey.result();
        PacketDistributor.sendToPlayer(player, CoverageSurveyPayload.of(result));
        job.finish(now);
        if (RanCraft.LOGGER.isDebugEnabled()) {
            RanCraft.LOGGER.debug("RANCraft coverage survey for {} sent: {} points, {} serving cell(s), "
                            + "{} ms of server time over {} tick(s)",
                    player.getGameProfile().getName(), job.survey.total(), result.palette().size(),
                    String.format("%.2f", job.spentNanos / 1_000_000.0), now - job.startedTick);
        }
    }

    /**
     * Feet level on the top surface: the first free block above the motion-blocking, non-leaf
     * ground, which is what {@code Level.getHeight} returns for a loaded chunk.
     *
     * <p>Reads the chunk through {@link ServerChunkCache#getChunkNow}, which returns only a chunk
     * that has finished loading and never waits. {@code hasChunk} alone is not enough: it checks the
     * chunk's ticket, and {@code getHeight} would then block the server thread until a chunk that is
     * still generating is done. A column that is not ready is {@link SurfaceProbe#UNLOADED} and is
     * left as a hole in the plot; the next survey fills it.
     *
     * <p>{@link SurfaceProbe#UNLOADED} also covers "no ground in this column". An empty column (the
     * End's void, a void world) has a heightmap at the bottom of the world, which would put a
     * receiver -- and a painted tile -- on a floor that is not there. It is left as a hole too.
     */
    private static SurfaceProbe surfaceOf(ServerLevel level) {
        ServerChunkCache chunks = level.getChunkSource();
        int minY = level.getMinBuildHeight();
        return (x, z) -> {
            LevelChunk chunk = chunks.getChunkNow(x >> 4, z >> 4);
            if (chunk == null) {
                return SurfaceProbe.UNLOADED;
            }
            int feet = chunk.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x & 15, z & 15) + 1;
            // The heightmap of an empty column is the bottom of the world: no ground to stand a
            // receiver on, so leave a hole rather than a fake floor.
            return feet <= minY ? SurfaceProbe.UNLOADED : feet;
        };
    }

    /** Drops a player's survey. Called on logout, dimension change and respawn. */
    public static void forget(UUID playerId) {
        JOBS.remove(playerId);
    }

    /** Called on server stop so a second world load in the same JVM starts clean. */
    public static void clearAll() {
        JOBS.clear();
        roundRobin = 0;
    }
}
