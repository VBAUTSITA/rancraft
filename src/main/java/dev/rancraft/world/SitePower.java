package dev.rancraft.world;

import dev.rancraft.RanCraft;
import dev.rancraft.RanCraftConfig;
import dev.rancraft.block.AntennaBlockEntity;
import dev.rancraft.rf.PowerModel;
import dev.rancraft.rf.ReceiverState;
import dev.rancraft.util.EnergyBuffer;
import dev.rancraft.util.ServedReceivers;
import it.unimi.dsi.fastutil.longs.Long2ObjectLinkedOpenHashMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.LevelChunk;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.jetbrains.annotations.Nullable;

/**
 * One dimension's site power (Phase 3 slice 15, §3C.5): draws each cell's energy every tick while
 * {@code requirePower} is on, and keeps the served-receivers seam.
 *
 * <p><b>The draw.</b> Every loaded antenna that passes its own rules (a Sector Antenna, a mast column's
 * base; the backhaul aside) is tracked here, by {@link AntennaBlockEntity#refreshRegistration()}. Once a
 * server tick (after every level has ticked, so a generator's push of this tick is in), each tracked
 * cell on the air pays {@link AntennaBlockEntity#fePerTick} ({@link PowerModel}) from its buffer
 * ({@link EnergyBuffer#draw}). One that cannot pay goes off the air: its latch goes off and its antenna
 * is refreshed, which unregisters it and sets {@code OnAir} false (the lens greys it). One that is off
 * comes back once its buffer is above {@code powerRestartFraction} (10 %), refreshed the same way. The
 * antenna stays the one writer of {@code OnAir}. A cell off the air for another reason (backhaul NONE)
 * draws nothing.
 *
 * <p><b>Where block entities tick, only.</b> A cell in a loaded chunk that does not tick block entities
 * (the edge of the loaded area) neither draws nor restarts, as a furnace there neither burns nor
 * smelts, and as a generator there makes nothing: power stands still with the machines that feed it.
 * Such a cell keeps whatever {@code OnAir} it had.
 *
 * <p><b>requirePower off</b> (the default): nothing is drawn, no latch is read
 * ({@link AntennaBlockEntity#powerAllows()}), antennas take no energy, and a quiet tick costs a config
 * read. When the flag changes while the server runs, every tracked cell is refreshed at the next tick,
 * so cells go off the air (empty buffers) or back on at once.
 *
 * <p><b>The served-receivers seam</b> ({@link ServedReceivers}): each evaluation of a player by
 * {@code SignalTicker} (a replay included) records its serving cell; a fixed device's turn in
 * {@code FixedReceiverTicker} records it when the cell changed or its last record is
 * {@link #SERVED_NOTE_INTERVAL_TICKS} old, which leaves the count unchanged. Phase 4's cell sleep can read
 * {@link #servedCount}; nothing in Phase 3 does except {@code /rancraft power status}. Recorded whatever
 * {@code requirePower} says: it changes nothing.
 *
 * <p>Not saved: the energy lives in the antennas' own save ({@code Energy}, {@code PowerOn}), and the
 * served counter starts again with the server. Server thread only.
 */
@EventBusSubscriber(modid = RanCraft.MOD_ID)
public final class SitePower {

    /** How often old served records are forgotten: once a minute. */
    static final int PRUNE_INTERVAL_TICKS = 1200;

    /**
     * A fixed receiver served by the same cell reports to the seam at most this often (20 s), not on
     * every turn ({@link ServedReceivers#due}): below the shortest window (1 minute), so the count is
     * unchanged while a receiver is served, and it costs the fixed-receiver ticker a map write every
     * 20 s per receiver instead of every second. Players report on every evaluation (there are few).
     */
    public static final long SERVED_NOTE_INTERVAL_TICKS = 400L;

    private static final Map<ResourceKey<Level>, SitePower> BY_LEVEL = new ConcurrentHashMap<>();

