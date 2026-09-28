package dev.rancraft.rf;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Phase 3 tests 2-4 (§3A.4): resolution from bandwidth, quantisation, NLOS bias. */
class RangingTest {

    private static final double C = Ranging.SPEED_OF_LIGHT_M_PER_US;

    /** A cell heard at exactly {@code distance} blocks with the given obstruction. */
    private static CellSample heard(long id, double distance, double obstructionDb, String bandId, double rsrpDbm) {
        return new CellSample(id, 10, 70, -20, rsrpDbm, distance, 100.0, obstructionDb,
                bandId, 0, 15.0, 0.0, 0.0);
    }

    private static CellSample heard(long id, double distance, double obstructionDb, String bandId) {
        return heard(id, distance, obstructionDb, bandId, -80.0);
    }

    // ---- Band: the appended bandwidth ----------------------------------------------------------

    @Test
    @DisplayName("Band keeps its six-argument shape (bandwidth defaults to 10 MHz); DEFAULT_900 is 10 MHz")
    void bandwidthDefaults() {
        Band legacy = new Band("band_x", 1800.0, 3.6, 1.1, -108.0, 2);
        assertEquals(Band.DEFAULT_BANDWIDTH_MHZ, legacy.bandwidthMhz());
        assertEquals(10.0, Band.DEFAULT_BANDWIDTH_MHZ);
        assertEquals(10.0, Band.DEFAULT_900.bandwidthMhz(), "the fallback must match band_900.json");
        assertEquals(new Band("band_x", 1800.0, 3.6, 1.1, -108.0, 2, 10.0), legacy);
    }

    // ---- Test 2: resolution --------------------------------------------------------------------

    @Test
    @DisplayName("Test 2: the shipped bands' resolution matches the spec table (30 / 30 / 15 / 3 m)")
    void resolutionMatchesTheTable() throws IOException {
        String[] bands = {"band_700", "band_900", "band_1800", "band_3500"};
        double[] bandwidthMhz = {10.0, 10.0, 20.0, 100.0};
        double[] tableMeters = {30.0, 30.0, 15.0, 3.0};

        for (int i = 0; i < bands.length; i++) {
            double shipped = shippedBandwidthMhz(bands[i]);
            assertEquals(bandwidthMhz[i], shipped, 0.0, bands[i] + ".json bandwidth_mhz");

            double meters = Ranging.resolutionMeters(shipped);
            assertEquals(C / bandwidthMhz[i], meters, 1e-12, "exactly c / BW");
            // The table prints one decimal: c / 10 MHz is 29.98 m, which it rounds to 30.0.
            assertEquals(tableMeters[i], meters, 0.05, bands[i] + " resolution in metres");
            assertEquals(meters, Ranging.resolutionBlocks(shipped, 1.0), 1e-12, "1 m per block");
            assertEquals(meters / 2.0, Ranging.resolutionBlocks(shipped, 2.0), 1e-12, "2 m per block");
        }
        assertEquals(10.0, Ranging.resolutionMeters(10.0) / Ranging.resolutionMeters(100.0), 1e-12,
                "band_3500's 100 MHz resolves 10x finer than a 10 MHz carrier");
    }

    @Test
    @DisplayName("Resolution rejects a bandwidth or scale that is not a positive number")
    void resolutionRejectsNonsense() {
        assertThrows(IllegalArgumentException.class, () -> Ranging.resolutionMeters(0.0));
        assertThrows(IllegalArgumentException.class, () -> Ranging.resolutionMeters(-10.0));
        assertThrows(IllegalArgumentException.class, () -> Ranging.resolutionMeters(Double.NaN));
        assertThrows(IllegalArgumentException.class, () -> Ranging.resolutionBlocks(10.0, 0.0));
        assertThrows(IllegalArgumentException.class, () -> Ranging.quantise(44.0, 0.0));
    }

    // ---- Test 3: quantisation ------------------------------------------------------------------

