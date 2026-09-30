package dev.rancraft.world;

import dev.rancraft.RanCraft;
import dev.rancraft.RanCraftConfig;
import dev.rancraft.block.AntennaBlockEntity;
import dev.rancraft.data.RfDataLoader;
import dev.rancraft.device.DeviceContext;
import dev.rancraft.device.DeviceMemory;
import dev.rancraft.device.SignalDevice;
import dev.rancraft.item.LensSettings;
import dev.rancraft.item.RfLensItem;
import dev.rancraft.net.LensLinksPayload;
import dev.rancraft.net.SignalSamplePayload;
import dev.rancraft.rf.Band;
import dev.rancraft.rf.BandTable;
import dev.rancraft.rf.CellParams;
import dev.rancraft.rf.CellSample;
import dev.rancraft.rf.DeviceRequirement;
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
import java.util.function.Function;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
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
 * <p>Antennas are passive; only players who asked for the signal cost anything: those carrying a
 * {@link SignalDevice} (a Field Test Meter, ...) in a hand or the hotbar, and those wearing an RF
 * Lens with link rays or the drive-test trail on. Work is staggered across the interval by player
 * id so every player does not land on the same tick.
 *
 * <p><b>One evaluation per player per interval, however many views or devices want it.</b> A lens
 * wearer's link rays come from the <em>same</em> evaluation as the meter reading, so the rays and
 * the HUD can never disagree about which cells are heard or which one serves. Each drawn link costs
 * one extra traced ray ({@link LinkTracer}), and only while links are on. The drive-test trail (RF
 * Vision Step 3a) costs nothing extra either: it is the same {@link SignalSamplePayload} the meter
 * gets, which now carries the point it was evaluated at. Devices (Phase 3, §3A.3) cost nothing extra
 * either: the one {@link SignalSample} is dispatched to every carried device after the evaluation
 * ({@link #dispatch}), and devices never compute RF.
 *
 * <p><b>Every evaluation is sent.</b> Whoever is evaluated gets the {@link SignalSamplePayload},
 * whichever view or device asked for the evaluation (see {@link #sendsSample}). The client's
 * drive-test log reads handovers off the server's counter, so an evaluation it never saw would hide
 * a handover and then mark it at the wrong place. The client logs every sample; the HUD still draws
 * only while the meter is held, and the trail only while the lens shows it.
 *
 * <p><b>Phase 3 slice 4 refactor.</b> The hard-coded "is a meter in a hand" question became a scan
 * of carried devices ({@link #carried}). What stayed exactly as it was: the stagger, the lens paths
 * (link rays with their cap, and the trail, which are not devices), the cache and its keys (block
 * epoch, site registry version, 0.5-block move), the cache skip while a handover candidate is armed,
 * the rule that a cached entry without links never starves a wearer who now wants them, and
 * {@link #forget} on logout, dimension change and respawn (which now also forgets device state).
 * What is new: cached replays are dispatched to devices too, and an armed candidate that went stale
 * during an evaluation pause is dropped before selection ({@link #staleCandidateGapTicks}). The
 * regression checklist is in NOTES.md, Phase 3 slice 4.
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
     * @param sample          the evaluation itself, full cell list included. <b>Slice 4:</b> kept so a
     *                        replay can be dispatched to devices, which need more than the four
     *                        cells the payload carries. Server memory only; never sent.
     * @param links           the link rays built from this evaluation, or {@code null} when the
     *                        player did not want any. An entry without links is never replayed to
     *                        a player who now wants them -- see {@link #hasLinksFor}.
     * @param linksBandFilter the lens band filter the links were chosen under.
     * @param linksCap        the {@code lensMaxLinks} they were capped at.
     */
    record Cached(
            SignalSample sample,
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

    /**
     * Whether {@link #evaluate} replays {@code cached} instead of running a new evaluation. Pure
     * (slice 4: extracted unchanged from {@code evaluate}, so {@code SignalTickerCacheTest} can pin
     * every condition). All must hold:
     *
     * <ul>
     *   <li>caching is enabled;
     *   <li><b>no handover candidate is armed.</b> Replaying would freeze the time-to-trigger clock,
     *       so a player standing still at a cell boundary would never hand over at all;
     *   <li>there is an entry, and it is current ({@link Cached#isCurrent}): same block epoch, same
     *       site registry version, and the eye moved less than {@value #MOVE_EPSILON_BLOCKS} blocks;
     *   <li><b>the cache must not starve links:</b> if link rays are wanted now, the entry has links
     *       built under the same band filter and cap ({@link Cached#hasLinksFor}). An entry built for
     *       a meter alone is never replayed to a player who has since switched the lens to links.
     * </ul>
     *
     * @param candidateArmed the <em>stored</em> handover state has a candidate (stale or not).
     * @param linkLens       the lens settings when link rays are wanted, otherwise {@code null}.
     */
    static boolean canReplay(
            Cached cached,
            boolean cachingEnabled,
            boolean candidateArmed,
            double eyeX, double eyeY, double eyeZ,
            long epoch,
            long siteVersion,
            LensSettings linkLens,
            int linkCap) {

        return cachingEnabled
                && !candidateArmed
                && cached != null
                && cached.isCurrent(eyeX, eyeY, eyeZ, epoch, siteVersion)
                && (linkLens == null || cached.hasLinksFor(linkLens, linkCap));
    }

    /**
     * One device a player carries (§3A.3).
     *
     * @param stack the live inventory stack, not a copy.
     * @param held  main hand or offhand. A device in the hotbar runs but draws no HUD.
     */
    record CarriedDevice(ItemStack stack, SignalDevice device, boolean held) {
    }

    /** One carried slot that holds a device, as {@link #scanCarried} finds it, before binding to game types. */
    record Found<S, D>(S stack, D device, boolean held) {
    }

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        MinecraftServer server = event.getServer();
        RfConfig config = RanCraftConfig.snapshot();
        int interval = Math.max(1, config.evaluationIntervalTicks());
        int tickCount = server.getTickCount();

        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            if (!isDue(player.getId(), tickCount, interval)) {
                continue;
            }
            LensSettings lens = wornLensOf(player);
            List<CarriedDevice> devices = carried(player);
            // One question, not two: whoever is evaluated is sent the sample. See sendsSample.
            if (!sendsSample(devices, lens)) {
                continue;
            }
            evaluate(player, config, linkLensOf(lens), devices, interval);
        }
    }

    /**
     * The stagger: a player is looked at on the server ticks whose phase in the interval matches
     * their entity id, so players spread across the interval instead of all landing on one tick.
     * Each player is due exactly once every {@code interval} server ticks, which is what
     * {@link #staleCandidateGapTicks} relies on. Pure (slice 4, extracted unchanged so
     * {@code SignalTickerTest} can pin it).
     *
     * @param interval the evaluation interval, already clamped to at least 1.
     */
    static boolean isDue(int playerId, int tickCount, int interval) {
        return Math.floorMod(playerId, interval) == Math.floorMod(tickCount, interval);
    }

    /** The lens settings when link rays are wanted, otherwise {@code null}. Pure (slice 4). */
    static LensSettings linkLensOf(LensSettings lens) {
        return lens != null && lens.showLinks() ? lens : null;
    }

    /**
     * The lens link cap: {@code lensMaxLinks}, never above what {@link LensLinksPayload} carries;
     * 0 when no link rays are wanted. Part of the cache key ({@link Cached#hasLinksFor}). Pure
     * (slice 4, extracted unchanged).
     */
    static int linkCap(LensSettings linkLens, int configuredMaxLinks) {
        return linkLens == null ? 0 : Math.min(configuredMaxLinks, LensLinksPayload.MAX_LINKS);
    }

    /**
     * Whether this player is evaluated this interval, which is the same as whether they are sent the
     * sample: {@link #evaluate} always sends it. Three things ask for an evaluation: a carried device
     * (in a hand or the hotbar), a worn lens showing the drive-test trail (the log), and a worn lens
     * showing link rays (the rays are built from the same evaluation).
     *
     * <p><b>Slice 4: any carried device, held or not.</b> Before §3A.3 only a meter in a hand
     * counted. Now a device in the hotbar makes its carrier evaluated too (the Network Locator keeps
     * its emergency record in a pocket), and so it must also make them <em>sent</em> the sample, for
     * the reason below. The spec's "the meter sends SignalSamplePayload only when held" is therefore
     * "the meter HUD draws only when held", which {@code SignalHudOverlay} enforces on the client.
     * The sample itself is sent once per evaluation by the ticker, never by a device.
     *
     * <p><b>Why the link rays get the sample too.</b> Until the Phase 3 slice 0 gate review, a lens
     * on the LINKS preset was evaluated but not sent the sample. Its handover state machine kept
     * running while the client's drive-test log saw nothing. The log reads a handover off the
     * server's counter going up between two consecutive samples it received. So the first sample
     * after the wearer went back to the trail, or took the meter out, carried every handover of the
     * LINKS walk at once. The log then drew one false HANDOVER pillar at the switch point (and wrote
     * {@code event=HANDOVER} on that CSV row), and none where the handovers fired. The rule is
     * therefore: <em>every evaluation that can move the handover counter reaches the client</em>. A
     * player who is not evaluated (no device carried, and no lens or one on the ANTENNAS or COVERAGE
     * preset) has a frozen handover state, so the log misses nothing.
     *
     * <p>The extra cost is one {@link SignalSamplePayload} per interval for a player evaluated only
     * for link rays or for a device in the hotbar. Nothing changes on screen: the HUD draws only
     * while the meter is held, and the trail only when shown.
     *
     * <p>Pure and package-private so the rule is pinned by {@code SignalTickerTest}.
     *
     * @param carried the devices the player carries ({@link #carried}), held or not. Only whether
     *                there are any matters.
     * @param lens    the worn lens's settings, or {@code null} when no lens is worn. The band filter
     *                does not matter: a filter that hides every heard cell still has the wearer
     *                evaluated (the rays come back empty) and so still moves the handover counter.
     */
    static boolean sendsSample(List<CarriedDevice> carried, LensSettings lens) {
        return !carried.isEmpty() || (lens != null && (lens.showTrail() || lens.showLinks()));
    }

    /**
     * The longest gap, in game ticks, between two evaluations of one player that still counts as
     * continuous observation of an armed handover candidate. A longer gap drops the candidate before
     * selection ({@link dev.rancraft.rf.CellSelector#expireStaleCandidate}), so the time-to-trigger
     * restarts where evaluation resumed instead of counting the unobserved pause.
     *
     * <p><b>Exactly one interval, with no margin, and why that is safe.</b> A player who keeps
     * carrying a device (or keeps a lens on a preset that needs evaluation) is evaluated on every
     * tick whose stagger phase matches, which recurs every {@code interval} server ticks. While a
     * candidate is armed the cache is skipped, so each of those is a fresh evaluation that records
     * its game time. Game time advances by one per server tick, or not at all while the tick rate
     * manager is frozen ({@code /tick freeze}: checked in {@code MinecraftServer.tickServer} and
     * {@code ServerLevel.tick}), and every dimension reads the overworld's clock. So a continuously
     * observed player's gap is exactly one interval, or shorter ({@link #isDue}; pinned by
     * {@code SignalTickerTest}). Any longer gap means at least one scheduled evaluation did not
     * happen.
     *
     * <p><b>One harmless exception: a live change of {@code evaluationIntervalTicks}.</b> The stagger
     * re-phases, so the first gap after the change can be up to {@code old + new - gcd(old, new)}
     * ticks, which exceeds the new interval unless the old interval divides the new one (20 to 10:
     * up to 20 ticks). An armed candidate is then dropped once and re-armed on that
     * evaluation: the handover can be delayed by at most one time-to-trigger, never made early. Not
     * worth remembering the previous interval for an admin action.
     *
     * <p>A margin would bring the bug back for short pauses. At the defaults (interval 20, TTT 40), a
     * candidate armed at tick T and one skipped evaluation give a gap of 40 at T + 40, which already
     * equals the TTT: a two-interval threshold would fire that handover having observed the
     * neighbour only at T.
     *
     * <p>Config-free: it derives from {@code evaluationIntervalTicks}. A receiver on another cadence
     * (Phase 3's fixed receivers, evaluated round-robin under a time budget) must derive its own.
     */
    static long staleCandidateGapTicks(int interval) {
        return Math.max(1, interval);
    }

    /**
     * The devices this player carries: main hand, offhand, then hotbar slots 0-8, each stack once.
     * Replaces Phase 2's {@code holdsMeter()}. Armour and the rest of the inventory do not count:
     * the lens is not a device, and a device packed away in the backpack is switched off.
     *
     * <p><b>Game abstraction, labelled (NOTES.md, Phase 3 slice 4): a device measures only while
     * carried</b> in a hand or the hotbar. A real UE measures all the time, switched on in a pocket
     * or a bag alike. Here a device anywhere else is off: it is not evaluated, gets no sample, and
     * its state (a Locator's emergency record, for one) stops updating. That is what keeps the
     * server's cost bounded to the receivers someone is using. The drive-test log is therefore only
     * as complete as the evaluations.
     */
    static List<CarriedDevice> carried(ServerPlayer player) {
        Inventory inventory = player.getInventory();
        List<ItemStack> hotbar = new ArrayList<>(Inventory.getSelectionSize());
        for (int slot = 0; slot < Inventory.getSelectionSize(); slot++) {
            hotbar.add(inventory.items.get(slot));
        }
        List<Found<ItemStack, SignalDevice>> found = scanCarried(
                player.getMainHandItem(), player.getOffhandItem(), hotbar, SignalTicker::deviceOf);
        if (found.isEmpty()) {
            return List.of();
        }
        List<CarriedDevice> devices = new ArrayList<>(found.size());
        for (Found<ItemStack, SignalDevice> device : found) {
            devices.add(new CarriedDevice(device.stack(), device.device(), device.held()));
        }
        return devices;
    }

    /**
     * The scan behind {@link #carried}, generic so it is testable without a game.
     *
     * <p><b>The main-hand stack is also a hotbar slot.</b> {@code Player.getMainHandItem()} is
     * {@code Inventory.getSelected()}, which is {@code items.get(selected)}, the very object in
     * hotbar slot {@code selected} (checked in the 1.21.1 sources). Scanning both would dispatch a
     * held device twice, once as held and once as not. So a hotbar slot holding the <em>same
     * object</em> as the main hand (or the offhand) is skipped. Identity, not equality: two separate
     * meters are two devices, and each is dispatched.
     *
     * @return in dispatch order: main hand (held), offhand (held), hotbar left to right (not held).
     *         Slots whose stack is null or not a device ({@code deviceOf} returns null) are left out.
     */
    static <S, D> List<Found<S, D>> scanCarried(
            S mainHand, S offhand, List<? extends S> hotbar, Function<? super S, ? extends D> deviceOf) {

        List<Found<S, D>> found = new ArrayList<>(2);
        addIfDevice(found, mainHand, true, deviceOf);
        addIfDevice(found, offhand, true, deviceOf);
        for (S stack : hotbar) {
            if (stack == mainHand || stack == offhand) {
                continue;
            }
            addIfDevice(found, stack, false, deviceOf);
        }
        return found;
    }

    private static <S, D> void addIfDevice(
            List<Found<S, D>> found, S stack, boolean held, Function<? super S, ? extends D> deviceOf) {
        if (stack == null) {
            return;
        }
        D device = deviceOf.apply(stack);
        if (device != null) {
            found.add(new Found<>(stack, device, held));
        }
    }

    /** The stack's item as a device, or {@code null}. An empty stack is air, which is not a device. */
    private static SignalDevice deviceOf(ItemStack stack) {
        return stack.getItem() instanceof SignalDevice device ? device : null;
    }

    /**
     * Hands the one sample to every carried device, each with its own verdict
     * ({@code device.requirement(stack).check(sample, bands)}). Called after the sample was sent, on
     * a fresh evaluation and on a cached replay alike; devices are idempotent on
     * {@code sample.timestampTick()} (see {@link dev.rancraft.device.ReplayGuard}).
     *
     * <p>Devices never compute RF: this is the only thing they get, and it costs no evaluation.
     *
     * @param tick the game time now, which is later than {@code sample.timestampTick()} on a replay
     *             (equal while game time is frozen by {@code /tick freeze}; see
     *             {@link dev.rancraft.device.ReplayGuard}).
     */
    static void dispatch(
            ServerPlayer player,
            List<CarriedDevice> devices,
            SignalSample sample,
            BandTable bands,
            RfConfig config,
            ServerLevel level,
            long tick) {

        for (CarriedDevice carried : devices) {
            DeviceRequirement.Verdict verdict = carried.device().requirement(carried.stack()).check(sample, bands);
            carried.device().onSample(player, carried.stack(), carried.held(),
                    new DeviceContext(sample, verdict, bands, config, level, tick));
        }
    }

    /** The worn lens's settings, or {@code null} when no lens is worn. */
    private static LensSettings wornLensOf(ServerPlayer player) {
        ItemStack lens = RfLensItem.wornBy(player);
        return lens == null ? null : RfLensItem.settingsOf(lens);
    }

    /**
     * Evaluates one player, or replays their cached evaluation, sends the result, then dispatches it
     * to the carried devices. The sample is sent on both paths, with no way to skip it, because the
     * drive-test log must see every evaluation (see {@link #sendsSample}); and it is dispatched on
     * both paths, so a device sees every interval whether or not the player moved.
     *
     * @param linkLens the lens settings when link rays are wanted, otherwise {@code null}.
     * @param devices  the carried devices, possibly empty (a lens-only player).
     * @param interval the evaluation interval in ticks, which is also the longest gap after which
     *                 an armed handover candidate still counts as observed (see
     *                 {@link #staleCandidateGapTicks}).
     */
    private static void evaluate(
            ServerPlayer player, RfConfig config, LensSettings linkLens, List<CarriedDevice> devices, int interval) {
        ServerLevel level = player.serverLevel();
        SiteRegistry registry = SiteRegistry.of(level);

        Vec3 eye = player.getEyePosition();
        double eyeX = eye.x;
        double eyeY = eye.y;
        double eyeZ = eye.z;
        long epoch = blockEpochOf(level);
        long siteVersion = registry.version();
        int linkCap = linkCap(linkLens, RanCraftConfig.lensMaxLinks());
        UUID receiverKey = player.getUUID();
        BandTable bands = RfDataLoader.bands();

        // Replay the cached evaluation when nothing it depends on changed (canReplay). Any armed
        // candidate in the stored state skips the cache, a stale one included, so the fresh
        // evaluation below can drop it and re-arm it.
        Cached cached = CACHE.get(receiverKey);
        if (canReplay(cached, config.enableSampleCaching(), RECEIVERS.get(receiverKey).hasCandidate(),
                eyeX, eyeY, eyeZ, epoch, siteVersion, linkLens, linkCap)) {
            send(player, cached.payload(), linkLens == null ? null : cached.links());
            // Replays are dispatched too (§3A.3). The sample keeps the tick of the evaluation it
            // replays, which is what devices are idempotent on.
            dispatch(player, devices, cached.sample(), bands, config, level, level.getGameTime());
            return;
        }

        Collection<CellParams> candidates =
                registry.near(eyeX, eyeY, eyeZ, config.maxEvaluationRangeBlocks());

        LevelWorldProbe probe = new LevelWorldProbe(level, RfDataLoader.materials());
        long gameTime = level.getGameTime();

        // A candidate armed before an evaluation pause is dropped here, before selection, so the
        // time-to-trigger restarts where observation resumed. See staleCandidateGapTicks.
        ReceiverState previous = RECEIVERS.resume(receiverKey, gameTime, staleCandidateGapTicks(interval));

        long startNanos = System.nanoTime();
        RfEngine.Evaluation evaluation = RfEngine.evaluate(
                probe, eyeX, eyeY, eyeZ, candidates, bands, config, gameTime, previous);
        long elapsedNanos = System.nanoTime() - startNanos;

        RECEIVERS.put(receiverKey, evaluation.state(), gameTime);
        SignalSample sample = evaluation.sample();

        if (elapsedNanos > SLOW_EVALUATION_NANOS) {
            warnSlow(candidates.size(), probe.probes(), elapsedNanos, evaluation.stats());
        }

        // The eye position this evaluation ran at travels with the sample, so the drive-test trail
        // marks the point measured rather than wherever the client is when the packet lands. A
        // cached replay below carries its original point, which isCurrent() keeps within
        // MOVE_EPSILON_BLOCKS of the player.
        SignalSamplePayload payload = toPayload(sample, candidates, gameTime, config, bands, eyeX, eyeY, eyeZ);
        LensLinksPayload links = linkLens == null
                ? null
                : traceLinks(level, sample, candidates, eyeX, eyeY, eyeZ, linkLens, linkCap, bands, config, gameTime);

        CACHE.put(receiverKey, new Cached(
                sample, payload, links, linkLens == null ? LensSettings.ALL_BANDS : linkLens.bandFilter(), linkCap,
                eyeX, eyeY, eyeZ, epoch, siteVersion));
        send(player, payload, links);
        dispatch(player, devices, sample, bands, config, level, gameTime);
    }

    /** The sample always; the link rays when the lens wants them ({@code links} is null otherwise). */
    private static void send(ServerPlayer player, SignalSamplePayload sample, LensLinksPayload links) {
        PacketDistributor.sendToPlayer(player, sample);
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
     *
     * <p><b>Slice 4:</b> package-private, and handed the band table the evaluation used instead of
     * reading {@code RfDataLoader.bands()} a second time (the same table in practice; now provably).
     * The unused {@code EvaluationStats} parameter is gone. The body is unchanged, and
     * {@code SignalTickerPayloadTest} pins its output byte for byte against the pre-refactor code.
     */
    static SignalSamplePayload toPayload(
            SignalSample sample,
            Collection<CellParams> candidates,
            long tick,
            RfConfig config,
            BandTable bands,
            double rxX, double rxY, double rxZ) {

        Optional<CellSample> serving = sample.serving();
        if (serving.isEmpty()) {
            return SignalSamplePayload.empty(tick, sample.handoverCount(), rxX, rxY, rxZ);
        }

        Band band = bands.getOrFallback(serving.get().bandId());
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

    /**
     * Drops the cached payload, the handover state (with its last evaluation tick), any coverage
     * survey, and every device's per-player state ({@link DeviceMemory}) for one player.
     * Package-private so {@code SignalTickerTest} can check the device part.
     */
    static void forget(UUID playerId) {
        CACHE.remove(playerId);
        RECEIVERS.clear(playerId);
        CoverageSurveyor.forget(playerId);
        DeviceMemory.forget(playerId);
    }

    @SubscribeEvent
    public static void onServerStopped(ServerStoppedEvent event) {
        CACHE.clear();
        RECEIVERS.clearAll();
        DeviceMemory.clearAll();
        BLOCK_EPOCHS.clear();
        CoverageSurveyor.clearAll();
        SiteRegistry.clearAll();
    }
}
