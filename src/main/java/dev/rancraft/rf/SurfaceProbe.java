package dev.rancraft.rf;

/**
 * Where the ground is. The second (and only other) channel through which {@code rf} reads the world.
 *
 * <p>{@link CoverageSurvey} needs to stand a receiver on the terrain at every grid point, and the
 * height map is a Minecraft concept. Keeping it behind a one-method interface, like
 * {@link WorldProbe}, is what keeps {@code dev.rancraft.rf} free of Minecraft imports and lets the
 * tests survey synthetic ground.
 *
 * <p><b>Abstraction:</b> one height per column. A survey therefore paints the top surface only;
 * caves, overhangs and the floors of a building under a roof are not surveyed.
 */
@FunctionalInterface
public interface SurfaceProbe {

    /**
     * Returned when the column cannot be read (its chunk is not loaded), or has no ground to stand
     * on (a void column). The survey marks such a point {@link CoverageSurvey#UNSURVEYED} rather
     * than guessing a height or loading a chunk.
     */
    int UNLOADED = Integer.MIN_VALUE;

    /** y of the first free block above the ground at (x,z) — where feet stand — or UNLOADED. */
    int surfaceY(int x, int z);
}
