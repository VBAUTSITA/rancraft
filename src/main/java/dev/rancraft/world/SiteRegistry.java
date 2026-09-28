package dev.rancraft.world;

import dev.rancraft.rf.CellParams;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;

/**
 * Per-level index of transmitting masts.
 *
 * <p>Backed by coarse XZ bins so {@link #near} costs O(bins touched) rather than O(all sites).
 * Binning is 2D on purpose: world height is ~384 blocks against a 600-block search radius, so a
 * Y axis would add buckets without excluding anything.
 *
 * <p>A mast in an unloaded chunk is simply absent from the registry and therefore does not
 * transmit. That is intended Phase 1 behaviour -- the alternative is force-loading chunks to hunt
 * for towers.
 */
public final class SiteRegistry {

    /** Bin edge length in blocks. */
    public static final int BIN_SIZE = 128;

    private static final Map<ResourceKey<Level>, SiteRegistry> BY_LEVEL = new ConcurrentHashMap<>();

    private final Long2ObjectMap<CellParams> byId = new Long2ObjectOpenHashMap<>();
    private final Long2ObjectMap<List<CellParams>> bins = new Long2ObjectOpenHashMap<>();

    private long version;

    public static SiteRegistry of(ServerLevel level) {
        return BY_LEVEL.computeIfAbsent(level.dimension(), key -> new SiteRegistry());
    }

    /** Called on server stop so a second world load in the same JVM starts clean. */
    public static void clearAll() {
        BY_LEVEL.clear();
    }

    /**
     * Adds or replaces a cell. Re-registering an identical cell is a no-op and does not bump
     * {@link #version()}: chunk loads and redstone refreshes re-register unchanged antennas all the
     * time, and every one of them would otherwise invalidate every cached sample and survey.
     */
    public synchronized void register(CellParams cell) {
        CellParams previous = byId.put(cell.cellId(), cell);
        if (cell.equals(previous)) {
            // Records compare by value, so this is "nothing about the cell changed". The bin
            // already holds an equal entry.
            return;
        }
        if (previous != null) {
            removeFromBin(previous);
        }
        bins.computeIfAbsent(binKey(cell.x(), cell.z()), key -> new ArrayList<>()).add(cell);
        version++;
    }

    public synchronized void unregister(long cellId) {
        CellParams removed = byId.remove(cellId);
        if (removed != null) {
            removeFromBin(removed);
            version++;
        }
    }

    public synchronized int size() {
        return byId.size();
    }

    /**
     * Bumped whenever the registry's content actually changes: a cell added, removed, or
     * reconfigured (retilted, re-banded, re-PCI'd). Anything derived from the network -- a cached
     * sample, a coverage survey -- compares this to decide whether it is stale.
     *
     * <p>Monotonic for the life of this registry. {@link #clearAll()} discards registries, so a
     * fresh world starts again from 0; nothing outlives a server stop to compare across that.
     */
    public synchronized long version() {
        return version;
    }

    /** Candidate cells whose radiating point lies within {@code radius} of the given position. */
    public synchronized Collection<CellParams> near(double x, double y, double z, double radius) {
        int minBinX = Math.floorDiv((int) Math.floor(x - radius), BIN_SIZE);
        int maxBinX = Math.floorDiv((int) Math.floor(x + radius), BIN_SIZE);
        int minBinZ = Math.floorDiv((int) Math.floor(z - radius), BIN_SIZE);
        int maxBinZ = Math.floorDiv((int) Math.floor(z + radius), BIN_SIZE);

        double radiusSq = radius * radius;
        List<CellParams> found = new ArrayList<>();

        for (int binX = minBinX; binX <= maxBinX; binX++) {
            for (int binZ = minBinZ; binZ <= maxBinZ; binZ++) {
                List<CellParams> bin = bins.get(binKeyFromBin(binX, binZ));
                if (bin == null) {
                    continue;
                }
                for (CellParams cell : bin) {
                    double dx = (cell.x() + 0.5) - x;
                    double dy = (cell.y() + 0.5) - y;
                    double dz = (cell.z() + 0.5) - z;
                    if (dx * dx + dy * dy + dz * dz <= radiusSq) {
                        found.add(cell);
                    }
                }
            }
        }
        return found;
    }

    private void removeFromBin(CellParams cell) {
        long key = binKey(cell.x(), cell.z());
        List<CellParams> bin = bins.get(key);
        if (bin == null) {
            return;
        }
        bin.removeIf(existing -> existing.cellId() == cell.cellId());
        if (bin.isEmpty()) {
            bins.remove(key);
        }
    }

    private static long binKey(int x, int z) {
        return binKeyFromBin(Math.floorDiv(x, BIN_SIZE), Math.floorDiv(z, BIN_SIZE));
    }

    private static long binKeyFromBin(int binX, int binZ) {
        return (((long) binX) << 32) ^ (binZ & 0xFFFFFFFFL);
    }
}
