package dev.rancraft.item;

import dev.rancraft.registry.ModDataComponents;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

/**
 * A whip-antenna field meter: 0 dBi, no gain, no directionality.
 *
 * <p>Simply holding it subscribes the player to updates -- see
 * {@link dev.rancraft.world.SignalTicker}. Right-click only toggles how much the HUD shows.
 */
public class FieldTestMeterItem extends Item {

    public FieldTestMeterItem(Properties properties) {
        super(properties);
    }

    public static boolean isDetailed(ItemStack stack) {
        return stack.getOrDefault(ModDataComponents.METER_DETAILED.get(), Boolean.FALSE);
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
