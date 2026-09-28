package dev.rancraft.world;

import dev.rancraft.RanCraft;
import dev.rancraft.RanCraftConfig;
import dev.rancraft.block.AntennaBlockEntity;
import dev.rancraft.data.RfDataLoader;
import dev.rancraft.item.FieldTestMeterItem;
import dev.rancraft.item.LensSettings;
import dev.rancraft.item.RfLensItem;
import dev.rancraft.net.LensLinksPayload;
import dev.rancraft.net.SignalSamplePayload;
import dev.rancraft.rf.Band;
import dev.rancraft.rf.BandTable;
import dev.rancraft.rf.CellParams;
import dev.rancraft.rf.CellSample;
import dev.rancraft.rf.LinkTracer;
import dev.rancraft.rf.PciConflict;
import dev.rancraft.rf.PciPlanner;
import dev.rancraft.rf.ReceiverState;
import dev.rancraft.rf.ReceiverStateStore;
import dev.rancraft.rf.RfConfig;
import dev.rancraft.rf.RfEngine;
import dev.rancraft.rf.SignalSample;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.neoforge.event.level.ChunkEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * Drives evaluation from the receiver side at 1 Hz.
 *
 * <p>Antennas are passive; only players who asked to see the signal cost anything: those holding a
 * Field Test Meter, and those wearing an RF Lens with link rays or the drive-test trail on. Work is
 * staggered across the interval by player id so every player does not land on the same tick.
 *
 * <p><b>One evaluation per player per interval, however many views want it.</b> A lens wearer's
 * link rays come from the <em>same</em> evaluation as the meter reading, so the rays and the HUD can
 * never disagree about which cells are heard or which one serves. Each drawn link costs one extra
 * traced ray ({@link LinkTracer}), and only while links are on. The drive-test trail (RF Vision
 * Step 3a) costs nothing extra either: it is the same {@link SignalSamplePayload} the meter gets,
 * which now carries the point it was evaluated at, sent whenever the meter is held <em>or</em> the
 * worn lens shows the trail. The client logs it; the HUD still draws only while the meter is held.
 *
 * <p>Coverage painting is not driven from here -- it is a much larger, time-sliced job. See
 * {@link CoverageSurveyor}.
 */
@EventBusSubscriber(modid = RanCraft.MOD_ID)
public final class SignalTicker {

    private SignalTicker() {
    }

    /** Below this the player is considered not to have moved. */
    private static final double MOVE_EPSILON_BLOCKS = 0.5;

    private static final long SLOW_EVALUATION_NANOS = 2_000_000L;
    private static final long SLOW_WARN_INTERVAL_MILLIS = 30_000L;

    private static final Map<UUID, Cached> CACHE = new ConcurrentHashMap<>();

    /**
     * Handover state per player. Cleared on logout, dimension change and death -- see
     * {@link #forget(UUID)}. Left uncleared, this map grows for the life of the server.
     */
    private static final ReceiverStateStore<UUID> RECEIVERS = new ReceiverStateStore<>();
    private static final Map<ResourceKey<Level>, AtomicLong> BLOCK_EPOCHS = new ConcurrentHashMap<>();

    private static volatile long lastSlowWarnMillis = 0L;

