package dev.rancraft.client;

import dev.rancraft.net.OpenAntennaConfigPayload;
import dev.rancraft.net.UpdateCellParamsPayload;
import dev.rancraft.rf.AntennaGeometry;
import dev.rancraft.rf.AntennaPattern;
import dev.rancraft.rf.CellParams;
import dev.rancraft.rf.ParabolicPattern;
import dev.rancraft.rf.PciPlanner;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * Antenna configuration, with a live pattern preview.
 *
 * <p>A plain {@link Screen}, not a menu: there are no item slots, so the container machinery would
 * buy nothing.
 *
 * <p>The preview samples the <em>same</em> {@link AntennaPattern} object the server evaluates
 * against, at 2 degree steps. That is the payoff for keeping {@code dev.rancraft.rf} free of
 * Minecraft imports -- the plot cannot drift from the measurement, because there is only one
 * implementation of the maths.
 *
 * <p>Max gain is read-only and derived from the beamwidths. Letting a player set gain and beamwidth
 * independently would make "360 degrees wide and 20 dBi" free and collapse the whole tradeoff.
 */
public class AntennaConfigScreen extends Screen {

    private static final int PANEL_WIDTH = 176;
    private static final int ROW_HEIGHT = 22;
    private static final int WIDGET_WIDTH = 150;

    private static final int PLOT_SIZE = 110;
    private static final int PLOT_STEP_DEG = 2;

    private static final int COLOR_LABEL = 0xFFBBBBBB;
    private static final int COLOR_TITLE = 0xFFFFFFFF;
    private static final int COLOR_ERROR = 0xFFFF5555;
    private static final int COLOR_WARN = 0xFFFFAA00;
    private static final int COLOR_PLOT = 0xFF55FF55;
    private static final int COLOR_GRID = 0xFF444444;
    private static final int COLOR_BORESIGHT = 0xFFFFFF55;

    /** Plot floor: the pattern is drawn over this many dB of dynamic range below peak gain. */
    private static final double PLOT_RANGE_DB = 40.0;

    private final OpenAntennaConfigPayload initial;

    private String bandId;
    private double txPowerDbm;
    private double azimuthDeg;
    private double tiltDeg;
    private double hBeamwidthDeg;
    private double vBeamwidthDeg;
    private int pci;

    public AntennaConfigScreen(OpenAntennaConfigPayload payload) {
        super(Component.translatable("gui.rancraft.antenna_config.title"));
        this.initial = payload;
        this.bandId = payload.bandId();
        this.txPowerDbm = payload.txPowerDbm();
        this.azimuthDeg = payload.azimuthDeg();
        this.tiltDeg = payload.tiltDeg();
        this.hBeamwidthDeg = payload.hBeamwidthDeg();
        this.vBeamwidthDeg = payload.vBeamwidthDeg();
        this.pci = payload.pci();
    }

    // ---- derived values -----------------------------------------------------

    /**
     * Gain from beamwidth, exactly as the server derives it, so the read-only figure on screen is
     * the figure that will be applied.
     */
    private double maxGainDbi() {
        if (hBeamwidthDeg >= CellParams.OMNI_H_BEAMWIDTH_DEG) {
            return CellParams.DEFAULT_GAIN_DBI;
        }
        return ParabolicPattern.gainFromBeamwidthDbi(hBeamwidthDeg, vBeamwidthDeg);
    }

    /** The cell as currently edited, for feeding the preview. */
    private CellParams previewCell() {
        return new CellParams(
                0L, 0, 0, 0, bandId, txPowerDbm, maxGainDbi(),
                azimuthDeg, tiltDeg, hBeamwidthDeg, vBeamwidthDeg, pci);
    }

    // ---- layout -------------------------------------------------------------

