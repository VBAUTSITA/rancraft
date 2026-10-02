package dev.rancraft.client;

import dev.rancraft.RanCraft;
import dev.rancraft.item.FieldTestMeterItem;
import dev.rancraft.net.SignalSamplePayload;
import dev.rancraft.rf.CellSample;
import dev.rancraft.rf.ServiceLevel;
import dev.rancraft.rf.SinrCalculator;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RegisterGuiLayersEvent;

/**
 * Pure view over {@link ClientSignalState}. Nothing here recomputes propagation, gain, SINR or the
 * choice of serving cell -- every figure on screen arrived in a packet.
 *
 * <p>RSRP and SINR are coloured by their own classification rather than by the combined service
 * level, so a green RSRP above a red SINR reads at a glance as "plenty of signal, too much
 * interference". Diagnosing that is the whole point of Phase 2.
 */
@EventBusSubscriber(modid = RanCraft.MOD_ID, value = Dist.CLIENT, bus = EventBusSubscriber.Bus.MOD)
public final class SignalHudOverlay {

    private SignalHudOverlay() {
    }

    private static final int MARGIN = 6;
    private static final int LINE_HEIGHT = 10;
    private static final int LABEL_WIDTH = 11;

    /** Signal-strength glyphs, one per bar: U+2581 U+2583 U+2585 U+2587. */
    private static final String BAR_GLYPHS = "▁▃▅▇";

    /**
     * Warning marker. Deliberately ASCII: U+26A0 is not reliably present in the default font, and
     * a missing-glyph box next to a PCI collision would be worse than no marker at all.
     */
    private static final String WARN_MARK = "[!]";

    private static final int COLOR_DIM = 0xFF555555;
    private static final int COLOR_LABEL = 0xFFBBBBBB;
    private static final int COLOR_TITLE = 0xFFFFFFFF;
    private static final int COLOR_WARN = 0xFFFFAA00;

    /**
     * Indexed by bar count: 0 grey, 1 red, 2 orange, 3 yellow, 4 green. The values live in
     * {@link LensStyle} since RF Vision Step 3a, so the drive-test trail's markers can never drift
     * from the meter's colours; they are unchanged (pinned by {@code LensStyleTest}).
     */
    private static final int[] COLOR_BY_BARS = LensStyle.levelArgbByBars();

    /**
     * The meter's layer, then (Phase 3 slice 5) the Network Locator's directly above it. Both here,
     * in this order, because {@code registerAbove} needs its anchor registered already and two
     * handlers of one event have no guaranteed order. The Locator renders after the meter each frame
     * and stacks under its detailed readout ({@link HudStack}).
     */
    @SubscribeEvent
    public static void registerLayers(RegisterGuiLayersEvent event) {
        ResourceLocation signalHud = ResourceLocation.fromNamespaceAndPath(RanCraft.MOD_ID, "signal_hud");
        event.registerAboveAll(signalHud, SignalHudOverlay::render);
        event.registerAbove(signalHud, LocatorHudOverlay.ID, LocatorHudOverlay::render);
    }

    private static void render(GuiGraphics graphics, DeltaTracker deltaTracker) {
        Minecraft minecraft = Minecraft.getInstance();
        LocalPlayer player = minecraft.player;
        if (player == null || minecraft.options.hideGui) {
            return;
        }

        ItemStack meter = heldMeter(player);
        if (meter == null) {
            return;
        }

        SignalSamplePayload sample = ClientSignalState.latest();
        // Note this is "nothing is being received", not "service level is NONE". A link can be
        // unusable through interference while still being worth showing in full -- that reading is
        // the diagnosis, and replacing it with NO SERVICE would throw the diagnosis away.
        boolean noService = ClientSignalState.isStale() || !sample.hasServing();

        if (FieldTestMeterItem.isDetailed(meter)) {
            // Phase 3 slice 5: the detailed readout owns the top-left rows it drew; a held Network
            // Locator stacks under them. Nothing the meter draws changes.
            HudStack.claimTopLeft(renderDetailed(graphics, minecraft.font, sample, noService));
        } else {
            renderCompact(graphics, minecraft.font, sample, noService);
        }
    }

