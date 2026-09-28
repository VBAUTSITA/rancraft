package dev.rancraft.net;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.rancraft.rf.Band;
import dev.rancraft.rf.BandTable;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The band cycle behind the lens's band key. Pure: a filter string and a band table in, a filter out. */
class LensControlPayloadTest {

    private static Band band(String id, double frequencyMhz) {
        return new Band(id, frequencyMhz, 3.5, 1.0, Band.DEFAULT_NOISE_FLOOR_DBM, 1);
    }

    /** Loaded deliberately out of frequency order, with a frequency tie, as a datapack might. */
    private static BandTable table(Band... bands) {
        Map<String, Band> map = new LinkedHashMap<>();
        for (Band band : bands) {
            map.put(band.id(), band);
        }
        return new BandTable(map, bands[0]);
    }

    private static final BandTable SHIPPED = table(
            band("band_3500", 3500.0), band("band_700", 700.0), band("band_1800", 1800.0), band("band_900", 900.0));

    private static List<String> cycleFrom(String start, BandTable bands, int presses) {
        List<String> seen = new ArrayList<>();
        String current = start;
        for (int i = 0; i < presses; i++) {
            current = LensControlPayload.nextBandFilter(current, bands);
            seen.add(current);
        }
        return seen;
    }

    @Test
    @DisplayName("all bands, then each band by ascending frequency, then back to all")
    void cyclesByFrequency() {
        assertEquals(List.of("band_700", "band_900", "band_1800", "band_3500", ""), cycleFrom("", SHIPPED, 5));
    }

    @Test
    @DisplayName("equal frequencies are ordered by id, so the cycle is stable across reloads")
    void tiesByBandId() {
        BandTable tied = table(band("band_b", 900.0), band("band_a", 900.0));
        assertEquals(List.of("band_a", "band_b", ""), cycleFrom("", tied, 3));
    }

    @Test
    @DisplayName("a filter naming a band that is no longer loaded goes back to all bands")
    void unknownGoesToAll() {
        assertEquals("", LensControlPayload.nextBandFilter("band_removed", SHIPPED));
    }

    @Test
    @DisplayName("a null filter is read as all bands")
    void nullIsAll() {
        assertEquals("band_700", LensControlPayload.nextBandFilter(null, SHIPPED));
    }

    @Test
    @DisplayName("a single band toggles between it and all")
    void singleBand() {
        assertEquals(List.of("band_900", "", "band_900"), cycleFrom("", table(Band.DEFAULT_900), 3));
    }
}
