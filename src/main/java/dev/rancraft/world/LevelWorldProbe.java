package dev.rancraft.world;

import dev.rancraft.data.MaterialTable;
import dev.rancraft.rf.WorldProbe;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.LevelChunk;

/**
 * Binds the RF engine to a real level.
 *
 * <p>Reads only chunks that have finished loading, and never waits for one. Blocks are read
 * through {@link ServerChunkCache#getChunkNow}, which returns a chunk only once it is FULL and
 * otherwise returns {@code null} at once. {@code Level.hasChunkAt} is not enough: it checks only
 * the chunk's ticket level, which a chunk reaches while it is still generating, and
 * {@code Level.getBlockState} on such a chunk blocks the server thread
 * ({@code ServerChunkCache.getChunk} -> {@code managedBlock}) until worldgen is done -- a stall
 * inside one probe call, which no tick budget between calls can bound. A voxel in a chunk that is
 * unloaded or still generating reads as air: no chunk load is forced and nothing waits. That
 * matches the coverage survey's surface probe ({@code CoverageSurveyor.surfaceOf}). Since what a ray
 * reads therefore changes when a chunk on it reaches or leaves FULL, {@link RegionEpochs} bumps that
 * chunk's bin then, so a cached sample is not replayed past it (Phase 3B review fix).
 *
 * <p><b>Server thread only.</b> {@code getChunkNow} returns {@code null} on any other thread, so
 * off-thread every voxel would read as air. Every caller ({@link SignalTicker}'s evaluation and
 * link tracing, {@link CoverageSurveyor}) runs in the server tick.
 *
 * <p>The last chunk read is cached, since a ray walks through neighbouring voxels and almost
 * always stays in the same chunk. Caching it -- including a cached "not ready" -- is safe because
 * a probe lives for one evaluation or one tick slice on the server thread, and no chunk finishes
 * loading or unloads while that code runs.
 *
 * <p>Not thread safe -- it carries a mutable cursor and the chunk cache. One instance per
 * evaluation (or per coverage job per tick).
 */
public final class LevelWorldProbe implements WorldProbe {

    private final ServerChunkCache chunks;
    private final MaterialTable materials;
    private final BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();

    private final int minY;
    private final int maxY;

    /** Chunk column last looked up. */
    private int chunkX = Integer.MIN_VALUE;
    private int chunkZ = Integer.MIN_VALUE;
    /** That column's chunk, or {@code null} when it is not loaded or not yet FULL: reads as air. */
    private LevelChunk chunk;

    private int probes;

    public LevelWorldProbe(ServerLevel level, MaterialTable materials) {
        this.chunks = level.getChunkSource();
        this.materials = materials;
        this.minY = level.getMinBuildHeight();
        this.maxY = level.getMaxBuildHeight();
    }

    @Override
    public double attenuationDbAt(int x, int y, int z) {
        probes++;

        if (y < minY || y >= maxY) {
            return 0.0;
        }

        int cx = x >> 4;
        int cz = z >> 4;
        if (cx != chunkX || cz != chunkZ) {
            // Never waits: null unless the chunk has finished loading.
            chunk = chunks.getChunkNow(cx, cz);
            chunkX = cx;
            chunkZ = cz;
        }
        if (chunk == null) {
            return 0.0; // not loaded, or still generating: read as air, never wait for it
        }
        cursor.set(x, y, z);
        return materials.attenuationDb(chunk.getBlockState(cursor));
    }

    /** Voxels probed since construction. Used for the slow-evaluation warning. */
    public int probes() {
        return probes;
    }
}
