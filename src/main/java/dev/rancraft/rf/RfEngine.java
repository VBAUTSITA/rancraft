package dev.rancraft.rf;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;

/**
 * Evaluates every candidate cell against one receiver position.
 *
 * <h2>Why the stage order matters</h2>
 * Phase 2 evaluates half again as many cells as Phase 1 (interference needs neighbours) inside the
 * same 1 ms budget. That only works because the one expensive operation -- the voxel ray march --
 * runs last, and only for cells that could still be heard if there were nothing in the way:
 *
 * <ol>
 *   <li><b>Distance filter.</b> One subtraction and a square root.
 *   <li><b>Pattern gain.</b> Two {@code atan2} calls. Essentially free.
 *   <li><b>Optimistic budget.</b> {@code Tx + gain + Gr - PL(d)} with zero obstruction. This is the
 *       best the cell could possibly do. If that is already under the floor, no arrangement of
 *       blocks can save it, so it is dropped without ever touching the world. A cell sitting in an
 *       antenna's backlobe at 400 m dies here for the cost of those two {@code atan2} calls.
 *   <li><b>Sort by that optimistic value</b> and keep the best {@code maxCellsEvaluated}, so the
 *       rays that do get spent go to the cells most likely to matter. Sorting by distance instead,
 *       as Phase 1 did, would spend them on a near cell that is pointing the other way.
 *   <li><b>Ray march</b>, then the real RSRP, then the floor check again.
 * </ol>
 */
public final class RfEngine {

    private RfEngine() {
    }

    /** Ray-march accounting, so the ticker can log how much work the pruning actually saved. */
    public record EvaluationStats(
            int candidates,
            int prunedByDistance,
            int prunedByBudget,
            int rayMarched,
            int reported
    ) {
        /** Fraction of in-range cells that never needed a ray. */
        public double prunedFraction() {
            int inRange = candidates - prunedByDistance;
            return inRange <= 0 ? 0.0 : (double) prunedByBudget / inRange;
        }
    }

    /** Everything one evaluation produces: the sample, the carried-forward state, and the cost. */
    public record Evaluation(
            SignalSample sample, ReceiverState state, boolean handedOver, EvaluationStats stats) {
    }

    /**
     * Phase 1 compatible entry point: no receiver state, so the strongest cell always serves.
     *
     * <p>Kept because it is the honest way to ask "what would this reading be with no hysteresis",
     * which is exactly what the Phase 1 regression test asks.
     */
    public static SignalSample evaluate(
            WorldProbe probe,
            double rxX, double rxY, double rxZ,
            Collection<CellParams> candidates,
            BandTable bands,
            RfConfig config,
            long tick) {

        return evaluate(probe, rxX, rxY, rxZ, candidates, bands, config, tick, ReceiverState.NONE).sample();
    }

