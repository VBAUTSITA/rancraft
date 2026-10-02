package dev.rancraft.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.rancraft.rf.DriveTestLog;
import dev.rancraft.rf.MicrowaveLink;
import dev.rancraft.rf.ServiceLevel;
import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The lens's drawing rules: every input is a server value, and these only decide how it looks. */
class LensStyleTest {

    private static int red(int rgb) {
        return (rgb >> 16) & 0xFF;
    }

    private static int green(int rgb) {
        return (rgb >> 8) & 0xFF;
    }

    private static int blue(int rgb) {
        return rgb & 0xFF;
    }

    // ---- lobes on and off the air (Phase 3 slice 6) -------------------------------------------

    @Test
    @DisplayName("an on-air lobe keeps its band colour and opacity; an off-air one is grey at half opacity")
    void offAirLobesAreGreyed() {
        int band = 0x33AAFF;
        assertEquals(band, LensStyle.lobeRgb(band, true));
        assertEquals(LensStyle.OFF_AIR_RGB, LensStyle.lobeRgb(band, false));
        assertEquals(LensStyle.OFF_AIR_RGB, LensStyle.lobeRgb(0xFF0000, false), "grey whatever the band");
        assertEquals(red(LensStyle.OFF_AIR_RGB), green(LensStyle.OFF_AIR_RGB), "a neutral grey");
        assertEquals(green(LensStyle.OFF_AIR_RGB), blue(LensStyle.OFF_AIR_RGB), "a neutral grey");
        assertEquals(0x33AAFF, LensStyle.lobeRgb(0xFF33AAFF, true), "alpha bits are dropped");

        for (int alpha : new int[] {70, 120, 200}) {
            assertEquals(alpha, LensStyle.lobeAlpha(alpha, true));
            assertEquals(alpha / 2, LensStyle.lobeAlpha(alpha, false));
        }
        assertEquals(1, LensStyle.lobeAlpha(1, false), "never fully transparent");
        assertEquals(255, LensStyle.lobeAlpha(999, true), "clamped");
    }

    // ---- loss colour ------------------------------------------------------------------------

    @Test
    @DisplayName("loss colour runs green at 0 dB, yellow at 20 dB, red at 40 dB and beyond")
    void lossRamp() {
        int clear = LensStyle.lossRgb(0.0);
        assertEquals(255, green(clear));
        assertTrue(red(clear) < 100, "clear is green, not yellow");

        int half = LensStyle.lossRgb(LensStyle.LOSS_FULL_RED_DB / 2.0);
        assertEquals(255, red(half));
        assertEquals(255, green(half));

        int full = LensStyle.lossRgb(LensStyle.LOSS_FULL_RED_DB);
        assertEquals(255, red(full));
        assertTrue(green(full) < 100, "40 dB is red, not orange");

        assertEquals(full, LensStyle.lossRgb(120.0), "past 40 dB stays red");
    }

    @Test
    @DisplayName("more loss is never greener: red never falls and green never rises")
    void lossMonotonic() {
        int previous = LensStyle.lossRgb(0.0);
        for (double db = 0.25; db <= 60.0; db += 0.25) {
            int next = LensStyle.lossRgb(db);
            assertTrue(red(next) >= red(previous), "red fell at " + db + " dB");
            assertTrue(green(next) <= green(previous), "green rose at " + db + " dB");
            assertEquals(blue(previous), blue(next), "blue is constant");
            previous = next;
        }
    }

    @Test
    @DisplayName("negative or non-numeric loss is drawn as clear")
    void lossDegenerateInputs() {
        int clear = LensStyle.lossRgb(0.0);
        assertEquals(clear, LensStyle.lossRgb(-5.0));
        assertEquals(clear, LensStyle.lossRgb(Double.NaN));
    }

    // ---- pieces -----------------------------------------------------------------------------

    private static void assertTiles(List<LensStyle.Piece> pieces) {
        assertEquals(0.0, pieces.get(0).t0(), "starts at the antenna");
        assertEquals(1.0, pieces.get(pieces.size() - 1).t1(), "ends at the receiver");
        for (int k = 0; k < pieces.size(); k++) {
            LensStyle.Piece piece = pieces.get(k);
            assertTrue(piece.t1() > piece.t0(), "no zero-length or reversed piece: " + piece);
            if (k > 0) {
                assertEquals(pieces.get(k - 1).t1(), piece.t0(), "pieces are contiguous");
            }
        }
    }

