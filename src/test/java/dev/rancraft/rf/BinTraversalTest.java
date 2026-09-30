package dev.rancraft.rf;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link BinTraversal}: the 2D DDA over bins behind the region block epochs (Phase 3 slice 7,
 * §3B.2). The four cases §3B's test list names (axis-aligned, diagonal, negative coordinates, start
 * and end in the same bin), then the property the cache relies on: every voxel {@link RayMarcher}
 * reads lies in a bin this returns for the same ray, on random rays and on rays through bin corners.
 */
class BinTraversalTest {

    private static final int BIN = 128;

    /** Bins as "x,z" strings in the order returned, for readable failures. */
    private static List<String> named(long[] keys) {
        List<String> names = new ArrayList<>(keys.length);
        for (long key : keys) {
            names.add(BinTraversal.binX(key) + "," + BinTraversal.binZ(key));
        }
        return names;
    }

    private static List<String> along(double x0, double z0, double x1, double z1) {
        return named(BinTraversal.binsAlong(x0, z0, x1, z1, BIN));
    }

    // ---- the four named cases ---------------------------------------------------------------------

    @Test
    @DisplayName("axis-aligned: every bin crossed, once, in ray order, in both directions and on both axes")
    void axisAligned() {
        assertEquals(List.of("0,0", "1,0", "2,0"), along(10.5, 20.5, 300.5, 20.5));
        assertEquals(List.of("2,0", "1,0", "0,0"), along(300.5, 20.5, 10.5, 20.5));
        assertEquals(List.of("3,0", "3,1", "3,2", "3,3"), along(400.5, 1.5, 400.5, 500.5));
        assertEquals(List.of("0,0", "1,0"), along(127.5, 5.5, 128.5, 5.5), "one block across an edge");
    }

    @Test
    @DisplayName("diagonal: the bins the line really crosses, and both side bins through an exact corner")
    void diagonal() {
        // z = 10.5 + (x - 10.5) * 190 / 290: x = 128 at z 87.5, z = 128 at x 189.8, x = 256 at z 171.3.
        assertEquals(List.of("0,0", "1,0", "1,1", "2,1"), along(10.5, 10.5, 300.5, 200.5));
        // Exactly through the corner (128, 128): the voxel march may step either axis first there.
        assertEquals(List.of("0,0", "1,0", "0,1", "1,1"), along(0.5, 0.5, 255.5, 255.5));
        assertEquals(List.of("1,1", "0,1", "1,0", "0,0"), along(255.5, 255.5, 0.5, 0.5));
        // Near a corner but not through it: z = 128 is crossed first (at x 115.5), then x = 128 (at z 134.25).
        assertEquals(List.of("0,0", "0,1", "1,1"), along(100.5, 120.5, 140.5, 140.5));
    }

    @Test
    @DisplayName("negative coordinates: floor division, so -1 is bin -1 and -128 is bin -1, -129 bin -2")
    void negativeCoordinates() {
        assertEquals(List.of("-1,-1", "-2,-1", "-3,-1"), along(-10.5, -20.5, -300.5, -20.5));
        assertEquals(List.of("-1,0", "0,0"), along(-5.5, 3.5, 5.5, 3.5), "across zero");
        assertEquals(List.of("-1,-1", "-1,0", "0,0"), along(-0.5, -0.25, 0.75, 1.75), "across both zero lines");
        assertEquals(List.of("-1,-1"), along(-128.0, -1.0, -1.0, -128.0), "-128 and -1 are both bin -1");
        assertEquals(List.of("-2,0", "-1,0"), along(-128.5, 0.5, -127.5, 0.5), "-129 is bin -2");
        assertEquals(-1, BinTraversal.binOf(-1, BIN));
        assertEquals(-1, BinTraversal.binOf(-128, BIN));
        assertEquals(-2, BinTraversal.binOf(-129, BIN));
    }

