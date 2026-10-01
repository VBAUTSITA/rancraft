package dev.rancraft.item;

import dev.rancraft.registry.ModDataComponents;
import java.util.List;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Equipable;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;

/**
 * Wearable RF vision. Put it on and the invisible field becomes geometry you can walk around.
 *
 * <p>Occupies the helmet slot, so wearing it <em>is</em> the on switch -- there is no mode to
 * remember. What it draws is carried by {@link LensSettings} on the stack, and the wearer changes
 * it with two key bindings (defaults B and V): one cycles the band filter, the other cycles the
 * {@link LensLayers} presets. The keys ask the server, which edits the stack; the lens has no
 * client-side state of its own.
 *
 * <p>Four layers:
 * <ul>
 *   <li><b>Lobes (Step 1).</b> Each antenna's declared radiation pattern. Public configuration,
 *       already synced to the client, drawn with the same pattern maths the server evaluates with.
 *   <li><b>Link rays (Step 2a).</b> A line to every cell the server heard at the wearer's head,
 *       coloured by where along it the terrain took its dB.
 *   <li><b>Coverage (Step 2b).</b> A best-server plot painted on the ground around the wearer.
 *   <li><b>Drive-test trail (Step 3a).</b> A marker at every point the server measured the wearer,
 *       coloured by the service level there, with pillars where handovers happened. Exported as CSV
 *       with {@code /rancraftc drivetest export}.
 * </ul>
 *
 * <p><b>It measures nothing.</b> Link rays, coverage and the trail are server measurements shipped
 * as values ({@code net.LensLinksPayload}, {@code net.CoverageSurveyPayload},
 * {@code net.SignalSamplePayload}); the client only draws them.
 * RSRP, SINR, obstruction, serving-cell choice and coverage all stay server-authoritative. See
 * VISION.md for where that line sits and why.
 *
 * <p><b>Not tier-gated</b> (VISION.md open question 1, answered by §3C.1 in Phase 3 slice 10). Radio
 * tiers gate antennas and devices, never the lens: it works with every band from the first mast,
 * and gets a cheap early recipe (§3C.6, slice 16). Gating the teaching tool behind late game would be
 * backwards for this mod.
 */
public class RfLensItem extends Item implements Equipable {

    public RfLensItem(Properties properties) {
        super(properties);
    }

    public static LensSettings settingsOf(ItemStack stack) {
        return stack.getOrDefault(ModDataComponents.LENS_SETTINGS.get(), LensSettings.DEFAULT);
    }

    /** The lens the player is wearing, or {@code null}. */
    public static ItemStack wornBy(Player player) {
        ItemStack head = player.getItemBySlot(EquipmentSlot.HEAD);
        return head.getItem() instanceof RfLensItem ? head : null;
    }

    @Override
    public EquipmentSlot getEquipmentSlot() {
        return EquipmentSlot.HEAD;
    }

    /**
     * Right-click is the vanilla equip swap, like any other headgear. Band and layer cycling live
     * on key bindings instead, because they have to work while the lens is on your head -- where
     * right-click cannot reach it.
     */
    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        return this.swapWithEquipmentSlot(this, level, player, hand);
    }

    @Override
    public void appendHoverText(ItemStack stack, Item.TooltipContext context, List<Component> tooltip, TooltipFlag flag) {
        super.appendHoverText(stack, context, tooltip, flag);
        tooltip.add(Component.translatable("item.rancraft.rf_lens.tooltip.wear").withStyle(ChatFormatting.GRAY));
        tooltip.add(Component.translatable("item.rancraft.rf_lens.tooltip.keys").withStyle(ChatFormatting.GRAY));
        tooltip.add(Component.translatable("item.rancraft.rf_lens.tooltip.drivetest").withStyle(ChatFormatting.GRAY));
    }
}
