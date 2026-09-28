package dev.rancraft.item;

import dev.rancraft.device.DeviceContext;
import dev.rancraft.device.NetworkLocator;
import dev.rancraft.device.SignalDevice;
import dev.rancraft.registry.ModDataComponents;
import dev.rancraft.rf.DeviceRequirement;
import java.util.List;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;

/**
 * The Network Locator: works out where you are from the cell towers you can hear. Phase 3 slice 5,
 * §3A.6.
 *
 * <p><b>It is not GPS, and it is never called that.</b> GPS (and Galileo, GLONASS, BeiDou) is
 * satellite navigation: the receiver times signals from satellites. This locates a phone from the
 * mobile network instead, the family of methods 3GPP defines for LTE and NR:
 * <ul>
 *   <li><b>E-CID</b> (enhanced cell ID): which cells you hear, how strongly, and the timing advance
 *       to the serving cell, a rough range;</li>
 *   <li><b>OTDOA</b> (observed time difference of arrival): the phone times dedicated positioning
 *       reference signals from several cells; each <em>difference</em> of arrival times puts it on a
 *       hyperbola, and the hyperbolas cross at the position;</li>
 *   <li><b>NR multi-RTT</b> (multi-cell round-trip time): round trips to several cells give absolute
 *       ranges, which cross as circles.</li>
 * </ul>
 * RANCraft models the last kind: absolute ranges to every cell heard ({@code rf.Ranging}), quantised
 * by each band's bandwidth (a wideband band_3500 cell ranges to 3 m, a 10 MHz band_900 cell to
 * 30 m), biased long behind terrain, and solved by least squares ({@code rf.LocatorSolver}). The
 * answer degrades by fix type rather than switching off: NO SIGNAL, RANGE ONLY (one cell: a ring),
 * AMBIGUOUS (two: two points), POOR GEOMETRY (towers too close to a line), FIX.
 *
 * <p>A {@link SignalDevice} with requirement {@link DeviceRequirement#NONE}: it works wherever any
 * cell is heard, and carrying it (in a hand or the hotbar) costs no evaluation beyond the one the
 * player already gets ({@link NetworkLocator}). Held, it draws its HUD and the range rings; in the
 * hotbar it still keeps the emergency record.
 *
 * <p><b>Waypoints</b> ({@link LocatorWaypoints}, a data component): sneak + use saves the current
 * <em>estimate</em>, use cycles the selected one, and the HUD gives distance and bearing from the
 * estimate to it. A waypoint saved under a bad fix leads you to the wrong place. That is the point.
 */
public class NetworkLocatorItem extends Item implements SignalDevice {

    public NetworkLocatorItem(Properties properties) {
        super(properties);
    }

    /** The waypoints saved on this Locator; empty when none. */
    public static LocatorWaypoints waypointsOf(ItemStack stack) {
        return stack.getOrDefault(ModDataComponents.LOCATOR_WAYPOINTS.get(), LocatorWaypoints.EMPTY);
    }

    /** NONE: the Locator degrades by fix type rather than refusing to work (§3A.6). */
    @Override
    public DeviceRequirement requirement(ItemStack stack) {
        return DeviceRequirement.NONE;
    }

    @Override
    public void onSample(ServerPlayer player, ItemStack stack, boolean held, DeviceContext ctx) {
        NetworkLocator.onSample(player, stack, held, ctx);
    }

    /**
     * Use cycles the selected waypoint; sneak + use saves the current estimate. Both on the server,
     * which alone knows the Locator's latest fix; the client only swings the hand.
     */
    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        if (player instanceof ServerPlayer serverPlayer) {
            if (player.isSecondaryUseActive()) {
                NetworkLocator.saveWaypoint(serverPlayer, stack);
            } else {
                NetworkLocator.cycleWaypoint(serverPlayer, stack);
            }
        }
        return InteractionResultHolder.sidedSuccess(stack, level.isClientSide());
    }

    @Override
    public void appendHoverText(ItemStack stack, Item.TooltipContext context, List<Component> tooltip, TooltipFlag flag) {
        super.appendHoverText(stack, context, tooltip, flag);
        tooltip.add(Component.translatable("item.rancraft.network_locator.tooltip.what").withStyle(ChatFormatting.GRAY));
        tooltip.add(Component.translatable("item.rancraft.network_locator.tooltip.use").withStyle(ChatFormatting.GRAY));
        LocatorWaypoints waypoints = waypointsOf(stack);
        tooltip.add(Component.translatable("item.rancraft.network_locator.tooltip.waypoints",
                waypoints.size(), LocatorWaypoints.MAX_ENTRIES).withStyle(ChatFormatting.DARK_GRAY));
    }
}