    @Test
    @DisplayName("no breakpoints is one clear stretch: line of sight")
    void piecesLineOfSight() {
        List<LensStyle.Piece> pieces = LensStyle.pieces(new float[0], new float[0]);
        assertEquals(List.of(new LensStyle.Piece(0.0, 1.0, 0.0)), pieces);
    }

    @Test
    @DisplayName("a single wall splits the link: clear before it, its loss after it")
    void piecesSingleWall() {
        List<LensStyle.Piece> pieces = LensStyle.pieces(new float[] {0.5F}, new float[] {12.0F});
        assertEquals(2, pieces.size());
        assertEquals(new LensStyle.Piece(0.0, 0.5, 0.0), pieces.get(0));
        assertEquals(new LensStyle.Piece(0.5, 1.0, 12.0), pieces.get(1));
    }

    @Test
    @DisplayName("each stretch carries the server's loss at its start; the last carries the total")
    void piecesCarryServerLoss() {
        float[] t = {0.2F, 0.4F, 0.7F};
        float[] db = {3.0F, 9.5F, 21.25F};
        List<LensStyle.Piece> pieces = LensStyle.pieces(t, db);
        assertTiles(pieces);
        assertEquals(4, pieces.size());
        assertEquals(0.0, pieces.get(0).lossDb());
        assertEquals(3.0, pieces.get(1).lossDb());
        assertEquals(9.5, pieces.get(2).lossDb());
        assertEquals(21.25, pieces.get(3).lossDb(), "the final stretch shows the total obstruction");
    }

    @Test
    @DisplayName("breakpoints at the same t never make a zero-length piece, and the later loss wins")
    void piecesCoincidentBreakpoints() {
        List<LensStyle.Piece> pieces = LensStyle.pieces(new float[] {0.5F, 0.5F}, new float[] {4.0F, 7.0F});
        assertTiles(pieces);
        assertEquals(2, pieces.size());
        assertEquals(7.0, pieces.get(1).lossDb());
    }

    @Test
    @DisplayName("out-of-range or backwards positions are clamped, never drawn outside the link")
    void piecesDefensiveClamping() {
        List<LensStyle.Piece> pieces = LensStyle.pieces(
                new float[] {-0.5F, 0.6F, 0.3F, 1.7F}, new float[] {1.0F, 2.0F, 3.0F, 4.0F});
        assertTiles(pieces);
        for (LensStyle.Piece piece : pieces) {
            assertTrue(piece.t0() >= 0.0 && piece.t1() <= 1.0, "inside the link: " + piece);
        }
    }

    @Test
    @DisplayName("a breakpoint at the antenna colours the whole link from the start")
    void piecesBreakpointAtAntenna() {
        List<LensStyle.Piece> pieces = LensStyle.pieces(new float[] {0.0F}, new float[] {6.0F});
        assertEquals(List.of(new LensStyle.Piece(0.0, 1.0, 6.0)), pieces);
    }

    // ---- labels -----------------------------------------------------------------------------

    @Test
    @DisplayName("link label reads PCI, band and RSRP, and marks the serving cell")
    void linkLabel() {
        assertEquals("PCI 12 · band_1800 · -85.3 dBm",
                LensStyle.linkLabel(12, "band_1800", -85.34, false));
        assertEquals("PCI 7 · band_700 · -101.0 dBm [serving]",
                LensStyle.linkLabel(7, "band_700", -101.0, true));
    }

    @Test
    @DisplayName("labels use a full stop for decimals whatever the player's locale")
    void linkLabelLocaleIndependent() {
        Locale previous = Locale.getDefault();
        try {
            Locale.setDefault(Locale.GERMANY);
            assertEquals("PCI 1 · b · -90.5 dBm", LensStyle.linkLabel(1, "b", -90.5, false));
        } finally {
            Locale.setDefault(previous);
        }
    }

    @Test
    @DisplayName("coverage legend tag names PCI and band")
    void cellLabel() {
        assertEquals("PCI 301 · band_3500", LensStyle.cellLabel(301, "band_3500"));
    }

