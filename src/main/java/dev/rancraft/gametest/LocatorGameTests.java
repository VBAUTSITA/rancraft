package dev.rancraft.gametest;

import dev.rancraft.RanCraft;
import dev.rancraft.RanCraftConfig;
import dev.rancraft.device.EmergencyRecord;
import dev.rancraft.registry.ModAttachments;
import dev.rancraft.rf.LocatorFix;
import dev.rancraft.rf.LocatorParams;
import dev.rancraft.rf.LocatorSolver;
import dev.rancraft.rf.RangeMeasurement;
import dev.rancraft.rf.Ranging;
import dev.rancraft.rf.SurfaceProbe;
import dev.rancraft.world.LevelSurfaceProbe;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.SplittableRandom;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestGenerator;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.TestFunction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.GameType;
import net.neoforged.neoforge.common.NeoForge;
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
 * two paths call.
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
                        TIMEOUT_TICKS, 0L, true, LocatorGameTests::surfaceProbeCost));
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

    /** A fix's worst case: 7 Gauss-Newton runs of at most 15 iterations, one lookup each. */
    private static final int LOOKUPS_PER_WORST_FIX = 7 * 15;

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
