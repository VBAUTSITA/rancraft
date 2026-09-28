package dev.rancraft.rf;

import java.util.HashSet;
import java.util.Set;

/** Synthetic worlds for driving the engine without Minecraft. */
final class TestProbes {

    private TestProbes() {
    }

    /** Counts how many voxels the marcher actually asked about. */
    static final class Counting implements WorldProbe {
        private final double valueDb;
        int calls;

        Counting(double valueDb) {
            this.valueDb = valueDb;
        }

        @Override
        public double attenuationDbAt(int x, int y, int z) {
            calls++;
            return valueDb;
        }
    }

    /** Attenuating blocks at an explicit set of voxels; air everywhere else. */
    static final class Sparse implements WorldProbe {
        private final Set<Long> solid = new HashSet<>();
        private final double valueDb;

        Sparse(double valueDb) {
            this.valueDb = valueDb;
        }

        Sparse add(int x, int y, int z) {
            solid.add(key(x, y, z));
            return this;
        }

        @Override
        public double attenuationDbAt(int x, int y, int z) {
            return solid.contains(key(x, y, z)) ? valueDb : 0.0;
        }

        private static long key(int x, int y, int z) {
            return (((long) x) * 73856093L) ^ (((long) y) * 19349663L) ^ (((long) z) * 83492791L);
        }
    }

    /** Deterministic scatter, used to give the symmetry test something non-trivial to chew on. */
    static final class Scatter implements WorldProbe {
        private final double valueDb;

        Scatter(double valueDb) {
            this.valueDb = valueDb;
        }

        @Override
        public double attenuationDbAt(int x, int y, int z) {
            return Math.floorMod(x + y + z, 7) == 0 ? valueDb : 0.0;
        }
    }
}