    /** Loaded cells that pass their own rules, by cell id, in tracking order. */
    private final Long2ObjectLinkedOpenHashMap<Tracked> cells = new Long2ObjectLinkedOpenHashMap<>();
    /** Reused each tick: the cells to visit, copied so a refresh may change {@link #cells}. */
    private final List<Tracked> visiting = new ArrayList<>();
    private final ServedReceivers<Object> served = new ServedReceivers<>();
    /** {@code requirePower} as the last tick applied it; null before the first. */
    private Boolean appliedRequire;

    // ---- statistics (game tests and the status command) -------------------------------------------

    private long drawTicks;
    private long drawNanos;
    private long lastTickNanos;
    private int lastDrawn;
    private long outages;
    private long restarts;

    /**
     * One tracked cell, with its chunk once looked up: the draw marks the chunk unsaved after every
     * paid tick, and a chunk-map lookup per cell per tick (what {@code Level.blockEntityChanged} does)
     * was most of the draw's cost (79 µs a tick for 200 cells, measured). The chunk is the antenna's
     * own: valid while the antenna is loaded, which is while it is tracked.
     */
    private static final class Tracked {

        final AntennaBlockEntity antenna;
        @Nullable LevelChunk chunk;

        Tracked(AntennaBlockEntity antenna) {
            this.antenna = antenna;
        }

        /** Marks the antenna's chunk for saving (the buffer changed). */
        void changed(ServerLevel level) {
            if (chunk == null) {
                BlockPos pos = antenna.getBlockPos();
                chunk = level.getChunkSource().getChunkNow(
                        SectionPos.blockToSectionCoord(pos.getX()), SectionPos.blockToSectionCoord(pos.getZ()));
            }
            if (chunk != null) {
                chunk.setUnsaved(true);
            }
        }
    }

    private SitePower() {
    }

    /** The site power of one dimension, created on first use. */
    public static SitePower of(ServerLevel level) {
        return BY_LEVEL.computeIfAbsent(level.dimension(), key -> new SitePower());
    }

    /** The site power of one dimension, or {@code null} when nothing has used it. Creates nothing. */
    public static @Nullable SitePower peek(ServerLevel level) {
        return BY_LEVEL.get(level.dimension());
    }

    // ---- tracking ---------------------------------------------------------------------------------

    /** An antenna's refresh: tracked while it passes its own rules, dropped when it does not. */
    public void track(AntennaBlockEntity antenna, boolean eligible) {
        if (!eligible) {
            untrack(antenna);
            return;
        }
        Tracked tracked = cells.get(antenna.cellId());
        if (tracked == null || tracked.antenna != antenna) {
            cells.put(antenna.cellId(), new Tracked(antenna));
        }
    }

    /** The antenna was removed or unloaded. Only that entity is dropped, not a newer one at its place. */
    public void untrack(AntennaBlockEntity antenna) {
        Tracked tracked = cells.get(antenna.cellId());
        if (tracked != null && tracked.antenna == antenna) {
            cells.remove(antenna.cellId());
        }
    }

    /** Whether a cell is tracked (loaded and passing its own rules). */
    public boolean tracks(long cellId) {
        return cells.containsKey(cellId);
    }

    /** The tracked cells, in tracking order (a copy). */
    public List<AntennaBlockEntity> cells() {
        List<AntennaBlockEntity> antennas = new ArrayList<>(cells.size());
        for (Tracked tracked : cells.values()) {
            antennas.add(tracked.antenna);
        }
        return antennas;
    }

    // ---- the served-receivers seam ----------------------------------------------------------------

    /**
     * Records that {@code cellId} served {@code receiver} at {@code tick}: a player's UUID, or a fixed
     * device's packed position. Nothing when there is no serving cell ({@link ReceiverState#NO_CELL}).
     */
    public static void noteServed(ServerLevel level, long cellId, Object receiver, long tick) {
        if (cellId != ReceiverState.NO_CELL) {
            of(level).served.served(cellId, receiver, tick);
        }
    }

