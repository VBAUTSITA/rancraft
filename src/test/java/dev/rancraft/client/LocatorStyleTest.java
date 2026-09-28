package dev.rancraft.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.rancraft.net.LocatorFixPayload.Kind;
import dev.rancraft.rf.LocatorFix;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * How the Network Locator draws (Phase 3 slice 5): every input is a server value, and these rules
 * only decide the look. No position, range or candidate is chosen here.
 */
class LocatorStyleTest {

    @Test
    @DisplayName("each fix type has its HUD colour, and the five are told apart except the two partial states")
    void stateColours() {
        assertEquals(LocatorStyle.FIX_ARGB, LocatorStyle.stateArgb(Kind.FIX));
        assertEquals(LocatorStyle.AMBIGUOUS_ARGB, LocatorStyle.stateArgb(Kind.AMBIGUOUS));
        assertEquals(LocatorStyle.PARTIAL_ARGB, LocatorStyle.stateArgb(Kind.RANGE_ONLY));
        assertEquals(LocatorStyle.PARTIAL_ARGB, LocatorStyle.stateArgb(Kind.POOR_GEOMETRY));
        assertEquals(LocatorStyle.NO_SIGNAL_ARGB, LocatorStyle.stateArgb(Kind.NO_SIGNAL));
        Set<Integer> distinct = new HashSet<>();
        for (Kind kind : Kind.values()) {
            distinct.add(LocatorStyle.stateArgb(kind));
            assertEquals(0xFF, LocatorStyle.stateArgb(kind) >>> 24, kind + " is opaque");
        }
        assertEquals(4, distinct.size());
    }

    @Test
    @DisplayName("a ring is the range sphere's cross-section at the slice height: sqrt(r^2 - dy^2)")
    void sliceRadius() {
        // A 50-block slant range from a cell 30 blocks above the slice: 40 blocks horizontally.
        assertEquals(40.0, LocatorStyle.sliceRadius(50.0, 100.0, 70.0), 1e-12);
        assertEquals(40.0, LocatorStyle.sliceRadius(50.0, 40.0, 70.0), 1e-12, "below the slice alike");
        assertEquals(50.0, LocatorStyle.sliceRadius(50.0, 70.0, 70.0), 1e-12, "at the cell's height: the full range");
        assertEquals(0.0, LocatorStyle.sliceRadius(20.0, 100.0, 70.0), "the sphere does not reach the slice");
        assertEquals(0.0, LocatorStyle.sliceRadius(30.0, 100.0, 70.0), "it just touches it");
        assertEquals(0.0, LocatorStyle.sliceRadius(Double.NaN, 100.0, 70.0));
        assertEquals(0.0, LocatorStyle.sliceRadius(Double.POSITIVE_INFINITY, 100.0, 70.0));
        assertEquals(0.0, LocatorStyle.sliceRadius(50.0, Double.NaN, 70.0));
    }

    @Test
    @DisplayName("sliced at the FIX's own assumed eye height, so the rings cross at the marker; else at the viewer's")
    void sliceHeight() {
        LocatorFix fix = new LocatorFix.Fix(10.0, 81.62, -4.0, 1.3, 6.0, 4);
        assertEquals(81.62, LocatorStyle.sliceHeight(fix, 200.0));
        assertEquals(64.5, LocatorStyle.sliceHeight(new LocatorFix.PoorGeometry(9.0, 3), 64.5));
        assertEquals(64.5, LocatorStyle.sliceHeight(new LocatorFix.RangeOnly(0, 0, 40), 64.5));
        assertEquals(64.5, LocatorStyle.sliceHeight(new LocatorFix.Ambiguous(0, 0, 1, 1, 0), 64.5));
        assertEquals(64.5, LocatorStyle.sliceHeight(new LocatorFix.NoSignal(), 64.5));
    }

    @Test
    @DisplayName("AMBIGUOUS: the likely candidate bright, the other dim; no preference draws both alike")
    void candidateAlpha() {
        assertEquals(LocatorStyle.BRIGHT_ALPHA, LocatorStyle.candidateAlpha(0, 0));
        assertEquals(LocatorStyle.DIM_ALPHA, LocatorStyle.candidateAlpha(0, 1));
        assertEquals(LocatorStyle.DIM_ALPHA, LocatorStyle.candidateAlpha(1, 0));
        assertEquals(LocatorStyle.BRIGHT_ALPHA, LocatorStyle.candidateAlpha(1, 1));
        int none = LocatorFix.Ambiguous.NO_PREFERENCE;
        assertEquals(LocatorStyle.candidateAlpha(none, 0), LocatorStyle.candidateAlpha(none, 1));
        assertTrue(LocatorStyle.candidateAlpha(none, 0) < LocatorStyle.BRIGHT_ALPHA,
                "neither looks like the chosen one");
        assertTrue(LocatorStyle.candidateAlpha(none, 0) > LocatorStyle.DIM_ALPHA,
                "neither looks like the rejected one");
    }

    @Test
    @DisplayName("circles get about one segment per 2 blocks, clamped to 24..360, and none below a quarter block")
    void segments() {
        assertEquals(0, LocatorStyle.segments(0.0));
        assertEquals(0, LocatorStyle.segments(0.2));
        assertEquals(0, LocatorStyle.segments(Double.NaN));
        assertEquals(0, LocatorStyle.segments(Double.POSITIVE_INFINITY));
        assertEquals(0, LocatorStyle.segments(-5.0));
        assertEquals(LocatorStyle.MIN_SEGMENTS, LocatorStyle.segments(0.25));
        assertEquals(LocatorStyle.MIN_SEGMENTS, LocatorStyle.segments(3.0));
        assertEquals(315, LocatorStyle.segments(100.0), "ceil(2 pi 100 / 2)");
        assertEquals(LocatorStyle.MAX_SEGMENTS, LocatorStyle.segments(500.0));
        assertEquals(LocatorStyle.MAX_SEGMENTS, LocatorStyle.segments(1.0e9));
        // At the cap, a 500-block ring's chords stay within 0.2 blocks of the circle (the javadoc's claim).
        double sagitta = 500.0 * (1.0 - Math.cos(Math.PI / LocatorStyle.MAX_SEGMENTS));
        assertTrue(sagitta < 0.2, "sagitta " + sagitta);
    }
}
