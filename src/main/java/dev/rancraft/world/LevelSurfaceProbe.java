package dev.rancraft.world;

import dev.rancraft.rf.SurfaceProbe;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.Heightmap;

/**
 * {@link SurfaceProbe} backed by a live {@link ServerLevel}: where the top surface is, for columns in
 * chunks that are already loaded, and never a chunk load.
 *
 * <p>Shared by the coverage survey ({@link CoverageSurveyor}, where it started) and the Network
 * Locator's altitude aiding (Phase 3 slice 5), so both stand a receiver on the ground the same way.
 * Server thread only: it reads live chunk data.
 *
 * <p>Feet level on the top surface is the first free block above the motion-blocking, non-leaf
 * ground, which is what {@code Level.getHeight} returns for a loaded chunk.
 *
 * <p>Reads the chunk through {@link ServerChunkCache#getChunkNow}, which returns only a chunk that
 * has finished loading and never waits. {@code hasChunk} alone is not enough: it checks the chunk's
 * ticket, and {@code getHeight} would then block the server thread until a chunk that is still
 * generating is done. A column that is not ready is {@link SurfaceProbe#UNLOADED}.
 *
 * <p>{@link SurfaceProbe#UNLOADED} also covers "no ground in this column". An empty column (the
 * End's void, a void world) has a heightmap at the bottom of the world, which would put a receiver
 * on a floor that is not there.
 */
public final class LevelSurfaceProbe implements SurfaceProbe {

    private final ServerChunkCache chunks;
    private final int minY;

    public LevelSurfaceProbe(ServerLevel level) {
        this.chunks = level.getChunkSource();
        this.minY = level.getMinBuildHeight();
    }

    /**
     * The probe the Network Locator uses for altitude aiding in {@code level}.
     *
     * <p><b>Under a ceiling (the Nether), every column is unknown.</b> The top-down heightmap finds
     * the bedrock roof there, not the floor a player stands on, so reading it would assume the
     * receiver stands on the roof. Reporting {@link SurfaceProbe#UNLOADED} instead makes the solver
     * fall back to its last known height (the previous fix, or else the lowest radiating point
     * among the cells heard): a guess, labelled as one in NOTES.md, rather than a wrong reading.
     * The coverage survey does not run under a ceiling at all.
     */
    public static SurfaceProbe forLocator(ServerLevel level) {
        if (level.dimensionType().hasCeiling()) {
            return (x, z) -> SurfaceProbe.UNLOADED;
        }
        return new LevelSurfaceProbe(level);
    }

    @Override
    public int surfaceY(int x, int z) {
        LevelChunk chunk = chunks.getChunkNow(x >> 4, z >> 4);
        if (chunk == null) {
            return SurfaceProbe.UNLOADED;
        }
        int feet = chunk.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x & 15, z & 15) + 1;
        // The heightmap of an empty column is the bottom of the world: no ground to stand a
        // receiver on, so report none rather than a fake floor.
        return feet <= minY ? SurfaceProbe.UNLOADED : feet;
    }
}