    @Test
    @DisplayName("start and end in the same bin: that bin only, whatever the direction; also a zero-length ray")
    void sameBin() {
        assertEquals(List.of("0,0"), along(1.5, 2.5, 127.9, 100.5));
        assertEquals(List.of("0,0"), along(127.9, 100.5, 1.5, 2.5));
        assertEquals(List.of("-3,5"), along(-300.5, 700.5, -300.5, 700.5));
        assertEquals(List.of("-3,5"), along(-257.0, 640.0, -383.99, 767.99));
    }

    // ---- keys and helpers -------------------------------------------------------------------------

    @Test
    @DisplayName("keys pack x high and z low, round-trip negatives, and match SiteRegistry's packing")
    void keys() {
        for (int x : new int[] {0, 1, -1, 12345, -98765, Integer.MAX_VALUE, Integer.MIN_VALUE}) {
            for (int z : new int[] {0, 1, -1, 54321, -4242, Integer.MAX_VALUE, Integer.MIN_VALUE}) {
                long key = BinTraversal.key(x, z);
                assertEquals(x, BinTraversal.binX(key));
                assertEquals(z, BinTraversal.binZ(key));
                assertEquals((((long) x) << 32) ^ (z & 0xFFFFFFFFL), key);
            }
        }
        assertEquals(BinTraversal.key(-1, 3), BinTraversal.keyOfBlock(-1, 400, BIN));
    }

    @Test
    @DisplayName("union and sortedDistinct: sorted, each key once, inputs untouched")
    void unionIsSortedAndDistinct() {
        long[] a = {BinTraversal.key(2, 0), BinTraversal.key(1, 0), BinTraversal.key(0, 0)};
        long[] b = {BinTraversal.key(0, 0), BinTraversal.key(-1, 5)};
        long[] union = BinTraversal.union(a, b);
        assertEquals(4, union.length);
        for (int i = 1; i < union.length; i++) {
            assertTrue(union[i - 1] < union[i]);
        }
        assertEquals(BinTraversal.key(2, 0), a[0], "input not reordered");
        assertEquals(0, BinTraversal.union().length);
    }

    @Test
    @DisplayName("a bin size below 1 or a coordinate that is not finite is refused")
    void refusesNonsense() {
        assertThrows(IllegalArgumentException.class, () -> BinTraversal.binsAlong(0, 0, 10, 10, 0));
        assertThrows(IllegalArgumentException.class, () -> BinTraversal.binsAlong(Double.NaN, 0, 10, 10, BIN));
        assertThrows(IllegalArgumentException.class,
                () -> BinTraversal.binsAlong(0, 0, Double.POSITIVE_INFINITY, 10, BIN));
    }

    @Test
    @DisplayName("a 1400-block ray (the evaluation range) returns a short list: 12 bins or fewer")
    void longRayStaysShort() {
        long[] bins = BinTraversal.binsAlong(0.5, 0.5, 1400.5, 0.5, BIN);
        assertEquals(11, bins.length);
        long[] diagonal = BinTraversal.binsAlong(0.5, 0.5, 990.5, 989.5, BIN);
        assertTrue(diagonal.length <= 16, "a diagonal crosses at most |dx| + |dz| + 1 bins: " + diagonal.length);
    }

    // ---- the property the cache relies on ---------------------------------------------------------

    /** Records the bin of every voxel the marcher asks about. Air everywhere: no early exit. */
    private static final class BinRecorder implements WorldProbe {
        private final int binSize;
        final Set<Long> bins = new TreeSet<>();
        int calls;

        BinRecorder(int binSize) {
            this.binSize = binSize;
        }

        @Override
        public double attenuationDbAt(int x, int y, int z) {
            calls++;
            bins.add(BinTraversal.keyOfBlock(x, z, binSize));
            return 0.0;
        }
    }

    private static Set<Long> setOf(long[] keys) {
        Set<Long> set = new TreeSet<>();
        for (long key : keys) {
            set.add(key);
        }
        return set;
    }

