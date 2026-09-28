package dev.rancraft.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.rancraft.net.CoverageSurveyPayload;
import dev.rancraft.rf.CoverageSurvey;
import dev.rancraft.rf.ServiceLevel;
import dev.rancraft.rf.SurfaceProbe;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Coverage painting is a restyling of the server's survey: every colour traces back to a payload value. */
class CoverageTilesTest {

    private static final int U = CoverageSurvey.UNSURVEYED;
    private static final int X = CoverageSurvey.NO_CELL;
    private static final int NONE = ServiceLevel.NONE.ordinal();
    private static final int POOR = ServiceLevel.POOR.ordinal();
    private static final int FAIR = ServiceLevel.FAIR.ordinal();
    private static final int GOOD = ServiceLevel.GOOD.ordinal();
    private static final int EXCELLENT = ServiceLevel.EXCELLENT.ordinal();

    private static final CoverageSurveyPayload.PaletteEntry CELL_A =
            new CoverageSurveyPayload.PaletteEntry(11L, 1, "band_1800", 0, 70, 0);
    private static final CoverageSurveyPayload.PaletteEntry CELL_B =
            new CoverageSurveyPayload.PaletteEntry(22L, 2, "band_1800", 50, 70, 50);

    private static byte[] bytes(int... values) {
        byte[] out = new byte[values.length];
        for (int k = 0; k < values.length; k++) {
            out[k] = (byte) values[k];
        }
        return out;
    }

    /**
     * A 4 x 4 grid, step 2, origin (100, -20). Rows are j, columns i.
     * <pre>
     *   A/EXC   A/GOOD  B/GOOD  --
     *   A/GOOD  A/FAIR  B/POOR  B/NONE
     *   A/POOR  -/NONE  B/FAIR  B/GOOD
     *   --      --      --      --
     * </pre>
     */
    private static CoverageSurveyPayload fourByFour() {
        int[] cellIndex = {
                0, 0, 1, X,
                0, 0, 1, 1,
                0, X, 1, 1,
                X, X, X, X};
        byte[] level = bytes(
                EXCELLENT, GOOD, GOOD, U,
                GOOD, FAIR, POOR, NONE,
                POOR, NONE, FAIR, GOOD,
                U, U, U, U);
        int[] surfaceY = new int[16];
        for (int k = 0; k < 16; k++) {
            surfaceY[k] = level[k] == U ? SurfaceProbe.UNLOADED : 64 + k;
        }
        return new CoverageSurveyPayload(100, -20, 2, 4, "", List.of(CELL_A, CELL_B), surfaceY, cellIndex, level);
    }

    @Test
    @DisplayName("each tile's colour is the server's cell at the server's level")
    void tileColours() {
        CoverageSurveyPayload survey = fourByFour();
        CoverageTiles tiles = CoverageTiles.of(survey);
        int a = LensStyle.cellRgb(CELL_A.cellId());
        int b = LensStyle.cellRgb(CELL_B.cellId());

        assertEquals(LensStyle.argb(110, a), tiles.argbAt(survey.index(0, 0)));
        assertEquals(LensStyle.argb(90, a), tiles.argbAt(survey.index(1, 0)));
        assertEquals(LensStyle.argb(90, b), tiles.argbAt(survey.index(2, 0)));
        assertEquals(LensStyle.argb(70, a), tiles.argbAt(survey.index(1, 1)));
        assertEquals(LensStyle.argb(50, b), tiles.argbAt(survey.index(2, 1)));
        assertEquals(LensStyle.argb(50, a), tiles.argbAt(survey.index(0, 2)));
    }

    @Test
    @DisplayName("no service is grey, whether or not a cell nominally wins the point")
    void noServiceIsGrey() {
        CoverageSurveyPayload survey = fourByFour();
        CoverageTiles tiles = CoverageTiles.of(survey);
        int grey = LensStyle.argb(LensStyle.NO_SERVICE_ALPHA, LensStyle.NO_SERVICE_RGB);

        assertEquals(grey, tiles.argbAt(survey.index(3, 1)), "cell B wins but SINR is below 0 dB");
        assertEquals(grey, tiles.argbAt(survey.index(1, 2)), "no cell at all");
    }

