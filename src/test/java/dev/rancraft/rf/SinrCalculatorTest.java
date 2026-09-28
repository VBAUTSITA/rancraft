package dev.rancraft.rf;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.rancraft.rf.SinrCalculator.RxSignal;
import dev.rancraft.rf.SinrCalculator.SinrParams;
import dev.rancraft.rf.SinrCalculator.SinrResult;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Phase 2 tests 7-10: SINR. */
class SinrCalculatorTest {

    private static final double NOISE_DBM = -110.0;
    private static final SinrParams DEFAULTS = SinrParams.DEFAULTS;

    /** PCI 147 % 3 == 0. */
    private static final int SERVING_PCI = 147;
    /** 150 % 3 == 0 -- collides on mod 3. */
    private static final int MOD3_MATCH_PCI = 150;
    /** 148 % 3 == 1 -- does not. */
    private static final int MOD3_CLEAN_PCI = 148;

    private static RxSignal cell(long id, String band, int pci, double rsrp) {
        return new RxSignal(id, band, pci, rsrp);
    }

    // ---- Test 7 ----------------------------------------------------------------------------

    @Test
    @DisplayName("7. A lone cell is noise-limited: SINR equals RSRP minus the noise floor")
    void lonelyCellIsNoiseLimited() {
        RxSignal serving = cell(1L, "band_900", SERVING_PCI, -80.0);
        SinrResult result = SinrCalculator.compute(serving, List.of(serving), NOISE_DBM, DEFAULTS);

        assertEquals(-80.0 - NOISE_DBM, result.sinrDb(), 0.1);
        assertEquals(30.0, result.sinrDb(), 0.1);
        assertEquals(0, result.coChannelCount());
        assertEquals(Double.NEGATIVE_INFINITY, result.interferenceDbm(),
                "no interferers means no interference power, not zero dBm");
    }

    // ---- Test 8 ----------------------------------------------------------------------------

    @Test
    @DisplayName("8. Two identical co-channel cells sit just below 0 dB SINR")
    void equalCoChannelPairIsJustBelowZero() {
        RxSignal serving = cell(1L, "band_900", SERVING_PCI, -80.0);
        RxSignal twin = cell(2L, "band_900", MOD3_CLEAN_PCI, -80.0);

        SinrResult result = SinrCalculator.compute(serving, List.of(serving, twin), NOISE_DBM, DEFAULTS);

        assertTrue(result.sinrDb() < 0.0,
                "interferer plus noise must exceed the wanted signal: " + result.sinrDb());
        assertTrue(result.sinrDb() > -0.5,
                "an equal-power twin costs ~3 dB of carrier-to-interference, not more: " + result.sinrDb());
        assertEquals(ServiceLevel.NONE, result.serviceLevel(-80.0),
                "strong but unusable -- the headline Phase 2 diagnosis");
    }

    // ---- Test 9 ----------------------------------------------------------------------------

    @Test
    @DisplayName("9. A co-channel neighbour hurts; the same power on another band hurts ~ACIR less")
    void coChannelHurtsFarMoreThanAdjacentChannel() {
        RxSignal serving = cell(1L, "band_900", SERVING_PCI, -70.0);
        RxSignal coChannel = cell(2L, "band_900", MOD3_CLEAN_PCI, -70.0);
        RxSignal otherBand = cell(3L, "band_1800", MOD3_CLEAN_PCI, -70.0);

        double alone = SinrCalculator.compute(serving, List.of(serving), NOISE_DBM, DEFAULTS).sinrDb();
        double withCo = SinrCalculator.compute(serving, List.of(serving, coChannel), NOISE_DBM, DEFAULTS).sinrDb();
        double withOther = SinrCalculator.compute(serving, List.of(serving, otherBand), NOISE_DBM, DEFAULTS).sinrDb();

        assertTrue(withCo < alone, "a co-channel neighbour must strictly lower SINR");
        assertTrue(withOther < alone, "an adjacent-channel neighbour must also lower it");
        assertTrue(withOther > withCo, "but far less");

        // With interference dominating the noise, the gap between the two collapses to the
        // adjacent-channel rejection figure itself.
        assertEquals(DEFAULTS.adjacentChannelRejectionDb(), withOther - withCo, 1.0);
    }

    @Test
    @DisplayName("9b. Interferers below the reporting floor are ignored entirely")
    void negligibleInterferersAreDropped() {
        RxSignal serving = cell(1L, "band_900", SERVING_PCI, -70.0);
        RxSignal negligible = cell(2L, "band_900", MOD3_CLEAN_PCI, SinrCalculator.INTERFERER_FLOOR_DBM - 1.0);

        SinrResult result = SinrCalculator.compute(serving, List.of(serving, negligible), NOISE_DBM, DEFAULTS);
        assertEquals(0, result.coChannelCount());
    }

    // ---- Test 10 ---------------------------------------------------------------------------

