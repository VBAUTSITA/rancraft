package dev.rancraft.rf;

import java.util.Arrays;

/**
 * Which square XZ bins a straight ray crosses: a 2D DDA (Amanatides-Woo) over bins instead of
 * voxels. Phase 3 slice 7 (§3B.2, region block epochs).
 *
 * <h2>What it is for</h2>
 * A cached evaluation is replayed while nothing it depends on has changed. Until slice 7 "nothing"
 * meant "no block placed or broken anywhere in the dimension", so on a busy server every cache
 * was invalidated by every block anyone touched. Now the world is cut into
 * {@code binSize x binSize} XZ bins (the server uses {@code SiteRegistry.BIN_SIZE}, 128), each with
 * its own block-change counter, and an evaluation depends only on the bins its rays cross.
 *
 * <h2>Why "the bins along every marched ray" is exact, not an approximation</h2>
 * An evaluation ({@link RfEngine#evaluate}) reads the world in one place only: the voxel march of
 * each cell it decided to march. <b>Which cells are marched cannot change with blocks.</b> They are
 * chosen by the distance filter, then by the <em>optimistic</em> RSRP ({@code Tx + gain - PL(d)}
 * with zero obstruction), then by the {@code maxCellsEvaluated} cap applied in that optimistic
 * order. None of the three has an obstruction term: they read the receiver position, the cells'
 * declared parameters and the config, never a block. And the cells that were not marched cannot be
 * revived by any block change: a cell pruned by the budget could not be heard even through open
 * air, and removing a block can only lower the loss towards that open-air value, never below it; a
 * cell cut by the cap loses to at least {@code maxCellsEvaluated} others on a ranking no block
 * enters. So for a fixed receiver point and a fixed set of cells, the marched rays are fixed, and
 * the result depends on the world only through the voxels those rays visit. Every such voxel lies
 * in a bin this class returns for its ray (the voxel march and this bin march step through the
 * same line; see "Conservative at bin corners" below). A block change in any other bin therefore
 * cannot change the result, so the cached sample is still exactly what a fresh evaluation would
 * produce.
 *
 * <p>The other inputs are covered separately by the cache: the cells (the site registry version)
 * and the receiver point (the 0.5-block move). One more input is not a block at all: the server's
 * probe reads a voxel in a chunk that is not loaded (as FULL) as air, so a chunk on a ray loading or
 * unloading changes what the ray reads with no block changing. The server bumps the chunk's bin
 * then as well (a 16-block chunk lies inside one bin), so the argument above holds for that too
 * (Phase 3B review fix; NOTES.md, slice 7). Early exits make the set a superset, never a subset:
 * a ray that stopped at a wall still lists the bins past it, which is conservative (a change there
 * could not have altered an early exit, but it costs only an unnecessary re-evaluation).
 *
 * <h2>Conservative at bin corners</h2>
 * Bin edges are also voxel edges, and both marches compute the crossing of an edge as the same
 * parameter {@code t = (edge - x0) / dx} along the same segment. The only place they could
 * disagree is a ray passing (within rounding) exactly through a bin corner, where the voxel march
 * steps one axis first and a floating-point error could make it pick the other. There this march
 * takes <b>both</b> side bins, so whichever the voxel march visits is covered. The window is
 * {@link #CORNER_EPSILON} in {@code t}, thousands of times the error either march can accumulate
 * over its steps. The start and end bins are always included. {@code BinTraversalTest} checks the
 * containment against the real {@link RayMarcher} on random and corner-grazing rays.
 *
 * <h2>Bin keys</h2>
 * A bin is packed into one {@code long}: its x index in the high 32 bits, its z index in the low 32
 * ({@link #key}), the same packing {@code SiteRegistry} uses. A block at {@code (x, z)} is in bin
 * {@code (floorDiv(x, binSize), floorDiv(z, binSize))} ({@link #keyOfBlock}).
 *
 * <p>Pure: no game types, so it is unit-tested headless and the server's cache key is the same code
 * the tests pin.
 */
public final class BinTraversal {

    private BinTraversal() {
    }

    /**
     * How close (in the ray parameter {@code t}, 0 at the start, 1 at the end) two crossings must be
     * to count as one pass through a bin corner, in which case both side bins are taken. The voxel
     * march accumulates at most about {@code maxRaySteps x 2.2e-16} of rounding in {@code t}
     * (under 1e-12 at 1200 steps); this is three orders of magnitude wider. For a 1400-block ray it
     * is a strip about 1.4e-6 blocks wide around each corner.
     */
    public static final double CORNER_EPSILON = 1e-9;