    public static Evaluation evaluate(
            WorldProbe probe,
            double rxX, double rxY, double rxZ,
            Collection<CellParams> candidates,
            BandTable bands,
            RfConfig config,
            long tick,
            ReceiverState previous) {

        int candidateCount = candidates.size();
        if (candidateCount == 0) {
            return outOfService(previous, tick, config, new EvaluationStats(0, 0, 0, 0, 0));
        }

        ParabolicPattern parabolic =
                new ParabolicPattern(config.antennaFrontToBackDb(), config.antennaSidelobeFloorDb());

        // Stages 1-3: everything that can be decided without touching the world.
        List<Prospect> prospects = new ArrayList<>(candidateCount);
        int prunedByDistance = 0;
        int prunedByBudget = 0;

        for (CellParams cell : candidates) {
            double distance = distanceBlocks(cell, rxX, rxY, rxZ);
            if (distance > config.maxEvaluationRangeBlocks()) {
                prunedByDistance++;
                continue;
            }

            Band band = bands.getOrFallback(cell.bandId());
            double pathLossDb = RfMath.pathLossDb(
                    band.frequencyMhz(), band.pathLossExponent(), distance * config.metersPerBlock());

            double bearingDeg = AntennaGeometry.bearingDeg(cell.centerX(), cell.centerZ(), rxX, rxZ);
            double elevationDeg = AntennaGeometry.elevationDeg(
                    cell.centerX(), cell.centerY(), cell.centerZ(), rxX, rxY, rxZ);

            AntennaPattern pattern = AntennaPattern.isOmni(cell) ? OmniPattern.INSTANCE : parabolic;
            double effectiveGainDbi = pattern.gainDbi(bearingDeg, elevationDeg, cell);

            // The best this cell could possibly do: perfect line of sight, nothing in the way.
            double optimisticRsrpDbm = RfMath.rsrpDbm(cell.txPowerDbm(), effectiveGainDbi, pathLossDb, 0.0);
            if (!RfMath.inService(optimisticRsrpDbm)) {
                prunedByBudget++;
                continue;
            }

            prospects.add(new Prospect(
                    cell, band, distance, pathLossDb, effectiveGainDbi, optimisticRsrpDbm,
                    ParabolicPattern.azimuthOffsetDeg(bearingDeg, cell.azimuthDeg()),
                    ParabolicPattern.elevationOffsetDeg(elevationDeg, cell.tiltDeg())));
        }

        if (prospects.isEmpty()) {
            return outOfService(previous, tick, config,
                    new EvaluationStats(candidateCount, prunedByDistance, prunedByBudget, 0, 0));
        }

        // Stage 4: best-first, then cap. Rays go to the cells with the most to lose.
        prospects.sort(Comparator.comparingDouble(Prospect::optimisticRsrpDbm).reversed());
        int limit = Math.min(prospects.size(), Math.max(0, config.maxCellsEvaluated()));

        // Stage 5: the expensive part.
        List<CellSample> evaluated = new ArrayList<>(limit);
        int rayMarched = 0;
        for (int i = 0; i < limit; i++) {
            rayMarched++;
            CellSample sample = march(probe, rxX, rxY, rxZ, prospects.get(i), config);
            if (sample != null) {
                evaluated.add(sample);
            }
        }

        if (evaluated.isEmpty()) {
            return outOfService(previous, tick, config,
                    new EvaluationStats(candidateCount, prunedByDistance, prunedByBudget, rayMarched, 0));
        }

        evaluated.sort(Comparator.comparingDouble(CellSample::rsrpDbm).reversed());

        // Selection, then SINR against everything else that was heard.
        CellSelector.Selection selection =
                CellSelector.select(previous, evaluated, tick, config.selectionParams());
        CellSample serving = selection.serving();
        if (serving == null) {
            return outOfService(previous, tick, config,
                    new EvaluationStats(candidateCount, prunedByDistance, prunedByBudget, rayMarched, 0));
        }

        double noiseFloorDbm = bands.getOrFallback(serving.bandId()).noiseFloorDbm();
        SinrCalculator.SinrResult sinr = SinrCalculator.compute(
                serving.asRxSignal(),
                evaluated.stream().map(CellSample::asRxSignal).toList(),
                noiseFloorDbm,
                config.sinrParams());

        SignalSample sample = new SignalSample(
                evaluated, tick,
                serving.cellId(),
                sinr.sinrDb(), sinr.interferenceDbm(), sinr.noiseDbm(),
                sinr.serviceLevel(serving.rsrpDbm()),
                selection.state().handoverCount());

        return new Evaluation(sample, selection.state(), selection.handedOver(),
                new EvaluationStats(
                        candidateCount, prunedByDistance, prunedByBudget, rayMarched, evaluated.size()));
    }

    /** @return null when the ray ran out of steps or the cell fell below the floor. */
    private static CellSample march(
            WorldProbe probe,
            double rxX, double rxY, double rxZ,
            Prospect prospect,
            RfConfig config) {

        CellParams cell = prospect.cell();
        RayMarcher.MarchResult result = RayMarcher.march(
                probe,
                cell.centerX(), cell.centerY(), cell.centerZ(),
                rxX, rxY, rxZ,
                prospect.band().penetrationFactor(),
                config.maxObstructionDb(),
                config.maxRaySteps());

        if (result.cappedOut()) {
            // Step cap exhausted before reaching the receiver: treat as out of range.
            return null;
        }

        double rsrpDbm = RfMath.rsrpDbm(
                cell.txPowerDbm(), prospect.effectiveGainDbi(), prospect.pathLossDb(), result.obstructionDb());
        if (!RfMath.inService(rsrpDbm)) {
            return null;
        }

        return new CellSample(
                cell.cellId(), cell.x(), cell.y(), cell.z(),
                rsrpDbm, prospect.distanceBlocks(), prospect.pathLossDb(), result.obstructionDb(),
                cell.bandId(), cell.pci(),
                prospect.effectiveGainDbi(), prospect.azimuthOffsetDeg(), prospect.elevationOffsetDeg());
    }

    private static Evaluation outOfService(
            ReceiverState previous, long tick, RfConfig config, EvaluationStats stats) {

        CellSelector.Selection selection =
                CellSelector.select(previous, List.of(), tick, config.selectionParams());
        return new Evaluation(
                SignalSample.empty(tick, selection.state().handoverCount()),
                selection.state(),
                false,
                stats);
    }

    /** 3D Euclidean distance from the receiver to the centre of the radiating voxel. */
    private static double distanceBlocks(CellParams cell, double rxX, double rxY, double rxZ) {
        double dx = cell.centerX() - rxX;
        double dy = cell.centerY() - rxY;
        double dz = cell.centerZ() - rxZ;
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    /** A candidate that survived the cheap stages and is worth a ray. */
    private record Prospect(
            CellParams cell,
            Band band,
            double distanceBlocks,
            double pathLossDb,
            double effectiveGainDbi,
            double optimisticRsrpDbm,
            double azimuthOffsetDeg,
            double elevationOffsetDeg) {
    }
}
