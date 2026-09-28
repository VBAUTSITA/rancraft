package dev.rancraft.client;

import dev.rancraft.net.LocatorFixPayload.Kind;
import dev.rancraft.rf.LocatorFix;

/**
 * How the Network Locator's world render and HUD look (Phase 3 slice 5): colours, marker brightness,
 * and where a range ring is drawn.
 *
 * <p><b>Every input is a server value</b> (a measured range, a radiating point, the fix's assumed
 * height, a likely index). This only decides how to draw it; it computes no range, no position and
 * no choice between candidates. No Minecraft types, so it is unit-tested headless.
 */
public final class LocatorStyle {

    private LocatorStyle() {
    }

    // ---- states (HUD) -----------------------------------------------------------------------------

    public static final int FIX_ARGB = 0xFF55FF55;
    public static final int AMBIGUOUS_ARGB = 0xFFFFFF55;
    public static final int PARTIAL_ARGB = 0xFFFFAA00;
    public static final int NO_SIGNAL_ARGB = 0xFFAAAAAA;

    /**
     * The HUD colour of a fix type: green for a position, yellow for two candidates, orange for
     * "something, but no position" (RANGE ONLY, POOR GEOMETRY), grey for nothing.
     */
    public static int stateArgb(Kind kind) {
        return switch (kind) {
            case FIX -> FIX_ARGB;
            case AMBIGUOUS -> AMBIGUOUS_ARGB;
            case RANGE_ONLY, POOR_GEOMETRY -> PARTIAL_ARGB;
            case NO_SIGNAL -> NO_SIGNAL_ARGB;
        };
    }

    // ---- rings --------------------------------------------------------------------------------------

    /** Below this a ring is not drawn: it would be a dot. */
    public static final double MIN_RING_RADIUS = 0.25;

    /**
     * The horizontal radius of a range ring where it is drawn.
     *
     * <p>A measured range is a slant (3D) distance from the cell's radiating point: the receiver is
     * somewhere on a <em>sphere</em> of that radius. The Locator draws that sphere's cross-section at
     * one height, {@code sliceY}: {@code sqrt(r^2 - (cellY - sliceY)^2)}, the same horizontal range the
     * solver uses ({@code LocatorSolver.horizontalRange}). Sliced at the height where the receiver
     * is assumed to be, the rings cross at the estimate; that is the picture of what the solver did.
     * Zero when the sphere does not reach that height.
     *
     * <p>This is geometry of a shape the server sent, not a measurement: nothing about the signal
     * is computed.
     */
    public static double sliceRadius(double slantRadius, double cellY, double sliceY) {
        double dy = Math.abs(cellY - sliceY);
        if (!(slantRadius > dy) || !Double.isFinite(slantRadius) || !Double.isFinite(dy)) {
            return 0.0;
        }
        return Math.sqrt((slantRadius - dy) * (slantRadius + dy));
    }

    /**
     * The height to slice the rings at: the FIX's own assumed eye height (altitude aiding, a server
     * value) when there is a FIX, so the rings cross at the marker; otherwise there is no estimate
     * height, and the viewer's eye height is used, so the rings show where the ranges put you at the
     * height you are at.
     */
    public static double sliceHeight(LocatorFix fix, double viewerEyeY) {
        return fix instanceof LocatorFix.Fix position ? position.y() : viewerEyeY;
    }

    // ---- markers -----------------------------------------------------------------------------------

    public static final int BRIGHT_ALPHA = 255;
    public static final int EVEN_ALPHA = 170;
    public static final int DIM_ALPHA = 80;

    /**
     * Opacity of AMBIGUOUS candidate {@code index} (0 = a, 1 = b): the likely one bright, the other
     * dim. With no preference ({@code likely} = -1: no previous estimate, or a tie) both are drawn
     * alike, because the Locator has no reason to prefer either and must not look as if it did.
     */
    public static int candidateAlpha(int likely, int index) {
        if (likely != 0 && likely != 1) {
            return EVEN_ALPHA;
        }
        return likely == index ? BRIGHT_ALPHA : DIM_ALPHA;
    }
}