    @Test
    @DisplayName("Test 3: with 30-block resolution a true 44 reports 30 and a true 46 reports 60")
    void quantisation() {
        assertEquals(30.0, Ranging.quantise(44.0, 30.0), 0.0);
        assertEquals(60.0, Ranging.quantise(46.0, 30.0), 0.0);
        assertEquals(0.0, Ranging.quantise(14.9, 30.0), 0.0, "under half a step rounds to zero");
        assertEquals(30.0, Ranging.quantise(15.0, 30.0), 0.0, "a half step rounds up, as Math.round does");

        // The same through the measurement, on a band whose resolution is exactly 30 blocks.
        Band thirty = new Band("band_30", 900.0, 3.5, 1.0, -110.0, 1, C / 30.0);
        assertEquals(30.0, Ranging.resolutionBlocks(thirty.bandwidthMhz(), 1.0), 1e-12);
        assertEquals(30.0, Ranging.measure(heard(1L, 44.0, 0.0, "band_30"), thirty, 1.0, 0.25).rangeBlocks(), 1e-9);
        assertEquals(60.0, Ranging.measure(heard(1L, 46.0, 0.0, "band_30"), thirty, 1.0, 0.25).rangeBlocks(), 1e-9);
    }

    @Test
    @DisplayName("Sigma is resolution / sqrt(12), and a resolution tending to zero reports the true range")
    void sigmaAndFineResolution() {
        assertEquals(30.0 / Math.sqrt(12.0), Ranging.sigmaBlocks(30.0), 1e-12);
        assertEquals(8.660, Ranging.sigmaBlocks(30.0), 0.001);

        RangeMeasurement fine = Ranging.measure(heard(1L, 123.456789, 0.0, "fine"),
                new Band("fine", 900.0, 3.5, 1.0, -110.0, 1, 1e9), 1.0, 0.25);
        assertEquals(123.456789, fine.rangeBlocks(), 1e-6);
        assertTrue(fine.sigmaBlocks() < 1e-6);

        // Past 2^52 steps rounding cannot change a double, and must not overflow.
        assertEquals(1e20, Ranging.quantise(1e20, 1e-9), 0.0);
    }

    // ---- Test 4: NLOS bias ---------------------------------------------------------------------

    @Test
    @DisplayName("Test 4: the NLOS bias is zero at zero obstruction and exactly 0.25 x dB otherwise")
    void nlosBias() {
        assertEquals(0.0, Ranging.nlosBiasBlocks(0.0, 0.25), 0.0);
        assertEquals(0.25 * 12.0, Ranging.nlosBiasBlocks(12.0, 0.25), 0.0);
        assertEquals(0.25 * 37.5, Ranging.nlosBiasBlocks(37.5, 0.25), 0.0);
        assertEquals(0.0, Ranging.nlosBiasBlocks(40.0, 0.0), 0.0, "a zero rate turns it off");

        // Through the measurement: the bias lands on top of the quantised range, and nowhere else.
        Band band900 = Band.DEFAULT_900;
        RangeMeasurement clear = Ranging.measure(heard(1L, 100.0, 0.0, "band_900"), band900, 1.0, 0.25);
        RangeMeasurement behindHill = Ranging.measure(heard(1L, 100.0, 36.0, "band_900"), band900, 1.0, 0.25);
        assertEquals(Ranging.quantise(100.0, C / 10.0), clear.rangeBlocks(), 0.0, "no obstruction, no bias");
        assertEquals(clear.rangeBlocks() + 9.0, behindHill.rangeBlocks(), 1e-12, "36 dB x 0.25 = 9 blocks long");
        assertEquals(clear.sigmaBlocks(), behindHill.sigmaBlocks(), 0.0,
                "the bias is systematic: it is not in sigma");
        assertEquals(36.0, behindHill.obstructionDb());
    }

    // ---- The list form: usability, order, identity ---------------------------------------------

