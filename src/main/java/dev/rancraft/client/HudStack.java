package dev.rancraft.client;

/**
 * Who owns the top-left corner of the screen this frame. Phase 3 slice 5.
 *
 * <p>Two HUDs want the top-left: the Field Test Meter's <em>detailed</em> readout (its compact one is
 * top-right) and the Network Locator's. <b>The rule: the meter keeps the corner, and the Locator
 * stacks directly under whatever the meter drew</b>, a small gap below it. With only one of them held,
 * or the meter in compact mode, the Locator starts at the corner. Nothing overlaps, and the meter,
 * the regression reference of the Phase 3 refactor, draws exactly as before.
 *
 * <p>How: the meter's layer is registered first and the Locator's directly above it (both in
 * {@link SignalHudOverlay#registerLayers}, the only order {@code RegisterGuiLayersEvent} guarantees),
 * so each frame the meter renders first. When it draws its detailed readout it {@link #claimTopLeft
 * claims} the rows down to its last line; the Locator then {@link #takeTopLeft takes} the next free
 * row, which also resets the claim for the next frame. So a claim never outlives the frame it was
 * made in, even when the Locator is not drawn.
 *
 * <p>Plain integers and no Minecraft types, so the rule is unit-tested headless. Render thread only.
 */
public final class HudStack {

    private HudStack() {
    }

    /** The screen margin both HUDs use, in GUI pixels. */
    public static final int MARGIN = 6;

    /** Space left between the meter's last line and the Locator's title. */
    public static final int GAP = 4;

    private static int claimedBottom = -1;

    /**
     * The meter drew top-left down to {@code bottomExclusive} (the y just past its last line) this
     * frame. A second claim in one frame keeps the lower of the two.
     */
    public static void claimTopLeft(int bottomExclusive) {
        claimedBottom = Math.max(claimedBottom, bottomExclusive);
    }

    /**
     * The first free row of the top-left corner this frame, for the Locator, and the claim is
     * cleared for the next frame. {@link #MARGIN} when nothing was claimed.
     */
    public static int takeTopLeft() {
        int top = claimedBottom < 0 ? MARGIN : Math.max(MARGIN, claimedBottom + GAP);
        claimedBottom = -1;
        return top;
    }
}
