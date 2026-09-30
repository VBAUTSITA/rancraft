package dev.rancraft.rf;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Phase 3 slice 9 (§3B.4, 3B tests): the deterministic generator behind the Radio Link's draws. */
class SplitMix64Test {

    /** An arbitrary {@code BlockPos.asLong()}-style key; only its bits matter here. */
    private static final long POS = 0x0000_1EFF_FE38_4040L;

    @Test
    @DisplayName("matches the reference splitmix64.c output")
    void referenceVectors() {
        SplitMix64 zero = new SplitMix64(0L);
        assertEquals(0xE220A8397B1DCDAFL, zero.nextLong());
        assertEquals(0x6E789E6AA1B965F4L, zero.nextLong());
        assertEquals(0x06C45D188009454FL, zero.nextLong());
        SplitMix64 other = new SplitMix64(1234567L);
        assertEquals(6457827717110365317L, other.nextLong());
        assertEquals(3203168211198807973L, other.nextLong());
        assertEquals(Long.parseUnsignedLong("9817491932198370423"), other.nextLong());
    }

    @Test
    @DisplayName("the seed is pos.asLong() ^ gameTime (§3B.4)")
    void seed() {
        assertEquals(POS ^ 24_000L, SplitMix64.seed(POS, 24_000L));
        assertEquals(POS, SplitMix64.seed(POS, 0L));
    }

    @Test
    @DisplayName("draws are identical across two runs with the same seed")
    void sameSeedSameDraws() {
        long seed = SplitMix64.seed(POS, 1_234_567L);
        SplitMix64 first = new SplitMix64(seed);
        SplitMix64 second = new SplitMix64(seed);
        for (int i = 0; i < 1_000; i++) {
            assertEquals(first.nextDouble(), second.nextDouble(), 0.0, "draw " + i);
        }
    }

    @Test
    @DisplayName("draws differ across ticks, and are spread evenly over [0, 1)")
    void differentTicksDifferentDraws() {
        Set<Double> firsts = new HashSet<>();
        double sum = 0.0;
        int below = 0;
        int ticks = 10_000;
        for (long tick = 0; tick < ticks; tick++) {
            double draw = new SplitMix64(SplitMix64.seed(POS, 50_000L + tick)).nextDouble();
            assertTrue(draw >= 0.0 && draw < 1.0, "in [0, 1): " + draw);
            firsts.add(draw);
            sum += draw;
            if (draw < 0.25) {
                below++;
            }
        }
        assertEquals(ticks, firsts.size(), "every tick draws something new");
        // Uniform: mean 0.5 (sd 0.0029 over 10,000), a quarter below 0.25 (sd 0.0043).
        assertEquals(0.5, sum / ticks, 0.015);
        assertEquals(0.25, below / (double) ticks, 0.02);
        // Neighbouring ticks are unrelated, not just different.
        assertNotEquals(new SplitMix64(SplitMix64.seed(POS, 7L)).nextLong(),
                new SplitMix64(SplitMix64.seed(POS, 8L)).nextLong());
    }

    @Test
    @DisplayName("nextDouble is the top 53 bits of nextLong")
    void nextDoubleIsTop53Bits() {
        SplitMix64 bits = new SplitMix64(99L);
        SplitMix64 doubles = new SplitMix64(99L);
        for (int i = 0; i < 100; i++) {
            assertEquals((bits.nextLong() >>> 11) * 0x1.0p-53, doubles.nextDouble(), 0.0);
        }
    }
}
