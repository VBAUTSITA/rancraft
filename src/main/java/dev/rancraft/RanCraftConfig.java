package dev.rancraft;

import dev.rancraft.net.CoverageSurveyPayload;
import dev.rancraft.net.LensLinksPayload;
import dev.rancraft.net.LocatorFixPayload;
import dev.rancraft.rf.DriveTestLog;
import dev.rancraft.rf.LocatorParams;
import dev.rancraft.rf.RfConfig;
import dev.rancraft.rf.RayMarcher;
import net.neoforged.neoforge.common.ModConfigSpec;

/**
 * COMMON config. Read on the server; {@link #snapshot()} converts it into the Minecraft-free
 * {@link RfConfig} that the engine actually consumes.
 */
public final class RanCraftConfig {

    private RanCraftConfig() {
    }

    private static final ModConfigSpec.Builder BUILDER = new ModConfigSpec.Builder();

    public static final ModConfigSpec.DoubleValue METERS_PER_BLOCK = BUILDER
            .comment("Real-world metres represented by one block. Scales all path loss.")
            .defineInRange("metersPerBlock", 1.0, 0.01, 1000.0);

    public static final ModConfigSpec.DoubleValue MAX_EVALUATION_RANGE_BLOCKS = BUILDER
            .comment("Cells further than this are skipped before any ray marching.",
                    "Raised from 600 to 1400 in Phase 2. At 600 this cap, not the -105 dBm floor,",
                    "bounded band_700, band_900 and band_1800 alike, so three of the four bands had",
                    "identical range. At 1400 band_1800 (701 blocks) and band_3500 (261) are limited",
                    "by physics; the two low bands still hit the cap. Raise to ~2900 to differentiate",
                    "all four, at the cost of larger registry queries. See NOTES.md.")
            .defineInRange("maxEvaluationRangeBlocks", 1400.0, 16.0, 4096.0);

    public static final ModConfigSpec.IntValue MAX_CELLS_EVALUATED = BUILDER
            .comment("Upper bound on cells ray-marched per evaluation, best-prospect first.",
                    "Raised from 8 in Phase 2: interference needs neighbours to be interfering.")
            .defineInRange("maxCellsEvaluated", 12, 1, 64);

    public static final ModConfigSpec.DoubleValue MAX_OBSTRUCTION_DB = BUILDER
            .comment("Accumulated obstruction at which the ray march gives up and calls the link dead.")
            .defineInRange("maxObstructionDb", 120.0, 10.0, 1000.0);

    public static final ModConfigSpec.IntValue MAX_RAY_STEPS = BUILDER
            .comment("Hard cap on voxels visited in one march.")
            .defineInRange("maxRaySteps", RayMarcher.DEFAULT_MAX_STEPS, 16, 16384);

    public static final ModConfigSpec.IntValue EVALUATION_INTERVAL_TICKS = BUILDER
            .comment("Ticks between evaluations for a given player. 20 = 1 Hz.")
            .defineInRange("evaluationIntervalTicks", 20, 1, 200);

    public static final ModConfigSpec.BooleanValue REQUIRE_REDSTONE = BUILDER
            .comment("When true a Signal Mast only transmits while receiving a redstone signal.")
            .define("requireRedstone", false);

    public static final ModConfigSpec.BooleanValue ENABLE_SAMPLE_CACHING = BUILDER
            .comment("Reuse the previous sample when the player has barely moved and nothing nearby changed.",
                    "Turn off while debugging propagation.")
            .define("enableSampleCaching", true);

    // ---- Phase 2: antenna pattern -----------------------------------------------------------

    public static final ModConfigSpec.DoubleValue ANTENNA_FRONT_TO_BACK_DB = BUILDER
            .comment("A_m: how far down the back of a sector antenna is from its boresight.",
                    "Also caps the combined horizontal+vertical loss.")
            .defineInRange("antennaFrontToBackDb", 30.0, 3.0, 60.0);

    public static final ModConfigSpec.DoubleValue ANTENNA_SIDELOBE_FLOOR_DB = BUILDER
            .comment("SLA_v: the floor the vertical pattern saturates at.")
            .defineInRange("antennaSidelobeFloorDb", 30.0, 3.0, 60.0);

    // ---- Phase 2: interference ---------------------------------------------------------------

