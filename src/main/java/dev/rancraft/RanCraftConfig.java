package dev.rancraft;

import dev.rancraft.net.CoverageSurveyPayload;
import dev.rancraft.net.LensLinksPayload;
import dev.rancraft.net.LocatorFixPayload;
import dev.rancraft.rf.BackhaulGraph;
import dev.rancraft.rf.BlerModel;
import dev.rancraft.rf.DriveTestLog;
import dev.rancraft.rf.LocatorParams;
import dev.rancraft.rf.PowerModel;
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

    // ---- Phase 3: mast columns (§3B.1) ---------------------------------------------------------
    // Gameplay (which blocks form one tower), not an engine parameter, so it stays out of RfConfig.
    // COMMON configs are not synced, so the RF Lens does not rely on the client's copy: the server
    // sends each base's radiating height in its update tag (RadiatingY, Phase 3B review fix 4), and
    // the client's own value is only the fallback before that tag arrives (or for the moment a block
    // change reaches the client ahead of it); see NOTES.md, slice 6 and "Phase 3B review, round 1".

    public static final ModConfigSpec.IntValue MAX_MAST_HEIGHT = BUILDER
            .comment("Mast columns: a vertical run of Signal Masts is one cell, radiating from above its",
                    "top mast. Only the lowest this-many masts raise the radiating point; masts above",
                    "them are structure. A change applies to a column the next time it is rebuilt or",
                    "its chunk loads. GAME ABSTRACTION: a structural game rule, not RF (the propagation",
                    "model has no antenna-height term; height helps only by clearing obstruction).")
            .defineInRange("maxMastHeight", 64, 1, 4096);

    // ---- Phase 3: fixed receivers (§3B.3) -------------------------------------------------------
    // Server cost, not an engine parameter, so it stays out of RfConfig (as coverageTickBudgetMs).

    public static final ModConfigSpec.DoubleValue FIXED_RECEIVER_TICK_BUDGET_MS = BUILDER
            .comment("Server-thread time per tick spent evaluating fixed receivers (device blocks such as",
                    "the Radio Link), shared by every dimension. Receivers are served round-robin, each at",
                    "most once per evaluationIntervalTicks; one whose surroundings did not change is",
                    "replayed its last sample, which costs about a microsecond. Over budget, the rest",
                    "wait for the next tick. 0.5 ms is 1% of a 50 ms tick.")
            .defineInRange("fixedReceiverTickBudgetMs", 0.5, 0.05, 20.0);

    // ---- Phase 3: Radio Link (§3B.4) ----------------------------------------------------------
    // The block-error-rate curve. rf code reads it (BlerModel), so it crosses into RfConfig in
    // snapshot(), as the locator's tunables do. See NOTES.md, Phase 3 slice 9.

    public static final ModConfigSpec.DoubleValue BLER_SINR50_DB = BUILDER
            .comment("Radio Link: the SINR, in dB, at which half of all messages fail to decode at one end.",
                    "BLER(sinr) = 1 / (1 + 10^((sinr - blerSinr50Db) / blerSlopeDb)); a message crosses the",
                    "network with probability (1 - BLER(sender)) x (1 - BLER(receiver)). GAME ABSTRACTION:",
                    "one generic curve, not a real modulation-and-coding table, and no retransmission (HARQ).")
            .defineInRange("blerSinr50Db", BlerModel.DEFAULT_SINR50_DB, -20.0, 30.0);

    public static final ModConfigSpec.DoubleValue BLER_SLOPE_DB = BUILDER
            .comment("Radio Link: dB of SINR per tenfold change in the odds of decoding. Smaller is steeper.",
                    "At the defaults (0 dB, 2 dB) a link at POOR service (SINR 0-5 dB) loses many updates",
                    "and one at FAIR (5 dB and up) almost none.")
            .defineInRange("blerSlopeDb", BlerModel.DEFAULT_SLOPE_DB, 0.1, 20.0);

    // ---- Phase 3: backhaul (§3C.2) ------------------------------------------------------------
    // fiberRadiusBlocks, siteRadiusBlocks and enableRainFade are read by rf code (BackhaulGraph,
    // MicrowaveLink), so they cross into RfConfig in snapshot(). requireBackhaul is gameplay and
    // backhaulRecomputeTicks server cost; both stay out of RfConfig, as the lens settings do. The
    // microwave link's own figures (frequency, power, thresholds, rain rates) are data, in
    // data/rancraft/rf/backhaul/microwave.json. See NOTES.md, Phase 3 slice 11.

    public static final ModConfigSpec.BooleanValue REQUIRE_BACKHAUL = BUILDER
            .comment("When true, a cell with no path to a Core Site goes off the air: no fiber (a core within",
                    "fiberRadiusBlocks) and no chain of working microwave links from Backhaul Dishes.",
                    "Off by default, like requireRedstone, so an existing world keeps working. Either way, a",
                    "cell whose best path to a core crosses a DEGRADED link is backhaul-limited: the devices",
                    "it serves get at most FAIR service.")
            .define("requireBackhaul", false);

    public static final ModConfigSpec.DoubleValue FIBER_RADIUS_BLOCKS = BUILDER
            .comment("A cell's base, or a Backhaul Dish, within this horizontal distance of a Core Site in the",
                    "same dimension is on fiber to the core network. GAME ABSTRACTION: fiber is implicit;",
                    "nothing is laid, and being near a core is being connected.")
            .defineInRange("fiberRadiusBlocks", BackhaulGraph.Topology.DEFAULT_FIBER_RADIUS_BLOCKS, 0.0, 4096.0);

    public static final ModConfigSpec.DoubleValue SITE_RADIUS_BLOCKS = BUILDER
            .comment("A Backhaul Dish within this horizontal distance of a cell's base serves that cell, and",
                    "dishes serving the same cell are connected through its site (a relay site).",
                    "Horizontal, so a dish on top of a tall mast column serves the column's own cell.")
            .defineInRange("siteRadiusBlocks", BackhaulGraph.Topology.DEFAULT_SITE_RADIUS_BLOCKS, 0.0, 256.0);

    public static final ModConfigSpec.IntValue BACKHAUL_RECOMPUTE_TICKS = BUILDER
            .comment("Least ticks between two recomputations of the microwave links and each cell's",
                    "backhaul, whatever changed in between. 100 = 5 s.")
            .defineInRange("backhaulRecomputeTicks", 100, 1, 12_000);

    // Not in §5's list: added by the Phase 3C review (finding 2) so that a recompute that must measure
    // many long links again spreads its marches over ticks. Server cost: stays out of RfConfig.
    public static final ModConfigSpec.DoubleValue BACKHAUL_MARCH_BUDGET_MS = BUILDER
            .comment("Server-thread time per tick spent measuring microwave links again (new links, links whose",
                    "terrain or chunks changed, every link after a server start), shared by every dimension.",
                    "Over budget, the rest wait for the next tick; the links' states and each cell's backhaul",
                    "change once all are measured. At least one link is measured per tick, so a tick can run",
                    "over by one link (about 0.1 to 0.6 ms for a 1000-block link). 0.25 ms is 0.5% of a 50 ms tick.")
            .defineInRange("backhaulMarchBudgetMs", 0.25, 0.01, 20.0);

    public static final ModConfigSpec.BooleanValue ENABLE_RAIN_FADE = BUILDER
            .comment("Rain at a microwave link's midpoint adds loss: rain_db_per_km x the link's length in km,",
                    "the higher thunder figure in a thunderstorm, nothing where it snows. APPROXIMATE: figures",
                    "in the spirit of ITU-R P.838 at 18 GHz. Turn off to take weather out of the backhaul.")
            .define("enableRainFade", true);

    // ---- Phase 3: Proximity Scanner (§3C.4) ---------------------------------------------------
    // Gameplay and server cost (an entity query per dispatch), not RF: it stays out of RfConfig. Not in
    // §5's list; added so the spec's "24 blocks" is tunable like everything else (NOTES.md, slice 14).

    /** The spec's figure, §3C.4: hostile mobs within 24 blocks. */
    public static final double DEFAULT_SCANNER_RANGE_BLOCKS = 24.0;

    public static final ModConfigSpec.DoubleValue SCANNER_RANGE_BLOCKS = BUILDER
            .comment("Proximity Scanner: hostile mobs within this straight-line distance, in blocks, are listed",
                    "while it is held with GOOD service on a tier-3 band. GAME ABSTRACTION: the list is not",
                    "radio sensing; it stands in for a high-rate sensor feed that needs a high-capacity link.")
            .defineInRange("scannerRangeBlocks", DEFAULT_SCANNER_RANGE_BLOCKS, 1.0, 64.0);

    // ---- Phase 3: power (§3C.5) -----------------------------------------------------------------
    // requirePower is the spec's (§5). The rest are the spec's figures (§3C.5), made tunable because
    // everything tunable goes here (NOTES.md, slice 15): the PowerModel's figures, the buffer and its
    // restart fraction, the Site Generator's output and the served-receivers window. None is read by
    // the engine, so none crosses into RfConfig: the model is built by powerModel().

    public static final ModConfigSpec.BooleanValue REQUIRE_POWER = BUILDER
            .comment("When true, a cell on the air draws energy (FE) from its buffer every tick and goes off the",
                    "air when the buffer runs out; it comes back once the buffer is above powerRestartFraction.",
                    "A Site Generator next to an antenna (or any mast of its column) fills it. Off by default,",
                    "like requireRedstone and requireBackhaul, so an existing world keeps working; while it is off",
                    "antennas take no energy and a generator next to one burns nothing.")
            .define("requirePower", false);

    public static final ModConfigSpec.IntValue POWER_BUFFER_FE = BUILDER
            .comment("Energy one cell can hold, in FE: a Sector Antenna's own buffer, or a mast column's (its base",
                    "holds it). 10000 FE runs a sector at 20 dBm for about 47 s and at 30 dBm for about 7 s.")
            .defineInRange("powerBufferFe", 10_000, 100, 10_000_000);

    public static final ModConfigSpec.DoubleValue POWER_RESTART_FRACTION = BUILDER
            .comment("A cell that ran out of energy comes back on the air only once its buffer holds more than this",
                    "fraction of powerBufferFe (0.1 = 10 %), so a cell on a weak supply does not flap on and off.")
            .defineInRange("powerRestartFraction", 0.10, 0.0, 1.0);

    public static final ModConfigSpec.DoubleValue POWER_BASE_FE_MAST = BUILDER
            .comment("Power model: the base load of a Signal Mast's radio, FE per tick on the air.",
                    "FE/tick = baseFe + powerPaFePerWatt x P_rf_W / powerPaEfficiency, P_rf_W = 10^((txDbm - 30) / 10).")
            .defineInRange("powerBaseFeMast", PowerModel.DEFAULT_BASE_FE_MAST, 0.0, 100_000.0);

    public static final ModConfigSpec.DoubleValue POWER_BASE_FE_SECTOR = BUILDER
            .comment("Power model: the base load of a Sector Antenna's radio, FE per tick on the air.")
            .defineInRange("powerBaseFeSector", PowerModel.DEFAULT_BASE_FE_SECTOR, 0.0, 100_000.0);

    public static final ModConfigSpec.DoubleValue POWER_BASE_FE_WIDEBAND = BUILDER
            .comment("Power model: added to the base load of a radio with a Wideband Radio Unit (tier 3), FE per tick.")
            .defineInRange("powerBaseFeWideband", PowerModel.DEFAULT_BASE_FE_WIDEBAND, 0.0, 100_000.0);

    public static final ModConfigSpec.DoubleValue POWER_PA_FE_PER_WATT = BUILDER
            .comment("Power model: FE per tick per watt the power amplifier draws from the supply. Sets the scale",
                    "of the game's energy unit; FE has no fixed exchange rate to joules.")
            .defineInRange("powerPaFePerWatt", PowerModel.DEFAULT_PA_FE_PER_WATT, 0.0, 100_000.0);

    public static final ModConfigSpec.DoubleValue POWER_PA_EFFICIENCY = BUILDER
            .comment("Power model: the power amplifier's efficiency, RF watts out per watt in. 0.3 is in the range",
                    "of a real macro-cell amplifier. 10 dB more Tx power always costs 10x the amplifier's energy.")
            .defineInRange("powerPaEfficiency", PowerModel.DEFAULT_PA_EFFICIENCY, 0.01, 1.0);

    public static final ModConfigSpec.IntValue SITE_GENERATOR_FE_PER_TICK = BUILDER
            .comment("Site Generator: FE it makes per tick while it burns. It burns furnace fuel only while what it",
                    "makes is taken, so fuel lasts exactly as long as the energy drawn allows (a coal is 1600",
                    "burn ticks: 64000 FE at 40 FE/t).")
            .defineInRange("siteGeneratorFePerTick", 40, 1, 100_000);

    public static final ModConfigSpec.IntValue SERVED_WINDOW_MINUTES = BUILDER
            .comment("Seam for cell sleep (Phase 4): each cell counts the distinct receivers it served in the last",
                    "this-many minutes. Shown by /rancraft power status; nothing else reads it yet.")
            .defineInRange("servedWindowMinutes", 5, 1, 120);

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

    /**
     * The mast-column height cap (§3B.1). Read by the server and by the RF Lens on the client, so it
     * falls back to the default if read before the config has loaded, rather than throwing.
     */
    public static int maxMastHeight() {
        return SPEC.isLoaded() ? MAX_MAST_HEIGHT.get() : MAX_MAST_HEIGHT.getDefault();
    }

    /**
     * The fixed-receiver ticker's per-tick budget (§3B.3), in milliseconds. Falls back to the default
     * if read before the config has loaded, rather than throwing.
     */
    public static double fixedReceiverTickBudgetMs() {
        return SPEC.isLoaded() ? FIXED_RECEIVER_TICK_BUDGET_MS.get() : FIXED_RECEIVER_TICK_BUDGET_MS.getDefault();
    }

    /**
     * Whether a cell needs backhaul to stay on the air (§3C.2). Gameplay, deliberately not in
     * {@link RfConfig}. Falls back to the default (off) if read before the config has loaded.
     */
    public static boolean requireBackhaul() {
        return SPEC.isLoaded() ? REQUIRE_BACKHAUL.get() : REQUIRE_BACKHAUL.getDefault();
    }

    /**
     * Least ticks between backhaul recomputations (§3C.2). Server cost, deliberately not in
     * {@link RfConfig}. Falls back to the default if read before the config has loaded.
     */
    public static int backhaulRecomputeTicks() {
        return SPEC.isLoaded() ? BACKHAUL_RECOMPUTE_TICKS.get() : BACKHAUL_RECOMPUTE_TICKS.getDefault();
    }

    /**
     * The backhaul recompute's per-tick march budget (Phase 3C review), in milliseconds. Server cost,
     * deliberately not in {@link RfConfig}. Falls back to the default if read before the config has loaded.
     */
    public static double backhaulMarchBudgetMs() {
        return SPEC.isLoaded() ? BACKHAUL_MARCH_BUDGET_MS.get() : BACKHAUL_MARCH_BUDGET_MS.getDefault();
    }

    /**
     * The fiber radius (§3C.2). Also in {@link RfConfig} (the graph reads it there); this accessor is
     * for game code that needs only the radius, such as the Storage Terminal's "near a core" rule.
     */
    public static double fiberRadiusBlocks() {
        return SPEC.isLoaded() ? FIBER_RADIUS_BLOCKS.get() : FIBER_RADIUS_BLOCKS.getDefault();
    }

    /**
     * The evaluation interval alone, without building a {@link #snapshot()} (Phase 3 slice 13): the
     * Storage Terminal's open menu reads it every tick to judge how old its last verdict may be. Falls
     * back to the default if read before the config has loaded.
     */
    public static int evaluationIntervalTicks() {
        return SPEC.isLoaded() ? EVALUATION_INTERVAL_TICKS.get() : EVALUATION_INTERVAL_TICKS.getDefault();
    }

    /**
     * The Proximity Scanner's range (§3C.4), in blocks. Gameplay, deliberately not in {@link RfConfig}.
     * Falls back to the default if read before the config has loaded.
     */
    public static double scannerRangeBlocks() {
        return SPEC.isLoaded() ? SCANNER_RANGE_BLOCKS.get() : SCANNER_RANGE_BLOCKS.getDefault();
    }

    /**
     * Whether a cell needs energy to stay on the air (§3C.5). Gameplay, deliberately not in
     * {@link RfConfig}. Falls back to the default (off) if read before the config has loaded.
     */
    public static boolean requirePower() {
        return SPEC.isLoaded() ? REQUIRE_POWER.get() : REQUIRE_POWER.getDefault();
    }

    /** A cell's buffer, in FE (§3C.5: 10,000). Falls back to the default before the config has loaded. */
    public static int powerBufferFe() {
        return SPEC.isLoaded() ? POWER_BUFFER_FE.get() : POWER_BUFFER_FE.getDefault();
    }

    /** The restart fraction (§3C.5: above 10 %). Falls back to the default before the config has loaded. */
    public static double powerRestartFraction() {
        return SPEC.isLoaded() ? POWER_RESTART_FRACTION.get() : POWER_RESTART_FRACTION.getDefault();
    }

    /** The Site Generator's output while it burns (§3C.5: 40 FE/t). Falls back to the default before load. */
    public static int siteGeneratorFePerTick() {
        return SPEC.isLoaded() ? SITE_GENERATOR_FE_PER_TICK.get() : SITE_GENERATOR_FE_PER_TICK.getDefault();
    }

    /** The served-receivers window in ticks (the seam, §3C.5). Falls back to the default before load. */
    public static long servedWindowTicks() {
        int minutes = SPEC.isLoaded() ? SERVED_WINDOW_MINUTES.get() : SERVED_WINDOW_MINUTES.getDefault();
        return minutes * 60L * 20L;
    }

    /**
     * The power model's figures (§3C.5), built from this config; {@link PowerModel#DEFAULT} before the
     * config has loaded. Not part of {@link #snapshot()}: the engine never reads it.
     */
    public static PowerModel powerModel() {
        if (!SPEC.isLoaded()) {
            return PowerModel.DEFAULT;
        }
        return new PowerModel(POWER_BASE_FE_MAST.get(), POWER_BASE_FE_SECTOR.get(), POWER_BASE_FE_WIDEBAND.get(),
                POWER_PA_FE_PER_WATT.get(), POWER_PA_EFFICIENCY.get());
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
                LOCATOR_SITE_MERGE_BLOCKS.get(),
                BLER_SINR50_DB.get(),
                BLER_SLOPE_DB.get(),
                FIBER_RADIUS_BLOCKS.get(),
                SITE_RADIUS_BLOCKS.get(),
                ENABLE_RAIN_FADE.get());
    }
}
