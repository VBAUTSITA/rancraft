package dev.rancraft.device;

import dev.rancraft.RanCraftConfig;
import dev.rancraft.item.ProximityScannerItem;
import dev.rancraft.net.ScannerPayload;
import dev.rancraft.net.ScannerPayload.Status;
import dev.rancraft.rf.CellSample;
import dev.rancraft.rf.DeviceRequirement;
import dev.rancraft.rf.ServiceLevel;
import dev.rancraft.util.ProximityScan;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.AABB;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * The Proximity Scanner on the server (Phase 3 slice 14, §3C.4): the item ({@link ProximityScannerItem})
 * delegates here.
 *
 * <p><b>Game abstraction, labelled (NOTES.md, Phase 3 slice 14): the scan is not RF physics.</b> Nothing
 * here senses a mob by radio: no radar, no reflection, no propagation, nothing "seen through" a wall. The
 * server already knows where every mob is and simply lists the hostile ones within
 * {@code scannerRangeBlocks} (24) of the player, walls or not. The list stands in for a <em>high-rate
 * sensor feed</em> (a drone's or a body camera's video, say) that only a <em>high-capacity link</em> can
 * carry: GOOD service on a band of capacity tier 3 ({@link #REQUIREMENT}), which today means band_3500. It
 * is the gameplay reward that makes a hard-to-propagate band worth deploying: the short-range, wideband
 * cell buys something the long-range bands cannot. The network decides whether the feed arrives; it plays
 * no part in what is in it.
 *
 * <p><b>Devices never compute RF.</b> {@link #onSample} reads the verdict the ticker computed once per
 * evaluation, with the serving cell's backhaul cap already applied (§3C.2), so a backhaul-limited cell
 * (capped at FAIR) turns the scanner off as it does the Storage Terminal. The one look at the world is the
 * entity query for the list, and it runs only on an OK verdict, while the scanner is held.
 *
 * <p><b>What is sent.</b> While held, once per dispatch (each evaluation interval; a cached replay is
 * dispatched too, and the list is taken afresh each time, because mobs move while the player stands
 * still): a {@link ScannerPayload}. With an OK verdict it carries the list, nearest first, at most 16; with
 * any other verdict it carries only the reason, which the HUD words as "needs tier 3", "signal too weak"
 * or "backhaul limited". In the hotbar the scanner runs but sends nothing: it has no HUD there. It keeps no
 * per-player state: everything it sends is worked out from the dispatch in hand.
 */
public final class ProximityScanner {

    /** §3C.4: GOOD service, on a band of capacity tier 3 or higher (band_3500). */
    public static final DeviceRequirement REQUIREMENT = new DeviceRequirement(ServiceLevel.GOOD, 3);

    private ProximityScanner() {
    }

    /**
     * Server side, once per dispatch; see the class comment. Nothing is sent while the player is dead (the
     * ticker keeps evaluating a player on the death screen) or by a scanner that is not the sending one.
     */
    public static void onSample(ServerPlayer player, ItemStack stack, boolean held, DeviceContext ctx) {
        if (!player.isAlive()) {
            return;
        }
        ItemStack mainHand = player.getMainHandItem();
        if (!sendsPayload(held, mainHand == stack, mainHand.getItem() instanceof ProximityScannerItem)) {
            return;
        }
        PacketDistributor.sendToPlayer(player, payloadFor(player, ctx, RanCraftConfig.scannerRangeBlocks()));
    }

    /**
     * Which held scanner sends: the one in the main hand, or the offhand one when the main hand holds no
     * scanner. So a player with one in each hand gets one payload per dispatch. A scanner in the hotbar
     * ({@code held} false) never sends. The Network Locator's rule. Pure, so it is pinned headless.
     */
    static boolean sendsPayload(boolean held, boolean inMainHand, boolean mainHandHoldsAScanner) {
        return held && (inMainHand || !mainHandHoldsAScanner);
    }

    /**
     * The scanner's status on one dispatch: OK, or the reason it is off, by the Storage Terminal's rule
     * ({@link TerminalLink#reasonOf}): a LOW_QUALITY verdict is BACKHAUL_LIMITED when the radio alone
     * reaches GOOD (only the cap failed it), WEAK_SIGNAL otherwise.
     */
    public static Status statusOf(DeviceContext ctx) {
        TerminalLink.Problem reason = TerminalLink.reasonOf(ctx.verdict(), ctx.sample().serviceLevel(), REQUIREMENT);
        if (reason == null) {
            return Status.OK;
        }
        return switch (reason) {
            case NO_SERVICE, NO_READING -> Status.NO_SERVICE;
            case WEAK_SIGNAL -> Status.WEAK_SIGNAL;
            case BACKHAUL_LIMITED -> Status.BACKHAUL_LIMITED;
            case LOW_TIER -> Status.LOW_TIER;
        };
    }

    /**
     * The payload for one dispatch: the status and what the HUD needs to word it, and on an OK verdict
     * the list ({@link #scan}). Public for the game tests, which check what a dispatch would send.
     */
    public static ScannerPayload payloadFor(ServerPlayer player, DeviceContext ctx, double rangeBlocks) {
        Status status = statusOf(ctx);
        String bandId = "";
        int bandTier = 0;
        CellSample serving = ctx.sample().serving().orElse(null);
        if (serving != null) {
            bandId = serving.bandId();
            bandTier = ctx.bands().getOrFallback(bandId).capacityTier();
        }
        List<ProximityScan.Contact> contacts = status == Status.OK ? scan(player, rangeBlocks) : List.of();
        return ScannerPayload.of(status, REQUIREMENT, ctx.sample().serviceLevel(), ctx.serviceCap(), bandId, bandTier,
                rangeBlocks, contacts);
    }

    /**
     * The hostile mobs within {@code rangeBlocks} (straight line, inclusive) of the player's feet, nearest
     * first, at most {@link ScannerPayload#MAX_CONTACTS}. <b>Not RF</b> (see the class comment): a lookup
     * of positions the server already has, through walls and terrain alike.
     *
     * <p>"Hostile" is vanilla's own marker, {@link Enemy}: every monster, plus the slimes, ghasts,
     * phantoms, shulkers, hoglins and the dragon that are hostile without being a {@code Monster}. A
     * neutral monster (an enderman, a piglin) counts, as vanilla counts it. Only living mobs in loaded
     * chunks are found ({@code getEntitiesOfClass} reads the loaded entity sections); nothing is loaded.
     */
    public static List<ProximityScan.Contact> scan(ServerPlayer player, double rangeBlocks) {
        if (!(rangeBlocks > 0.0) || !Double.isFinite(rangeBlocks)) {
            return List.of();
        }
        ServerLevel level = player.serverLevel();
        double x = player.getX();
        double y = player.getY();
        double z = player.getZ();
        AABB box = new AABB(x - rangeBlocks, y - rangeBlocks, z - rangeBlocks,
                x + rangeBlocks, y + rangeBlocks, z + rangeBlocks);
        List<Mob> mobs = level.getEntitiesOfClass(Mob.class, box, mob -> mob instanceof Enemy && mob.isAlive());
        if (mobs.isEmpty()) {
            return List.of();
        }
        List<ProximityScan.Candidate> candidates = new ArrayList<>(mobs.size());
        for (Mob mob : mobs) {
            candidates.add(new ProximityScan.Candidate(EntityType.getKey(mob.getType()).toString(),
                    mob.getX(), mob.getY(), mob.getZ()));
        }
        return ProximityScan.nearest(x, y, z, candidates, rangeBlocks, ScannerPayload.MAX_CONTACTS);
    }
}
