package dev.rancraft.rf;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Phase 3 slice 9 (§3B.4, 3B tests): the block error rate curve and the delivery probability. */
class BlerModelTest {

    private static final BlerModel MODEL = BlerModel.DEFAULT;

    @Test
    @DisplayName("matches the §3B.4 table within 0.001")
    void matchesTheTable() {
        double[] sinr = {-2.0, 0.0, 2.0, 5.0, 10.0};
        double[] bler = {0.91, 0.50, 0.091, 0.0032, 0.00001};
        for (int i = 0; i < sinr.length; i++) {
            assertEquals(bler[i], MODEL.bler(sinr[i]), 0.001, "BLER at " + sinr[i] + " dB");
        }
        // And the exact values of the formula.
        assertEquals(10.0 / 11.0, MODEL.bler(-2.0), 1e-15);
        assertEquals(0.5, MODEL.bler(0.0), 1e-15);
        assertEquals(1.0 / 11.0, MODEL.bler(2.0), 1e-15);
        assertEquals(1.0 / (1.0 + Math.pow(10.0, 2.5)), MODEL.bler(5.0), 1e-15);
        assertEquals(1.0 / (1.0 + 1e5), MODEL.bler(10.0), 1e-18);
    }

    @Test
    @DisplayName("is monotonic in SINR: never rises, strictly falls where it is not saturated")
    void monotonic() {
        double previous = MODEL.bler(-40.0);
        double previousSuccess = MODEL.success(-40.0);
        for (int step = 1; step <= 8000; step++) {
            double sinr = -40.0 + step * 0.01;
            double bler = MODEL.bler(sinr);
            double success = MODEL.success(sinr);
            assertTrue(bler <= previous, "BLER rose at " + sinr + " dB");
            assertTrue(success >= previousSuccess, "success fell at " + sinr + " dB");
            if (sinr > -20.0 && sinr < 20.0) {
                assertTrue(bler < previous, "BLER flat at " + sinr + " dB");
            }
            assertTrue(bler >= 0.0 && bler <= 1.0, "in [0, 1]");
            previous = bler;
            previousSuccess = success;
        }
    }

    @Test
    @DisplayName("success is 1 - BLER, and stays exact where the BLER is tiny")
    void successIsTheComplement() {
        for (double sinr = -20.0; sinr <= 20.0; sinr += 0.25) {
            assertEquals(1.0 - MODEL.bler(sinr), MODEL.success(sinr), 1e-15, "at " + sinr + " dB");
        }
        // 1 / (1 + 1e-25): computed directly it is 1 - 1e-25, which a double rounds to 1.
        assertEquals(1.0, MODEL.success(50.0), 0.0);
        assertEquals(1e-25, MODEL.bler(50.0), 1e-35);
    }

    @Test
    @DisplayName("the infinities and NaN: no signal never delivers, a perfect one always does")
    void edges() {
        assertEquals(1.0, MODEL.bler(Double.NEGATIVE_INFINITY));
        assertEquals(0.0, MODEL.success(Double.NEGATIVE_INFINITY));
        assertEquals(0.0, MODEL.bler(Double.POSITIVE_INFINITY));
        assertEquals(1.0, MODEL.success(Double.POSITIVE_INFINITY));
        assertEquals(1.0, MODEL.bler(Double.NaN));
        assertEquals(0.0, MODEL.success(Double.NaN));
        assertEquals(0.0, MODEL.deliveryProbability(Double.NEGATIVE_INFINITY, 30.0));
    }

    @Test
    @DisplayName("P(deliver) = (1 - BLER(tx)) x (1 - BLER(rx)), symmetric in the two ends")
    void deliveryProbability() {
        assertEquals(0.25, MODEL.deliveryProbability(0.0, 0.0), 1e-15);
        assertEquals((10.0 / 11.0) * 0.5, MODEL.deliveryProbability(2.0, 0.0), 1e-15);
        for (double tx = -5.0; tx <= 15.0; tx += 1.5) {
            for (double rx = -5.0; rx <= 15.0; rx += 1.5) {
                double expected = (1.0 - MODEL.bler(tx)) * (1.0 - MODEL.bler(rx));
                assertEquals(expected, MODEL.deliveryProbability(tx, rx), 1e-15);
                assertEquals(MODEL.deliveryProbability(rx, tx), MODEL.deliveryProbability(tx, rx), 1e-15);
            }
        }
    }

    @Test
    @DisplayName("POOR (0-5 dB) is flaky, FAIR (5 dB and up) is solid")
    void poorIsFlakyFairIsSolid() {
        // Both ends at the top of POOR lose about one message in 140; at 2.5 dB one in ten; at 1 dB four in ten.
        assertTrue(MODEL.deliveryProbability(4.9, 4.9) < 0.994);
        assertTrue(MODEL.deliveryProbability(2.5, 2.5) < 0.9);
        assertTrue(MODEL.deliveryProbability(1.0, 1.0) < 0.6);
        // Both ends at the bottom of FAIR: 99.4 %; one step into FAIR, better than 99.99 %.
        assertTrue(MODEL.deliveryProbability(ServiceLevel.SINR_FAIR_DB, ServiceLevel.SINR_FAIR_DB) > 0.993);
        assertTrue(MODEL.deliveryProbability(10.0, 10.0) > 0.9999);
    }

    @Test
    @DisplayName("sinr50 moves the curve, slope sets its steepness; bad parameters are refused")
    void parameters() {
        BlerModel shifted = new BlerModel(3.0, 2.0);
        assertEquals(0.5, shifted.bler(3.0), 1e-15);
        assertEquals(MODEL.bler(2.0), shifted.bler(5.0), 1e-15);
        BlerModel steep = new BlerModel(0.0, 1.0);
        assertEquals(MODEL.bler(4.0), steep.bler(2.0), 1e-15);
        assertTrue(steep.bler(2.0) < MODEL.bler(2.0));
        assertThrows(IllegalArgumentException.class, () -> new BlerModel(0.0, 0.0));
        assertThrows(IllegalArgumentException.class, () -> new BlerModel(0.0, -2.0));
        assertThrows(IllegalArgumentException.class, () -> new BlerModel(Double.NaN, 2.0));
        assertThrows(IllegalArgumentException.class, () -> new BlerModel(0.0, Double.POSITIVE_INFINITY));
    }

    @Test
    @DisplayName("the config defaults are §5's: blerSinr50Db 0, blerSlopeDb 2")
    void configDefaults() {
        assertEquals(0.0, RfConfig.DEFAULTS.blerSinr50Db());
        assertEquals(2.0, RfConfig.DEFAULTS.blerSlopeDb());
        assertEquals(BlerModel.DEFAULT, RfConfig.DEFAULTS.blerModel());
    }

    @Test
    @DisplayName("a delivery decision is uniform < probability")
    void deliveredDecision() {
        assertTrue(BlerModel.delivered(1.0, 0.999_999));
        assertFalse(BlerModel.delivered(0.0, 0.0));
        assertTrue(BlerModel.delivered(0.25, 0.249));
        assertFalse(BlerModel.delivered(0.25, 0.25));
    }
}