    // ---- coverage colours -------------------------------------------------------------------

    @Test
    @DisplayName("a cell's hue is in [0, 1) and depends only on its id")
    void cellHueStable() {
        long[] ids = {0L, 1L, -1L, 42L, Long.MAX_VALUE, Long.MIN_VALUE, 274_877_906_944L};
        for (long id : ids) {
            double hue = LensStyle.cellHue(id);
            assertTrue(hue >= 0.0 && hue < 1.0, "hue " + hue + " for id " + id);
            assertEquals(hue, LensStyle.cellHue(id), 0.0);
            assertEquals(LensStyle.cellRgb(id), LensStyle.cellRgb(id));
        }
    }

    private static double hueDistance(long a, long b) {
        double apart = Math.abs(LensStyle.cellHue(a) - LensStyle.cellHue(b));
        return Math.min(apart, 1.0 - apart);
    }

    /** Cell ids are packed block positions: y in bits 0-11, z in bits 12-37, x in bits 38-63. */
    private static long packed(int x, int y, int z) {
        return ((long) (x & 0x3FFFFFF) << 38) | ((long) (z & 0x3FFFFFF) << 12) | (y & 0xFFFL);
    }

    @Test
    @DisplayName("stacked masts (one block apart, ids one apart) are always 0.38 of the wheel apart")
    void stackedMastsDiffer() {
        double expected = 2.0 - (1.0 + Math.sqrt(5.0)) / 2.0; // 1 - 1/phi
        int[][] columns = {{0, 0}, {10, -3}, {-1000, 2500}, {29_999_000, -29_999_000}, {-1, -1}};
        for (int[] column : columns) {
            // Steps over y = -1, where the packed 12-bit y field wraps and the ids are not
            // consecutive (true of BlockPos.asLong itself).
            for (int y = -60; y < 320; y += 7) {
                long lower = packed(column[0], y, column[1]);
                long upper = packed(column[0], y + 1, column[1]);
                assertEquals(expected, hueDistance(lower, upper), 1e-9,
                        "column " + column[0] + "," + column[1] + " at y " + y);
            }
        }
    }

    @Test
    @DisplayName("neighbouring columns along z are also well separated")
    void neighbouringColumnsDiffer() {
        for (int z = -50; z < 50; z += 3) {
            double distance = hueDistance(packed(7, 64, z), packed(7, 64, z + 1));
            assertTrue(distance > 0.3, "only " + distance + " apart at z " + z);
        }
    }

    @Test
    @DisplayName("tile opacity follows service level: better service, bolder paint")
    void levelAlpha() {
        assertEquals(110, LensStyle.levelAlpha(ServiceLevel.EXCELLENT));
        assertEquals(90, LensStyle.levelAlpha(ServiceLevel.GOOD));
        assertEquals(70, LensStyle.levelAlpha(ServiceLevel.FAIR));
        assertEquals(50, LensStyle.levelAlpha(ServiceLevel.POOR));
        assertEquals(LensStyle.NO_SERVICE_ALPHA, LensStyle.levelAlpha(ServiceLevel.NONE));
    }

    @Test
    @DisplayName("tile colour: unsurveyed is not drawn, no service is grey, otherwise the cell's colour")
    void tileArgb() {
        int grey = LensStyle.argb(LensStyle.NO_SERVICE_ALPHA, LensStyle.NO_SERVICE_RGB);
        int cell = 0x12AB34;

        assertEquals(LensStyle.NOT_DRAWN, LensStyle.tileArgb(null, true, cell));
        assertEquals(grey, LensStyle.tileArgb(ServiceLevel.NONE, true, cell),
                "a best server nobody can decode is still no service");
        assertEquals(grey, LensStyle.tileArgb(ServiceLevel.POOR, false, cell));
        assertEquals(LensStyle.argb(90, cell), LensStyle.tileArgb(ServiceLevel.GOOD, true, cell));
        for (ServiceLevel level : ServiceLevel.values()) {
            assertTrue(LensStyle.tileArgb(level, true, cell) != LensStyle.NOT_DRAWN, "drawn tiles are never the sentinel");
        }
    }

