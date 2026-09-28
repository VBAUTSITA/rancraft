package dev.rancraft.item;

import dev.rancraft.device.DeviceContext;
import dev.rancraft.device.SignalDevice;
import dev.rancraft.registry.ModDataComponents;
import dev.rancraft.rf.DeviceRequirement;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

/**
 * A whip-antenna field meter: 0 dBi, no gain, no directionality.
 *
 * <p>The first {@link SignalDevice} (Phase 3, §3A.2), and the regression reference for the ticker
 * refactor: carrying it (in a hand or the hotbar) subscribes the player to updates -- see
 * {@link dev.rancraft.world.SignalTicker}. The HUD draws only while it is held
 * ({@code client.SignalHudOverlay}). Right-click only toggles how much the HUD shows.
 *
 * <p>Requirement {@link DeviceRequirement#NONE}: a field meter works everywhere, and a reading of
 * NO SERVICE is exactly what it is for.
 */
public class FieldTestMeterItem extends Item implements SignalDevice {

    public FieldTestMeterItem(Properties properties) {
        super(properties);
    }

    public static boolean isDetailed(ItemStack stack) {
        return stack.getOrDefault(ModDataComponents.METER_DETAILED.get(), Boolean.FALSE);
    }

    @Override
    public DeviceRequirement requirement(ItemStack stack) {
        return DeviceRequirement.NONE;
    }

    /**
     * Nothing to do on the server, so trivially idempotent on replays.
     *
     * <p>§3A.2 says the meter "sends SignalSamplePayload only when held". Since the Phase 3 slice 0
     * gate fix the ticker sends that payload for <em>every</em> evaluation, whichever device or lens
     * caused it, because the client's drive-test log must see every evaluation that can move the
     * handover counter ({@code SignalTicker.sendsSample}). Sending it here too would send it twice;
     * sending it only here, only when held, would bring back the false handover pillars for a
     * player evaluated for a device in the hotbar. So "only when held" is where it matters, in the
     * HUD, which draws only while a meter is in a hand. The payload is built by the ticker exactly
     * as before the refactor (pinned byte for byte by {@code SignalTickerPayloadTest}).
     */
    @Override
    public void onSample(ServerPlayer player, ItemStack stack, boolean held, DeviceContext ctx) {
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        if (!level.isClientSide()) {
            stack.set(ModDataComponents.METER_DETAILED.get(), !isDetailed(stack));
        }
        return InteractionResultHolder.sidedSuccess(stack, level.isClientSide());
    }
}
