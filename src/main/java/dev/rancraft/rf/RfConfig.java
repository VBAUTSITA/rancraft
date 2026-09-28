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
        double nlosBiasBlocksPerDb
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
            LocatorParams.DEFAULT_NLOS_BIAS_BLOCKS_PER_DB);

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

    public LocatorParams locatorParams() {
        return new LocatorParams(locatorMinRsrpDbm, locatorMaxCells, locatorMaxHdop, nlosBiasBlocksPerDb);
    }
}
