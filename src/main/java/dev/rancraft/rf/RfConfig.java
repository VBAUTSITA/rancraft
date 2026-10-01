package dev.rancraft.rf;

/**
 * Plain mirror of the mod config spec, so the engine never reads a config API.
 *
 * <p>{@code evaluationIntervalTicks}, {@code requireRedstone} and {@code enableSampleCaching} are
 * not used by the engine -- they live here so there is exactly one config object crossing the
 * boundary into {@code dev.rancraft.rf}.
 *
 * <p>Phase 2 appends the antenna, interference, handover and PCI tunables. Components are appended,
 * never reordered.
 *
 * <p>Phase 3 appends the four Network Locator tunables that {@code rf} code reads ({@link Ranging}
 * and {@link LocatorSolver}, via {@link #locatorParams()}). They cross the boundary here, like
 * every other tunable, so there stays exactly one config object, and a device reaches them through
 * the {@code RfConfig} its context already carries. {@code locatorEmergencyMaxAgeTicks} is <em>not</em>
 * here: it is gameplay for the game-side emergency record and no {@code rf} code reads it, so it
 * stays in the mod config only, as the lens settings do.
 *
 * <p>The Phase 3A review (round 1) appends {@code locatorSiteMergeBlocks}: which antennas count as
 * one site for positioning ({@link LocatorParams#siteMergeBlocks()}).
 *
 * <p>Phase 3 slice 9 appends the two {@link BlerModel} parameters, {@code blerSinr50Db} and
 * {@code blerSlopeDb} (§5), for the same reason as the locator's: {@code rf} code reads them, and the
 * Radio Link reaches them through the {@code RfConfig} its device context carries
 * ({@link #blerModel()}).
 *
 * <p>Phase 3 slice 11 appends the backhaul values {@code rf} code reads (§3C.2, §5):
 * {@code fiberRadiusBlocks} and {@code siteRadiusBlocks} ({@link BackhaulGraph}, via
 * {@link #backhaulTopology()}) and {@code enableRainFade} ({@link MicrowaveLink#evaluate}).
 * {@code requireBackhaul} (gameplay) and {@code backhaulRecomputeTicks} (server cost) stay in the mod
 * config only, as the lens settings do. The link's own figures are data, not config:
 * {@code rf/backhaul/microwave.json}.
 */
public record RfConfig(
        double metersPerBlock,
        double maxEvaluationRangeBlocks,
        int maxCellsEvaluated,
        double maxObstructionDb,
        int maxRaySteps,
        int evaluationIntervalTicks,
        boolean requireRedstone,
        boolean enableSampleCaching,
        // ---- Phase 2 ----
        double antennaFrontToBackDb,
        double antennaSidelobeFloorDb,
        double adjacentChannelRejectionDb,
        double pciMod3PenaltyFactor,
        boolean enablePciMod3Penalty,
        double handoverHysteresisDb,
        int timeToTriggerTicks,
        double pciPlanningRadius,
        double pciMod3Radius,
        // ---- Phase 3: Network Locator ----
        double locatorMinRsrpDbm,
        int locatorMaxCells,
        double locatorMaxHdop,
        double nlosBiasBlocksPerDb,
        // ---- Phase 3A review, round 1: sites for positioning ----
        double locatorSiteMergeBlocks,
        // ---- Phase 3 slice 9: Radio Link block error rate ----
        double blerSinr50Db,
        double blerSlopeDb,
        // ---- Phase 3 slice 11: backhaul ----
        double fiberRadiusBlocks,
        double siteRadiusBlocks,
        boolean enableRainFade
) {
    public static final RfConfig DEFAULTS = new RfConfig(
            1.0,
            // Raised from Phase 1's 600. At 600 the cap bit before the sensitivity floor on
            // band_700, band_900 AND band_1800 alike, making three of the four bands identical in
            // range. 1400 lets physics decide on band_1800 (701) and band_3500 (261); the two low
            // bands still share the cap. See NOTES.md for the measured table.
            1400.0,
            // Raised from 8: interference needs neighbours to be interfering. The pruning in
            // RfEngine is what keeps the extra four cells from costing anything.
            12,
            120.0,
            RayMarcher.DEFAULT_MAX_STEPS,
            20,
            false,
            true,
            ParabolicPattern.DEFAULT_FRONT_TO_BACK_DB,
            ParabolicPattern.DEFAULT_SIDELOBE_FLOOR_DB,
            30.0,
            2.0,
            true,
            3.0,
            40,
            500.0,
            250.0,
            LocatorParams.DEFAULT_MIN_RSRP_DBM,
            LocatorParams.DEFAULT_MAX_CELLS,
            LocatorParams.DEFAULT_MAX_HDOP,
            LocatorParams.DEFAULT_NLOS_BIAS_BLOCKS_PER_DB,
            LocatorParams.DEFAULT_SITE_MERGE_BLOCKS,
            BlerModel.DEFAULT_SINR50_DB,
            BlerModel.DEFAULT_SLOPE_DB,
            BackhaulGraph.Topology.DEFAULT_FIBER_RADIUS_BLOCKS,
            BackhaulGraph.Topology.DEFAULT_SITE_RADIUS_BLOCKS,
            true);

    public SinrCalculator.SinrParams sinrParams() {
        return new SinrCalculator.SinrParams(
                adjacentChannelRejectionDb, pciMod3PenaltyFactor, enablePciMod3Penalty);
    }

    public CellSelector.SelectionParams selectionParams() {
        return new CellSelector.SelectionParams(handoverHysteresisDb, timeToTriggerTicks);
    }

    public PciPlanner.PciParams pciParams() {
        return new PciPlanner.PciParams(pciPlanningRadius, pciMod3Radius);
    }

    /** The block-error-rate curve (§3B.4). Throws if {@code blerSlopeDb} is not positive. */
    public BlerModel blerModel() {
        return new BlerModel(blerSinr50Db, blerSlopeDb);
    }

    /** The backhaul topology radii (§3C.2). Throws if either is negative. */
    public BackhaulGraph.Topology backhaulTopology() {
        return new BackhaulGraph.Topology(fiberRadiusBlocks, siteRadiusBlocks);
    }

    public LocatorParams locatorParams() {
        return new LocatorParams(locatorMinRsrpDbm, locatorMaxCells, locatorMaxHdop, nlosBiasBlocksPerDb,
                locatorSiteMergeBlocks);
    }
}