    /** Distinct receivers {@code cellId} served in the last {@code servedWindowMinutes}, as of {@code now}. */
    public int servedCount(long cellId, long now) {
        return served.count(cellId, now, RanCraftConfig.servedWindowTicks());
    }

    /** Served records kept (pruned once a minute). */
    public int servedRecords() {
        return served.records();
    }

    // ---- the draw ---------------------------------------------------------------------------------

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        MinecraftServer server = event.getServer();
        for (ServerLevel level : server.getAllLevels()) {
            SitePower power = peek(level);
            if (power != null) {
                power.tick(level, level.getGameTime());
            }
        }
    }

    /**
     * One tick of one dimension (class javadoc). Public for the game tests, which time it; the server
     * calls it once a tick.
     */
    public void tick(ServerLevel level, long gameTime) {
        if (gameTime % PRUNE_INTERVAL_TICKS == 0) {
            served.prune(gameTime, RanCraftConfig.servedWindowTicks());
        }
        boolean required = RanCraftConfig.requirePower();
        if (appliedRequire == null) {
            // The first tick: every cell loaded so far was refreshed under the flag as it is.
            appliedRequire = required;
        } else if (appliedRequire != required) {
            appliedRequire = required;
            refreshAll();
        }
        if (!required) {
            return;
        }
        long start = System.nanoTime();
        int capacity = RanCraftConfig.powerBufferFe();
        double restartFraction = RanCraftConfig.powerRestartFraction();
        PowerModel model = RanCraftConfig.powerModel();
        int drawn = 0;
        visiting.clear();
        visiting.addAll(cells.values());
        for (Tracked tracked : visiting) {
            AntennaBlockEntity cell = tracked.antenna;
            if (cell.isRemoved()) {
                untrack(cell);
                continue;
            }
            if (!level.shouldTickBlocksAt(cell.getBlockPos())) {
                continue;
            }
            EnergyBuffer buffer = cell.energyBuffer();
            if (!buffer.on()) {
                if (buffer.restart(capacity, restartFraction)) {
                    restarts++;
                    tracked.changed(level);
                    cell.refreshRegistration();
                }
                continue;
            }
            if (!cell.onAir()) {
                continue;
            }
            int before = buffer.stored();
            boolean stays = buffer.draw(cell.fePerTick(model));
            drawn++;
            if (buffer.stored() != before || !stays) {
                tracked.changed(level);
            }
            if (!stays) {
                outages++;
                cell.refreshRegistration();
            }
        }
        visiting.clear();
        lastDrawn = drawn;
        lastTickNanos = System.nanoTime() - start;
        drawTicks++;
        drawNanos += lastTickNanos;
    }

    /** Refreshes every tracked cell: the flag changed, so each one's verdict may have. */
    private void refreshAll() {
        visiting.clear();
        visiting.addAll(cells.values());
        for (Tracked tracked : visiting) {
            if (!tracked.antenna.isRemoved()) {
                tracked.antenna.refreshRegistration();
            }
        }
        visiting.clear();
    }

    // ---- statistics -------------------------------------------------------------------------------

    /** Cells that paid a tick in the last draw, and its wall time (game tests, status). */
    public int lastDrawn() {
        return lastDrawn;
    }

    public long lastTickNanos() {
        return lastTickNanos;
    }

    /** Ticks with {@code requirePower} on so far, and their total wall time. */
    public long drawTicks() {
        return drawTicks;
    }

    public long drawNanos() {
        return drawNanos;
    }

    /** Cells that ran out, and cells that came back, since the server started. */
    public long outages() {
        return outages;
    }

    public long restarts() {
        return restarts;
    }

    @SubscribeEvent
    public static void onServerStopped(ServerStoppedEvent event) {
        BY_LEVEL.clear();
    }
}
