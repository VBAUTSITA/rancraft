package dev.rancraft.world;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.rancraft.item.LensLayers;
import dev.rancraft.item.LensSettings;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Who the ticker evaluates, which is also who is sent the sample ({@link SignalTicker#sendsSample}).
 *
 * <p>The drive-test log marks a handover where the server's counter goes up between two samples it
 * received. So every evaluation that can move that counter must reach the client, whichever view
 * asked for it. Otherwise the handovers of an unsent stretch land together, as one false pillar, on
 * the next sample the client does get. A LINKS-only lens used to be evaluated without being sent
 * the sample; these tests pin that down for slice 4's ticker refactor.
 *
 * <p>Headless: only the pure rule is called. Loading the ticker class starts no game.
 */
class SignalTickerTest {

    private static LensSettings lens(LensLayers layers) {
        return LensSettings.DEFAULT.withLayers(layers);
    }

    @Test
    @DisplayName("a held meter is sent the sample with no lens, and with the lens on any preset")
    void meterIsAlwaysSent() {
        assertTrue(SignalTicker.sendsSample(true, null));
        for (LensLayers layers : LensLayers.values()) {
            assertTrue(SignalTicker.sendsSample(true, lens(layers)), layers.name());
        }
    }

    @Test
    @DisplayName("ALL and TRAIL wearers are sent the sample: the trail is drawn from it")
    void trailPresetsAreSent() {
        assertTrue(SignalTicker.sendsSample(false, lens(LensLayers.ALL)));
        assertTrue(SignalTicker.sendsSample(false, lens(LensLayers.TRAIL)));
    }

    @Test
    @DisplayName("a LINKS wearer is evaluated for the rays, so is sent the sample too: its handovers reach the log")
    void linksPresetIsSent() {
        assertTrue(SignalTicker.sendsSample(false, lens(LensLayers.LINKS)),
                "evaluated but not sent: the log would mark this walk's handovers at the next sample it gets");
    }

    @Test
    @DisplayName("ANTENNAS and COVERAGE wearers without a meter are not evaluated, so their handover state cannot move unseen")
    void idlePresetsAreNotEvaluated() {
        assertFalse(SignalTicker.sendsSample(false, lens(LensLayers.ANTENNAS)));
        assertFalse(SignalTicker.sendsSample(false, lens(LensLayers.COVERAGE)));
        assertFalse(SignalTicker.sendsSample(false, null), "no meter and no lens costs nothing");
    }

    @Test
    @DisplayName("the band filter does not matter: links filtered to a band nobody transmits on still move the counter")
    void bandFilterDoesNotGateTheSample() {
        assertTrue(SignalTicker.sendsSample(false, lens(LensLayers.LINKS).withBandFilter("band_3500")));
        assertTrue(SignalTicker.sendsSample(false, lens(LensLayers.TRAIL).withBandFilter("band_700")));
        assertFalse(SignalTicker.sendsSample(false, lens(LensLayers.ANTENNAS).withBandFilter("band_900")));
    }

    @Test
    @DisplayName("a hand-edited lens with links but no trail is sent the sample; lobes and coverage alone are not evaluated")
    void handEditedCombinations() {
        // Neither combination is a preset (both read as ALL through LensLayers.of): the rule is on
        // the flags, not on the preset name.
        LensSettings lobesAndLinks = new LensSettings(LensSettings.ALL_BANDS, true, true, false, false);
        LensSettings lobesAndCoverage = new LensSettings(LensSettings.ALL_BANDS, true, false, true, false);
        assertTrue(SignalTicker.sendsSample(false, lobesAndLinks));
        assertFalse(SignalTicker.sendsSample(false, lobesAndCoverage));
    }
}