    @Test
    @DisplayName("10. A mod-3 collision adds exactly 3 dB of effective interference (factor 2.0)")
    void mod3CollisionCostsThreeDb() {
        RxSignal serving = cell(1L, "band_900", SERVING_PCI, -80.0);
        RxSignal colliding = cell(2L, "band_900", MOD3_MATCH_PCI, -85.0);
        RxSignal clean = cell(2L, "band_900", MOD3_CLEAN_PCI, -85.0);

        SinrResult withCollision =
                SinrCalculator.compute(serving, List.of(serving, colliding), NOISE_DBM, DEFAULTS);
        SinrResult withoutCollision =
                SinrCalculator.compute(serving, List.of(serving, clean), NOISE_DBM, DEFAULTS);

        double extraDb = withCollision.interferenceDbm() - withoutCollision.interferenceDbm();
        // A factor of 2.0 in linear power is 10*log10(2) = 3.0103 dB, not a round 3.0.
        assertEquals(10.0 * Math.log10(DEFAULTS.pciMod3PenaltyFactor()), extraDb, 1e-9);
        assertEquals(3.01, extraDb, 0.02);

        assertEquals(1, withCollision.mod3Count());
        assertEquals(0, withoutCollision.mod3Count());
        assertTrue(withCollision.sinrDb() < withoutCollision.sinrDb());
    }

    @Test
    @DisplayName("10b. The mod-3 penalty can be switched off without touching anything else")
    void mod3PenaltyIsOptional() {
        RxSignal serving = cell(1L, "band_900", SERVING_PCI, -80.0);
        RxSignal colliding = cell(2L, "band_900", MOD3_MATCH_PCI, -85.0);
        SinrParams off = new SinrParams(30.0, 2.0, false);

        SinrResult on = SinrCalculator.compute(serving, List.of(serving, colliding), NOISE_DBM, DEFAULTS);
        SinrResult disabled = SinrCalculator.compute(serving, List.of(serving, colliding), NOISE_DBM, off);

        assertTrue(disabled.sinrDb() > on.sinrDb());
        assertEquals(0, disabled.mod3Count());
        assertEquals(1, disabled.coChannelCount(), "still an interferer, just not a penalised one");
    }

    // ---- ServiceLevel ----------------------------------------------------------------------

    @Test
    @DisplayName("Service level is the worse of the RSRP class and the SINR class")
    void serviceLevelTakesTheWorse() {
        assertEquals(ServiceLevel.EXCELLENT, ServiceLevel.of(-70.0, 25.0));
        assertEquals(ServiceLevel.POOR, ServiceLevel.of(-70.0, 2.0), "strong but noisy");
        assertEquals(ServiceLevel.POOR, ServiceLevel.of(-100.0, 30.0), "clean but weak");
        assertEquals(ServiceLevel.NONE, ServiceLevel.of(-106.0, 30.0));
        assertEquals(ServiceLevel.NONE, ServiceLevel.of(-70.0, -1.0));
    }

    @Test
    @DisplayName("Service level boundaries land where the table says")
    void serviceLevelBoundaries() {
        assertEquals(ServiceLevel.EXCELLENT, ServiceLevel.fromSinr(20.0));
        assertEquals(ServiceLevel.GOOD, ServiceLevel.fromSinr(19.9));
        assertEquals(ServiceLevel.GOOD, ServiceLevel.fromSinr(13.0));
        assertEquals(ServiceLevel.FAIR, ServiceLevel.fromSinr(12.9));
        assertEquals(ServiceLevel.FAIR, ServiceLevel.fromSinr(5.0));
        assertEquals(ServiceLevel.POOR, ServiceLevel.fromSinr(4.9));
        assertEquals(ServiceLevel.POOR, ServiceLevel.fromSinr(0.0));
        assertEquals(ServiceLevel.NONE, ServiceLevel.fromSinr(-0.1));

        assertEquals(ServiceLevel.EXCELLENT, ServiceLevel.fromRsrp(-75.0));
        assertEquals(ServiceLevel.GOOD, ServiceLevel.fromRsrp(-75.1));
        assertEquals(ServiceLevel.NONE, ServiceLevel.fromRsrp(-105.1));

        assertTrue(ServiceLevel.GOOD.atLeast(ServiceLevel.FAIR));
        assertTrue(ServiceLevel.FAIR.atLeast(ServiceLevel.FAIR));
        assertTrue(!ServiceLevel.POOR.atLeast(ServiceLevel.FAIR));
    }

    @Test
    @DisplayName("Display SINR is clamped but the raw value survives for classification")
    void displayClamp() {
        RxSignal serving = cell(1L, "band_900", SERVING_PCI, -20.0);
        SinrResult huge = SinrCalculator.compute(serving, List.of(serving), NOISE_DBM, DEFAULTS);

        assertTrue(huge.sinrDb() > SinrCalculator.DISPLAY_MAX_DB);
        assertEquals(SinrCalculator.DISPLAY_MAX_DB, huge.displaySinrDb(), 1e-9);
    }
}