    private static ItemStack heldMeter(LocalPlayer player) {
        ItemStack main = player.getMainHandItem();
        if (main.getItem() instanceof FieldTestMeterItem) {
            return main;
        }
        ItemStack off = player.getOffhandItem();
        if (off.getItem() instanceof FieldTestMeterItem) {
            return off;
        }
        return null;
    }

    // ---- compact ------------------------------------------------------------

    private static void renderCompact(
            GuiGraphics graphics, Font font, SignalSamplePayload sample, boolean noService) {

        int screenWidth = graphics.guiWidth();

        if (noService) {
            drawRight(graphics, font, "NO SERVICE", screenWidth - MARGIN, MARGIN, COLOR_BY_BARS[0]);
            return;
        }

        CellSample serving = sample.serving();
        // Compact mode shows the combined service level, so a strong but noisy cell does not
        // misleadingly show four bars.
        int bars = sample.serviceLevel().bars();
        String readout = String.format("%.0f dBm", serving.rsrpDbm());

        int readoutWidth = font.width(readout);
        int glyphWidth = font.width(BAR_GLYPHS);
        int x = screenWidth - MARGIN - readoutWidth - 4 - glyphWidth;

        drawBars(graphics, font, bars, x, MARGIN);
        graphics.drawString(font, readout, x + glyphWidth + 4, MARGIN, COLOR_BY_BARS[bars], false);

        // Phase 3 slice 12: the serving cell's backhaul cap, as the server applied it. The bars above
        // stay the radio link's own level.
        String backhaul = sample.backhaulNote();
        if (!backhaul.isEmpty()) {
            drawRight(graphics, font, backhaul, screenWidth - MARGIN, MARGIN + LINE_HEIGHT, COLOR_WARN);
        }
    }

    // ---- detailed -----------------------------------------------------------

    /** @return the y just past the last line drawn (slice 5, for {@link HudStack}; the drawing is unchanged). */
    private static int renderDetailed(
            GuiGraphics graphics, Font font, SignalSamplePayload sample, boolean noService) {

        int x = MARGIN;
        int y = MARGIN;

        graphics.drawString(font, "RANCraft Field Test", x, y, COLOR_TITLE, false);
        y += LINE_HEIGHT + 2;

        if (noService) {
            graphics.drawString(font, "NO SERVICE", x, y, COLOR_BY_BARS[0], false);
            return y + LINE_HEIGHT;
        }

        CellSample serving = sample.serving();
        int rsrpBars = ServiceLevel.fromRsrp(serving.rsrpDbm()).bars();
        ServiceLevel sinrLevel = ServiceLevel.fromSinr(sample.sinrDb());

        y = line(graphics, font, x, y, "Serving",
                String.format("PCI %d  @ %d, %d, %d",
                        serving.pci(), serving.x(), serving.y(), serving.z()));
        y = line(graphics, font, x, y, "Band",
                String.format("%s (%.0f MHz)", sample.servingBandId(), sample.servingFrequencyMhz()));

        // RSRP: coloured by RSRP alone, with the bar glyphs inline.
        y = rsrpLine(graphics, font, x, y, serving.rsrpDbm(), rsrpBars);

        // SINR: coloured by SINR alone. Side by side these two make "strong but noisy" obvious.
        y = valueLine(graphics, font, x, y, "SINR",
                String.format("%6.1f dB   %s", sample.displaySinrDb(), sinrLevel.label()),
                COLOR_BY_BARS[sinrLevel.bars()]);

        y = line(graphics, font, x, y, "Distance",
                String.format("%.1f m    Azimuth off: %+.0f%s   Elev off: %+.0f%s",
                        serving.distanceBlocks() * sample.metersPerBlock(),
                        serving.azimuthOffsetDeg(), degrees(font),
                        serving.elevationOffsetDeg(), degrees(font)));
        y = line(graphics, font, x, y, "Ant gain",
                String.format("%.1f dBi", serving.effectiveGainDbi()));
        y = line(graphics, font, x, y, "Path loss",
                String.format("%.1f dB   Obstruction: %.1f dB",
                        serving.pathLossDb(), serving.obstructionDb()));
        y = line(graphics, font, x, y, "Interf",
                String.format("%s  (%d co-channel)      HO: %d",
                        formatDbm(sample.interferenceDbm()),
                        sample.coChannelCount(),
                        sample.handoverCount()));

        if (!sample.servingConflictNote().isEmpty()) {
            graphics.drawString(font, WARN_MARK + " " + sample.servingConflictNote(), x, y, COLOR_WARN, false);
            y += LINE_HEIGHT;
        }

        // Phase 3 slice 12 (§3C.2): "BH: LIMITED (capped FAIR)" when the serving cell's backhaul caps
        // what its devices get. The figures above are the radio link alone, untouched by it.
        String backhaul = sample.backhaulNote();
        if (!backhaul.isEmpty()) {
            graphics.drawString(font, WARN_MARK + " " + backhaul, x, y, COLOR_WARN, false);
            y += LINE_HEIGHT;
        }
        return y;
    }

