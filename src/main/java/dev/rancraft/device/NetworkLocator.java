package dev.rancraft.device;

import dev.rancraft.RanCraft;
import dev.rancraft.RanCraftConfig;
import dev.rancraft.device.LocatorTracker.Reading;
import dev.rancraft.item.LocatorWaypoints;
import dev.rancraft.item.NetworkLocatorItem;
import dev.rancraft.net.LocatorFixPayload;
import dev.rancraft.net.LocatorFixPayload.Kind;
import dev.rancraft.registry.ModAttachments;
import dev.rancraft.registry.ModDataComponents;
import dev.rancraft.rf.LocatorFix;
import dev.rancraft.rf.RfConfig;
import dev.rancraft.world.LevelSurfaceProbe;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * The Network Locator on the server (Phase 3 slice 5, §3A.6): the item ({@link NetworkLocatorItem})
 * delegates here. Game-side glue around the pure {@link LocatorTracker}.
 *
 * <p><b>Every evaluation</b> (fresh or a cached replay, in a hand or the hotbar) the Locator works
 * out its answer from the one {@code SignalSample} the ticker produced, and keeps it per player
 * ({@link #READINGS}, a {@link DeviceMemory}, so {@code SignalTicker.forget} drops it on logout,
 * dimension change and respawn). A FIX is stamped into the player's emergency record. While the
 * Locator is <em>held</em>, the answer is sent to that player as a {@link LocatorFixPayload}, after
 * the sample it came from. In the hotbar it runs but sends nothing: it has no HUD there.
 *
 * <p><b>It computes no RF and costs no evaluation.</b> The ticker evaluates the player once per
 * interval whatever they carry, and hands this the result; the Locator ranges and solves on numbers
 * that are already there. Its one look at the world is the ground height for altitude aiding
 * ({@link LevelSurfaceProbe#forLocator}), from loaded chunks only.
 *
 * <p><b>Replays and two Locators.</b> A cached replay (the player standing still) and a second
 * Locator dispatched from the same evaluation are both recognised by {@link LocatorTracker#isReplay}:
 * the stored fix is reused, not solved again, and only its confirmation tick moves. The payload is
 * sent by one Locator per evaluation ({@link #sendsPayload}).
 */
@EventBusSubscriber(modid = RanCraft.MOD_ID)
public final class NetworkLocator {

    private NetworkLocator() {
    }

    /** What each player's Locator last worked out. Forgotten with the rest of the player's device state. */
    static final DeviceMemory<Reading> READINGS = DeviceMemory.create("network_locator.reading");

    /**
     * Server side, once per dispatch; see the class comment.
     *
     * <p>Nothing happens while the player is dead. The ticker keeps evaluating a player on the death
     * screen (it looks at every player on the list), and with {@code keepInventory} the dead entity
     * still carries the Locator. Stamping a FIX then would put a "last fix" back into the record
     * that {@link #freezeOnDeath} just cleared, and NeoForge copies it to the respawned player: the
     * next life would start with a known position it never measured.
     */
    public static void onSample(ServerPlayer player, ItemStack stack, boolean held, DeviceContext ctx) {
        if (!player.isAlive()) {
            return;
        }
        UUID id = player.getUUID();
        Reading reading = LocatorTracker.update(READINGS.get(id), ctx.sample(), ctx.bands(), ctx.config(),
                LevelSurfaceProbe.forLocator(ctx.level()), ctx.tick());
        READINGS.put(id, reading);

        if (reading.fix() instanceof LocatorFix.Fix fix) {
            stampFix(player, ctx.level(), fix, reading.confirmedTick());
        }
        ItemStack mainHand = player.getMainHandItem();
        if (sendsPayload(held, mainHand == stack, mainHand.getItem() instanceof NetworkLocatorItem)) {
            PacketDistributor.sendToPlayer(player, payloadOf(player, reading, ctx.config()));
        }
    }

    /**
     * Which held Locator sends the payload: the one in the main hand, or the offhand one when the main
     * hand holds no Locator. So a player holding one in each hand gets one payload per evaluation, and
     * it matches the HUD, which reads the main-hand Locator's waypoints first. A Locator in the hotbar
     * ({@code held} false) never sends. Pure, so it is pinned headless.
     */
    static boolean sendsPayload(boolean held, boolean inMainHand, boolean mainHandHoldsALocator) {
        return held && (inMainHand || !mainHandHoldsALocator);
    }

    /**
     * The FIX becomes the emergency record's last fix, stamped with the game time the Locator
     * reported it ({@link Reading#confirmedTick()}: a player standing still keeps confirming the same
     * fix, so it stays young). It is the <em>estimate</em>, never the player's true position.
     * Written only when it changed.
     */
    private static void stampFix(ServerPlayer player, ServerLevel level, LocatorFix.Fix fix, long tick) {
        EmergencyRecord record = player.getExistingData(ModAttachments.LOCATOR_EMERGENCY).orElse(EmergencyRecord.EMPTY);
        EmergencyRecord.Stamp stamp = new EmergencyRecord.Stamp(dimensionId(level),
                fix.x(), fix.y(), fix.z(), fix.errorBlocks(), tick);
        if (!record.lastFix().equals(Optional.of(stamp))) {
            player.setData(ModAttachments.LOCATOR_EMERGENCY, record.withFix(stamp));
        }
    }

    /** The payload for one reading, with the player's frozen "last fix before death" if any. */
    static LocatorFixPayload payloadOf(Player player, Reading reading, RfConfig config) {
        Optional<LocatorFixPayload.Emergency> emergency = player.getExistingData(ModAttachments.LOCATOR_EMERGENCY)
                .flatMap(EmergencyRecord::beforeDeath)
                .map(frozen -> new LocatorFixPayload.Emergency(frozen.fix().dimension(),
                        frozen.fix().x(), frozen.fix().y(), frozen.fix().z(), frozen.fix().errorBlocks(),
                        frozen.fix().gameTime(), frozen.deathTick()));
        return LocatorFixPayload.of(reading.sample().timestampTick(), reading.fix(), reading.used(),
                reading.bestResolutionMeters(), reading.bestResolutionBandId(), config.metersPerBlock(),
                config.locatorParams(), emergency);
    }

    // ---- waypoints (the item's use) -------------------------------------------------------------

    /**
     * Sneak + use: saves the Locator's current FIX as a waypoint on {@code stack} and selects it.
     * The estimate, never the true position: the server reads the Locator's own last answer, which
     * is why saving happens here and not on the client. Refused, with a message, when there is no
     * current FIX (no reading, a stale one because the Locator was put away, or any other fix type).
     */
    public static void saveWaypoint(ServerPlayer player, ItemStack stack) {
        Reading reading = READINGS.get(player.getUUID());
        long now = player.serverLevel().getGameTime();
        long maxAge = LocatorTracker.freshForTicks(RanCraftConfig.EVALUATION_INTERVAL_TICKS.get());
        if (!LocatorTracker.isFresh(reading, now, maxAge)) {
            player.displayClientMessage(Component.translatable("rancraft.locator.waypoint.no_reading"), true);
            return;
        }
        if (!(reading.fix() instanceof LocatorFix.Fix fix)) {
            player.displayClientMessage(Component.translatable("rancraft.locator.waypoint.no_fix",
                    Kind.of(reading.fix()).label()), true);
            return;
        }

        double metersPerBlock = RanCraftConfig.METERS_PER_BLOCK.get();
        LocatorWaypoints.Waypoint waypoint = new LocatorWaypoints.Waypoint(
                dimensionId(player.serverLevel()), fix.x(), fix.y(), fix.z(), fix.errorBlocks());
        LocatorWaypoints.Saved saved = NetworkLocatorItem.waypointsOf(stack).save(waypoint);
        stack.set(ModDataComponents.LOCATOR_WAYPOINTS.get(), saved.waypoints());
        player.displayClientMessage(Component.translatable(
                saved.replaced() ? "rancraft.locator.waypoint.replaced" : "rancraft.locator.waypoint.saved",
                saved.index() + 1, saved.waypoints().size(),
                (long) Math.floor(fix.x()), (long) Math.floor(fix.z()),
                String.format(Locale.ROOT, "%.0f", fix.errorBlocks() * metersPerBlock)), true);
    }

    /** Use: selects the next waypoint on {@code stack}, wrapping to the first. */
    public static void cycleWaypoint(ServerPlayer player, ItemStack stack) {
        LocatorWaypoints waypoints = NetworkLocatorItem.waypointsOf(stack);
        if (waypoints.isEmpty()) {
            player.displayClientMessage(Component.translatable("rancraft.locator.waypoint.none"), true);
            return;
        }
        LocatorWaypoints next = waypoints.cycle();
        stack.set(ModDataComponents.LOCATOR_WAYPOINTS.get(), next);
        player.displayClientMessage(Component.translatable("rancraft.locator.waypoint.selected",
                next.selected() + 1, next.size()), true);
    }

    // ---- the emergency record at death ----------------------------------------------------------

    /**
     * On death, the last FIX is frozen as the "last fix before death" if it is younger than
     * {@code locatorEmergencyMaxAgeTicks}; the Locator HUD shows it after respawn, until the next
     * death. The record is a {@code copyOnDeath} attachment ({@link ModAttachments#LOCATOR_EMERGENCY}),
     * so NeoForge copies it to the respawned player.
     *
     * <p><b>Real-world analogue: network-derived emergency caller location.</b> When a phone calls 112
     * or 911, the network can locate it from the same kind of cell measurements (E-CID, OTDOA,
     * multi-RTT, run by a location server) and pass the estimate, with its uncertainty, to the
     * emergency service. This record is that estimate: a fix taken behind a hill sends you back to
     * the wrong place, as a bad network fix sends responders to the wrong door.
     *
     * <p>Lowest priority, and not for a cancelled event: {@code ServerPlayer.die} returns at once when
     * the event is cancelled, so the death is certain only if no one else cancelled it. A totem of
     * undying never gets here ({@code LivingEntity.hurt} checks it before calling {@code die}).
     */
    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onLivingDeath(LivingDeathEvent event) {
        if (event.getEntity() instanceof Player player && !player.level().isClientSide()) {
            freezeOnDeath(player, player.level().getGameTime(), RanCraftConfig.locatorEmergencyMaxAgeTicks());
        }
    }

    /** The death half of {@link #onLivingDeath}; public for the game test that checks it. */
    public static void freezeOnDeath(Player player, long deathTick, long maxAgeTicks) {
        Optional<EmergencyRecord> record = player.getExistingData(ModAttachments.LOCATOR_EMERGENCY);
        if (record.isEmpty()) {
            return;
        }
        EmergencyRecord next = record.get().onDeath(deathTick, maxAgeTicks);
        if (next.isEmpty()) {
            player.removeData(ModAttachments.LOCATOR_EMERGENCY);
        } else {
            player.setData(ModAttachments.LOCATOR_EMERGENCY, next);
        }
    }

    private static String dimensionId(Level level) {
        return level.dimension().location().toString();
    }
}
