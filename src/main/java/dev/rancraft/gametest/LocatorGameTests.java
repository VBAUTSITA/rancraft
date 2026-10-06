package dev.rancraft.gametest;

import com.mojang.authlib.GameProfile;
import dev.rancraft.RanCraft;
import dev.rancraft.RanCraftConfig;
import dev.rancraft.data.RfDataLoader;
import dev.rancraft.device.DeviceContext;
import dev.rancraft.device.DeviceMemory;
import dev.rancraft.device.EmergencyRecord;
import dev.rancraft.device.LocatorTracker;
import dev.rancraft.device.NetworkLocator;
import dev.rancraft.registry.ModAttachments;
import dev.rancraft.registry.ModItems;
import dev.rancraft.rf.BandTable;
import dev.rancraft.rf.CellSample;
import dev.rancraft.rf.DeviceRequirement;
import dev.rancraft.rf.LocatorFix;
import dev.rancraft.rf.LocatorParams;
import dev.rancraft.rf.LocatorSolver;
import dev.rancraft.rf.RangeMeasurement;
import dev.rancraft.rf.Ranging;
import dev.rancraft.rf.RfConfig;
import dev.rancraft.rf.ServiceLevel;
import dev.rancraft.rf.SignalSample;
import dev.rancraft.rf.SurfaceProbe;
import dev.rancraft.world.LevelSurfaceProbe;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.SplittableRandom;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestGenerator;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.TestFunction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.registries.NeoForgeRegistries;

/**
 * Runtime checks of the Network Locator's game side (Phase 3 slice 5, §3A.6), the parts a unit test
 * cannot reach because they live in NeoForge or in a live level.
 *
 * <ul>
 *   <li><b>{@code emergency_record_survives_death}</b>: the emergency record is a registered,
 *       serialised, {@code copyOnDeath} attachment; a {@link LivingDeathEvent} posted on the real
 *       event bus reaches {@code NetworkLocator.onLivingDeath}, which freezes the last FIX (the
 *       <em>estimate</em>, never the player's position); NeoForge's own {@code PlayerEvent.Clone}
 *       subscriber then copies it to the respawned player. A fix older than
 *       {@code locatorEmergencyMaxAgeTicks} freezes nothing, and nothing is written.</li>
 *   <li><b>{@code dead_player_locator_is_inert}</b> (Phase 3A review, round 1): a dead player's
 *       Locator does nothing. {@code NetworkLocator.onSample} on a player whose health is 0 stores no
 *       reading and stamps no last fix (which the clone would otherwise carry into the next life);
 *       the same call alive does both.</li>
 *   <li><b>{@code surface_probe_cost}</b>: the altitude-aiding ground lookups in a real level
 *       ({@link LevelSurfaceProbe}), which slice 3 could only time against a synthetic probe, and a
 *       whole 8-cell solve on them. The numbers are logged ("RANCraft locator cost") and recorded in
 *       NOTES.md. It fails only if a solve averages over the 1 ms evaluation budget.</li>
 * </ul>
 *
 * <p>Test-harness abstraction, stated plainly: the players are vanilla's mock {@link Player}
 * ({@link GameTestHelper#makeMockPlayer}), never on the server's player list, so the ticker does
 * not evaluate them (a mock {@code ServerPlayer} cannot receive the mod's payloads; see PHASE_3.md).
 * The death is the event, not {@code Player.die}, and the respawn is the clone event
 * {@code ServerPlayer.restoreFrom} fires, not a real respawn. What runs is exactly the code those
 * two paths call. {@code dead_player_locator_is_inert} needs a {@code ServerPlayer} (the Locator's
 * server step takes one), so it builds a NeoForge {@link FakePlayer} directly: not on the player list
 * either, so the ticker never evaluates it; its connection sends nothing; built with its own random
 * profile, not through {@code FakePlayerFactory}, so no cached shared fake player is altered. The
 * sample is hand-built, as the ticker would hand it over.
 */
