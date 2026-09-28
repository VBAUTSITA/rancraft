package dev.rancraft.rf;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.rancraft.rf.DeviceRequirement.Verdict;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Phase 3 test 1 (§3A.1): every verdict, the check order, and serving cell over strongest cell. */
class DeviceRequirementTest {

    private static final BandTable BANDS = BandTable.of(
            Band.DEFAULT_900,                                        // tier 1, and the fallback
            new Band("band_1800", 1800.0, 3.6, 1.1, -108.0, 2),
            new Band("band_3500", 3500.0, 4.0, 1.8, -106.0, 3));

    private static final DeviceRequirement GOOD_TIER_3 = new DeviceRequirement(ServiceLevel.GOOD, 3);
    private static final DeviceRequirement GOOD_TIER_2 = new DeviceRequirement(ServiceLevel.GOOD, 2);

    /** A served sample: the given cells, strongest first, served by {@code servingId} at {@code level}. */
    private static SignalSample served(long servingId, ServiceLevel level, CellSample... cells) {
        return new SignalSample(List.of(cells), 1_000L, servingId, 15.0, -100.0, -110.0, level, 0);
    }

    private static CellSample cell(long id, double rsrpDbm, String bandId) {
        return TestCells.sample(id, rsrpDbm, bandId, 0);
    }

    @Test
    @DisplayName("NO_SERVICE fires on every kind of no-service sample, even for requirement NONE")
    void noService() {
        SignalSample empty = SignalSample.empty(1_000L);
        assertEquals(Verdict.NO_SERVICE, DeviceRequirement.NONE.check(empty, BANDS));
        assertEquals(Verdict.NO_SERVICE, GOOD_TIER_3.check(empty, BANDS));

        // Cells were heard, but none is serving (the serving id is not in the list).
        SignalSample orphan = served(99L, ServiceLevel.GOOD, cell(1L, -70.0, "band_3500"));
        assertTrue(orphan.isNoService());
        assertEquals(Verdict.NO_SERVICE, DeviceRequirement.NONE.check(orphan, BANDS));

        // A serving cell exists but the level is NONE (drowned by interference).
        SignalSample drowned = served(1L, ServiceLevel.NONE, cell(1L, -70.0, "band_3500"));
        assertEquals(Verdict.NO_SERVICE, DeviceRequirement.NONE.check(drowned, BANDS));
        assertEquals(Verdict.NO_SERVICE, GOOD_TIER_3.check(drowned, BANDS),
                "NO_SERVICE is checked before quality and tier");
    }

    @Test
    @DisplayName("LOW_QUALITY fires below the minimum level, and is checked before the tier")
    void lowQuality() {
        SignalSample fairOn3500 = served(1L, ServiceLevel.FAIR, cell(1L, -80.0, "band_3500"));
        assertEquals(Verdict.LOW_QUALITY, GOOD_TIER_3.check(fairOn3500, BANDS));

        // Both quality and tier fail: quality is reported, because it is checked first.
        SignalSample poorOn900 = served(1L, ServiceLevel.POOR, cell(1L, -95.0, "band_900"));
        assertEquals(Verdict.LOW_QUALITY, GOOD_TIER_3.check(poorOn900, BANDS));
    }

    @Test
    @DisplayName("LOW_TIER fires when quality is good enough but the serving band's tier is too low")
    void lowTier() {
        SignalSample excellentOn1800 = served(1L, ServiceLevel.EXCELLENT, cell(1L, -60.0, "band_1800"));
        assertEquals(Verdict.LOW_TIER, GOOD_TIER_3.check(excellentOn1800, BANDS));
        assertEquals(Verdict.OK, GOOD_TIER_2.check(excellentOn1800, BANDS));
    }

    @Test
    @DisplayName("OK at exactly the minimum level and tier; NONE accepts any served sample")
    void okAtTheBoundary() {
        SignalSample goodOn3500 = served(1L, ServiceLevel.GOOD, cell(1L, -75.0, "band_3500"));
        assertEquals(Verdict.OK, GOOD_TIER_3.check(goodOn3500, BANDS));

        SignalSample poorOn900 = served(1L, ServiceLevel.POOR, cell(1L, -99.0, "band_900"));
        assertEquals(Verdict.OK, DeviceRequirement.NONE.check(poorOn900, BANDS));
        assertEquals(Verdict.OK, new DeviceRequirement(ServiceLevel.POOR, 1).check(poorOn900, BANDS));
    }

    @Test
    @DisplayName("The tier check reads the serving cell's band, not the strongest cell's")
    void tierReadsServingNotStrongest() {
        // Hysteresis holds the receiver on a band_900 cell although a band_3500 cell is stronger.
        CellSample strongest3500 = cell(1L, -70.0, "band_3500");
        CellSample serving900 = cell(2L, -72.0, "band_900");
        SignalSample heldOn900 = served(2L, ServiceLevel.GOOD, strongest3500, serving900);

        assertEquals(strongest3500, heldOn900.strongest().orElseThrow(), "fixture: strongest is band_3500");
        assertEquals(serving900, heldOn900.serving().orElseThrow(), "fixture: serving is band_900");
        assertEquals(Verdict.LOW_TIER, GOOD_TIER_3.check(heldOn900, BANDS),
                "reading index 0 would have said OK");

        // And the mirror case: strongest on band_900, serving on band_3500.
        CellSample strongest900 = cell(3L, -70.0, "band_900");
        CellSample serving3500 = cell(4L, -72.0, "band_3500");
        SignalSample heldOn3500 = served(4L, ServiceLevel.GOOD, strongest900, serving3500);
        assertEquals(Verdict.OK, GOOD_TIER_3.check(heldOn3500, BANDS),
                "reading index 0 would have said LOW_TIER");
    }

    @Test
    @DisplayName("An unknown band id is judged by the fallback band, as the engine propagated it")
    void unknownBandUsesFallback() {
        SignalSample onRemovedBand = served(1L, ServiceLevel.GOOD, cell(1L, -70.0, "band_removed"));
        assertEquals(1, BANDS.getOrFallback("band_removed").capacityTier(), "fixture: fallback is tier 1");
        assertEquals(Verdict.LOW_TIER, GOOD_TIER_2.check(onRemovedBand, BANDS));
        assertEquals(Verdict.OK, new DeviceRequirement(ServiceLevel.GOOD, 1).check(onRemovedBand, BANDS));
    }

    @Test
    @DisplayName("NONE asks for nothing; a null level is rejected")
    void noneAndValidation() {
        assertEquals(ServiceLevel.NONE, DeviceRequirement.NONE.minServiceLevel());
        assertEquals(0, DeviceRequirement.NONE.minCapacityTier());
        assertThrows(NullPointerException.class, () -> new DeviceRequirement(null, 1));
        assertNotEquals(DeviceRequirement.NONE, GOOD_TIER_3);
    }
}