    @Test
    @DisplayName("HSV conversion hits the primaries and wraps the hue")
    void hsv() {
        assertEquals(0xFF0000, LensStyle.hsvToRgb(0.0, 1.0, 1.0));
        assertEquals(0x00FF00, LensStyle.hsvToRgb(1.0 / 3.0, 1.0, 1.0));
        assertEquals(0x0000FF, LensStyle.hsvToRgb(2.0 / 3.0, 1.0, 1.0));
        assertEquals(0xFFFFFF, LensStyle.hsvToRgb(0.5, 0.0, 1.0));
        assertEquals(0x000000, LensStyle.hsvToRgb(0.5, 1.0, 0.0));
        assertEquals(0xFF0000, LensStyle.hsvToRgb(1.0, 1.0, 1.0));
    }

    // ---- label stacking ---------------------------------------------------------------------

    @Test
    @DisplayName("masts stacked in one column share one label stack, ordered by height")
    void stackColumn() {
        double[] x = {10.5, 10.5, 10.5};
        double[] y = {70.5, 72.5, 71.5};
        double[] z = {-3.5, -3.5, -3.5};
        LensStyle.Anchor[] anchors = LensStyle.stack(x, y, z, 2.5, 0.75);

        for (LensStyle.Anchor anchor : anchors) {
            assertEquals(10.5, anchor.x());
            assertEquals(72.5 + 0.75, anchor.y(), 1e-12, "above the highest antenna");
            assertEquals(-3.5, anchor.z());
        }
        assertEquals(0, anchors[0].stackIndex(), "lowest at the bottom");
        assertEquals(2, anchors[1].stackIndex(), "highest on top");
        assertEquals(1, anchors[2].stackIndex());
    }

    @Test
    @DisplayName("a three-sector site on neighbouring blocks is one stack at its centroid")
    void stackSectorSite() {
        double[] x = {0.5, 1.5, 0.5};
        double[] y = {80.5, 80.5, 80.5};
        double[] z = {0.5, 0.5, 1.5};
        LensStyle.Anchor[] anchors = LensStyle.stack(x, y, z, 2.5, 0.75);

        assertEquals(anchors[0].x(), anchors[1].x());
        assertEquals((0.5 + 1.5 + 0.5) / 3.0, anchors[0].x(), 1e-12);
        assertEquals((0.5 + 0.5 + 1.5) / 3.0, anchors[0].z(), 1e-12);
        assertEquals(0, anchors[0].stackIndex(), "equal heights keep input order, upward");
        assertEquals(1, anchors[1].stackIndex());
        assertEquals(2, anchors[2].stackIndex());
    }

    @Test
    @DisplayName("antennas far apart get their own labels")
    void stackSeparateSites() {
        double[] x = {0.5, 40.5};
        double[] y = {70.5, 90.5};
        double[] z = {0.5, 0.5};
        LensStyle.Anchor[] anchors = LensStyle.stack(x, y, z, 2.5, 0.75);

        assertEquals(new LensStyle.Anchor(0.5, 71.25, 0.5, 0), anchors[0]);
        assertEquals(new LensStyle.Anchor(40.5, 91.25, 0.5, 0), anchors[1]);
    }

    @Test
    @DisplayName("no links, no labels")
    void stackEmpty() {
        assertEquals(0, LensStyle.stack(new double[0], new double[0], new double[0], 2.5, 0.75).length);
    }

    // ---- service level colours and the drive-test trail (Step 3a) ---------------------------

    @Test
    @DisplayName("level colours are the meter HUD's: grey, red, orange, yellow, green")
    void levelColoursMatchTheHud() {
        // These are the exact values SignalHudOverlay drew before Step 3a moved them here.
        assertEquals(0xAAAAAA, LensStyle.levelRgb(ServiceLevel.NONE));
        assertEquals(0xFF5555, LensStyle.levelRgb(ServiceLevel.POOR));
        assertEquals(0xFFAA00, LensStyle.levelRgb(ServiceLevel.FAIR));
        assertEquals(0xFFFF55, LensStyle.levelRgb(ServiceLevel.GOOD));
        assertEquals(0x55FF55, LensStyle.levelRgb(ServiceLevel.EXCELLENT));
        assertEquals(0xAAAAAA, LensStyle.levelRgb(null), "a missing level is drawn as no service");

        int[] hud = LensStyle.levelArgbByBars();
        assertEquals(List.of(0xFFAAAAAA, 0xFFFF5555, 0xFFFFAA00, 0xFFFFFF55, 0xFF55FF55),
                List.of(hud[0], hud[1], hud[2], hud[3], hud[4]));
        hud[0] = 0;
        assertEquals(0xFFAAAAAA, LensStyle.levelArgbByBars()[0], "each call hands out a fresh copy");
    }