    public static final ModConfigSpec.DoubleValue ADJACENT_CHANNEL_REJECTION_DB = BUILDER
            .comment("How much a receiver suppresses a neighbour transmitting on a different band.",
                    "Higher means other-band sites matter less.")
            .defineInRange("adjacentChannelRejectionDb", 30.0, 0.0, 90.0);

    public static final ModConfigSpec.DoubleValue PCI_MOD3_PENALTY_FACTOR = BUILDER
            .comment("Linear power multiplier on a co-channel interferer sharing pci % 3.",
                    "2.0 = +3 dB. GAME ABSTRACTION: real mod-3 conflicts damage channel estimation,",
                    "not received power. See SinrCalculator for the full note.")
            .defineInRange("pciMod3PenaltyFactor", 2.0, 1.0, 10.0);

    public static final ModConfigSpec.BooleanValue ENABLE_PCI_MOD3_PENALTY = BUILDER
            .comment("Turn the mod-3 penalty off to see how much of a reading it accounts for.")
            .define("enablePciMod3Penalty", true);

    // ---- Phase 2: handover -------------------------------------------------------------------

    public static final ModConfigSpec.DoubleValue HANDOVER_HYSTERESIS_DB = BUILDER
            .comment("A3 offset: how much better a neighbour must be before it is even a candidate.")
            .defineInRange("handoverHysteresisDb", 3.0, 0.0, 20.0);

    public static final ModConfigSpec.IntValue TIME_TO_TRIGGER_TICKS = BUILDER
            .comment("How long that must hold before the handover fires. 40 = 2 s.")
            .defineInRange("timeToTriggerTicks", 40, 0, 600);

    // ---- Phase 2: PCI planning ---------------------------------------------------------------

    public static final ModConfigSpec.DoubleValue PCI_PLANNING_RADIUS = BUILDER
            .comment("Same-band cells within this range must not share a PCI.")
            .defineInRange("pciPlanningRadius", 500.0, 16.0, 8192.0);

    public static final ModConfigSpec.DoubleValue PCI_MOD3_RADIUS = BUILDER
            .comment("Same-band cells within this range should not share pci % 3.")
            .defineInRange("pciMod3Radius", 250.0, 16.0, 8192.0);

    // ---- RF Lens, Step 2 ---------------------------------------------------------------------
    // Server cost controls for the lens, not engine parameters, so they stay out of RfConfig.
    // Upper bounds are the wire caps, so a config value can never ask for more than a payload holds.

    public static final ModConfigSpec.IntValue LENS_MAX_LINKS = BUILDER
            .comment("Most link rays sent to a lens wearer per sample, strongest cells first.",
                    "Each one costs the server an extra traced ray per sample.")
            .defineInRange("lensMaxLinks", 8, 1, LensLinksPayload.MAX_LINKS);

    public static final ModConfigSpec.IntValue COVERAGE_GRID_SIZE = BUILDER
            .comment("Coverage painting: grid points per side. Each point is one full evaluation,",
                    "so a survey's cost grows with the square of this. 48 x 48 = 2304 points.")
            .defineInRange("coverageGridSize", 48, 8, CoverageSurveyPayload.MAX_SIZE);

    public static final ModConfigSpec.IntValue COVERAGE_STEP_BLOCKS = BUILDER
            .comment("Coverage painting: blocks between grid points. The painted square is",
                    "coverageGridSize * coverageStepBlocks blocks across; 48 * 4 = 192.")
            .defineInRange("coverageStepBlocks", 4, 1, 16);

    public static final ModConfigSpec.DoubleValue COVERAGE_TICK_BUDGET_MS = BUILDER
            .comment("Server-thread time per tick spent on coverage surveys, shared by every lens",
                    "wearer. Surveys cannot run off-thread (they read live block state), so this",
                    "is time taken out of the tick: 2 ms is 4% of a 50 ms tick.")
            .defineInRange("coverageTickBudgetMs", 2.0, 0.1, 20.0);

    public static final ModConfigSpec.IntValue COVERAGE_MIN_INTERVAL_TICKS = BUILDER
            .comment("Least ticks between a finished survey and a fresh one triggered by a block",
                    "change, an antenna change or a short walk. Walking off the painted area",
                    "restarts at once regardless. 100 = 5 s.")
            .defineInRange("coverageMinIntervalTicks", 100, 0, 6000);

