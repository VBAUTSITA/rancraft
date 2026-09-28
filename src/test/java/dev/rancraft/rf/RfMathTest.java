package dev.rancraft.rf;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class RfMathTest {

    @Test
    @DisplayName("FSPL at 1 m is 31.52 dB at 900 MHz")
    void fsplAt1m() {
        assertEquals(31.52, RfMath.fsplAt1mDb(900.0), 0.01);
    }

    @Test
    @DisplayName("Known value: 900 MHz, n=3.5, Tx=20, Gt=6, d=100 m -> PL 101.52 dB, RSRP -75.52 dBm")
    void knownValue() {
        double pathLoss = RfMath.pathLossDb(900.0, 3.5, 100.0);
        assertEquals(101.52, pathLoss, 0.01);

        double rsrp = RfMath.rsrpDbm(20.0, 6.0, pathLoss, 0.0);
        assertEquals(-75.52, rsrp, 0.01);
    }

    @Test
    @DisplayName("Distance is clamped to 1 m, so d=0 is finite rather than -Infinity")
    void distanceClampInMath() {
        double atZero = RfMath.pathLossDb(900.0, 3.5, 0.0);
        assertTrue(Double.isFinite(atZero));
        assertEquals(RfMath.fsplAt1mDb(900.0), atZero, 1e-9);
    }

    @Test
    @DisplayName("Bar thresholds land on the documented boundaries")
    void barThresholds() {
        assertEquals(4, RfMath.bars(-74.9));
        assertEquals(4, RfMath.bars(-75.0));
        assertEquals(3, RfMath.bars(-75.1));
        assertEquals(3, RfMath.bars(-85.0));
        assertEquals(2, RfMath.bars(-85.1));
        assertEquals(2, RfMath.bars(-95.0));
        assertEquals(1, RfMath.bars(-95.1));
        assertEquals(1, RfMath.bars(-105.0));
        assertEquals(0, RfMath.bars(-105.1));
    }
}
