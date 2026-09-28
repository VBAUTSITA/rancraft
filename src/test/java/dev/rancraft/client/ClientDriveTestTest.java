package dev.rancraft.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.rancraft.rf.DriveTestLog;
import dev.rancraft.rf.ServiceLevel;
import java.util.List;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The client's drive-test log lifecycle: one log per dimension within a session, all of it gone on
 * logging out. Drives the real {@link ClientEvents} handlers with hand-built events (NeoForge fires
 * {@code LoggingOut} with null player and connection itself when a new world is being created), so
 * no client starts.
 */
class ClientDriveTestTest {

    private static final ResourceKey<Level> OVERWORLD =
            ResourceKey.create(Registries.DIMENSION, ResourceLocation.withDefaultNamespace("overworld"));
    private static final ResourceKey<Level> NETHER =
            ResourceKey.create(Registries.DIMENSION, ResourceLocation.withDefaultNamespace("the_nether"));

    @BeforeEach
    @AfterEach
    void emptyLog() {
        ClientDriveTest.clear();
    }

    /** A served sample at (x, 70, z), far enough apart that the stationary rule never merges two. */
    private static DriveTestLog.Sample sample(long tick, double x, double z) {
        return new DriveTestLog.Sample(tick, x, 70.0, z, 42L, 7, "band_900",
                -80.0, 12.0, ServiceLevel.GOOD, 0, 3);
    }

    private static void walk(ResourceKey<Level> dimension, int samples, double z) {
        for (int i = 0; i < samples; i++) {
            ClientDriveTest.record(dimension, sample(20L * i, 4.0 * i, z));
        }
    }

    @Test
    @DisplayName("within a session each dimension keeps its own log, in the order first visited")
    void perDimensionWithinSession() {
        walk(OVERWORLD, 3, 0.0);
        walk(NETHER, 2, 0.0);

        assertEquals(3, ClientDriveTest.logFor(OVERWORLD).size());
        assertEquals(2, ClientDriveTest.logFor(NETHER).size());
        assertEquals(5, ClientDriveTest.size());
        assertEquals(List.of(OVERWORLD, NETHER), List.copyOf(ClientDriveTest.logs().keySet()));
    }

    @Test
    @DisplayName("respawn and dimension change (Clone) keep every dimension's trail")
    void cloneKeepsTheTrail() {
        walk(OVERWORLD, 3, 0.0);
        walk(NETHER, 2, 0.0);

        ClientEvents.onClone(new ClientPlayerNetworkEvent.Clone(null, null, null, null));

        assertEquals(3, ClientDriveTest.logFor(OVERWORLD).size());
        assertEquals(2, ClientDriveTest.logFor(NETHER).size());
    }

    @Test
    @DisplayName("logging out drops every dimension's trail, so the next world starts empty")
    void logoutClearsEveryDimension() {
        walk(OVERWORLD, 3, 0.0);
        walk(NETHER, 2, 0.0);

        ClientEvents.onLoggingOut(new ClientPlayerNetworkEvent.LoggingOut(null, null, null));

        assertEquals(0, ClientDriveTest.size());
        assertTrue(ClientDriveTest.logs().isEmpty());
        assertNull(ClientDriveTest.logFor(OVERWORLD));
        assertNull(ClientDriveTest.logFor(NETHER));
    }

    /**
     * The bug this pins: before, the Overworld log survived the relog, so the next world's first
     * sample was appended to the old trail (drawn, joined and exported with it) and classified
     * against the old world's last sample.
     */
    @Test
    @DisplayName("after a logout the next world's Overworld log holds only that world's samples")
    void nextWorldDoesNotInheritTheOldTrail() {
        walk(OVERWORLD, 3, 0.0);

        ClientEvents.onLoggingOut(new ClientPlayerNetworkEvent.LoggingOut(null, null, null));
        DriveTestLog.Sample first = new DriveTestLog.Sample(5L, 1000.0, 70.0, 1000.0, 99L, 3, "band_1800",
                -85.0, 8.0, ServiceLevel.FAIR, 0, 1);
        ClientDriveTest.record(OVERWORLD, first);

        DriveTestLog log = ClientDriveTest.logFor(OVERWORLD);
        assertNotNull(log);
        assertEquals(1, log.size());
        DriveTestLog.Entry only = log.entries().get(0);
        assertEquals(first, only.sample());
        assertEquals(DriveTestLog.Event.NONE, only.event(), "the first sample of a session is not a cell change");
    }
}