@GameTestHolder(RanCraft.MOD_ID)
public final class LocatorGameTests {

    private static final String BATCH = "rancraft_locator";
    private static final int TIMEOUT_TICKS = 100;

    private LocatorGameTests() {
    }

    @GameTestGenerator
    public static Collection<TestFunction> locatorTests() {
        String prefix = LocatorGameTests.class.getSimpleName().toLowerCase(Locale.ROOT) + ".";
        return List.of(
                new TestFunction(BATCH, prefix + "emergency_record_survives_death", HarvestGameTests.EMPTY_TEMPLATE,
                        TIMEOUT_TICKS, 0L, true, LocatorGameTests::emergencyRecordSurvivesDeath),
                new TestFunction(BATCH, prefix + "surface_probe_cost", HarvestGameTests.EMPTY_TEMPLATE,
                        TIMEOUT_TICKS, 0L, true, LocatorGameTests::surfaceProbeCost),
                new TestFunction(BATCH, prefix + "dead_player_locator_is_inert", HarvestGameTests.EMPTY_TEMPLATE,
                        TIMEOUT_TICKS, 0L, true, LocatorGameTests::deadPlayerLocatorIsInert));
    }

    // ---- a dead player's Locator ------------------------------------------------------------------

    /**
     * NOTES.md, slice 5, decision 8: the ticker evaluates every player on the list, one on the death
     * screen included, and with {@code keepInventory} the dead entity still carries the Locator.
     * Without the {@code isAlive()} guard in {@code NetworkLocator.onSample} it would stamp a new last
     * fix into the record death had just cleared, and the clone would carry it into the next life.
     */
    private static void deadPlayerLocatorIsInert(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        long now = level.getGameTime();
        BlockPos origin = helper.absolutePos(BlockPos.ZERO);
        SurfaceProbe ground = LevelSurfaceProbe.forLocator(level);
        int feet = ground.surfaceY(origin.getX(), origin.getZ());
        if (feet == SurfaceProbe.UNLOADED) {
            helper.fail("no ground under the test at " + origin);
            return;
        }

        // Three well-spread band_900 masts round a receiver standing at the test origin, as the
        // ticker would hand them over (true distances; the Locator quantises them itself).
        double rxX = origin.getX() + 0.5;
        double rxY = feet + LocatorSolver.RECEIVER_EYE_HEIGHT;
        double rxZ = origin.getZ() + 0.5;
        int[][] sites = {{0, 40}, {-35, -20}, {35, -20}};
        List<CellSample> cells = new ArrayList<>();
        for (int i = 0; i < sites.length; i++) {
            int x = origin.getX() + sites[i][0];
            int y = feet + 20;
            int z = origin.getZ() + sites[i][1];
            double dx = x + 0.5 - rxX;
            double dy = y + 0.5 - rxY;
            double dz = z + 0.5 - rxZ;
            cells.add(new CellSample(7_000L + i, x, y, z, -70.0, Math.sqrt(dx * dx + dy * dy + dz * dz), 90.0, 0.0,
                    "band_900", i, 6.0, 0.0, 0.0));
        }
        SignalSample sample = new SignalSample(cells, now, cells.get(0).cellId(), 12.0, -95.0, -104.0,
                ServiceLevel.GOOD, 0);
        BandTable bands = RfDataLoader.bands();
        RfConfig config = RanCraftConfig.snapshot();
        LocatorFix expected = LocatorTracker.update(null, sample, bands, config, ground, now).fix();
        if (!(expected instanceof LocatorFix.Fix)) {
            helper.fail("fixture: three well-spread masts gave " + expected + ", not a FIX");
            return;
        }
        DeviceContext ctx = new DeviceContext(sample, DeviceRequirement.Verdict.OK, bands, config, level, now);
        ItemStack locator = new ItemStack(ModItems.NETWORK_LOCATOR.get());

        FakePlayer player = new FakePlayer(level, new GameProfile(UUID.randomUUID(), "rancraft_dead_locator"));
        UUID id = player.getUUID();
        try {
            player.setHealth(0.0F);
            if (player.isAlive()) {
                helper.fail("fixture: a player with 0 health is still alive");
                return;
            }
            NetworkLocator.onSample(player, locator, false, ctx);
            if (player.hasData(ModAttachments.LOCATOR_EMERGENCY) || NetworkLocator.hasReading(id)) {
                helper.fail("the dead player's Locator acted: record "
                        + player.getExistingData(ModAttachments.LOCATOR_EMERGENCY)
                        + ", reading kept " + NetworkLocator.hasReading(id));
                return;
            }

            // Alive, the same call stores the reading and stamps the FIX: the guard is what stopped it.
            player.setHealth(20.0F);
            NetworkLocator.onSample(player, locator, false, ctx);
            Optional<EmergencyRecord> record = player.getExistingData(ModAttachments.LOCATOR_EMERGENCY);
            if (!NetworkLocator.hasReading(id) || record.isEmpty() || record.get().lastFix().isEmpty()) {
                helper.fail("alive, the Locator did nothing: record " + record + ", reading kept "
                        + NetworkLocator.hasReading(id));
                return;
            }
            helper.succeed();
        } finally {
            DeviceMemory.forget(id);
        }
    }

