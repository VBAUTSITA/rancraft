package dev.rancraft.client;

import dev.rancraft.net.CoverageSurveyPayload;
import dev.rancraft.rf.CoverageSurvey;
import dev.rancraft.rf.ServiceLevel;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

/**
 * Everything {@link CoverageRenderer} draws for one survey, worked out once when the survey
 * arrives rather than every frame: a colour per point and a legend tag per serving cell.
 *
 * <p>This is a pure restyling of the payload. Colours come from {@link LensStyle} applied to the
 * server's {@code cellIndex} and {@code level}; nothing is evaluated, interpolated or inferred.
 * A point the server did not survey stays undrawn.
 *
 * <p>Holds no client render types, so it is unit-tested headless.
 */
final class CoverageTiles {

    /** Legend tags float this far above the ground of the tile they are pinned to, in blocks. */
    static final double LABEL_HEIGHT = 1.5;

    /**
     * A legend tag: which cell owns a painted area, in that cell's colour, pinned to the painted
     * point nearest the area's centroid (the centroid itself can fall outside a curved area).
     *
     * @param points how many points the cell paints; bigger areas are labelled first.
     */
    record RegionLabel(double x, double y, double z, String text, int rgb, int points) {
    }

    private final CoverageSurveyPayload survey;
    private final int[] argb;
    private final List<RegionLabel> labels;

    private CoverageTiles(CoverageSurveyPayload survey, int[] argb, List<RegionLabel> labels) {
        this.survey = survey;
        this.argb = argb;
        this.labels = List.copyOf(labels);
    }

    static CoverageTiles of(CoverageSurveyPayload survey) {
        int size = survey.size();
        int points = survey.pointCount();
        List<CoverageSurveyPayload.PaletteEntry> palette = survey.palette();
        int[] cellIndex = survey.cellIndex();

        int[] paletteRgb = new int[palette.size()];
        for (int p = 0; p < paletteRgb.length; p++) {
            paletteRgb[p] = LensStyle.cellRgb(palette.get(p).cellId());
        }

        int[] argb = new int[points];
        long[] sumI = new long[palette.size()];
        long[] sumJ = new long[palette.size()];
        int[] count = new int[palette.size()];
        for (int j = 0; j < size; j++) {
            for (int i = 0; i < size; i++) {
                int k = survey.index(i, j);
                ServiceLevel level = survey.levelAt(k);
                int cell = cellIndex[k];
                boolean hasServing = cell != CoverageSurvey.NO_CELL;
                argb[k] = LensStyle.tileArgb(level, hasServing, hasServing ? paletteRgb[cell] : 0);
                if (paintsCell(level, cell)) {
                    sumI[cell] += i;
                    sumJ[cell] += j;
                    count[cell]++;
                }
            }
        }

        // Second pass: for each cell, the painted point nearest its centroid. Ties go to the
        // first in index order, so the tag does not jitter between identical surveys.
        int[] best = new int[palette.size()];
        double[] bestDistance = new double[palette.size()];
        Arrays.fill(best, -1);
        for (int j = 0; j < size; j++) {
            for (int i = 0; i < size; i++) {
                int k = survey.index(i, j);
                int cell = cellIndex[k];
                if (!paintsCell(survey.levelAt(k), cell)) {
                    continue;
                }
                double di = i - (double) sumI[cell] / count[cell];
                double dj = j - (double) sumJ[cell] / count[cell];
                double distance = di * di + dj * dj;
                if (best[cell] < 0 || distance < bestDistance[cell]) {
                    best[cell] = k;
                    bestDistance[cell] = distance;
                }
            }
        }

        List<RegionLabel> labels = new ArrayList<>();
        double half = survey.step() / 2.0;
        for (int p = 0; p < palette.size(); p++) {
            if (best[p] < 0) {
                continue;
            }
            int k = best[p];
            int i = k % size;
            int j = k / size;
            CoverageSurveyPayload.PaletteEntry entry = palette.get(p);
            labels.add(new RegionLabel(
                    survey.blockX(i) + half,
                    survey.surfaceY()[k] + LABEL_HEIGHT,
                    survey.blockZ(j) + half,
                    LensStyle.cellLabel(entry.pci(), entry.bandId()),
                    paletteRgb[p],
                    count[p]));
        }
        labels.sort(Comparator.comparingInt(RegionLabel::points).reversed());

        return new CoverageTiles(survey, argb, labels);
    }

    /** True when the point is painted in its serving cell's colour rather than grey or not at all. */
    private static boolean paintsCell(ServiceLevel level, int cell) {
        return level != null && level != ServiceLevel.NONE && cell != CoverageSurvey.NO_CELL;
    }

    /** The survey these tiles were built from. Compared by identity to spot a new one. */
    CoverageSurveyPayload survey() {
        return survey;
    }

    /** 0xAARRGGBB for point {@code k}, or {@link LensStyle#NOT_DRAWN}. */
    int argbAt(int k) {
        return argb[k];
    }

    /** One per cell that paints at least one point, biggest area first. */
    List<RegionLabel> labels() {
        return labels;
    }
}