    @Test
    @DisplayName("Only cells at or above locatorMinRsrpDbm are measured, in the order given")
    void usableCellsInOrder() {
        BandTable bands = BandTable.of(Band.DEFAULT_900, new Band("band_3500", 3500.0, 4.0, 1.8, -106.0, 3, 100.0));
        List<CellSample> cells = List.of(
                heard(1L, 50.0, 0.0, "band_900", -70.0),
                heard(2L, 80.0, 4.0, "band_3500", -100.0),       // exactly at the threshold: used
                heard(3L, 90.0, 0.0, "band_900", -100.01),       // just under: not used
                heard(4L, 120.0, 0.0, "band_unknown", -95.0));   // unknown band: fallback (band_900)

        List<RangeMeasurement> ranges = Ranging.measure(cells, bands, 1.0, LocatorParams.DEFAULTS);
        assertEquals(List.of(1L, 2L, 4L), ranges.stream().map(RangeMeasurement::cellId).toList());

        RangeMeasurement wide = ranges.get(1);
        assertEquals("band_3500", wide.bandId());
        assertEquals(Ranging.sigmaBlocks(C / 100.0), wide.sigmaBlocks(), 1e-12);
        assertEquals(10.5, wide.x(), 0.0, "radiating centre is the voxel centre");
        assertEquals(70.5, wide.y(), 0.0);
        assertEquals(-19.5, wide.z(), 0.0);
        assertEquals(Ranging.quantise(80.0, C / 100.0) + 1.0, wide.rangeBlocks(), 1e-12);

        assertEquals(Ranging.sigmaBlocks(C / 10.0), ranges.get(2).sigmaBlocks(), 1e-12,
                "an unknown band is ranged as the fallback band, as the engine propagated it");

        assertTrue(Ranging.usable(cells.get(1), -100.0));
        assertFalse(Ranging.usable(cells.get(2), -100.0));
    }

    @Test
    @DisplayName("The RfConfig form reads metersPerBlock and the locator tunables from the config")
    void configForm() {
        RfConfig config = RfConfig.DEFAULTS;
        assertEquals(LocatorParams.DEFAULTS, config.locatorParams());
        BandTable bands = BandTable.of(Band.DEFAULT_900);
        List<CellSample> cells = List.of(heard(1L, 44.0, 8.0, "band_900"), heard(2L, 44.0, 0.0, "band_900", -101.0));
        assertEquals(Ranging.measure(cells, bands, 1.0, LocatorParams.DEFAULTS), Ranging.measure(cells, bands, config));
        assertEquals(1, Ranging.measure(cells, bands, config).size());
    }

    @Test
    @DisplayName("A cell with a non-finite distance or obstruction is left out, never measured as NaN")
    void nonFiniteInputsAreDropped() {
        BandTable bands = BandTable.of(Band.DEFAULT_900);
        List<CellSample> cells = List.of(
                heard(1L, Double.NaN, 0.0, "band_900"),
                heard(2L, 50.0, Double.POSITIVE_INFINITY, "band_900"),
                heard(3L, 50.0, 0.0, "band_900"));
        List<RangeMeasurement> ranges = Ranging.measure(cells, bands, 1.0, LocatorParams.DEFAULTS);
        assertEquals(List.of(3L), ranges.stream().map(RangeMeasurement::cellId).toList());
        assertTrue(Ranging.measure(cells, bands, 0.0, LocatorParams.DEFAULTS).isEmpty(), "no scale, no ranges");
    }

    /** Reads {@code bandwidth_mhz} from the band JSON shipped in the mod's resources. */
    private static double shippedBandwidthMhz(String bandId) throws IOException {
        String path = "/data/rancraft/rf/bands/" + bandId + ".json";
        try (InputStream in = RangingTest.class.getResourceAsStream(path)) {
            assertNotNull(in, path + " is not on the classpath");
            String json = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            Matcher matcher = Pattern.compile("\"bandwidth_mhz\"\\s*:\\s*([0-9.eE+-]+)").matcher(json);
            assertTrue(matcher.find(), path + " has no bandwidth_mhz");
            return Double.parseDouble(matcher.group(1));
        }
    }
}