    /**
     * The last evaluation for one player, replayed while nothing it depends on has changed.
     *
     * <p>It depends on three things: where the player's head is, the blocks around it (the
     * per-dimension block epoch), and the antennas (the site registry version). The last one used
     * to be missing, and reconfiguring an antenna changes no block, so a player standing still
     * kept reading the old tilt until they took a step.
     *
     * @param links           the link rays built from this evaluation, or {@code null} when the
     *                        player did not want any. An entry without links is never replayed to
     *                        a player who now wants them -- see {@link #hasLinksFor}.
     * @param linksBandFilter the lens band filter the links were chosen under.
     * @param linksCap        the {@code lensMaxLinks} they were capped at.
     */
    private record Cached(
            SignalSamplePayload payload,
            LensLinksPayload links,
            String linksBandFilter,
            int linksCap,
            double x, double y, double z,
            long blockEpoch,
            long siteVersion) {

        boolean isCurrent(double eyeX, double eyeY, double eyeZ, long epoch, long sites) {
            double dx = x - eyeX;
            double dy = y - eyeY;
            double dz = z - eyeZ;
            return blockEpoch == epoch
                    && siteVersion == sites
                    && dx * dx + dy * dy + dz * dz < MOVE_EPSILON_BLOCKS * MOVE_EPSILON_BLOCKS;
        }

        boolean hasLinksFor(LensSettings lens, int cap) {
            return links != null && linksCap == cap && linksBandFilter.equals(lens.bandFilter());
        }
    }

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        MinecraftServer server = event.getServer();
        RfConfig config = RanCraftConfig.snapshot();
        int interval = Math.max(1, config.evaluationIntervalTicks());
        int phase = Math.floorMod(server.getTickCount(), interval);

        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            if (Math.floorMod(player.getId(), interval) != phase) {
                continue;
            }
            boolean meter = holdsMeter(player);
            LensSettings lens = wornLensOf(player);
            LensSettings linkLens = lens != null && lens.showLinks() ? lens : null;
            boolean trail = lens != null && lens.showTrail();
            // The sample feeds the meter HUD and the lens's drive-test trail alike; either is reason
            // enough to send it. Still one evaluation: evaluate() builds one sample for both.
            boolean sendSample = meter || trail;
            if (!sendSample && linkLens == null) {
                continue;
            }
            evaluate(player, config, sendSample, linkLens);
        }
    }

    private static boolean holdsMeter(ServerPlayer player) {
        return player.getMainHandItem().getItem() instanceof FieldTestMeterItem
                || player.getOffhandItem().getItem() instanceof FieldTestMeterItem;
    }

    /** The worn lens's settings, or {@code null} when no lens is worn. */
    private static LensSettings wornLensOf(ServerPlayer player) {
        ItemStack lens = RfLensItem.wornBy(player);
        return lens == null ? null : RfLensItem.settingsOf(lens);
    }

    /**
     * @param sendSample true when the player holds a meter (a HUD to feed) or wears a lens showing
     *                   the drive-test trail (a log to feed). The sample is built and cached either
     *                   way, so pulling the meter out replays it at once.
     * @param linkLens   the lens settings when link rays are wanted, otherwise {@code null}.
     */
    private static void evaluate(ServerPlayer player, RfConfig config, boolean sendSample, LensSettings linkLens) {
        ServerLevel level = player.serverLevel();
        SiteRegistry registry = SiteRegistry.of(level);

        Vec3 eye = player.getEyePosition();
        double eyeX = eye.x;
        double eyeY = eye.y;
        double eyeZ = eye.z;
        long epoch = blockEpochOf(level);
        long siteVersion = registry.version();
        int linkCap = linkLens == null ? 0 : Math.min(RanCraftConfig.lensMaxLinks(), LensLinksPayload.MAX_LINKS);

        // Caching is skipped while a handover candidate is armed: replaying a cached payload would
        // freeze the time-to-trigger clock, so a player standing still at a cell boundary would
        // never hand over at all.
        if (config.enableSampleCaching() && !RECEIVERS.get(player.getUUID()).hasCandidate()) {
            Cached cached = CACHE.get(player.getUUID());
            if (cached != null
                    && cached.isCurrent(eyeX, eyeY, eyeZ, epoch, siteVersion)
                    && (linkLens == null || cached.hasLinksFor(linkLens, linkCap))) {
                send(player, sendSample ? cached.payload() : null, linkLens == null ? null : cached.links());
                return;
            }
        }

        Collection<CellParams> candidates =
                registry.near(eyeX, eyeY, eyeZ, config.maxEvaluationRangeBlocks());

        LevelWorldProbe probe = new LevelWorldProbe(level, RfDataLoader.materials());
        BandTable bands = RfDataLoader.bands();
        long gameTime = level.getGameTime();

        UUID receiverKey = player.getUUID();
        ReceiverState previous = RECEIVERS.get(receiverKey);

        long startNanos = System.nanoTime();
        RfEngine.Evaluation evaluation = RfEngine.evaluate(
                probe, eyeX, eyeY, eyeZ, candidates, bands, config, gameTime, previous);
        long elapsedNanos = System.nanoTime() - startNanos;

        RECEIVERS.put(receiverKey, evaluation.state());
        SignalSample sample = evaluation.sample();

        if (elapsedNanos > SLOW_EVALUATION_NANOS) {
            warnSlow(candidates.size(), probe.probes(), elapsedNanos, evaluation.stats());
        }

        // The eye position this evaluation ran at travels with the sample, so the drive-test trail
        // marks the point measured rather than wherever the client is when the packet lands. A
        // cached replay below carries its original point, which isCurrent() keeps within
        // MOVE_EPSILON_BLOCKS of the player.
        SignalSamplePayload payload =
                toPayload(sample, candidates, gameTime, config, evaluation.stats(), eyeX, eyeY, eyeZ);
        LensLinksPayload links = linkLens == null
                ? null
                : traceLinks(level, sample, candidates, eyeX, eyeY, eyeZ, linkLens, linkCap, bands, config, gameTime);

        CACHE.put(player.getUUID(), new Cached(
                payload, links, linkLens == null ? LensSettings.ALL_BANDS : linkLens.bandFilter(), linkCap,
                eyeX, eyeY, eyeZ, epoch, siteVersion));
        send(player, sendSample ? payload : null, links);
    }

    private static void send(ServerPlayer player, SignalSamplePayload sample, LensLinksPayload links) {
        if (sample != null) {
            PacketDistributor.sendToPlayer(player, sample);
        }
        if (links != null) {
            PacketDistributor.sendToPlayer(player, links);
        }
    }

    /**
     * Traces the chosen links from each antenna's radiating centre to the player's eye -- the same
     * endpoints, penetration factor and march limits {@link RfEngine} just used for that cell, so
     * each link's final breakpoint equals the obstruction the meter reports for it.
     *
     * <p>A fresh probe, because the evaluation's probe carries a count that feeds the slow-sample
     * warning, which should measure the engine alone.
     */
    private static LensLinksPayload traceLinks(
            ServerLevel level,
            SignalSample sample,
            Collection<CellParams> candidates,
            double eyeX, double eyeY, double eyeZ,
            LensSettings lens,
            int cap,
            BandTable bands,
            RfConfig config,
            long tick) {

        List<CellSample> chosen = chooseLinks(sample, lens, cap);
        if (chosen.isEmpty()) {
            return LensLinksPayload.empty(tick);
        }

        Map<Long, CellParams> paramsById = new HashMap<>();
        for (CellParams cell : candidates) {
            paramsById.put(cell.cellId(), cell);
        }

        LevelWorldProbe probe = new LevelWorldProbe(level, RfDataLoader.materials());
        List<LensLinksPayload.Link> links = new ArrayList<>(chosen.size());
        for (CellSample cell : chosen) {
            CellParams params = paramsById.get(cell.cellId());
            if (params == null) {
                // Unreachable: the engine only reports cells it was handed.
                continue;
            }
            Band band = bands.getOrFallback(cell.bandId());
            LinkTracer.Trace trace = LinkTracer.trace(
                    probe,
                    params.centerX(), params.centerY(), params.centerZ(),
                    eyeX, eyeY, eyeZ,
                    band.penetrationFactor(),
                    config.maxObstructionDb(),
                    config.maxRaySteps(),
                    LinkTracer.DEFAULT_MAX_BREAKPOINTS);
            links.add(LensLinksPayload.Link.of(cell, cell.cellId() == sample.servingCellId(), trace));
        }
        return new LensLinksPayload(tick, links);
    }

    /**
     * The cells on the lens's band, strongest first, capped.
     *
     * <p>One exception to the plain cut: hysteresis can hold the serving cell below the strongest,
     * so with a small cap it could fall off the end. When it would, it takes the last kept slot.
     * The list stays strongest-first, because the serving cell is weaker than everything it now
     * follows; and the serving line is the one the wearer most needs to see.
     */
    static List<CellSample> chooseLinks(SignalSample sample, LensSettings lens, int cap) {
        if (cap <= 0) {
            return List.of();
        }
        List<CellSample> heard = new ArrayList<>();
        for (CellSample cell : sample.cells()) {
            if (lens.showsBand(cell.bandId())) {
                heard.add(cell);
            }
        }
        if (heard.size() <= cap) {
            return heard;
        }

        List<CellSample> chosen = new ArrayList<>(heard.subList(0, cap));
        for (int i = cap; i < heard.size(); i++) {
            if (heard.get(i).cellId() == sample.servingCellId()) {
                chosen.set(cap - 1, heard.get(i));
                break;
            }
        }
        return chosen;
    }

    /**
     * Adds the display-only extras the engine has no business carrying: the serving band frequency,
     * the worst outstanding PCI conflict on the serving cell, pre-rendered as one line, and the
     * point the evaluation ran at (for the drive-test trail).
     */
    private static SignalSamplePayload toPayload(
            SignalSample sample,
            Collection<CellParams> candidates,
            long tick,
            RfConfig config,
            RfEngine.EvaluationStats stats,
            double rxX, double rxY, double rxZ) {

        Optional<CellSample> serving = sample.serving();
        if (serving.isEmpty()) {
            return SignalSamplePayload.empty(tick, sample.handoverCount(), rxX, rxY, rxZ);
        }

        Band band = RfDataLoader.bands().getOrFallback(serving.get().bandId());
        return SignalSamplePayload.of(
                sample, band.id(), band.frequencyMhz(), config.metersPerBlock(),
                conflictNote(serving.get().cellId(), candidates, config),
                rxX, rxY, rxZ);
    }

    /**
     * The single most severe PCI conflict on the serving cell, as one line. ERROR sorts before
     * WARNING, so a collision is never hidden behind a mod-3 note.
     */
    private static String conflictNote(long cellId, Collection<CellParams> candidates, RfConfig config) {
        List<PciConflict> conflicts =
                PciPlanner.findConflictsFor(cellId, candidates, config.pciParams());
        return conflicts.stream()
                .min(Comparator.comparingInt(conflict -> conflict.severity().ordinal()))
                .map(PciConflict::describe)
                .orElse("");
    }

    private static void warnSlow(
            int cellCount, int probeCount, long elapsedNanos, RfEngine.EvaluationStats stats) {

        long now = System.currentTimeMillis();
        if (now - lastSlowWarnMillis < SLOW_WARN_INTERVAL_MILLIS) {
            return;
        }
        lastSlowWarnMillis = now;
        RanCraft.LOGGER.warn(
                "RANCraft evaluation took {} ms ({} candidate cells, {} voxel probes; "
                        + "{} ray-marched, {} budget-pruned = {}% of in-range cells never marched)",
                String.format("%.2f", elapsedNanos / 1_000_000.0), cellCount, probeCount,
                stats.rayMarched(), stats.prunedByBudget(),
                String.format("%.0f", stats.prunedFraction() * 100.0));
    }

    /**
     * The dimension's block-change counter. Anything derived from its blocks -- a cached sample, a
     * coverage survey -- records this and is stale once it moves. See {@link #bumpBlockEpoch}.
     */
    public static long blockEpochOf(ServerLevel level) {
        return BLOCK_EPOCHS.computeIfAbsent(level.dimension(), key -> new AtomicLong()).get();
    }

    /**
     * Coarser than "within the evaluation radius": any block change anywhere in the dimension
     * invalidates every cached sample in it. Conservative -- it recomputes more often than
     * strictly needed, never less. See NOTES.md.
     */
    private static void bumpBlockEpoch(LevelAccessor levelAccessor) {
        if (levelAccessor instanceof ServerLevel level) {
            BLOCK_EPOCHS.computeIfAbsent(level.dimension(), key -> new AtomicLong()).incrementAndGet();
        }
    }

    @SubscribeEvent
    public static void onBlockBroken(BlockEvent.BreakEvent event) {
        bumpBlockEpoch(event.getLevel());
    }

    @SubscribeEvent
    public static void onBlockPlaced(BlockEvent.EntityPlaceEvent event) {
        bumpBlockEpoch(event.getLevel());
    }

    /**
     * Every antenna type, not just the mast. This used to test for the Signal Mast's block entity
     * only, so sector antennas were never re-registered here -- and, in the unload handler below,
     * never unregistered, so a sector in an unloaded chunk kept transmitting.
     */
    @SubscribeEvent
    public static void onChunkLoad(ChunkEvent.Load event) {
        if (!(event.getLevel() instanceof ServerLevel)) {
            return;
        }
        if (!(event.getChunk() instanceof LevelChunk levelChunk)) {
            return;
        }
        for (BlockEntity blockEntity : levelChunk.getBlockEntities().values()) {
            if (blockEntity instanceof AntennaBlockEntity antenna) {
                antenna.refreshRegistration();
            }
        }
    }

    @SubscribeEvent
    public static void onChunkUnload(ChunkEvent.Unload event) {
        if (!(event.getLevel() instanceof ServerLevel level)) {
            return;
        }
        SiteRegistry registry = SiteRegistry.of(level);
        if (!(event.getChunk() instanceof LevelChunk levelChunk)) {
            return;
        }
        for (BlockEntity blockEntity : levelChunk.getBlockEntities().values()) {
            if (blockEntity instanceof AntennaBlockEntity antenna) {
                registry.unregister(antenna.cellId());
            }
        }
    }

    @SubscribeEvent
    public static void onPlayerLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        forget(event.getEntity().getUUID());
    }

    /**
     * A player who changes dimension is somewhere else entirely, so any armed candidate timer
     * refers to a cell that is now in another world.
     */
    @SubscribeEvent
    public static void onPlayerChangedDimension(PlayerEvent.PlayerChangedDimensionEvent event) {
        forget(event.getEntity().getUUID());
    }

    /** Death teleports the player to their spawn, which is the same problem. */
    @SubscribeEvent
    public static void onPlayerRespawn(PlayerEvent.PlayerRespawnEvent event) {
        forget(event.getEntity().getUUID());
    }

    /** Drops the cached payload, the handover state and any coverage survey for one player. */
    private static void forget(UUID playerId) {
        CACHE.remove(playerId);
        RECEIVERS.clear(playerId);
        CoverageSurveyor.forget(playerId);
    }

    @SubscribeEvent
    public static void onServerStopped(ServerStoppedEvent event) {
        CACHE.clear();
        RECEIVERS.clearAll();
        BLOCK_EPOCHS.clear();
        CoverageSurveyor.clearAll();
        SiteRegistry.clearAll();
    }
}