    @Test
    @DisplayName("unsurveyed points are left as holes")
    void unsurveyedNotDrawn() {
        CoverageSurveyPayload survey = fourByFour();
        CoverageTiles tiles = CoverageTiles.of(survey);
        assertEquals(LensStyle.NOT_DRAWN, tiles.argbAt(survey.index(3, 0)));
        for (int i = 0; i < 4; i++) {
            assertEquals(LensStyle.NOT_DRAWN, tiles.argbAt(survey.index(i, 3)));
        }
    }

    @Test
    @DisplayName("one legend tag per serving cell, pinned to its painted point nearest the centroid")
    void legendPlacement() {
        CoverageSurveyPayload survey = fourByFour();
        List<CoverageTiles.RegionLabel> labels = CoverageTiles.of(survey).labels();
        assertEquals(2, labels.size());

        // Cell A paints (0,0) (1,0) (0,1) (1,1) (0,2): centroid (0.4, 0.8), nearest point (0,1).
        CoverageTiles.RegionLabel a = labels.get(0);
        assertEquals("PCI 1 · band_1800", a.text());
        assertEquals(5, a.points());
        assertEquals(LensStyle.cellRgb(CELL_A.cellId()), a.rgb());
        assertEquals(survey.blockX(0) + 1.0, a.x(), "tile centre, a step of 2 wide");
        assertEquals(survey.blockZ(1) + 1.0, a.z());
        assertEquals(survey.surfaceY()[survey.index(0, 1)] + CoverageTiles.LABEL_HEIGHT, a.y());

        // Cell B paints (2,0) (2,1) (2,2) (3,2) -- its NONE point is grey, not B's.
        // Centroid (2.25, 1.25), nearest point (2,1).
        CoverageTiles.RegionLabel b = labels.get(1);
        assertEquals("PCI 2 · band_1800", b.text());
        assertEquals(4, b.points());
        assertEquals(survey.blockX(2) + 1.0, b.x());
        assertEquals(survey.blockZ(1) + 1.0, b.z());
    }

    @Test
    @DisplayName("legend tags come biggest area first, so a cap keeps the ones that matter")
    void legendOrder() {
        List<CoverageTiles.RegionLabel> labels = CoverageTiles.of(fourByFour()).labels();
        for (int k = 1; k < labels.size(); k++) {
            assertTrue(labels.get(k - 1).points() >= labels.get(k).points());
        }
    }

    @Test
    @DisplayName("a cell that only 'wins' where there is no service gets no legend tag")
    void noTagForServiceless() {
        CoverageSurveyPayload.PaletteEntry dead = new CoverageSurveyPayload.PaletteEntry(33L, 3, "band_700", 9, 9, 9);
        CoverageSurveyPayload survey = new CoverageSurveyPayload(0, 0, 1, 2, "",
                List.of(dead, CELL_A),
                new int[] {64, 64, SurfaceProbe.UNLOADED, SurfaceProbe.UNLOADED},
                new int[] {0, 1, X, X},
                bytes(NONE, GOOD, U, U));
        List<CoverageTiles.RegionLabel> labels = CoverageTiles.of(survey).labels();
        assertEquals(1, labels.size());
        assertEquals("PCI 1 · band_1800", labels.get(0).text());
    }

    @Test
    @DisplayName("an entirely unsurveyed grid draws nothing and names nothing")
    void allUnsurveyed() {
        int[] surfaceY = new int[9];
        Arrays.fill(surfaceY, SurfaceProbe.UNLOADED);
        int[] cellIndex = new int[9];
        Arrays.fill(cellIndex, X);
        byte[] level = new byte[9];
        Arrays.fill(level, (byte) U);
        CoverageSurveyPayload survey = new CoverageSurveyPayload(0, 0, 4, 3, "band_900", List.of(), surfaceY, cellIndex, level);

        CoverageTiles tiles = CoverageTiles.of(survey);
        for (int k = 0; k < 9; k++) {
            assertEquals(LensStyle.NOT_DRAWN, tiles.argbAt(k));
        }
        assertTrue(tiles.labels().isEmpty());
    }

    @Test
    @DisplayName("the same survey always styles the same way")
    void deterministic() {
        CoverageTiles first = CoverageTiles.of(fourByFour());
        CoverageTiles second = CoverageTiles.of(fourByFour());
        for (int k = 0; k < 16; k++) {
            assertEquals(first.argbAt(k), second.argbAt(k));
        }
        assertEquals(first.labels(), second.labels());
    }
}
