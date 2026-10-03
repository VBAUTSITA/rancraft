package dev.rancraft.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Phase 3 slice 15 (§3C.5): the buffer, the fractional draw, out of energy and the 10 % restart latch. */
class EnergyBufferTest {

    private static final int CAPACITY = 10_000;
    private static final double RESTART = 0.10;

    private static EnergyBuffer full() {
        EnergyBuffer buffer = new EnergyBuffer();
        buffer.load(CAPACITY, true);
        return buffer;
    }

    @Test
    @DisplayName("Receive fills to the capacity and no further; simulating changes nothing")
    void receiveCapsAtCapacity() {
        EnergyBuffer buffer = new EnergyBuffer();
        assertEquals(40, buffer.receive(40, CAPACITY, true));
        assertEquals(0, buffer.stored());
        assertEquals(40, buffer.receive(40, CAPACITY, false));
        assertEquals(40, buffer.stored());
        buffer.load(CAPACITY - 10, false);
        assertEquals(10, buffer.receive(40, CAPACITY, false));
        assertEquals(CAPACITY, buffer.stored());
        assertEquals(0, buffer.receive(40, CAPACITY, false));
        assertEquals(0, buffer.receive(-5, CAPACITY, false));
        assertEquals(0, buffer.receive(0, CAPACITY, false));
    }

    @Test
    @DisplayName("A buffer above a lowered capacity takes nothing and keeps what it has")
    void loweredCapacity() {
        EnergyBuffer buffer = full();
        assertEquals(0, buffer.receive(100, 5_000, false));
        assertEquals(CAPACITY, buffer.stored());
    }

    @Test
    @DisplayName("Extract gives what is there, simulating changes nothing")
    void extract() {
        EnergyBuffer buffer = new EnergyBuffer();
        buffer.load(30, true);
        assertEquals(30, buffer.extract(100, true));
        assertEquals(30, buffer.stored());
        assertEquals(20, buffer.extract(20, false));
        assertEquals(10, buffer.extract(100, false));
        assertEquals(0, buffer.stored());
        assertEquals(0, buffer.extract(-1, false));
    }

    @Test
    @DisplayName("A fractional rate is paid exactly over many ticks: 10.67 FE/t for 300 ticks is 3200 FE")
    void fractionalRateIsExact() {
        EnergyBuffer buffer = full();
        double rate = 4.0 + 20.0 / 3.0;
        for (int tick = 0; tick < 300; tick++) {
            assertTrue(buffer.draw(rate));
        }
        // 300 x 10.6667 = 3200, to within the fraction still owed (< 1 FE).
        int drawn = CAPACITY - buffer.stored();
        assertTrue(drawn == 3199 || drawn == 3200, "drawn " + drawn);
    }

    @Test
    @DisplayName("Out of energy: the buffer that cannot pay a tick empties and the latch goes off")
    void outOfEnergy() {
        EnergyBuffer buffer = new EnergyBuffer();
        buffer.load(25, true);
        assertTrue(buffer.draw(10.0));
        assertTrue(buffer.draw(10.0));
        assertEquals(5, buffer.stored());
        assertFalse(buffer.draw(10.0), "5 FE cannot pay a 10 FE tick");
        assertEquals(0, buffer.stored());
        assertFalse(buffer.on());
        assertFalse(buffer.draw(10.0), "an off buffer draws nothing");
    }

    @Test
    @DisplayName("No flapping: once off, the latch stays off until the buffer is above 10 %, not at the first FE")
    void restartsOnlyAboveTenPercent() {
        EnergyBuffer buffer = new EnergyBuffer();
        buffer.load(0, false);
        for (int fe = 0; fe < 1000; fe += 40) {
            buffer.receive(40, CAPACITY, false);
            if (buffer.stored() <= 1000) {
                assertFalse(buffer.restart(CAPACITY, RESTART), "still off at " + buffer.stored() + " FE");
            }
        }
        buffer.load(1000, false);
        assertFalse(buffer.restart(CAPACITY, RESTART), "exactly 10 % is not above it");
        assertFalse(buffer.on());
        buffer.receive(1, CAPACITY, false);
        assertTrue(buffer.restart(CAPACITY, RESTART), "1001 FE is above 10 %");
        assertTrue(buffer.on());
        assertFalse(buffer.restart(CAPACITY, RESTART), "already on: not a new restart");
    }

    @Test
    @DisplayName("Once on, the latch holds below 10 %: it goes off only when a tick cannot be paid")
    void staysOnBelowTenPercent() {
        EnergyBuffer buffer = new EnergyBuffer();
        buffer.load(1001, false);
        assertTrue(buffer.restart(CAPACITY, RESTART));
        int ticks = 0;
        while (buffer.draw(10.0)) {
            ticks++;
        }
        assertEquals(100, ticks, "1001 FE pays 100 ticks of 10 FE, then runs out");
        assertFalse(buffer.on());
    }

    @Test
    @DisplayName("A supply below the draw cycles slowly (the 10 % band), never every tick")
    void weakSupplyCyclesSlowly() {
        // 40 FE/t in, 70.67 FE/t out: a 30 dBm sector on one generator.
        EnergyBuffer buffer = new EnergyBuffer();
        double rate = 4.0 + 200.0 / 3.0;
        int switches = 0;
        boolean was = buffer.on();
        for (int tick = 0; tick < 2000; tick++) {
            buffer.receive(40, CAPACITY, false);
            if (buffer.on()) {
                buffer.draw(rate);
            } else {
                buffer.restart(CAPACITY, RESTART);
            }
            if (buffer.on() != was) {
                switches++;
                was = buffer.on();
            }
        }
        // Each cycle: about 25 ticks charging past 1000 FE, then about 33 on the air.
        assertTrue(switches > 0 && switches < 2000 / 20, "switches " + switches);
    }

    @Test
    @DisplayName("A rate that is not finite cannot be paid; a non-positive rate costs nothing")
    void oddRates() {
        EnergyBuffer buffer = full();
        assertTrue(buffer.draw(0.0));
        assertTrue(buffer.draw(-3.0));
        assertEquals(CAPACITY, buffer.stored());
        assertFalse(buffer.draw(Double.NaN));
        assertFalse(buffer.on());
        buffer = full();
        assertFalse(buffer.draw(Double.POSITIVE_INFINITY));
        assertEquals(0, buffer.stored());
    }

    @Test
    @DisplayName("Load clamps a negative amount to empty; an empty buffer never restarts")
    void loadAndEmpty() {
        EnergyBuffer buffer = new EnergyBuffer();
        buffer.load(-50, true);
        assertEquals(0, buffer.stored());
        buffer.load(0, false);
        assertFalse(buffer.restart(CAPACITY, 0.0), "0 FE is not above 0 %");
        buffer.load(1, false);
        assertTrue(buffer.restart(CAPACITY, 0.0));
        buffer.clear();
        assertEquals(0, buffer.stored());
        assertFalse(buffer.on());
    }
}