    // ---- the emergency record ---------------------------------------------------------------------

    private static void emergencyRecordSurvivesDeath(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        long now = level.getGameTime();
        long maxAge = RanCraftConfig.locatorEmergencyMaxAgeTicks();
        if (maxAge < 2) {
            helper.fail("needs locatorEmergencyMaxAgeTicks >= 2, found " + maxAge);
            return;
        }
        ResourceLocation registered = NeoForgeRegistries.ATTACHMENT_TYPES.getKey(ModAttachments.LOCATOR_EMERGENCY.get());
        if (registered == null || !registered.equals(ResourceLocation.fromNamespaceAndPath(RanCraft.MOD_ID, "locator_emergency"))) {
            helper.fail("the emergency record's attachment type is registered as " + registered);
            return;
        }
        String key = registered.toString();

        // The mock stands at the world origin; the estimate the Locator reported is elsewhere.
        Player dying = helper.makeMockPlayer(GameType.SURVIVAL);
        EmergencyRecord.Stamp estimate = new EmergencyRecord.Stamp(level.dimension().location().toString(),
                120.5, 71.62, -40.25, 9.0, now - maxAge / 2);
        dying.setData(ModAttachments.LOCATOR_EMERGENCY, EmergencyRecord.EMPTY.withFix(estimate));

        CompoundTag saved = dying.serializeAttachments(level.registryAccess());
        if (saved == null || !saved.contains(key)) {
            helper.fail("the record is not written with the player (no '" + key + "' in " + saved + ")");
            return;
        }
        Optional<EmergencyRecord> reread = EmergencyRecord.CODEC.parse(NbtOps.INSTANCE, saved.get(key)).result();
        if (!reread.equals(dying.getExistingData(ModAttachments.LOCATOR_EMERGENCY))) {
            helper.fail("the saved record reads back as " + reread);
            return;
        }

        if (NeoForge.EVENT_BUS.post(new LivingDeathEvent(dying, level.damageSources().generic())).isCanceled()) {
            helper.fail("something cancelled the death event");
            return;
        }
        EmergencyRecord afterDeath = dying.getExistingData(ModAttachments.LOCATOR_EMERGENCY).orElse(null);
        if (afterDeath == null || afterDeath.beforeDeath().isEmpty()) {
            helper.fail("death did not freeze a fix " + (maxAge / 2) + " ticks old: " + afterDeath);
            return;
        }
        EmergencyRecord.Frozen frozen = afterDeath.beforeDeath().get();
        if (!frozen.fix().equals(estimate) || frozen.deathTick() != now || afterDeath.lastFix().isPresent()) {
            helper.fail("froze " + afterDeath + ", expected the estimate " + estimate + " at " + now
                    + " and no last fix");
            return;
        }

        Player respawned = helper.makeMockPlayer(GameType.SURVIVAL);
        NeoForge.EVENT_BUS.post(new PlayerEvent.Clone(respawned, dying, true));
        Optional<EmergencyRecord> copied = respawned.getExistingData(ModAttachments.LOCATOR_EMERGENCY);
        if (!copied.equals(Optional.of(afterDeath))) {
            helper.fail("the respawned player got " + copied + ", expected " + afterDeath + " (copyOnDeath)");
            return;
        }

        // Too old: nothing frozen, the attachment removed, nothing written or copied.
        Player late = helper.makeMockPlayer(GameType.SURVIVAL);
        late.setData(ModAttachments.LOCATOR_EMERGENCY, EmergencyRecord.EMPTY.withFix(
                new EmergencyRecord.Stamp(estimate.dimension(), 1.0, 70.0, 1.0, 5.0, now - maxAge)));
        NeoForge.EVENT_BUS.post(new LivingDeathEvent(late, level.damageSources().generic()));
        if (late.hasData(ModAttachments.LOCATOR_EMERGENCY)) {
            helper.fail("a fix exactly " + maxAge + " ticks old was kept: "
                    + late.getExistingData(ModAttachments.LOCATOR_EMERGENCY));
            return;
        }
        CompoundTag lateSaved = late.serializeAttachments(level.registryAccess());
        if (lateSaved != null && lateSaved.contains(key)) {
            helper.fail("an empty record was still written: " + lateSaved);
            return;
        }
        Player lateRespawned = helper.makeMockPlayer(GameType.SURVIVAL);
        NeoForge.EVENT_BUS.post(new PlayerEvent.Clone(lateRespawned, late, true));
        if (lateRespawned.hasData(ModAttachments.LOCATOR_EMERGENCY)) {
            helper.fail("the next life inherited " + lateRespawned.getExistingData(ModAttachments.LOCATOR_EMERGENCY));
            return;
        }
        helper.succeed();
    }

