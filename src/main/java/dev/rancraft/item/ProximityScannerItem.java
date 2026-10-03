package dev.rancraft.item;

import dev.rancraft.device.DeviceContext;
import dev.rancraft.device.ProximityScanner;
import dev.rancraft.device.SignalDevice;
import dev.rancraft.rf.DeviceRequirement;
import java.util.List;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;

/**
 * The Proximity Scanner (Phase 3 slice 14, §3C.4): held, with GOOD service on a band of capacity tier 3
 * (band_3500), it lists the hostile mobs around you on the HUD (type, distance, bearing; nearest first, at
 * most 16). No world render. The logic is in {@link ProximityScanner}.
 *
 * <p><b>Game abstraction, labelled: the scan is not RF physics.</b> The list stands in for a high-rate
 * sensor feed that needs a high-capacity link; it is the gameplay reward that makes band_3500 worth
 * deploying. The network decides whether the feed arrives, not what is in it. See {@link ProximityScanner}.
 *
 * <p>A {@link SignalDevice}: carried in a hand or the hotbar it is dispatched the player's one sample per
 * evaluation and computes no RF. Only a held scanner sends anything; the server decides everything, and the
 * client only draws what it is sent.
 */
public class ProximityScannerItem extends Item implements SignalDevice {

    public ProximityScannerItem(Properties properties) {
        super(properties);
    }

    @Override
    public DeviceRequirement requirement(ItemStack stack) {
        return ProximityScanner.REQUIREMENT;
    }

    @Override
    public void onSample(ServerPlayer player, ItemStack stack, boolean held, DeviceContext ctx) {
        ProximityScanner.onSample(player, stack, held, ctx);
    }

    @Override
    public void appendHoverText(ItemStack stack, Item.TooltipContext context, List<Component> tooltip, TooltipFlag flag) {
        super.appendHoverText(stack, context, tooltip, flag);
        tooltip.add(Component.translatable("item.rancraft.proximity_scanner.tooltip.what").withStyle(ChatFormatting.GRAY));
        tooltip.add(Component.translatable("item.rancraft.proximity_scanner.tooltip.needs",
                ProximityScanner.REQUIREMENT.minServiceLevel().label(),
                ProximityScanner.REQUIREMENT.minCapacityTier()).withStyle(ChatFormatting.GRAY));
        tooltip.add(Component.translatable("item.rancraft.proximity_scanner.tooltip.honest")
                .withStyle(ChatFormatting.DARK_GRAY));
    }
}
