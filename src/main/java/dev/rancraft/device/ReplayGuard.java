package dev.rancraft.device;

import dev.rancraft.rf.SignalSample;
import java.util.UUID;

/**
 * Makes a {@link SignalDevice} idempotent on {@link SignalSample#timestampTick()}, as §3A.3 requires.
 *
 * <p>The ticker dispatches a cached replay exactly like a fresh evaluation. A replay is the same
 * sample, with the same {@code timestampTick}, handed over again one interval later while the
 * player stands still. A device that counts evaluations, appends to a history or stamps a record
 * must do that once per evaluation, not once per dispatch:
 *
 * <pre>{@code
 * private static final ReplayGuard FRESH = new ReplayGuard("network_locator.fresh");
 *
 * public void onSample(ServerPlayer player, ItemStack stack, boolean held, DeviceContext ctx) {
 *     if (FRESH.firstSighting(player.getUUID(), ctx.sample())) {
 *         // state changes: once per evaluation
 *     }
 *     // pure output (a HUD payload while held): every dispatch, replays included
 * }
 * }</pre>
 *
 * <p><b>Per player, not per stack.</b> A player carrying two of the same device acts once per
 * evaluation, on the first of them dispatched (main hand, then offhand, then hotbar left to right).
 * State that belongs to one stack belongs in a data component on that stack instead.
 *
 * <p>Equality, not "newer than": after the ticker forgets a player (logout, dimension change,
 * respawn) the memory is empty, and any tick is a first sighting. Game time never runs backwards
 * otherwise.
 *
 * <p><b>One case where two evaluations share a tick: {@code /tick freeze}.</b> The ticker's
 * stagger runs on the server's tick count, which keeps counting while the tick rate manager is
 * frozen; game time does not ({@code ServerLevel.tick} calls {@code tickTime()} only when
 * {@code runsNormally()}), and players still move ({@code TickRateManager.isEntityFrozen} exempts
 * them). So while the game is frozen, every fresh evaluation carries the same
 * {@code timestampTick}, and this guard reports all but the first as replays. That is the spec's key
 * (§3A.3 "idempotent on {@code sample.timestampTick()}") applied literally: a device's once-per-
 * evaluation state freezes together with game time, as the handover timers already do. Output
 * built from {@code ctx.sample()} on every dispatch (the pattern above) still follows the player.
 * Checked in the 1.21.1 sources; recorded in NOTES.md, Phase 3 slice 4.
 */
public final class ReplayGuard {

    private final DeviceMemory<Long> lastTick;

    /** @param name for debugging, e.g. {@code "network_locator.fresh"}. Registers a {@link DeviceMemory}. */
    public ReplayGuard(String name) {
        this.lastTick = DeviceMemory.create(name);
    }

    /** True the first time this player's device is handed this sample; false for a replay of it. */
    public boolean firstSighting(UUID player, SignalSample sample) {
        return firstSighting(player, sample.timestampTick());
    }

    /** True the first time this player's device is handed a sample with this timestamp. */
    public boolean firstSighting(UUID player, long timestampTick) {
        Long previous = lastTick.put(player, timestampTick);
        return previous == null || previous != timestampTick;
    }
}
