package dev.rancraft.device;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Which Locator sends the {@code LocatorFixPayload} (Phase 3 slice 5, §3A.6: "sent only while
 * held"): one per evaluation, from the hand the HUD reads.
 */
class NetworkLocatorTest {

    @Test
    @DisplayName("a Locator in the hotbar never sends: it runs (emergency record) but has no HUD")
    void hotbarNeverSends() {
        assertFalse(NetworkLocator.sendsPayload(false, false, false));
        assertFalse(NetworkLocator.sendsPayload(false, false, true));
    }

    @Test
    @DisplayName("held in the main hand: it sends")
    void mainHandSends() {
        assertTrue(NetworkLocator.sendsPayload(true, true, true));
    }

    @Test
    @DisplayName("held in the offhand: it sends unless the main hand also holds a Locator, which then does")
    void offhandDefersToTheMainHand() {
        assertTrue(NetworkLocator.sendsPayload(true, false, false), "offhand only");
        assertFalse(NetworkLocator.sendsPayload(true, false, true), "one in each hand: one payload, the main hand's");
    }
}
