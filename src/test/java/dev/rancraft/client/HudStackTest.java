package dev.rancraft.client;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The top-left rule (Phase 3 slice 5): the meter's detailed readout keeps the corner, and the
 * Network Locator stacks under whatever it drew this frame. A claim never outlives its frame. Phase 3
 * slice 14: the same rule top-right, for the meter's compact readout and the Proximity Scanner, kept
 * apart from the top-left claim.
 */
class HudStackTest {

    @BeforeEach
    void freshFrame() {
        HudStack.takeTopLeft();
        HudStack.takeTopRight();
    }

    @Test
    @DisplayName("nothing claimed (meter compact, or not held): the Locator starts at the corner")
    void unclaimed() {
        assertEquals(HudStack.MARGIN, HudStack.takeTopLeft());
    }

    @Test
    @DisplayName("the meter's detailed readout claimed rows: the Locator starts a gap below its last line")
    void stacksUnderTheMeter() {
        HudStack.claimTopLeft(96);
        assertEquals(96 + HudStack.GAP, HudStack.takeTopLeft());
    }

    @Test
    @DisplayName("a claim lasts one frame: the next frame without one is back at the corner")
    void claimLastsOneFrame() {
        HudStack.claimTopLeft(96);
        HudStack.takeTopLeft();
        assertEquals(HudStack.MARGIN, HudStack.takeTopLeft());
    }

    @Test
    @DisplayName("two claims in one frame keep the lower; a claim above the margin still leaves the margin")
    void lowestClaimWins() {
        HudStack.claimTopLeft(40);
        HudStack.claimTopLeft(96);
        HudStack.claimTopLeft(60);
        assertEquals(96 + HudStack.GAP, HudStack.takeTopLeft());

        HudStack.claimTopLeft(0);
        assertEquals(HudStack.MARGIN, HudStack.takeTopLeft());
    }

    @Test
    @DisplayName("top-right (slice 14): the scanner stacks under the meter's compact readout, for one frame")
    void topRight() {
        assertEquals(HudStack.MARGIN, HudStack.takeTopRight(), "the meter not held, or detailed: the corner");
        HudStack.claimTopRight(26);
        assertEquals(26 + HudStack.GAP, HudStack.takeTopRight(), "two compact lines: under them");
        assertEquals(HudStack.MARGIN, HudStack.takeTopRight(), "the claim lasted one frame");
    }

    @Test
    @DisplayName("the two corners are separate claims")
    void cornersAreIndependent() {
        HudStack.claimTopLeft(96);
        HudStack.claimTopRight(16);
        assertEquals(16 + HudStack.GAP, HudStack.takeTopRight());
        assertEquals(96 + HudStack.GAP, HudStack.takeTopLeft());
    }
}
