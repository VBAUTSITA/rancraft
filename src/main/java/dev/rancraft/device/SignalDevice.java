package dev.rancraft.device;

import dev.rancraft.rf.DeviceRequirement;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

/**
 * An item that consumes the signal its carrier receives (Phase 3, §3A.2).
 *
 * <p><b>Devices never compute RF.</b> The server evaluates each receiver once per interval,
 * however many devices it carries, and hands every device the same {@link DeviceContext}: the one
 * {@code SignalSample} that evaluation produced, plus this device's
 * {@link DeviceRequirement.Verdict} on it. A device reads that; it never runs the engine, marches
 * a ray or picks a serving cell. See {@code world.SignalTicker}.
 *
 * <p><b>Where a device is carried.</b> Main hand, offhand and hotbar slots 0-8. Any of them makes
 * the carrier evaluated. {@code held} is true only for the main hand and the offhand: a device in
 * the hotbar still runs (so the Network Locator's emergency record keeps working in a pocket) but
 * should draw no HUD and send no HUD payload. Everything else in the inventory is packed away and
 * does not run.
 *
 * <p><b>Every evaluation reaches the client anyway.</b> The ticker sends the
 * {@code SignalSamplePayload} itself, once per evaluation, whichever device or lens asked for it,
 * because the client's drive-test log must see every evaluation that can move the handover counter
 * ({@code SignalTicker.sendsSample}). A device sends only what is its own.
 *
 * <p><b>Replays.</b> While a player stands still, the ticker replays its cached evaluation instead
 * of running a new one, and dispatches that replay to every device too. A replayed sample carries
 * the {@link dev.rancraft.rf.SignalSample#timestampTick() timestampTick} of the evaluation it
 * replays, so {@code onSample} must be idempotent on it: the same tick twice must not count twice
 * (see {@link ReplayGuard}). {@link DeviceContext#tick()} is the current game time, which does move
 * on a replay (unless the game is frozen with {@code /tick freeze}; see {@link ReplayGuard}).
 *
 * <p><b>Per-player server state</b> lives in a {@link DeviceMemory}, which the ticker forgets
 * together with the player's handover state (logout, dimension change, respawn).
 */
public interface SignalDevice {

    /** What this device needs from the serving cell. {@link DeviceRequirement#NONE} asks nothing. */
    DeviceRequirement requirement(ItemStack stack);

    /**
     * Server side, once per evaluation. Must tolerate a replayed (cached) sample.
     *
     * @param stack the carried stack itself (not a copy), so a device may keep data components on it.
     * @param held  main hand or offhand; false for a hotbar slot.
     */
    void onSample(ServerPlayer player, ItemStack stack, boolean held, DeviceContext ctx);
}
