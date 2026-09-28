package dev.rancraft.client;

import dev.rancraft.RanCraftConfig;
import dev.rancraft.net.SignalSamplePayload;
import dev.rancraft.rf.DriveTestLog;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

/**
 * The client's drive-test log (RF Vision Step 3a): every signal sample the server sends, kept with
 * the point it was measured at, for the lens trail ({@link TrailRenderer}) and the CSV export
 * ({@link DriveTestCommands}).
 *
 * <p><b>Nothing here is measured or computed.</b> Each entry is a {@link SignalSamplePayload} copied
 * field for field ({@link SignalSamplePayload#toDriveTestSample()}), including the position, which
 * the server sends as the exact point it evaluated. The only thing derived is the <em>event</em>
 * (handover, reselection, outage), read off consecutive server values by {@link DriveTestLog}.
 *
 * <p>Every sample received is logged, whether it arrived because the lens shows the trail or the
 * link rays, or because a Field Test Meter is held. A meter is what a real drive-test scanner is, so
 * a walk with the meter out is a drive test too; the trail only decides whether the log is drawn.
 * The server sends a sample with every evaluation it runs for this player, so the log sees every
 * step of the handover counter and marks each handover where it fired (see
 * {@code DriveTestLog.classify}).
 *
 * <p><b>One log per dimension.</b> The same coordinates mean a different place in the Nether, and a
 * trail recorded in the Overworld drawn there would put markers where nothing was measured. Each
 * sample is filed under the dimension the client is in when it arrives. That is the dimension it
 * was measured in: the server sends samples and the respawn packet that moves the player to a new
 * dimension over one ordered connection, and the handler runs on the client thread in that order.
 *
 * <p><b>Kept for one session.</b> Cleared by {@code /rancraftc drivetest clear} and on logging out
 * ({@link ClientEvents#onLoggingOut}), like every other client readout. The key is only the
 * dimension, and the client cannot reliably tell one world's Overworld from another's, so a log kept
 * past a disconnect would draw, classify and export one world's trail together with the next
 * world's. NeoForge fires the logout event on leaving a world and also before every join (a new
 * singleplayer world, a server, a server transfer all go through {@code Minecraft.disconnect}), so
 * each session starts empty. Within a session the log is <em>not</em> cleared on respawn or
 * dimension change: a drive test that vanished when you walked through a portal would not be much of
 * a record, and the per-dimension split already keeps the trails apart. Export before leaving a
 * world if you want to keep it. See NOTES.md.
 *
 * <p>Client thread only: fed from the payload handler's enqueued work, read by the renderer and the
 * commands, all on the one thread. Like the other {@code Client*State} classes it is named only
 * inside a payload handler lambda, so a dedicated server never loads it.
 */
public final class ClientDriveTest {

    private ClientDriveTest() {
    }

    /** Insertion-ordered so an export lists dimensions in the order they were visited. */
    private static final Map<ResourceKey<Level>, DriveTestLog> LOGS = new LinkedHashMap<>();

    /** Logs one server sample. The client's "nothing received" placeholder has no position and is skipped. */
    public static void accept(SignalSamplePayload payload) {
        if (!payload.hasReceiverPosition()) {
            return;
        }
        ClientLevel level = Minecraft.getInstance().level;
        if (level == null) {
            return;
        }
        record(level.dimension(), payload.toDriveTestSample());
    }

    /**
     * Files one sample under the dimension it was measured in. Split from {@link #accept} only so the
     * tests can feed the log without a running client.
     */
    static void record(ResourceKey<Level> dimension, DriveTestLog.Sample sample) {
        LOGS.computeIfAbsent(dimension, key -> new DriveTestLog(RanCraftConfig.driveTestCapacity()))
                .record(sample);
    }

    /** The log for one dimension, or {@code null} if nothing was measured there since the last clear. */
    public static DriveTestLog logFor(ResourceKey<Level> dimension) {
        return LOGS.get(dimension);
    }

    /** Every dimension's log, in the order first visited. Read-only view. */
    public static Map<ResourceKey<Level>, DriveTestLog> logs() {
        return Collections.unmodifiableMap(LOGS);
    }

    /** Total samples held across every dimension. */
    public static int size() {
        int total = 0;
        for (DriveTestLog log : LOGS.values()) {
            total += log.size();
        }
        return total;
    }

    /**
     * Forgets every dimension's log and returns how many samples that dropped. The next log started
     * picks up the current {@code driveTestCapacity}.
     */
    public static int clear() {
        int dropped = size();
        LOGS.clear();
        return dropped;
    }
}