    /** Marches the 3D ray with no step cap that could bite and returns the bins of the voxels read. */
    private static BinRecorder march(double x0, double y0, double z0, double x1, double y1, double z1, int binSize) {
        BinRecorder recorder = new BinRecorder(binSize);
        RayMarcher.MarchResult result = RayMarcher.march(recorder, x0, y0, z0, x1, y1, z1, 1.0, 1e9, 1_000_000);
        assertTrue(!result.cappedOut() && !result.earlyExit(), "the march reached the receiver");
        return recorder;
    }

    @Test
    @DisplayName("random rays: every voxel RayMarcher reads is in a returned bin, and every returned bin is the start's, the end's or one it reads")
    void randomRaysMatchTheVoxelMarch() {
        Random random = new Random(0x5EED_B175L);
        for (int binSize : new int[] {BIN, 16, 5}) {
            for (int i = 0; i < 400; i++) {
                double x0 = (random.nextDouble() - 0.5) * 1600;
                double z0 = (random.nextDouble() - 0.5) * 1600;
                double y0 = random.nextDouble() * 300 - 60;
                double x1 = x0 + (random.nextDouble() - 0.5) * (binSize == BIN ? 1400 : 200);
                double z1 = z0 + (random.nextDouble() - 0.5) * (binSize == BIN ? 1400 : 200);
                double y1 = random.nextDouble() * 300 - 60;

                long[] bins = BinTraversal.binsAlong(x0, z0, x1, z1, binSize);
                BinRecorder read = march(x0, y0, z0, x1, y1, z1, binSize);

                Set<Long> returned = setOf(bins);
                String ray = "(" + x0 + ", " + z0 + ") -> (" + x1 + ", " + z1 + ") bin " + binSize;
                assertEquals(bins.length, returned.size(), "each bin once: " + ray);
                assertTrue(returned.containsAll(read.bins), "a voxel read outside the returned bins: " + ray);

                // Tight as well as safe: nothing extra beyond the two end bins (the marcher skips the
                // antenna's and the receiver's own voxels).
                Set<Long> expected = new TreeSet<>(read.bins);
                expected.add(BinTraversal.keyOfBlock((int) Math.floor(x0), (int) Math.floor(z0), binSize));
                expected.add(BinTraversal.keyOfBlock((int) Math.floor(x1), (int) Math.floor(z1), binSize));
                assertEquals(expected, returned, "extra bins: " + ray);
            }
        }
    }

    @Test
    @DisplayName("rays through bin corners at several slopes: every voxel read is in a returned bin, whichever axis the march steps first")
    void cornerRaysAreCovered() {
        int[][] slopes = {{1, 1}, {3, 1}, {1, 3}, {5, 2}, {7, 3}, {-1, 1}, {1, -1}, {-3, -1}, {2, -5}, {11, 7}};
        int checked = 0;
        for (int binSize : new int[] {BIN, 16, 4}) {
            for (int cornerX = -2; cornerX <= 2; cornerX++) {
                for (int cornerZ = -2; cornerZ <= 2; cornerZ++) {
                    double cx = (double) cornerX * binSize;
                    double cz = (double) cornerZ * binSize;
                    for (int[] slope : slopes) {
                        for (double scale : new double[] {0.75, 3.0, 17.25}) {
                            double x0 = cx - slope[0] * scale;
                            double z0 = cz - slope[1] * scale;
                            double x1 = cx + slope[0] * scale * 1.5;
                            double z1 = cz + slope[1] * scale * 1.5;
                            long[] bins = BinTraversal.binsAlong(x0, z0, x1, z1, binSize);
                            BinRecorder read = march(x0, 64.3, z0, x1, 70.9, z1, binSize);
                            assertTrue(setOf(bins).containsAll(read.bins),
                                    "corner (" + cx + ", " + cz + ") slope " + slope[0] + ":" + slope[1]
                                            + " scale " + scale + " bin " + binSize + ": " + named(bins));
                            checked++;
                        }
                    }
                }
            }
        }
        assertEquals(3 * 25 * 10 * 3, checked);
    }
}
