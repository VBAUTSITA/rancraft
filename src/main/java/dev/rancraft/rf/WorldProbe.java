package dev.rancraft.rf;

/**
 * The only channel through which the RF engine touches the world.
 *
 * <p>Keeping this a single-method interface is what lets {@code dev.rancraft.rf} compile with no
 * Minecraft on the classpath, and lets the unit tests drive the engine with synthetic geometry.
 */
@FunctionalInterface
public interface WorldProbe {

    /** Attenuation contributed by the block occupying this voxel, in dB. Air returns 0. */
    double attenuationDbAt(int x, int y, int z);

    /** An entirely empty world. Useful for free-space baselines. */
    WorldProbe AIR = (x, y, z) -> 0.0;
}
