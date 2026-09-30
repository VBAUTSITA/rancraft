package dev.rancraft.world;

import dev.rancraft.RanCraft;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import java.util.HashMap;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/**
 * Logs the one-time behaviour change of Phase 3 slice 6 (§3B.1): a world saved before it had every
 * stacked Signal Mast transmitting as its own cell; now a column is one cell and the rest of it is
 * structure. §3B.1 asks for one line, "N stacked masts now form M columns; N-M masts stopped
 * transmitting", rather than one per tower.
 *
 * <p>A column is counted when its base loads from a save ({@code SignalMastBlockEntity.onLoad}),
 * once per base position and dimension per server run, so walking away and back does not count it
 * twice. Chunks load a few at a time, so the line is written once the loading has gone quiet for
 * {@link #QUIET_TICKS}, with the running total; exploring into more stacked masts later writes an
 * updated total. Nothing persisted records that a world was already converted (slice 6 changes no
 * save data), so a world saved after slice 6 logs the same line on each start: it describes the
 * rule, not a migration step.
 *
 * <p>Columns under a sector antenna (mounting poles) stopped transmitting altogether, the base
 * included, so they are counted apart and named in a second sentence.
 */
@EventBusSubscriber(modid = RanCraft.MOD_ID)
public final class MastColumnCensus {

    private MastColumnCensus() {
    }

    /** Server ticks without a newly counted column before the running total is logged. 100 = 5 s. */
    static final int QUIET_TICKS = 100;

    private static final Map<ResourceKey<Level>, LongSet> COUNTED = new HashMap<>();
    private static Tally tally = Tally.EMPTY;
    private static boolean pending;
    private static int lastNoteTick;
    private static String lastLogged;

    /**
     * Running totals.
     *
     * @param stackedMasts masts in columns of two or more that still transmit (through their base).
     * @param columns      those columns.
     * @param poleMasts    masts in columns with a sector antenna on top, which no longer transmit.
     * @param poles        those columns.
     */
    record Tally(int stackedMasts, int columns, int poleMasts, int poles) {

        static final Tally EMPTY = new Tally(0, 0, 0, 0);

        /** Adds one column of {@code height} masts. A lone mast with nothing on top changed nothing. */
        Tally plus(int height, boolean mountingPole) {
            if (mountingPole) {
                return new Tally(stackedMasts, columns, poleMasts + height, poles + 1);
            }
            if (height >= 2) {
                return new Tally(stackedMasts + height, columns + 1, poleMasts, poles);
            }
            return this;
        }

        boolean changedAnything() {
            return columns > 0 || poles > 0;
        }

        /** §3B.1's sentence (with singulars where they apply), then the mounting poles if there are any. */
        String message() {
            StringBuilder text = new StringBuilder();
            if (columns > 0) {
                text.append(count(stackedMasts, "stacked mast")).append(" now form ").append(count(columns, "column"))
                        .append("; ").append(count(stackedMasts - columns, "mast")).append(" stopped transmitting.");
            }
            if (poles > 0) {
                if (!text.isEmpty()) {
                    text.append(' ');
                }
                text.append(count(poleMasts, "mast")).append(" under ").append(count(poles, "sector antenna"))
                        .append(poles == 1 ? " is a mounting pole" : " are mounting poles")
                        .append(" now and stopped transmitting.");
            }
            return text.toString();
        }

        private static String count(int n, String noun) {
            return n + " " + noun + (n == 1 ? "" : "s");
        }
    }

    /** One column's base loaded from a save. Server thread. */
    public static synchronized void noteLoaded(ServerLevel level, BlockPos base, int height, boolean mountingPole) {
        Tally next = tally.plus(height, mountingPole);
        if (next == tally) {
            return;
        }
        if (!COUNTED.computeIfAbsent(level.dimension(), key -> new LongOpenHashSet()).add(base.asLong())) {
            return;
        }
        tally = next;
        pending = true;
        lastNoteTick = level.getServer().getTickCount();
    }

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        Tally toLog;
        synchronized (MastColumnCensus.class) {
            if (!pending || event.getServer().getTickCount() - lastNoteTick < QUIET_TICKS) {
                return;
            }
            pending = false;
            toLog = tally;
        }
        if (toLog.changedAnything()) {
            String message = toLog.message();
            RanCraft.LOGGER.info("RANCraft mast columns (Phase 3, loaded this run): {}", message);
            synchronized (MastColumnCensus.class) {
                lastLogged = message;
            }
        }
    }

    /** The last line logged this run (without the prefix), or {@code null}. For the game test. */
    public static synchronized String lastLogged() {
        return lastLogged;
    }

    @SubscribeEvent
    public static void onServerStopped(ServerStoppedEvent event) {
        synchronized (MastColumnCensus.class) {
            COUNTED.clear();
            tally = Tally.EMPTY;
            pending = false;
            lastLogged = null;
        }
    }
}
