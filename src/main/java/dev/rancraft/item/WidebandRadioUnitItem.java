package dev.rancraft.item;

import dev.rancraft.block.SectorAntennaBlockEntity;
import dev.rancraft.rf.RadioTier;
import java.util.List;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;

/**
 * The Wideband Radio Unit: used on a Sector Antenna, it raises the antenna's radio to tier
 * {@link RadioTier#WIDEBAND} (3), which opens band_3500, and is consumed. Breaking the antenna drops
 * it again. Phase 3 slice 10, §3C.1.
 *
 * <p><b>Game abstraction, labelled</b> (see {@link RadioTier}): a real site adds a band by fitting
 * a radio unit built for it (n78 at 3.5 GHz needs a radio that handles 100 MHz carriers and that
 * band's filters and amplifier). Here one item unlocks every band up to tier 3 on the one antenna it
 * is fitted to, and the antenna keeps its other settings.
 *
 * <p>Using it goes through the sector's {@code useItemOn}, which steps aside for this item so the
 * configuration screen does not open instead ({@code SectorAntennaBlock}); sneak + use reaches this
 * method directly. On the client it only predicts the swing from the antenna's synced tier; the
 * server fits the unit and takes the item (not in creative, where vanilla keeps the stack).
 *
 * <p>No recipe yet (§3C.6, slice 16): creative-only, like every block and item so far.
 */
public class WidebandRadioUnitItem extends Item {

    public WidebandRadioUnitItem(Properties properties) {
        super(properties);
    }

    @Override
    public InteractionResult useOn(UseOnContext context) {
        Level level = context.getLevel();
        BlockPos pos = context.getClickedPos();
        if (!(level.getBlockEntity(pos) instanceof SectorAntennaBlockEntity sector)) {
            return InteractionResult.PASS;
        }
        Player player = context.getPlayer();
        if (sector.hasWidebandUnit()) {
            if (!level.isClientSide() && player != null) {
                player.displayClientMessage(Component.translatable("rancraft.wideband.already"), true);
            }
            return InteractionResult.FAIL;
        }
        if (!level.isClientSide()) {
            if (!sector.installWidebandUnit()) {
                return InteractionResult.FAIL;
            }
            context.getItemInHand().consume(1, player);
            level.playSound(null, pos, SoundEvents.SMITHING_TABLE_USE, SoundSource.BLOCKS, 1.0F, 1.0F);
            if (player != null) {
                player.displayClientMessage(Component.translatable("rancraft.wideband.installed"), true);
            }
        }
        return InteractionResult.sidedSuccess(level.isClientSide());
    }

    @Override
    public void appendHoverText(ItemStack stack, Item.TooltipContext context, List<Component> tooltip, TooltipFlag flag) {
        super.appendHoverText(stack, context, tooltip, flag);
        tooltip.add(Component.translatable("item.rancraft.wideband_radio_unit.tooltip.use").withStyle(ChatFormatting.GRAY));
        tooltip.add(Component.translatable("item.rancraft.wideband_radio_unit.tooltip.drop").withStyle(ChatFormatting.DARK_GRAY));
    }
}
