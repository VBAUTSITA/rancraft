package dev.rancraft.device;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Server-side state a {@link SignalDevice} keeps per player between samples: the Network Locator's
 * last fix, the tick a device last acted on ({@link ReplayGuard}), and so on.
 *
 * <p><b>Why a class and not a map in each device.</b> Every instance registers itself, and
 * {@code SignalTicker} forgets them all together with the player's handover state and cached
 * sample: on logout, dimension change and respawn ({@link #forget}), and everything on server stop
 * ({@link #clearAll}). A device that kept per-player state in its own map would leak one entry per
 * player who ever logged in, and would carry state into a place it no longer describes: an
 * Overworld fix used as the "previous" estimate in the Nether, or after a respawn at the other end
 * of the world.
 *
 * <p>Keyed by player {@code UUID}. Fixed (block) receivers (§3B.3) are keyed by block position and
 * live and die with their chunk; they need their own lifecycle, not this one.
 *
 * <p>Thread-safe, although everything here runs on the server thread.
 *
 * @param <V> the state kept per player. Never null: absence means "nothing remembered".
 */
public final class DeviceMemory<V> {

    private static final List<DeviceMemory<?>> ALL = new CopyOnWriteArrayList<>();

    private final String name;
    private final Map<UUID, V> values = new ConcurrentHashMap<>();

    private DeviceMemory(String name) {
        this.name = Objects.requireNonNull(name, "name");
    }

    /**
     * A new, registered store. Create one per kind of state, as a {@code static final} field of the
     * device class, so it exists (and is forgotten) for the life of the server.
     *
     * @param name for logs and debugging only, e.g. {@code "network_locator.last_fix"}.
     */
    public static <V> DeviceMemory<V> create(String name) {
        DeviceMemory<V> memory = new DeviceMemory<>(name);
        ALL.add(memory);
        return memory;
    }

    public String name() {
        return name;
    }

    /** The state remembered for this player, or {@code null}. */
    public V get(UUID player) {
        return values.get(player);
    }

    /** Remembers {@code value} for this player; returns what was remembered before, or {@code null}. */
    public V put(UUID player, V value) {
        return values.put(player, Objects.requireNonNull(value, "value"));
    }

    public void remove(UUID player) {
        values.remove(player);
    }

    public int size() {
        return values.size();
    }

    /** Forgets one player in every store. Safe for a player that was never seen. */
    public static void forget(UUID player) {
        for (DeviceMemory<?> memory : ALL) {
            memory.remove(player);
        }
    }

    /** Forgets everyone in every store (server stop, so a second world in the same JVM starts clean). */
    public static void clearAll() {
        for (DeviceMemory<?> memory : ALL) {
            memory.values.clear();
        }
    }

    @Override
    public String toString() {
        return "DeviceMemory[" + name + ", " + values.size() + " player(s)]";
    }
}