    @Override
    protected void init() {
        int left = this.width / 2 - PANEL_WIDTH - 8;
        int y = 40;

        addRenderableWidget(CycleButton.<String>builder(Component::literal)
                .withValues(initial.availableBandIds().isEmpty()
                        ? java.util.List.of(bandId)
                        : initial.availableBandIds())
                .withInitialValue(bandId)
                .create(left, y, WIDGET_WIDTH, 20,
                        Component.translatable("gui.rancraft.antenna_config.band"),
                        (button, value) -> bandId = value));
        y += ROW_HEIGHT;

        addRenderableWidget(new DoubleSlider(left, y, WIDGET_WIDTH, 20,
                "gui.rancraft.antenna_config.azimuth", "%.0f°",
                0.0, 359.0, azimuthDeg, value -> azimuthDeg = Math.round(value)));
        y += ROW_HEIGHT;

        addRenderableWidget(new DoubleSlider(left, y, WIDGET_WIDTH, 20,
                "gui.rancraft.antenna_config.tilt", "%+.0f°",
                UpdateCellParamsPayload.MIN_TILT_DEG, UpdateCellParamsPayload.MAX_TILT_DEG,
                tiltDeg, value -> tiltDeg = Math.round(value)));
        y += ROW_HEIGHT;

        addRenderableWidget(CycleButton.<Double>builder(
                        value -> Component.literal(String.format("%.0f°", value)))
                .withValues(box(UpdateCellParamsPayload.H_BEAMWIDTHS))
                .withInitialValue(nearest(hBeamwidthDeg, UpdateCellParamsPayload.H_BEAMWIDTHS))
                .create(left, y, WIDGET_WIDTH, 20,
                        Component.translatable("gui.rancraft.antenna_config.h_beamwidth"),
                        (button, value) -> hBeamwidthDeg = value));
        y += ROW_HEIGHT;

        addRenderableWidget(CycleButton.<Double>builder(
                        value -> Component.literal(String.format("%.0f°", value)))
                .withValues(box(UpdateCellParamsPayload.V_BEAMWIDTHS))
                .withInitialValue(nearest(vBeamwidthDeg, UpdateCellParamsPayload.V_BEAMWIDTHS))
                .create(left, y, WIDGET_WIDTH, 20,
                        Component.translatable("gui.rancraft.antenna_config.v_beamwidth"),
                        (button, value) -> vBeamwidthDeg = value));
        y += ROW_HEIGHT;

        addRenderableWidget(new DoubleSlider(left, y, WIDGET_WIDTH, 20,
                "gui.rancraft.antenna_config.tx_power", "%.0f dBm",
                UpdateCellParamsPayload.MIN_TX_DBM, UpdateCellParamsPayload.MAX_TX_DBM,
                txPowerDbm, value -> txPowerDbm = Math.round(value)));
        y += ROW_HEIGHT;

        addRenderableWidget(new DoubleSlider(left, y, WIDGET_WIDTH, 20,
                "gui.rancraft.antenna_config.pci", "%.0f",
                PciPlanner.MIN_PCI, PciPlanner.MAX_PCI, pci,
                value -> pci = (int) Math.round(value)));
        y += ROW_HEIGHT + 8;

        addRenderableWidget(Button.builder(
                        Component.translatable("gui.rancraft.antenna_config.apply"),
                        button -> apply())
                .bounds(left, y, WIDGET_WIDTH, 20)
                .build());
    }

    private void apply() {
        PacketDistributor.sendToServer(new UpdateCellParamsPayload(
                initial.pos(), bandId, txPowerDbm, azimuthDeg, tiltDeg,
                hBeamwidthDeg, vBeamwidthDeg, pci));
        onClose();
    }

    // ---- rendering ----------------------------------------------------------

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        super.render(graphics, mouseX, mouseY, partialTick);

        int left = this.width / 2 - PANEL_WIDTH - 8;
        graphics.drawString(font, title, left, 20, COLOR_TITLE, false);

        int plotLeft = this.width / 2 + 16;
        int plotTop = 40;

        graphics.drawString(font, Component.translatable("gui.rancraft.antenna_config.horizontal"),
                plotLeft, plotTop - 12, COLOR_LABEL, false);
        drawHorizontalPattern(graphics, plotLeft, plotTop);

