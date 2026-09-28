package dev.rancraft.client;

import dev.rancraft.RanCraft;
import dev.rancraft.item.NetworkLocatorItem;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

/**
 * The Network Locator's HUD, top-left while a Locator is in a hand (Phase 3 slice 5, §3A.6). A pure
 * view: the text is {@link LocatorHudText}, built from the server's {@code LocatorFixPayload} and the
 * held Locator's waypoints; nothing here computes a position.
 *
 * <p><b>Next to the meter.</b> The Field Test Meter's detailed readout is also top-left. The Locator
 * stacks under it when both are held ({@link HudStack}): registered directly above the meter's layer
 * in {@link SignalHudOverlay#registerLayers}, it renders after it each frame and starts on the first
 * free row. The meter is not moved.
 *
 * <p>Drawn like the meter (same margin, line height, no shadow), so the two read as one instrument
 * panel.
 */
public final class LocatorHudOverlay {

    private LocatorHudOverlay() {
    }

    public static final ResourceLocation ID = ResourceLocation.fromNamespaceAndPath(RanCraft.MOD_ID, "locator_hud");

    private static final int LINE_HEIGHT = 10;
    /** Least space between the title and the state drawn right-aligned on the same line. */
    private static final int TITLE_GAP = 24;

    /** Registered by {@link SignalHudOverlay#registerLayers}, directly above the meter's layer. */
    static void render(GuiGraphics graphics, DeltaTracker deltaTracker) {
        // Take the corner first, before any early return, so the meter's claim never outlives this frame.
        int top = HudStack.takeTopLeft();

        Minecraft minecraft = Minecraft.getInstance();
        LocalPlayer player = minecraft.player;
        if (player == null || minecraft.level == null || minecraft.options.hideGui) {
            return;
        }
        ItemStack locator = heldLocator(player);
        if (locator == null) {
            return;
        }

        LocatorHudText.Screen screen = LocatorHudText.screen(
                ClientLocatorState.latest(), ClientLocatorState.isStale(),
                NetworkLocatorItem.waypointsOf(locator), minecraft.level.dimension().location().toString());

        Font font = minecraft.font;
        String state = glyphs(font, screen.state());
        int width = font.width(screen.title()) + TITLE_GAP + font.width(state);
        for (LocatorHudText.Line line : screen.lines()) {
            width = Math.max(width, font.width(glyphs(font, line.text())));
        }

        int x = HudStack.MARGIN;
        int y = top;
        graphics.drawString(font, screen.title(), x, y, LocatorHudText.TEXT_ARGB, false);
        graphics.drawString(font, state, x + width - font.width(state), y, screen.stateArgb(), false);
        y += LINE_HEIGHT + 2;
        for (LocatorHudText.Line line : screen.lines()) {
            graphics.drawString(font, glyphs(font, line.text()), x, y, line.argb(), false);
            y += LINE_HEIGHT;
        }
    }

    /**
     * The Locator in the main hand, else the one in the offhand, else {@code null}. The same order the
     * server uses to choose which held Locator sends its payload ({@code device.NetworkLocator}).
     */
    static ItemStack heldLocator(Player player) {
        ItemStack main = player.getMainHandItem();
        if (main.getItem() instanceof NetworkLocatorItem) {
            return main;
        }
        ItemStack off = player.getOffhandItem();
        return off.getItem() instanceof NetworkLocatorItem ? off : null;
    }

    /**
     * Falls back to ASCII if the font lacks the "±" or degree sign, rather than drawing a
     * missing-glyph box (the meter's rule for the degree sign).
     */
    private static String glyphs(Font font, String text) {
        String out = text;
        if (out.indexOf('±') >= 0 && font.width("±") <= 0) {
            out = out.replace("±", "+/-");
        }
        if (out.indexOf('°') >= 0 && font.width("°") <= 0) {
            out = out.replace("°", " deg");
        }
        return out;
    }
}