    // ---- Phase 3: Network Locator ------------------------------------------------------------
    // The first four, and locatorSiteMergeBlocks (Phase 3A review), are read by rf code (Ranging,
    // LocatorSolver), so they cross into RfConfig in snapshot(). The emergency-record age is gameplay
    // for the game-side record only and stays out of RfConfig, as the lens settings do. See NOTES.md,
    // Phase 3 slice 3.

    public static final ModConfigSpec.DoubleValue LOCATOR_MIN_RSRP_DBM = BUILDER
            .comment("Network Locator: a cell is used for ranging only at or above this RSRP.",
                    "Weak cells are usually heard through obstruction, so their ranges are the most",
                    "biased. The engine's own floor is -105 dBm; cells below it are never heard.")
            .defineInRange("locatorMinRsrpDbm", LocatorParams.DEFAULT_MIN_RSRP_DBM, -140.0, -40.0);

    public static final ModConfigSpec.IntValue LOCATOR_MAX_CELLS = BUILDER
            .comment("Network Locator: most cells (strongest first) that go into one position fix.",
                    "At least 3 are needed for a fix. Capped at 8, the rings the Locator can draw.")
            .defineInRange("locatorMaxCells", LocatorParams.DEFAULT_MAX_CELLS, 3, LocatorFixPayload.MAX_RINGS);

    public static final ModConfigSpec.DoubleValue LOCATOR_MAX_HDOP = BUILDER
            .comment("Network Locator: above this horizontal dilution of precision the Locator shows",
                    "POOR GEOMETRY instead of a position. Towers in a line push HDOP towards infinity.")
            .defineInRange("locatorMaxHdop", LocatorParams.DEFAULT_MAX_HDOP, 1.0, 50.0);

    public static final ModConfigSpec.DoubleValue NLOS_BIAS_BLOCKS_PER_DB = BUILDER
            .comment("Network Locator: blocks added to a measured range per dB of obstruction.",
                    "GAME ABSTRACTION: a flat stand-in for non-line-of-sight bias. Real ranges read",
                    "long behind terrain because a longer reflected path arrives first; RANCraft has",
                    "no reflections. 0 turns the bias off.")
            .defineInRange("nlosBiasBlocksPerDb", LocatorParams.DEFAULT_NLOS_BIAS_BLOCKS_PER_DB, 0.0, 4.0);

    public static final ModConfigSpec.DoubleValue LOCATOR_SITE_MERGE_BLOCKS = BUILDER
            .comment("Network Locator: antennas within this horizontal distance are one site for positioning.",
                    "A site gives one range, however many of its sectors are heard: co-sited sectors add",
                    "no independent geometry. GAME ABSTRACTION: a real network knows each site's location;",
                    "the mod has no site block, so nearness stands in for it. The widest three-sector site",
                    "spans 2 blocks. 0 groups only antennas stacked in one column.")
            .defineInRange("locatorSiteMergeBlocks", LocatorParams.DEFAULT_SITE_MERGE_BLOCKS, 0.0, 16.0);

    public static final ModConfigSpec.IntValue LOCATOR_EMERGENCY_MAX_AGE_TICKS = BUILDER
            .comment("Network Locator: on death, the last fix is kept as the emergency record only if",
                    "it is younger than this. 1200 = 60 s.")
            .defineInRange("locatorEmergencyMaxAgeTicks", 1200, 0, 72_000);

    public static final ModConfigSpec SPEC = BUILDER.build();

    // ---- RF Vision Step 3a: the drive-test trail (CLIENT) --------------------------------------
    // The drive-test log lives on the client and costs the server nothing extra, so its knobs are a
    // separate CLIENT spec (config/rancraft-client.toml) that each player sets for themselves, rather
    // than COMMON entries a server admin would see and could not affect. Registered in RanCraft;
    // FML only ever loads it on a physical client.

    private static final ModConfigSpec.Builder CLIENT_BUILDER = new ModConfigSpec.Builder();