    /** The RSRP row: label, coloured value, dim-then-lit bar glyphs, then the bar count. */
    private static int rsrpLine(GuiGraphics graphics, Font font, int x, int y, double rsrpDbm, int bars) {
        int color = COLOR_BY_BARS[bars];
        String label = pad("RSRP") + ": ";
        String value = String.format("%.1f dBm      ", rsrpDbm);

        graphics.drawString(font, label, x, y, COLOR_LABEL, false);
        int valueX = x + font.width(label);
        graphics.drawString(font, value, valueX, y, color, false);

        int glyphX = valueX + font.width(value);
        drawBars(graphics, font, bars, glyphX, y);
        graphics.drawString(font, String.format("  (%d/4)", bars),
                glyphX + font.width(BAR_GLYPHS), y, color, false);
        return y + LINE_HEIGHT;
    }

    private static int line(GuiGraphics graphics, Font font, int x, int y, String label, String value) {
        return valueLine(graphics, font, x, y, label, value, COLOR_TITLE);
    }

    private static int valueLine(
            GuiGraphics graphics, Font font, int x, int y, String label, String value, int valueColor) {

        String text = pad(label) + ": ";
        graphics.drawString(font, text, x, y, COLOR_LABEL, false);
        graphics.drawString(font, value, x + font.width(text), y, valueColor, false);
        return y + LINE_HEIGHT;
    }

    /** No interferers at all is -Infinity in linear terms, which is not a number to print. */
    private static String formatDbm(double dbm) {
        return Double.isInfinite(dbm) || dbm <= SinrCalculator.INTERFERER_FLOOR_DBM
                ? "   none  "
                : String.format("%.1f dBm", dbm);
    }

    /** Falls back to "deg" if the font has no degree sign, rather than drawing a missing glyph. */
    private static String degrees(Font font) {
        return font.width("°") > 0 ? "°" : " deg";
    }

    /** Pads labels to a fixed width so the colons line up like a real field-test readout. */
    private static String pad(String label) {
        StringBuilder builder = new StringBuilder(label);
        while (builder.length() < LABEL_WIDTH) {
            builder.append(' ');
        }
        return builder.toString();
    }

    /** Draws all four glyphs dim, then overdraws the lit ones in the bar colour. */
    private static void drawBars(GuiGraphics graphics, Font font, int bars, int x, int y) {
        graphics.drawString(font, BAR_GLYPHS, x, y, COLOR_DIM, false);
        if (bars > 0) {
            graphics.drawString(font, BAR_GLYPHS.substring(0, bars), x, y, COLOR_BY_BARS[bars], false);
        }
    }

    private static void drawRight(GuiGraphics graphics, Font font, String text, int right, int y, int color) {
        graphics.drawString(font, text, right - font.width(text), y, color, false);
    }
}
