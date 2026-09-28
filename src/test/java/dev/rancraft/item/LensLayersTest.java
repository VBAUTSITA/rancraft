package dev.rancraft.item;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class LensLayersTest {

    @Test
    @DisplayName("next() cycles ALL -> ANTENNAS -> LINKS -> COVERAGE -> TRAIL -> ALL")
    void cycleOrder() {
        assertEquals(LensLayers.ANTENNAS, LensLayers.ALL.next());
        assertEquals(LensLayers.LINKS, LensLayers.ANTENNAS.next());
        assertEquals(LensLayers.COVERAGE, LensLayers.LINKS.next());
        assertEquals(LensLayers.TRAIL, LensLayers.COVERAGE.next());
        assertEquals(LensLayers.ALL, LensLayers.TRAIL.next());
    }

    @Test
    @DisplayName("each preset carries the flags the contract names; ALL includes the trail")
    void presetFlags() {
        assertFlags(LensLayers.ALL, true, true, true, true);
        assertFlags(LensLayers.ANTENNAS, true, false, false, false);
        assertFlags(LensLayers.LINKS, false, true, false, false);
        assertFlags(LensLayers.COVERAGE, false, false, true, false);
        assertFlags(LensLayers.TRAIL, false, false, false, true);
    }

    @Test
    @DisplayName("of() finds every preset from its own flags")
    void ofRoundTrips() {
        for (LensLayers layers : LensLayers.values()) {
            assertEquals(layers, LensLayers.of(
                    layers.showLobes(), layers.showLinks(), layers.showCoverage(), layers.showTrail()));
        }
    }

    @Test
    @DisplayName("a combination no preset names reads as ALL, so the next press lands on ANTENNAS")
    void unmatchedReadsAsAll() {
        assertEquals(LensLayers.ALL, LensLayers.of(true, true, false, false));
        assertEquals(LensLayers.ALL, LensLayers.of(false, true, true, false));
        assertEquals(LensLayers.ALL, LensLayers.of(false, false, false, false));
        assertEquals(LensLayers.ALL, LensLayers.of(true, true, true, false),
                "the pre-3a ALL flags without the trail are no longer a preset");
        assertEquals(LensLayers.ANTENNAS, LensLayers.of(true, false, true, false).next());
    }

    @Test
    @DisplayName("translation keys match the lang file's rancraft.lens.layers.* entries")
    void translationKeys() {
        assertEquals("rancraft.lens.layers.all", LensLayers.ALL.translationKey());
        assertEquals("rancraft.lens.layers.antennas", LensLayers.ANTENNAS.translationKey());
        assertEquals("rancraft.lens.layers.links", LensLayers.LINKS.translationKey());
        assertEquals("rancraft.lens.layers.coverage", LensLayers.COVERAGE.translationKey());
        assertEquals("rancraft.lens.layers.trail", LensLayers.TRAIL.translationKey());
    }

    private static void assertFlags(LensLayers layers, boolean lobes, boolean links, boolean coverage, boolean trail) {
        assertEquals(lobes, layers.showLobes(), layers + " lobes");
        assertEquals(links, layers.showLinks(), layers + " links");
        assertEquals(coverage, layers.showCoverage(), layers + " coverage");
        assertEquals(trail, layers.showTrail(), layers + " trail");
        assertTrue(layers.showLobes() || layers.showLinks() || layers.showCoverage() || layers.showTrail(),
                layers + " shows nothing, which no preset should");
    }
}
