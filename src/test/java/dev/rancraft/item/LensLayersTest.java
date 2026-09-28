package dev.rancraft.item;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class LensLayersTest {

    @Test
    @DisplayName("next() cycles ALL -> ANTENNAS -> LINKS -> COVERAGE -> ALL")
    void cycleOrder() {
        assertEquals(LensLayers.ANTENNAS, LensLayers.ALL.next());
        assertEquals(LensLayers.LINKS, LensLayers.ANTENNAS.next());
        assertEquals(LensLayers.COVERAGE, LensLayers.LINKS.next());
        assertEquals(LensLayers.ALL, LensLayers.COVERAGE.next());
    }

    @Test
    @DisplayName("each preset carries the flags the contract names")
    void presetFlags() {
        assertFlags(LensLayers.ALL, true, true, true);
        assertFlags(LensLayers.ANTENNAS, true, false, false);
        assertFlags(LensLayers.LINKS, false, true, false);
        assertFlags(LensLayers.COVERAGE, false, false, true);
    }

    @Test
    @DisplayName("of() finds every preset from its own flags")
    void ofRoundTrips() {
        for (LensLayers layers : LensLayers.values()) {
            assertEquals(layers, LensLayers.of(layers.showLobes(), layers.showLinks(), layers.showCoverage()));
        }
    }

    @Test
    @DisplayName("a combination no preset names reads as ALL, so the next press lands on ANTENNAS")
    void unmatchedReadsAsAll() {
        assertEquals(LensLayers.ALL, LensLayers.of(true, true, false));
        assertEquals(LensLayers.ALL, LensLayers.of(false, true, true));
        assertEquals(LensLayers.ALL, LensLayers.of(false, false, false));
        assertEquals(LensLayers.ANTENNAS, LensLayers.of(true, false, true).next());
    }

    @Test
    @DisplayName("translation keys match the lang file's rancraft.lens.layers.* entries")
    void translationKeys() {
        assertEquals("rancraft.lens.layers.all", LensLayers.ALL.translationKey());
        assertEquals("rancraft.lens.layers.antennas", LensLayers.ANTENNAS.translationKey());
        assertEquals("rancraft.lens.layers.links", LensLayers.LINKS.translationKey());
        assertEquals("rancraft.lens.layers.coverage", LensLayers.COVERAGE.translationKey());
    }

    private static void assertFlags(LensLayers layers, boolean lobes, boolean links, boolean coverage) {
        assertEquals(lobes, layers.showLobes(), layers + " lobes");
        assertEquals(links, layers.showLinks(), layers + " links");
        assertEquals(coverage, layers.showCoverage(), layers + " coverage");
        assertTrue(layers.showLobes() || layers.showLinks() || layers.showCoverage(),
                layers + " shows nothing, which no preset should");
    }
}