        int verticalTop = plotTop + PLOT_SIZE + 20;
        graphics.drawString(font, Component.translatable("gui.rancraft.antenna_config.vertical"),
                plotLeft, verticalTop - 12, COLOR_LABEL, false);
        drawVerticalPattern(graphics, plotLeft, verticalTop);

        // Max gain is derived, not chosen -- shown so the tradeoff is visible while editing.
        graphics.drawString(font,
                Component.translatable("gui.rancraft.antenna_config.max_gain")
                        .append(Component.literal(String.format(": %.1f dBi", maxGainDbi()))),
                plotLeft, verticalTop + PLOT_SIZE / 2 + 12, COLOR_TITLE, false);

        int conflictY = verticalTop + PLOT_SIZE / 2 + 26;
        for (String conflict : initial.conflicts()) {
            boolean warning = conflict.startsWith("mod-3");
            graphics.drawString(font, "[!] " + conflict, plotLeft, conflictY,
                    warning ? COLOR_WARN : COLOR_ERROR, false);
            conflictY += 10;
        }
    }

    /**
     * Polar plot of the horizontal cut, 0 degrees up = north, matching
     * {@link AntennaGeometry}'s compass convention so the plot and the world agree.
     */
    private void drawHorizontalPattern(GuiGraphics graphics, int left, int top) {
        int radius = PLOT_SIZE / 2;
        int cx = left + radius;
        int cy = top + radius;

        graphics.renderOutline(left, top, PLOT_SIZE, PLOT_SIZE, COLOR_GRID);
        graphics.hLine(cx - radius, cx + radius, cy, COLOR_GRID);
        graphics.vLine(cx, cy - radius, cy + radius, COLOR_GRID);

        CellParams cell = previewCell();
        AntennaPattern pattern = AntennaPattern.forCell(cell,
                ParabolicPattern.DEFAULT_FRONT_TO_BACK_DB, ParabolicPattern.DEFAULT_SIDELOBE_FLOOR_DB);
        double peak = cell.gainDbi();

        int previousX = Integer.MIN_VALUE;
        int previousY = 0;
        int firstX = 0;
        int firstY = 0;

        for (int bearing = 0; bearing <= 360; bearing += PLOT_STEP_DEG) {
            double gain = pattern.gainDbi(bearing, 0.0, cell);
            double r = radius * normalized(gain, peak);

            // Screen Y grows downward, and bearing 0 must point up, hence -cos for the Y term.
            int px = cx + (int) Math.round(r * Math.sin(Math.toRadians(bearing)));
            int py = cy - (int) Math.round(r * Math.cos(Math.toRadians(bearing)));

            if (previousX != Integer.MIN_VALUE) {
                drawLine(graphics, previousX, previousY, px, py, COLOR_PLOT);
            } else {
                firstX = px;
                firstY = py;
            }
            previousX = px;
            previousY = py;
        }
        drawLine(graphics, previousX, previousY, firstX, firstY, COLOR_PLOT);

        // Boresight marker: where the antenna is actually pointed.
        int bx = cx + (int) Math.round(radius * Math.sin(Math.toRadians(azimuthDeg)));
        int by = cy - (int) Math.round(radius * Math.cos(Math.toRadians(azimuthDeg)));
        drawLine(graphics, cx, cy, bx, by, COLOR_BORESIGHT);
    }

    /**
     * Side profile of the vertical cut. The horizon is the horizontal centre line and downtilt
     * visibly drops the lobe below it, which is the whole point of the control.
     */
    private void drawVerticalPattern(GuiGraphics graphics, int left, int top) {
        int height = PLOT_SIZE / 2;
        int cy = top + height / 2;

        graphics.renderOutline(left, top, PLOT_SIZE, height, COLOR_GRID);
        graphics.hLine(left, left + PLOT_SIZE, cy, COLOR_GRID);

        CellParams cell = previewCell();
        AntennaPattern pattern = AntennaPattern.forCell(cell,
                ParabolicPattern.DEFAULT_FRONT_TO_BACK_DB, ParabolicPattern.DEFAULT_SIDELOBE_FLOOR_DB);
        double peak = cell.gainDbi();

        int previousX = Integer.MIN_VALUE;
        int previousY = 0;

        // -90 (straight down) to +90 (straight up), drawn left to right as a half-plane cut.
        for (int elevation = -90; elevation <= 90; elevation += PLOT_STEP_DEG) {
            double gain = pattern.gainDbi(azimuthDeg, elevation, cell);
            double r = (PLOT_SIZE / 2.0) * normalized(gain, peak);

            int px = left + (int) Math.round(r * Math.cos(Math.toRadians(elevation)));
            int py = cy - (int) Math.round(r * Math.sin(Math.toRadians(elevation)));

            if (previousX != Integer.MIN_VALUE) {
                drawLine(graphics, previousX, previousY, px, py, COLOR_PLOT);
            }
            previousX = px;
            previousY = py;
        }

        // Boresight sits at elevation -tilt: positive tilt is downtilt.
        double boresight = -tiltDeg;
        int bx = left + (int) Math.round((PLOT_SIZE / 2.0) * Math.cos(Math.toRadians(boresight)));
        int by = cy - (int) Math.round((PLOT_SIZE / 2.0) * Math.sin(Math.toRadians(boresight)));
        drawLine(graphics, left, cy, bx, by, COLOR_BORESIGHT);
    }

    /** Maps gain onto 0..1 over {@link #PLOT_RANGE_DB} of dynamic range below peak. */
    private static double normalized(double gainDbi, double peakDbi) {
        double belowPeak = peakDbi - gainDbi;
        return Math.clamp(1.0 - belowPeak / PLOT_RANGE_DB, 0.0, 1.0);
    }

    /** Bresenham. {@code GuiGraphics} only draws axis-aligned lines. */
    private static void drawLine(GuiGraphics graphics, int x0, int y0, int x1, int y1, int color) {
        int dx = Math.abs(x1 - x0);
        int dy = -Math.abs(y1 - y0);
        int sx = x0 < x1 ? 1 : -1;
        int sy = y0 < y1 ? 1 : -1;
        int error = dx + dy;

        while (true) {
            graphics.fill(x0, y0, x0 + 1, y0 + 1, color);
            if (x0 == x1 && y0 == y1) {
                return;
            }
            int doubled = 2 * error;
            if (doubled >= dy) {
                error += dy;
                x0 += sx;
            }
            if (doubled <= dx) {
                error += dx;
                y0 += sy;
            }
        }
    }

    // ---- helpers ------------------------------------------------------------

    private static java.util.List<Double> box(double[] values) {
        java.util.List<Double> boxed = new java.util.ArrayList<>(values.length);
        for (double value : values) {
            boxed.add(value);
        }
        return boxed;
    }

    /** Snaps a loaded value onto the nearest offered option, so a cycle button always starts valid. */
    private static Double nearest(double value, double[] options) {
        double best = options[0];
        for (double option : options) {
            if (Math.abs(option - value) < Math.abs(best - value)) {
                best = option;
            }
        }
        return best;
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    /** A slider over an arbitrary double range, since vanilla's is 0..1 only. */
    private static final class DoubleSlider extends AbstractSliderButton {

        private final String labelKey;
        private final String valueFormat;
        private final double min;
        private final double max;
        private final java.util.function.DoubleConsumer sink;

        DoubleSlider(int x, int y, int width, int height, String labelKey, String valueFormat,
                     double min, double max, double initial, java.util.function.DoubleConsumer sink) {
            super(x, y, width, height, Component.empty(), (initial - min) / (max - min));
            this.labelKey = labelKey;
            this.valueFormat = valueFormat;
            this.min = min;
            this.max = max;
            this.sink = sink;
            updateMessage();
        }

        private double actualValue() {
            return min + value * (max - min);
        }

        @Override
        protected void updateMessage() {
            setMessage(Component.translatable(labelKey)
                    .append(Component.literal(": " + String.format(valueFormat, actualValue()))));
        }

        @Override
        protected void applyValue() {
            sink.accept(actualValue());
        }
    }
}
