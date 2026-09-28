package dev.rancraft.rf;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * A best-server survey over a square grid of ground points: the data behind the RF Lens coverage
 * painting (Step 2b).
 *
 * <p>For every grid point it stands a receiver on the terrain, runs the full {@link RfEngine}
 * evaluation there, and records which cell serves that spot and how well. The client paints the
 * ground from those server-computed values; it never evaluates a point itself.
 *
 * <h2>Why it is resumable</h2>
 * Every point is a full evaluation with its own ray marches. A 64 x 64 grid is ~35 ms of work, most
 * of a server tick, so doing it in one go would stall the game. The survey therefore keeps a cursor
 * and {@link #step} does only as many points as the caller's time budget allows; the caller resumes
 * it next tick. It must run on the server thread, not on a worker: {@link WorldProbe} reads live
 * block state, which is not thread-safe. Nothing here is synchronised for the same reason -- one
 * survey belongs to one thread.
 *
 * <h2>What the plot means, and the abstractions in it</h2>
 * <ul>
 *   <li><b>Stateless best-server, no hysteresis.</b> Each point is evaluated as if a receiver had
 *       just switched on there ({@link ReceiverState#NONE}), so the strongest cell always serves.
 *       That is how radio planning tools draw a best-server plot. The live meter keeps handover
 *       hysteresis, so near a boundary a walking player can stay on a cell the plot shows as
 *       second best. That disagreement is the hysteresis doing its job, not a bug.
 *   <li><b>Band-filtered surveys ignore other bands.</b> With a band filter, only that band's cells
 *       are evaluated, so interference from other bands is left out of the SINR. That interference
 *       is already suppressed by the ~30 dB adjacent-channel rejection, so the omission is small
 *       but real: a filtered plot can read slightly better than the unfiltered one.
 *   <li><b>One sample per tile, taken at its centre.</b> Point (i, j) stands for the whole
 *       {@code step x step} tile {@code [blockX, blockX + step) x [blockZ, blockZ + step)} and is
 *       measured at that tile's centre: the ground is read at its middle column
 *       ({@link Spec#sampleX}) and the receiver stands over its geometric centre
 *       ({@link Spec#receiverX}). Sampling a corner instead would shift every painted boundary
 *       by about half a step in +x and +z. The plot is a mosaic of samples, not an
 *       interpolation; a boundary is only as sharp as the step, and within half a step of true
 *       either way.
 *   <li><b>Receiver at standing eye height.</b> Each sample is taken
 *       {@link #RECEIVER_EYE_HEIGHT} above the surface, where the live meter measures a standing
 *       player, and only on the top surface (see {@link SurfaceProbe}).
 *   <li><b>Snapshot of the network.</b> The candidate cells are fixed when the survey is built.
 *       An antenna placed or retuned mid-survey appears in the next survey, not this one.
 * </ul>
 */
public final class CoverageSurvey {

    /** Standing player eye height, in blocks. Where the live meter's receiver sits, too. */
    public static final double RECEIVER_EYE_HEIGHT = 1.62;

    /** {@code cellIndex} of a point with no serving cell, or one that was never surveyed. */
    public static final int NO_CELL = -1;

    /** {@code level} of a point whose ground could not be read. Distinct from every ServiceLevel. */
    public static final byte UNSURVEYED = -1;

    /** Largest side the grid index arithmetic ({@code size * size} as an int) can hold. */
    private static final int MAX_SIDE = 46_340;

    /**
     * The grid. Point (i,j) is the tile whose minimum corner is block (originX + i*step,
     * originZ + j*step), 0 <= i,j < size, covering {@code [blockX, blockX + step) x [blockZ,
     * blockZ + step)}. It is sampled at the tile's centre: surface read at
     * ({@link #sampleX}, {@link #sampleZ}), receiver at ({@link #receiverX}, {@link #receiverZ}).
     * Index = j*size + i.
     *
     * @param bandFilter a band id, or {@code ""} for all bands. {@code null} is read as {@code ""}.
     */
    public record Spec(int originX, int originZ, int step, int size, String bandFilter) {

        public Spec {
            if (step < 1) {
                throw new IllegalArgumentException("step must be >= 1, was " + step);
            }
            if (size < 1 || size > MAX_SIDE) {
                throw new IllegalArgumentException("size must be in [1, " + MAX_SIDE + "], was " + size);
            }
            bandFilter = bandFilter == null ? "" : bandFilter;
        }

        /** {@code size * size}. */
        public int pointCount() {
            return size * size;
        }

        /** The middle of the painted area, which runs from {@code originX} to {@code originX + span}. */
        public double centreX() {
            return originX + spanBlocks() / 2.0;
        }

        public double centreZ() {
            return originZ + spanBlocks() / 2.0;
        }

        /** Side length of the painted area in blocks: {@code size * step}. */
        public double spanBlocks() {
            return (double) size * step;
        }

        /** Block x of column {@code i}: the tile's minimum (west) edge. The tile runs to {@code blockX + step}. */
        public int blockX(int i) {
            return originX + i * step;
        }

        /** Block z of row {@code j}: the tile's minimum (north) edge. */
        public int blockZ(int j) {
            return originZ + j * step;
        }

        /**
         * Block column tile {@code i} is sampled at: its middle column (the east one of the middle
         * two when {@code step} is even). The ground under the receiver is read here.
         */
        public int sampleX(int i) {
            return blockX(i) + step / 2;
        }

        /** Block row tile {@code j} is sampled at; see {@link #sampleX}. */
        public int sampleZ(int j) {
            return blockZ(j) + step / 2;
        }

        /**
         * Receiver x for tile {@code i}: the tile's geometric centre. For an odd step that is the
         * centre of {@link #sampleX}'s column; for an even step it is the edge between the middle
         * two columns, so the sample carries no half-block bias either way.
         */
        public double receiverX(int i) {
            return blockX(i) + step / 2.0;
        }

        /** Receiver z for tile {@code j}; see {@link #receiverX}. */
        public double receiverZ(int j) {
            return blockZ(j) + step / 2.0;
        }

        /** Point index of (i, j). */
        public int index(int i, int j) {
            return j * size + i;
        }

        public boolean filtersBand() {
            return !bandFilter.isEmpty();
        }
    }

    /**
     * A finished survey.
     *
     * <p>Arrays are indexed by {@link Spec#index}. They are copied in, so the result never aliases
     * a live survey, but the accessors hand out the result's own arrays: treat them as read-only.
     * {@code equals} compares array contents, so two surveys of the same world compare equal.
     *
     * @param palette   every cell that serves at least one point, in first-seen order (point index
     *                  order), with no duplicate cell ids. {@code cellIndex} points into it.
     * @param surfaceY  the ground each point stood on, or {@link SurfaceProbe#UNLOADED}.
     * @param cellIndex palette index of the serving cell, or {@link #NO_CELL}.
     * @param level     {@link ServiceLevel#ordinal()}, or {@link #UNSURVEYED}.
     */
    public record Result(Spec spec, List<CellParams> palette, int[] surfaceY, int[] cellIndex, byte[] level) {

        public Result {
            Objects.requireNonNull(spec, "spec");
            palette = List.copyOf(palette);
            int points = spec.pointCount();
            if (surfaceY.length != points || cellIndex.length != points || level.length != points) {
                throw new IllegalArgumentException("arrays must all hold " + points + " points");
            }
            surfaceY = surfaceY.clone();
            cellIndex = cellIndex.clone();
            level = level.clone();
            int levels = ServiceLevel.values().length;
            for (int k = 0; k < points; k++) {
                if (cellIndex[k] != NO_CELL && (cellIndex[k] < 0 || cellIndex[k] >= palette.size())) {
                    throw new IllegalArgumentException("cellIndex[" + k + "] out of palette range");
                }
                if (level[k] != UNSURVEYED && (level[k] < 0 || level[k] >= levels)) {
                    throw new IllegalArgumentException("level[" + k + "] is not a service level");
                }
            }
        }

        public boolean isSurveyed(int index) {
            return level[index] != UNSURVEYED;
        }

        /** The serving cell at a point, or null when nothing serves it (or it was not surveyed). */
        public CellParams servingAt(int index) {
            int cell = cellIndex[index];
            return cell == NO_CELL ? null : palette.get(cell);
        }

        /** The service level at a point, or null when it was not surveyed. */
        public ServiceLevel levelAt(int index) {
            return level[index] == UNSURVEYED ? null : ServiceLevel.values()[level[index]];
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof Result that
                    && spec.equals(that.spec)
                    && palette.equals(that.palette)
                    && Arrays.equals(surfaceY, that.surfaceY)
                    && Arrays.equals(cellIndex, that.cellIndex)
                    && Arrays.equals(level, that.level);
        }

        @Override
        public int hashCode() {
            int hash = Objects.hash(spec, palette);
            hash = 31 * hash + Arrays.hashCode(surfaceY);
            hash = 31 * hash + Arrays.hashCode(cellIndex);
            return 31 * hash + Arrays.hashCode(level);
        }

        @Override
        public String toString() {
            return "Result[spec=" + spec + ", palette=" + palette.size() + " cells, points=" + level.length + "]";
        }
    }

    /**
     * A grid of {@code size x size} points, {@code step} blocks apart, centred as nearly as the snap
     * allows on (x, z).
     *
     * <p>The origin is rounded to a multiple of {@code step}, so the grid's points sit on the same
     * block columns wherever the player stands; the centre is within half a step of (x, z). Without
     * the snap every tiny move would shift every sample to a different column and the painting
     * would shimmer as the player walked.
     *
     * @param bandFilter a band id, or {@code ""} for all bands.
     */
    public static Spec centredOn(double x, double z, int step, int size, String bandFilter) {
        if (step < 1) {
            throw new IllegalArgumentException("step must be >= 1, was " + step);
        }
        double half = (double) size * step / 2.0;
        return new Spec(snap(x - half, step), snap(z - half, step), step, size, bandFilter);
    }

    private static int snap(double value, int step) {
        return (int) Math.floor(value / step + 0.5) * step;
    }

    private final Spec spec;
    private final List<CellParams> candidates;
    private final Map<Long, CellParams> candidatesById;
    private final BandTable bands;
    private final RfConfig config;

    private final int[] surfaceY;
    private final int[] cellIndex;
    private final byte[] level;
    private final List<CellParams> palette = new ArrayList<>();
    private final Map<Long, Integer> paletteIndex = new HashMap<>();

    private int processed;
    private Result result;

    /**
     * @param candidates cells that might serve somewhere on the grid. Filtered to the spec's band
     *                   and de-duplicated by cell id (first wins) once, here, not per point.
     */
    public CoverageSurvey(Spec spec, Collection<CellParams> candidates, BandTable bands, RfConfig config) {
        this.spec = Objects.requireNonNull(spec, "spec");
        this.bands = Objects.requireNonNull(bands, "bands");
        this.config = Objects.requireNonNull(config, "config");

        Map<Long, CellParams> byId = new LinkedHashMap<>();
        for (CellParams cell : candidates) {
            if (!spec.filtersBand() || spec.bandFilter().equals(cell.bandId())) {
                byId.putIfAbsent(cell.cellId(), cell);
            }
        }
        this.candidatesById = byId;
        this.candidates = List.copyOf(byId.values());

        int points = spec.pointCount();
        this.surfaceY = new int[points];
        this.cellIndex = new int[points];
        this.level = new byte[points];
    }

    /**
     * Processes up to {@code maxPoints} further points, in index order; returns how many it
     * processed (0 once complete, or when {@code maxPoints <= 0}).
     *
     * <p>Slicing does not change the answer: any sequence of calls produces the same
     * {@link Result} as one call that does every point.
     */
    public int step(WorldProbe probe, SurfaceProbe surface, int maxPoints) {
        int budget = Math.min(Math.max(0, maxPoints), total() - processed);
        for (int n = 0; n < budget; n++) {
            surveyPoint(processed, probe, surface);
            processed++;
        }
        return budget;
    }

    public boolean isComplete() {
        return processed == total();
    }

    public int processed() {
        return processed;
    }

    public int total() {
        return spec.pointCount();
    }

    public Spec spec() {
        return spec;
    }

    /** The cells this survey evaluates, after the band filter and de-duplication. */
    public List<CellParams> candidates() {
        return candidates;
    }

    /** @throws IllegalStateException unless {@link #isComplete()} */
    public Result result() {
        if (!isComplete()) {
            throw new IllegalStateException(
                    "survey incomplete: " + processed + " of " + total() + " points processed");
        }
        if (result == null) {
            result = new Result(spec, palette, surfaceY, cellIndex, level);
        }
        return result;
    }

    private void surveyPoint(int index, WorldProbe probe, SurfaceProbe surface) {
        int i = index % spec.size();
        int j = index / spec.size();

        // The tile [blockX, blockX + step) is measured at its centre, not its corner, so the painted
        // mosaic is not shifted half a step against the ground it describes.
        int ground = surface.surfaceY(spec.sampleX(i), spec.sampleZ(j));
        if (ground == SurfaceProbe.UNLOADED) {
            // Never guess a height and never load a chunk to find one: leave a hole in the plot.
            surfaceY[index] = SurfaceProbe.UNLOADED;
            cellIndex[index] = NO_CELL;
            level[index] = UNSURVEYED;
            return;
        }

        SignalSample sample = RfEngine.evaluate(
                probe,
                spec.receiverX(i), ground + RECEIVER_EYE_HEIGHT, spec.receiverZ(j),
                candidates, bands, config,
                0L, ReceiverState.NONE).sample();

        surfaceY[index] = ground;
        level[index] = (byte) sample.serviceLevel().ordinal();
        cellIndex[index] = sample.serving()
                .map(serving -> paletteIndexOf(serving.cellId()))
                .orElse(NO_CELL);
    }

    private int paletteIndexOf(long cellId) {
        Integer existing = paletteIndex.get(cellId);
        if (existing != null) {
            return existing;
        }
        CellParams cell = candidatesById.get(cellId);
        if (cell == null) {
            // RfEngine only ever serves a cell it was given, so this is a bug, not bad input.
            throw new IllegalStateException("serving cell " + cellId + " is not a survey candidate");
        }
        int index = palette.size();
        palette.add(cell);
        paletteIndex.put(cellId, index);
        return index;
    }
}
