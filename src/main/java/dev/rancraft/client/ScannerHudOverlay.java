package dev.rancraft.client;

import dev.rancraft.RanCraft;
import dev.rancraft.item.ProximityScannerItem;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

/**
 * The Proximity Scanner's HUD, top-right while a scanner is in a hand (Phase 3 slice 14, §3C.4): a list,
 * no world render. A pure view: the text is {@link ScannerHudText}, built from the server's
 * {@code ScannerPayload}; nothing here finds, ranges or filters a mob.
 *
 * <p><b>Next to the meter.</b> The Field Test Meter's compact readout is also top-right. The scanner
 * stacks under it when both are held ({@link HudStack}): registered above the Locator's layer in
 * {@link SignalHudOverlay#registerLayers}, it renders after the meter each frame and starts on the first
 * free row. The meter is not moved. Drawn like the meter (same margin, line height, no shadow), right
 * aligned to the screen edge.
 */
public final class ScannerHudOverlay {

    private ScannerHudOverlay() {
    }

    public static final ResourceLocation ID = ResourceLocation.fromNamespaceAndPath(RanCraft.MOD_ID, "scanner_hud");

    private static final int LINE_HEIGHT = 10;
    /** Least space between the title and the state drawn right-aligned on the same line. */
    private static final int TITLE_GAP = 24;
    /** Space between the list's columns. */
    private static final int COLUMN_GAP = 6;

    /** Registered by {@link SignalHudOverlay#registerLayers}, above the Locator's layer. */
    static void render(GuiGraphics graphics, DeltaTracker deltaTracker) {
        // Take the corner first, before any early return, so the meter's claim never outlives this frame.
        int top = HudStack.takeTopRight();

        Minecraft minecraft = Minecraft.getInstance();
        LocalPlayer player = minecraft.player;
        if (player == null || minecraft.level == null || minecraft.options.hideGui) {
            return;
        }
        if (heldScanner(player) == null) {
            return;
        }

        ScannerHudText.Screen screen = ScannerHudText.screen(ClientScannerState.latest(), ClientScannerState.isStale(),
                ScannerHudOverlay::entityName, player.getYRot());

        Font font = minecraft.font;
        String[] arrows = font.width(ScannerHudText.ARROWS[1]) > 0 ? ScannerHudText.ARROWS : ScannerHudText.ASCII_ARROWS;
        String degree = font.width("°") > 0 ? "°" : " deg";

        int arrowWidth = 0;
        int nameWidth = 0;
        int distanceWidth = 0;
        int directionWidth = 0;
        for (ScannerHudText.Row row : screen.rows()) {
            arrowWidth = Math.max(arrowWidth, font.width(arrows[row.arrow()]));
            nameWidth = Math.max(nameWidth, font.width(row.name()));
            distanceWidth = Math.max(distanceWidth, font.width(row.distance()));
            directionWidth = Math.max(directionWidth, font.width(row.direction().replace("°", degree)));
        }
        int width = font.width(screen.title()) + TITLE_GAP + font.width(screen.state());
        for (ScannerHudText.Line line : screen.lines()) {
            width = Math.max(width, font.width(line.text()));
        }
        if (!screen.rows().isEmpty()) {
            width = Math.max(width, arrowWidth + nameWidth + distanceWidth + directionWidth + 3 * COLUMN_GAP);
        }

        int x = graphics.guiWidth() - HudStack.MARGIN - width;
        int y = top;
        graphics.drawString(font, screen.title(), x, y, ScannerHudText.TEXT_ARGB, false);
        graphics.drawString(font, screen.state(), x + width - font.width(screen.state()), y, screen.stateArgb(), false);
        y += LINE_HEIGHT + 2;
        for (ScannerHudText.Line line : screen.lines()) {
            graphics.drawString(font, line.text(), x, y, line.argb(), false);
            y += LINE_HEIGHT;
        }
        int nameX = x + arrowWidth + COLUMN_GAP;
        int distanceRight = nameX + nameWidth + COLUMN_GAP + distanceWidth;
        int directionX = distanceRight + COLUMN_GAP;
        for (ScannerHudText.Row row : screen.rows()) {
            graphics.drawString(font, arrows[row.arrow()], x, y, ScannerHudText.CONTACT_ARGB, false);
            graphics.drawString(font, row.name(), nameX, y, ScannerHudText.CONTACT_ARGB, false);
            graphics.drawString(font, row.distance(), distanceRight - font.width(row.distance()), y,
                    ScannerHudText.TEXT_ARGB, false);
            graphics.drawString(font, row.direction().replace("°", degree), directionX, y, ScannerHudText.DIM_ARGB, false);
            y += LINE_HEIGHT;
        }
    }

    /**
     * The scanner in the main hand, else the one in the offhand, else {@code null}: whether the HUD is
     * drawn. The server sends from the same hands ({@code device.ProximityScanner}).
     */
    static ItemStack heldScanner(Player player) {
        ItemStack main = player.getMainHandItem();
        if (main.getItem() instanceof ProximityScannerItem) {
            return main;
        }
        ItemStack off = player.getOffhandItem();
        return off.getItem() instanceof ProximityScannerItem ? off : null;
    }

    /** An entity type's name in the player's language; the id itself for a type this client does not know. */
    private static String entityName(String typeId) {
        ResourceLocation id = ResourceLocation.tryParse(typeId);
        if (id == null) {
            return typeId;
        }
        return BuiltInRegistries.ENTITY_TYPE.getOptional(id)
                .map(type -> type.getDescription().getString())
                .orElse(typeId);
    }
}