    /**
     * The bins the segment from {@code (x0, z0)} to {@code (x1, z1)} crosses, start bin first, in
     * the order the ray enters them, each once. Coordinates are in blocks; a point is in the bin of
     * the block it is inside ({@code floor}), exactly as the voxel march decides its voxels.
     *
     * <p>Start and end in the same bin: that one bin (a bin is convex, so the segment never leaves
     * it). A ray along a bin edge belongs to the bin its coordinate floors into, as its voxels do.
     *
     * @param binSize bin edge in blocks, at least 1.
     * @return packed keys ({@link #key}); never empty.
     * @throws IllegalArgumentException if {@code binSize < 1} or a coordinate is not finite.
     */
    public static long[] binsAlong(double x0, double z0, double x1, double z1, int binSize) {
        if (binSize < 1) {
            throw new IllegalArgumentException("binSize must be at least 1, was " + binSize);
        }
        if (!Double.isFinite(x0) || !Double.isFinite(z0) || !Double.isFinite(x1) || !Double.isFinite(z1)) {
            throw new IllegalArgumentException(
                    "coordinates must be finite: (" + x0 + ", " + z0 + ") -> (" + x1 + ", " + z1 + ")");
        }

        int binX = binOf(floorInt(x0), binSize);
        int binZ = binOf(floorInt(z0), binSize);
        final int endX = binOf(floorInt(x1), binSize);
        final int endZ = binOf(floorInt(z1), binSize);

        if (binX == endX && binZ == endZ) {
            return new long[] {key(binX, binZ)};
        }

        final double dx = x1 - x0;
        final double dz = z1 - z0;
        final int stepX = dx > 0.0 ? 1 : (dx < 0.0 ? -1 : 0);
        final int stepZ = dz > 0.0 ? 1 : (dz < 0.0 ? -1 : 0);

        // t runs over the whole segment, so t == 1 is the end point, as in RayMarcher.
        double tMaxX = firstCrossing(x0, dx, binX, binSize);
        double tMaxZ = firstCrossing(z0, dz, binZ, binSize);
        final double tDeltaX = dx == 0.0 ? Double.POSITIVE_INFINITY : binSize / Math.abs(dx);
        final double tDeltaZ = dz == 0.0 ? Double.POSITIVE_INFINITY : binSize / Math.abs(dz);

        // Every step moves one axis one bin towards the end (a corner moves both), so the exact
        // march takes |endX - binX| + |endZ - binZ| steps. The margin covers a last step past the end
        // inside CORNER_EPSILON; the cap only guards against an unforeseen floating-point loop.
        int maxSteps = Math.abs(endX - binX) + Math.abs(endZ - binZ) + 2;
        long[] out = new long[maxSteps * 2 + 2];
        int count = 0;
        out[count++] = key(binX, binZ);

        for (int step = 0; step < maxSteps; step++) {
            double next = Math.min(tMaxX, tMaxZ);
            if (next > 1.0 + CORNER_EPSILON) {
                break;
            }
            if (Math.abs(tMaxX - tMaxZ) <= CORNER_EPSILON) {
                // Through a bin corner, within rounding: take both side bins, then the diagonal.
                out[count++] = key(binX + stepX, binZ);
                out[count++] = key(binX, binZ + stepZ);
                binX += stepX;
                binZ += stepZ;
                tMaxX += tDeltaX;
                tMaxZ += tDeltaZ;
            } else if (tMaxX < tMaxZ) {
                binX += stepX;
                tMaxX += tDeltaX;
            } else {
                binZ += stepZ;
                tMaxZ += tDeltaZ;
            }
            out[count++] = key(binX, binZ);
        }

        long endKey = key(endX, endZ);
        if (!contains(out, count, endKey)) {
            out[count++] = endKey;
        }
        return distinct(out, count);
    }

    /** The union of several {@link #binsAlong} results: sorted by key, each once. */
    public static long[] union(long[]... bins) {
        int total = 0;
        for (long[] set : bins) {
            total += set.length;
        }
        long[] all = new long[total];
        int at = 0;
        for (long[] set : bins) {
            System.arraycopy(set, 0, all, at, set.length);
            at += set.length;
        }
        return sortedDistinct(all);
    }

    /** {@code keys} sorted ascending with duplicates removed. A new array; the input is not changed. */
    public static long[] sortedDistinct(long[] keys) {
        long[] sorted = keys.clone();
        Arrays.sort(sorted);
        int kept = 0;
        for (int i = 0; i < sorted.length; i++) {
            if (i == 0 || sorted[i] != sorted[i - 1]) {
                sorted[kept++] = sorted[i];
            }
        }
        return kept == sorted.length ? sorted : Arrays.copyOf(sorted, kept);
    }

    /** The bin index a block coordinate falls in: {@code floorDiv(block, binSize)}. */
    public static int binOf(int block, int binSize) {
        return Math.floorDiv(block, binSize);
    }

    /** The packed key of the bin holding the block at {@code (blockX, blockZ)}. */
    public static long keyOfBlock(int blockX, int blockZ, int binSize) {
        return key(binOf(blockX, binSize), binOf(blockZ, binSize));
    }

    /** Packs bin indices: x in the high 32 bits, z in the low 32 (the {@code SiteRegistry} packing). */
    public static long key(int binX, int binZ) {
        return (((long) binX) << 32) ^ (binZ & 0xFFFFFFFFL);
    }

    /** The x index of a packed key. */
    public static int binX(long key) {
        return (int) (key >> 32);
    }

    /** The z index of a packed key. */
    public static int binZ(long key) {
        return (int) key;
    }

    /** Distance along the ray, in units of t, to the first bin edge in this axis. */
    private static double firstCrossing(double origin, double delta, int bin, int binSize) {
        if (delta == 0.0) {
            return Double.POSITIVE_INFINITY;
        }
        double edge = delta > 0.0 ? (double) (bin + 1) * binSize : (double) bin * binSize;
        return (edge - origin) / delta;
    }

    private static boolean contains(long[] keys, int count, long key) {
        for (int i = 0; i < count; i++) {
            if (keys[i] == key) {
                return true;
            }
        }
        return false;
    }

    /** The first {@code count} keys with repeats removed, first occurrence kept (so in ray order). */
    private static long[] distinct(long[] keys, int count) {
        long[] out = new long[count];
        int kept = 0;
        for (int i = 0; i < count; i++) {
            if (!contains(out, kept, keys[i])) {
                out[kept++] = keys[i];
            }
        }
        return kept == out.length ? out : Arrays.copyOf(out, kept);
    }

    private static int floorInt(double v) {
        return (int) Math.floor(v);
    }
}
