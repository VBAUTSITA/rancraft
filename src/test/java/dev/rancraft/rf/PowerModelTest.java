package dev.rancraft.rf;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Phase 3 slice 15 (§3C.5, 3C tests): the power model matches the table and scales 10x per 10 dB in the PA. */
class PowerModelTest {

    private static final PowerModel MODEL = PowerModel.DEFAULT;
    private static final boolean SECTOR = true;
    private static final boolean MAST = false;

    @Test
    @DisplayName("P_rf: 20 dBm is 0.1 W, 30 dBm is 1 W, 0 dBm is 1 mW")
    void dbmToWatts() {
        assertEquals(0.1, PowerModel.rfWatts(20.0), 1e-12);
        assertEquals(1.0, PowerModel.rfWatts(30.0), 1e-12);
        assertEquals(0.001, PowerModel.rfWatts(0.0), 1e-15);
    }

    @Test
    @DisplayName("3C test: the §3C.5 table, a sector at 20 dBm draws about 10.7 FE/t and at 30 dBm about 70.7")
    void matchesTheTable() {
        assertEquals(10.7, MODEL.fePerTick(SECTOR, 2, 20.0), 0.05);
        assertEquals(70.7, MODEL.fePerTick(SECTOR, 2, 30.0), 0.05);
        // Exactly: 4 + 20 x 0.1 / 0.3 and 4 + 20 x 1 / 0.3.
        assertEquals(4.0 + 20.0 / 3.0, MODEL.fePerTick(SECTOR, 2, 20.0), 1e-9);
        assertEquals(4.0 + 200.0 / 3.0, MODEL.fePerTick(SECTOR, 2, 30.0), 1e-9);
    }

    @Test
    @DisplayName("3C test: the PA term scales exactly 10x per 10 dB, at every power")
    void paScalesTenfoldPerTenDb() {
        for (double tx = 0.0; tx <= 20.0; tx += 0.5) {
            double ratio = MODEL.paFePerTick(tx + 10.0) / MODEL.paFePerTick(tx);
            assertEquals(10.0, ratio, 1e-9, "at " + tx + " dBm");
        }
        // Twice 10 dB is 100x.
        assertEquals(100.0, MODEL.paFePerTick(30.0) / MODEL.paFePerTick(10.0), 1e-9);
    }

    @Test
    @DisplayName("3C done-when: a 30 dBm sector costs about 7x a 20 dBm one (6.625x)")
    void aboutSevenTimes() {
        double ratio = MODEL.fePerTick(SECTOR, 2, 30.0) / MODEL.fePerTick(SECTOR, 2, 20.0);
        assertEquals(6.625, ratio, 1e-9);
        assertTrue(ratio > 6.0 && ratio < 7.5);
    }

    @Test
    @DisplayName("Base load: mast 2, sector 4, plus 4 for a wideband (tier 3) radio")
    void baseLoad() {
        assertEquals(2.0, MODEL.baseFe(MAST, RadioTier.MIN), 0.0);
        assertEquals(4.0, MODEL.baseFe(SECTOR, 2), 0.0);
        assertEquals(8.0, MODEL.baseFe(SECTOR, RadioTier.WIDEBAND), 0.0);
        assertEquals(8.0, MODEL.baseFe(SECTOR, RadioTier.WIDEBAND + 1), 0.0);
        // A mast at the default 20 dBm.
        assertEquals(2.0 + 20.0 / 3.0, MODEL.fePerTick(MAST, RadioTier.MIN, CellParams.DEFAULT_TX_DBM), 1e-9);
        // A wideband sector at 20 dBm: 8 + 6.67.
        assertEquals(8.0 + 20.0 / 3.0, MODEL.fePerTick(SECTOR, RadioTier.WIDEBAND, 20.0), 1e-9);
    }

    @Test
    @DisplayName("Efficiency divides: a perfect amplifier draws 0.3x what a 30 % one does")
    void efficiencyDivides() {
        PowerModel perfect = new PowerModel(0.0, 0.0, 0.0, 20.0, 1.0);
        assertEquals(20.0, perfect.paFePerTick(30.0), 1e-9);
        assertEquals(0.3, perfect.paFePerTick(30.0) / MODEL.paFePerTick(30.0), 1e-12);
    }

    @Test
    @DisplayName("A Tx power that is not finite gives a draw no buffer can pay")
    void nonFiniteTx() {
        assertFalse(Double.isFinite(MODEL.fePerTick(SECTOR, 2, Double.NaN)));
        assertFalse(Double.isFinite(MODEL.fePerTick(SECTOR, 2, Double.POSITIVE_INFINITY)));
    }

    @Test
    @DisplayName("Figures are validated: efficiency in (0, 1], the rest finite and not negative")
    void validated() {
        assertThrows(IllegalArgumentException.class, () -> new PowerModel(2, 4, 4, 20, 0.0));
        assertThrows(IllegalArgumentException.class, () -> new PowerModel(2, 4, 4, 20, 1.5));
        assertThrows(IllegalArgumentException.class, () -> new PowerModel(2, 4, 4, 20, Double.NaN));
        assertThrows(IllegalArgumentException.class, () -> new PowerModel(-1, 4, 4, 20, 0.3));
        assertThrows(IllegalArgumentException.class, () -> new PowerModel(2, Double.NaN, 4, 20, 0.3));
        assertThrows(IllegalArgumentException.class, () -> new PowerModel(2, 4, Double.POSITIVE_INFINITY, 20, 0.3));
        assertThrows(IllegalArgumentException.class, () -> new PowerModel(2, 4, 4, -20, 0.3));
    }
}
