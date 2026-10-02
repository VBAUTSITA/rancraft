package dev.rancraft.item;

import dev.rancraft.device.DeviceContext;
import dev.rancraft.device.SignalDevice;
import dev.rancraft.device.StorageTerminal;
import dev.rancraft.rf.DeviceRequirement;
import java.util.List;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;

/**
 * The Wireless Storage Terminal (Phase 3 slice 13, §3C.3): opens a chest or barrel in the data centre
 * over the mobile network. The logic is in {@link StorageTerminal}.
 *
 * <ul>
 *   <li><b>Sneak + use on a chest or barrel</b> binds it (dimension and position, a data component), if
 *       it is within {@code fiberRadiusBlocks} of a Core Site. Sneaking skips the container's own menu,
 *       as vanilla does for any item in hand, so the use reaches {@link #useOn}.</li>
 *   <li><b>Use</b> opens it, if the terminal's last verdict is OK: GOOD service on a band of tier 2 or
 *       higher ({@link StorageTerminal#REQUIREMENT}), after the serving cell's backhaul cap. The session
 *       ends the moment that verdict fails ("connection lost").</li>
 * </ul>
 *
 * <p>A {@link SignalDevice}: carried in a hand or the hotbar it is dispatched the player's one sample
 * per evaluation and keeps the verdict; it computes no RF. The server decides everything; the client
 * only swings the hand.
 */
public class StorageTerminalItem extends Item implements SignalDevice {

    public StorageTerminalItem(Properties properties) {
        super(properties);
    }

    @Override
    public DeviceRequirement requirement(ItemStack stack) {
        return StorageTerminal.REQUIREMENT;
    }

    @Override
    public void onSample(ServerPlayer player, ItemStack stack, boolean held, DeviceContext ctx) {
        StorageTerminal.onSample(player, ctx);
    }

    /** Sneak + use on a block: bind (a chest or barrel) or be told why not. Anything else: {@link #use}. */
    @Override
    public InteractionResult useOn(UseOnContext context) {
        Player player = context.getPlayer();
        if (player == null || !player.isSecondaryUseActive()) {
            return InteractionResult.PASS;
        }
        if (!(context.getLevel() instanceof ServerLevel level)) {
            return InteractionResult.SUCCESS;
        }
        return StorageTerminal.bind(player, context.getItemInHand(), level, context.getClickedPos());
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        if (player instanceof ServerPlayer serverPlayer) {
            StorageTerminal.open(serverPlayer, stack);
        }
        return InteractionResultHolder.sidedSuccess(stack, level.isClientSide());
    }

    @Override
    public void appendHoverText(ItemStack stack, Item.TooltipContext context, List<Component> tooltip, TooltipFlag flag) {
        super.appendHoverText(stack, context, tooltip, flag);
        tooltip.add(Component.translatable("item.rancraft.storage_terminal.tooltip.what").withStyle(ChatFormatting.GRAY));
        tooltip.add(Component.translatable("item.rancraft.storage_terminal.tooltip.needs",
                StorageTerminal.REQUIREMENT.minServiceLevel().label(),
                StorageTerminal.REQUIREMENT.minCapacityTier()).withStyle(ChatFormatting.GRAY));
        tooltip.add(Component.translatable("item.rancraft.storage_terminal.tooltip.bind").withStyle(ChatFormatting.GRAY));
        GlobalPos target = StorageTerminal.targetOf(stack);
        if (target == null) {
            tooltip.add(Component.translatable("item.rancraft.storage_terminal.tooltip.unbound")
                    .withStyle(ChatFormatting.DARK_GRAY));
        } else {
            BlockPos pos = target.pos();
            tooltip.add(Component.translatable("item.rancraft.storage_terminal.tooltip.bound",
                    pos.getX() + ", " + pos.getY() + ", " + pos.getZ(), target.dimension().location().toString())
                    .withStyle(ChatFormatting.AQUA));
        }
    }
}
