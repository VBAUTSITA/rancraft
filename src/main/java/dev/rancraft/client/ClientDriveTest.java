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
 * <p>Every sample received is logged, whether it arrived because the lens shows the trail or
 * because a Field Test Meter is held. A meter is what a real drive-test scanner is, so a walk with
 * the meter out is a drive test too; the trail only decides whether the log is drawn.
 *
 * <p><b>One log per dimension.</b> The same coordinates mean a different place in the Nether, and a
 * trail recorded in the Overworld drawn there would put markers where nothing was measured. Each
 * sample is filed under the dimension the client is in when it arrives. That is the dimension it
 * was measured in: the server sends samples and the respawn packet that moves the player to a new
 * dimension over one ordered connection, and the handler runs on the client thread in that order.
 *
 * <p><b>Kept until {@code /rancraftc drivetest clear}.</b> Not cleared on disconnect, respawn or
 * dimension change, unlike the meter and lens readouts in {@link ClientEvents}: a drive test that
 * vanished when you walked through a portal or relogged would not be much of a record. The
 * consequence is that joining a different world keeps the old trail under the same dimension
 * names; clear it first. See NOTES.md.
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
        LOGS.computeIfAbsent(level.dimension(), key -> new DriveTestLog(RanCraftConfig.driveTestCapacity()))
                .record(payload.toDriveTestSample());
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