    @Test
    @DisplayName("handovers get a white pillar, reselections a yellow one, nothing else a pillar")
    void pillarsOnlyForMobilityEvents() {
        assertEquals(0xFFFFFF, LensStyle.pillarRgb(DriveTestLog.Event.HANDOVER));
        assertEquals(0xFFFF55, LensStyle.pillarRgb(DriveTestLog.Event.RESELECTION));
        assertEquals(LensStyle.NO_PILLAR, LensStyle.pillarRgb(DriveTestLog.Event.NONE));
        assertEquals(LensStyle.NO_PILLAR, LensStyle.pillarRgb(DriveTestLog.Event.OUTAGE));
        assertEquals(LensStyle.NO_PILLAR, LensStyle.pillarRgb(null));
    }

    @Test
    @DisplayName("consecutive samples are joined only across a plausible walking gap")
    void joinsOnlyNearbySamples() {
        DriveTestLog.Sample origin = trailSample(0.0);
        assertTrue(LensStyle.joins(origin, trailSample(5.6), 24.0), "a 1 Hz sprint step joins");
        assertTrue(LensStyle.joins(origin, trailSample(24.0), 24.0), "the limit itself joins");
        assertFalse(LensStyle.joins(origin, trailSample(24.5), 24.0), "a teleport does not");
        assertFalse(LensStyle.joins(null, origin, 24.0), "the first sample has nothing to join");
    }

    private static DriveTestLog.Sample trailSample(double x) {
        return new DriveTestLog.Sample(0L, x, 64.0, 0.0, 1L, 0, "band_900",
                -80.0, 20.0, ServiceLevel.EXCELLENT, 0, 1);
    }

    // ---- microwave hops (Phase 3 slice 12) ------------------------------------------------------

    @Test
    @DisplayName("slice 12: a hop's colour is its server state: green UP, orange DEGRADED, red DOWN, all distinct")
    void backhaulColours() {
        assertEquals(LensStyle.BACKHAUL_UP_RGB, LensStyle.backhaulRgb(MicrowaveLink.LinkState.UP));
        assertEquals(LensStyle.BACKHAUL_DEGRADED_RGB, LensStyle.backhaulRgb(MicrowaveLink.LinkState.DEGRADED));
        assertEquals(LensStyle.BACKHAUL_DOWN_RGB, LensStyle.backhaulRgb(MicrowaveLink.LinkState.DOWN));
        assertTrue(green(LensStyle.BACKHAUL_UP_RGB) > red(LensStyle.BACKHAUL_UP_RGB));
        assertTrue(red(LensStyle.BACKHAUL_DOWN_RGB) > green(LensStyle.BACKHAUL_DOWN_RGB));
        assertEquals(3, java.util.Set.of(LensStyle.BACKHAUL_UP_RGB, LensStyle.BACKHAUL_DEGRADED_RGB,
                LensStyle.BACKHAUL_DOWN_RGB).size());
    }

    @Test
    @DisplayName("slice 12: a hop's label is the server's RSL and margin, dot decimals; out of range says so")
    void backhaulLabel() {
        Locale previous = Locale.getDefault();
        try {
            Locale.setDefault(Locale.GERMANY);
            assertEquals("DEGRADED · -61.2 dBm (-11.2 dB)",
                    LensStyle.backhaulLabel(MicrowaveLink.LinkState.DEGRADED, -61.2f, -11.2f));
            assertEquals("UP · -33.5 dBm (+16.5 dB)", LensStyle.backhaulLabel(MicrowaveLink.LinkState.UP, -33.5f, 16.5f));
            assertEquals("DOWN · out of range",
                    LensStyle.backhaulLabel(MicrowaveLink.LinkState.DOWN, -Float.MAX_VALUE, -Float.MAX_VALUE));
        } finally {
            Locale.setDefault(previous);
        }
    }
}