    // ---- altitude aiding in a live level ---------------------------------------------------------

    /**
     * A fix's worst case: 8 Gauss-Newton runs (the centroid, at most 6 extra starts and, since row 16d,
     * the mirror run), each one lookup per iteration (at most {@link LocatorSolver#MAX_ITERATIONS})
     * plus one at its end point for the residual, and one more under the centroid to cross the circles
     * for the extra starts: 8 x 16 + 1 = 129, the bound {@code LocatorTrackerTest} asserts. (Slice 5
     * recorded 7 x 15 = 105, an undercount found by its gate review; 113 until row 16d.)
     */
    private static final int LOOKUPS_PER_WORST_FIX = 8 * (LocatorSolver.MAX_ITERATIONS + 1) + 1;

    private static void surfaceProbeCost(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos origin = helper.absolutePos(BlockPos.ZERO);
        SurfaceProbe ground = LevelSurfaceProbe.forLocator(level);
        int feet = ground.surfaceY(origin.getX(), origin.getZ());
        if (feet == SurfaceProbe.UNLOADED) {
            helper.fail("no ground under the test at " + origin);
            return;
        }

        // Columns the solver would read near an estimate: within 24 blocks of the receiver.
        int[] xs = new int[LOOKUPS_PER_WORST_FIX];
        int[] zs = new int[LOOKUPS_PER_WORST_FIX];
        SplittableRandom random = new SplittableRandom(5L);
        for (int i = 0; i < xs.length; i++) {
            xs[i] = origin.getX() + random.nextInt(-24, 25);
            zs[i] = origin.getZ() + random.nextInt(-24, 25);
        }
        long sink = 0;
        int loaded = 0;
        for (int i = 0; i < xs.length; i++) {
            if (ground.surfaceY(xs[i], zs[i]) != SurfaceProbe.UNLOADED) {
                loaded++;
            }
        }
        for (int warm = 0; warm < 2_000; warm++) {
            for (int i = 0; i < xs.length; i++) {
                sink += ground.surfaceY(xs[i], zs[i]);
            }
        }
        int reps = 2_000;
        long start = System.nanoTime();
        for (int rep = 0; rep < reps; rep++) {
            for (int i = 0; i < xs.length; i++) {
                sink += ground.surfaceY(xs[i], zs[i]);
            }
        }
        double nsPerLookup = (System.nanoTime() - start) / (double) (reps * xs.length);

        // A whole solve on the live ground: 8 band_1800 cells round a receiver standing on it.
        double rxX = origin.getX() + 0.5;
        double rxZ = origin.getZ() + 0.5;
        double rxY = feet + LocatorSolver.RECEIVER_EYE_HEIGHT;
        double resolution = Ranging.resolutionBlocks(20.0, 1.0);
        int[][] sites = {{60, 20}, {-50, 45}, {-30, -70}, {80, -60}, {-90, -10}, {10, 95}, {40, 70}, {-70, -60}};
        List<RangeMeasurement> ranges = new ArrayList<>();
        for (int i = 0; i < sites.length; i++) {
            double cx = rxX + sites[i][0];
            double cy = rxY + 30.0;
            double cz = rxZ + sites[i][1];
            double distance = Math.sqrt(sites[i][0] * sites[i][0] + 30.0 * 30.0 + sites[i][1] * sites[i][1]);
            ranges.add(new RangeMeasurement(i, cx, cy, cz, Ranging.quantise(distance, resolution),
                    Ranging.sigmaBlocks(resolution), "band_1800", 0.0));
        }
        int[] counted = {0};
        SurfaceProbe counting = (x, z) -> {
            counted[0]++;
            return ground.surfaceY(x, z);
        };
        LocatorFix fix = LocatorSolver.solve(ranges, counting, null, LocatorParams.DEFAULTS);
        int lookupsPerSolve = counted[0];
        if (!(fix instanceof LocatorFix.Fix position)) {
            helper.fail("8 well-spread cells gave " + fix + ", not a FIX");
            return;
        }
        for (int warm = 0; warm < 2_000; warm++) {
            sink += LocatorSolver.solve(ranges, ground, null, LocatorParams.DEFAULTS).hashCode();
        }
        int solves = 2_000;
        start = System.nanoTime();
        for (int rep = 0; rep < solves; rep++) {
            sink += LocatorSolver.solve(ranges, ground, null, LocatorParams.DEFAULTS).hashCode();
        }
        double microsPerSolve = (System.nanoTime() - start) / 1_000.0 / solves;
        double error = Math.hypot(position.x() - rxX, position.z() - rxZ);

        RanCraft.LOGGER.info(String.format(Locale.ROOT,
                "RANCraft locator cost: %.1f ns per ground lookup (%d/%d columns loaded); worst-case fix %d lookups"
                        + " = %.2f us; one 8-cell band_1800 solve on live ground: %d lookups, %.1f us"
                        + " (FIX %.2f blocks from the truth, +/- %.2f, HDOP %.2f) [%d]",
                nsPerLookup, loaded, xs.length, LOOKUPS_PER_WORST_FIX, nsPerLookup * LOOKUPS_PER_WORST_FIX / 1_000.0,
                lookupsPerSolve, microsPerSolve, error, position.errorBlocks(), position.hdop(), sink & 1));
        if (microsPerSolve > 1_000.0) {
            helper.fail(String.format(Locale.ROOT, "a solve costs %.1f us, over the 1 ms evaluation budget",
                    microsPerSolve));
            return;
        }
        helper.succeed();
    }
}