    public static final ModConfigSpec.IntValue DRIVE_TEST_CAPACITY = CLIENT_BUILDER
            .comment("Drive-test log: most samples kept per dimension before the oldest are dropped.",
                    "At the default 1 Hz sample rate 3600 is one hour. Standing still does not use",
                    "this up (a stationary sample replaces the previous one). A change applies to a",
                    "dimension's log the next time it starts, e.g. after /rancraftc drivetest clear.")
            .defineInRange("driveTestCapacity", DriveTestLog.DEFAULT_CAPACITY, 60, 36_000);

    public static final ModConfigSpec.DoubleValue TRAIL_RENDER_DISTANCE = CLIENT_BUILDER
            .comment("Drive-test trail: markers further than this from the camera, in blocks, are not",
                    "drawn. They stay in the log and in the CSV export either way.")
            .defineInRange("trailRenderDistance", 128.0, 16.0, 512.0);

    public static final ModConfigSpec CLIENT_SPEC = CLIENT_BUILDER.build();

    /** Falls back to the default if read before the client config has loaded, rather than throwing. */
    public static int driveTestCapacity() {
        return CLIENT_SPEC.isLoaded() ? DRIVE_TEST_CAPACITY.get() : DRIVE_TEST_CAPACITY.getDefault();
    }

    /** Read every frame by the trail renderer; same fallback as {@link #driveTestCapacity()}. */
    public static double trailRenderDistance() {
        return CLIENT_SPEC.isLoaded() ? TRAIL_RENDER_DISTANCE.get() : TRAIL_RENDER_DISTANCE.getDefault();
    }

    public static int lensMaxLinks() {
        return LENS_MAX_LINKS.get();
    }

    public static int coverageGridSize() {
        return COVERAGE_GRID_SIZE.get();
    }

    public static int coverageStepBlocks() {
        return COVERAGE_STEP_BLOCKS.get();
    }

    public static double coverageTickBudgetMs() {
        return COVERAGE_TICK_BUDGET_MS.get();
    }

    public static int coverageMinIntervalTicks() {
        return COVERAGE_MIN_INTERVAL_TICKS.get();
    }

    // Phase 3, Network Locator. The first four and the site merge distance also reach rf through
    // snapshot().locatorParams().

    public static double locatorMinRsrpDbm() {
        return LOCATOR_MIN_RSRP_DBM.get();
    }

    public static int locatorMaxCells() {
        return LOCATOR_MAX_CELLS.get();
    }

    public static double locatorMaxHdop() {
        return LOCATOR_MAX_HDOP.get();
    }

    public static double nlosBiasBlocksPerDb() {
        return NLOS_BIAS_BLOCKS_PER_DB.get();
    }

    public static double locatorSiteMergeBlocks() {
        return LOCATOR_SITE_MERGE_BLOCKS.get();
    }

    /** Gameplay only (the emergency record); deliberately not in {@link RfConfig}. */
    public static int locatorEmergencyMaxAgeTicks() {
        return LOCATOR_EMERGENCY_MAX_AGE_TICKS.get();
    }

    /** Immutable snapshot handed to the engine, so the engine never touches a config API. */
    public static RfConfig snapshot() {
        return new RfConfig(
                METERS_PER_BLOCK.get(),
                MAX_EVALUATION_RANGE_BLOCKS.get(),
                MAX_CELLS_EVALUATED.get(),
                MAX_OBSTRUCTION_DB.get(),
                MAX_RAY_STEPS.get(),
                EVALUATION_INTERVAL_TICKS.get(),
                REQUIRE_REDSTONE.get(),
                ENABLE_SAMPLE_CACHING.get(),
                ANTENNA_FRONT_TO_BACK_DB.get(),
                ANTENNA_SIDELOBE_FLOOR_DB.get(),
                ADJACENT_CHANNEL_REJECTION_DB.get(),
                PCI_MOD3_PENALTY_FACTOR.get(),
                ENABLE_PCI_MOD3_PENALTY.get(),
                HANDOVER_HYSTERESIS_DB.get(),
                TIME_TO_TRIGGER_TICKS.get(),
                PCI_PLANNING_RADIUS.get(),
                PCI_MOD3_RADIUS.get(),
                LOCATOR_MIN_RSRP_DBM.get(),
                LOCATOR_MAX_CELLS.get(),
                LOCATOR_MAX_HDOP.get(),
                NLOS_BIAS_BLOCKS_PER_DB.get(),
                LOCATOR_SITE_MERGE_BLOCKS.get());
    }
}
