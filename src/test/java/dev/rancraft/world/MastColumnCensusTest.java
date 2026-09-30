package dev.rancraft.world;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The one-line log of the mast-column behaviour change (§3B.1). */
class MastColumnCensusTest {

    @Test
    @DisplayName("§3B.1's sentence: N stacked masts now form M columns; N-M masts stopped transmitting")
    void specSentence() {
        MastColumnCensus.Tally tally = MastColumnCensus.Tally.EMPTY.plus(9, false).plus(3, false);
        assertEquals("12 stacked masts now form 2 columns; 10 masts stopped transmitting.", tally.message());
        assertTrue(tally.changedAnything());
    }

    @Test
    @DisplayName("a lone mast with nothing on top changed nothing and is not counted")
    void loneMastIsNotCounted() {
        MastColumnCensus.Tally tally = MastColumnCensus.Tally.EMPTY;
        assertSame(tally, tally.plus(1, false));
        assertFalse(tally.changedAnything());
        assertEquals("", tally.message());
    }

    @Test
    @DisplayName("a mounting pole (even one mast) stopped transmitting altogether and is named apart")
    void mountingPoles() {
        MastColumnCensus.Tally tally = MastColumnCensus.Tally.EMPTY.plus(1, true).plus(4, true);
        assertEquals("5 masts under 2 sector antennas are mounting poles now and stopped transmitting.",
                tally.message());

        MastColumnCensus.Tally both = tally.plus(9, false);
        assertEquals("9 stacked masts now form 1 column; 8 masts stopped transmitting. "
                + "5 masts under 2 sector antennas are mounting poles now and stopped transmitting.",
                both.message());

        assertEquals("1 mast under 1 sector antenna is a mounting pole now and stopped transmitting.",
                MastColumnCensus.Tally.EMPTY.plus(1, true).message());
        assertEquals("2 stacked masts now form 1 column; 1 mast stopped transmitting.",
                MastColumnCensus.Tally.EMPTY.plus(2, false).message());
    }
}
