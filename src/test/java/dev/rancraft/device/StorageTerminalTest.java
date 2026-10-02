package dev.rancraft.device;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.rancraft.rf.Band;
import dev.rancraft.rf.BandTable;
import dev.rancraft.rf.BackhaulGraph;
import dev.rancraft.rf.CellSample;
import dev.rancraft.rf.DeviceRequirement;
import dev.rancraft.rf.DeviceRequirement.Verdict;
import dev.rancraft.rf.RfConfig;
import dev.rancraft.rf.ServiceLevel;
import dev.rancraft.rf.SignalSample;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The Wireless Storage Terminal's rules (Phase 3 slice 13, §3C.3), headless: its requirement, the
 * verdict it keeps per player and how old that may be, why a session cannot run, the container sizes it
 * opens and the "near a Core Site" rule. The level-facing halves (binding, opening, the menu closing,
 * never loading a chunk) are {@code StorageTerminalGameTests}.
 */
class StorageTerminalTest {

    private static final BandTable BANDS = BandTable.of(
            Band.DEFAULT_900,
            new Band("band_1800", 1800.0, 3.6, 1.1, -108.0, 2),
            new Band("band_3500", 3500.0, 4.0, 1.8, -106.0, 3));

    private static final int INTERVAL = 20;

    private static SignalSample served(String bandId, ServiceLevel level, long tick) {
        CellSample cell = new CellSample(7L, 0, 70, 0, -75.0, 30.0, 80.0, 0.0, bandId, 4, 15.0, 0.0, 0.0);
        return new SignalSample(List.of(cell), tick, 7L, 18.0, -100.0, -108.0, level, 0);
    }

    /** As the ticker builds it: the verdict for the terminal's requirement, with the cap. */
    private static DeviceContext dispatched(SignalSample sample, ServiceLevel cap, long tick) {
        Verdict verdict = StorageTerminal.REQUIREMENT.check(sample, BANDS, cap);
        return new DeviceContext(sample, verdict, BANDS, RfConfig.DEFAULTS, null, tick, cap);
    }

    private static TerminalLink link(Verdict verdict, ServiceLevel radio, ServiceLevel cap, long tick) {
        return new TerminalLink(verdict, tick, radio, cap, "band_1800", 2);
    }

    @Test
    @DisplayName("§3C.3: the requirement is GOOD on a band of tier 2 or higher")
    void requirement() {
        assertEquals(new DeviceRequirement(ServiceLevel.GOOD, 2), StorageTerminal.REQUIREMENT);
        assertEquals(Verdict.LOW_TIER, StorageTerminal.REQUIREMENT.check(served("band_900", ServiceLevel.EXCELLENT, 1L), BANDS),
                "band_900 is tier 1");
        assertEquals(Verdict.OK, StorageTerminal.REQUIREMENT.check(served("band_1800", ServiceLevel.GOOD, 1L), BANDS));
        assertEquals(Verdict.OK, StorageTerminal.REQUIREMENT.check(served("band_3500", ServiceLevel.GOOD, 1L), BANDS));
        assertEquals(Verdict.LOW_QUALITY, StorageTerminal.REQUIREMENT.check(served("band_1800", ServiceLevel.FAIR, 1L), BANDS));
    }

    @Test
    @DisplayName("the kept verdict is the dispatch's: its tick (not the sample's), its cap, the serving band and tier")
    void linkFromAContext() {
        // A replay: the evaluation is 60 ticks old, the dispatch is now, and the cell is now LIMITED.
        SignalSample sample = served("band_1800", ServiceLevel.EXCELLENT, 1_000L);
        TerminalLink link = TerminalLink.of(dispatched(sample, BackhaulGraph.LIMITED_SERVICE_CAP, 1_060L));
        assertEquals(Verdict.LOW_QUALITY, link.verdict(), "GOOD is above the FAIR cap");
        assertEquals(1_060L, link.tick(), "the dispatch's game time, not the sample's timestamp");
        assertEquals(ServiceLevel.EXCELLENT, link.radio(), "the radio link's own level, uncapped");
        assertEquals(ServiceLevel.FAIR, link.serviceCap());
        assertEquals(ServiceLevel.FAIR, link.effective());
        assertEquals("band_1800", link.servingBandId());
        assertEquals(2, link.servingBandTier());

        TerminalLink none = TerminalLink.of(dispatched(SignalSample.empty(5L), ServiceLevel.EXCELLENT, 5L));
        assertEquals(Verdict.NO_SERVICE, none.verdict());
        assertNull(none.servingBandId());
        assertEquals(0, none.servingBandTier());
    }

    @Test
    @DisplayName("a verdict counts for two evaluation intervals, so one missed dispatch does not cut the session")
    void freshness() {
        assertEquals(40L, TerminalLink.maxAgeTicks(20));
        assertEquals(2L, TerminalLink.maxAgeTicks(0), "an interval below 1 is 1");
        TerminalLink link = link(Verdict.OK, ServiceLevel.GOOD, ServiceLevel.EXCELLENT, 1_000L);
        assertTrue(link.fresh(1_000L, INTERVAL));
        assertTrue(link.fresh(1_040L, INTERVAL), "two intervals old: still counts");
        assertFalse(link.fresh(1_041L, INTERVAL), "older: the terminal is no longer carried");
        assertTrue(link.fresh(990L, INTERVAL), "game time frozen or not yet moved: counts");
    }

    @Test
    @DisplayName("why a session cannot run: no reading, no service, weak signal, backhaul limited, low tier")
    void problems() {
        DeviceRequirement need = StorageTerminal.REQUIREMENT;
        long now = 2_000L;
        assertEquals(TerminalLink.Problem.NO_READING, TerminalLink.problemOf(null, need, now, INTERVAL));
        assertEquals(TerminalLink.Problem.NO_READING, TerminalLink.problemOf(
                link(Verdict.OK, ServiceLevel.GOOD, ServiceLevel.EXCELLENT, now - 41), need, now, INTERVAL),
                "an OK verdict too old does not count");
        assertNull(TerminalLink.problemOf(link(Verdict.OK, ServiceLevel.GOOD, ServiceLevel.EXCELLENT, now - 40),
                need, now, INTERVAL));
        assertEquals(TerminalLink.Problem.NO_SERVICE, TerminalLink.problemOf(
                link(Verdict.NO_SERVICE, ServiceLevel.NONE, ServiceLevel.EXCELLENT, now), need, now, INTERVAL));
        assertEquals(TerminalLink.Problem.LOW_TIER, TerminalLink.problemOf(
                link(Verdict.LOW_TIER, ServiceLevel.EXCELLENT, ServiceLevel.EXCELLENT, now), need, now, INTERVAL));

        // LOW_QUALITY: the radio alone is good enough, only the cap fails it -> fix the backhaul.
        assertEquals(TerminalLink.Problem.BACKHAUL_LIMITED, TerminalLink.problemOf(
                link(Verdict.LOW_QUALITY, ServiceLevel.GOOD, ServiceLevel.FAIR, now), need, now, INTERVAL));
        // The radio itself is short -> fix the signal, whether or not the cell is capped as well.
        assertEquals(TerminalLink.Problem.WEAK_SIGNAL, TerminalLink.problemOf(
                link(Verdict.LOW_QUALITY, ServiceLevel.FAIR, ServiceLevel.EXCELLENT, now), need, now, INTERVAL));
        assertEquals(TerminalLink.Problem.WEAK_SIGNAL, TerminalLink.problemOf(
                link(Verdict.LOW_QUALITY, ServiceLevel.FAIR, ServiceLevel.FAIR, now), need, now, INTERVAL));
    }

    @Test
    @DisplayName("3C done-when, the terminal's half: a LIMITED cell stops it; the same radio uncapped does not")
    void limitedCellStopsTheTerminal() {
        SignalSample sample = served("band_1800", ServiceLevel.EXCELLENT, 3_000L);
        TerminalLink capped = TerminalLink.of(dispatched(sample, BackhaulGraph.LIMITED_SERVICE_CAP, 3_000L));
        assertEquals(TerminalLink.Problem.BACKHAUL_LIMITED,
                TerminalLink.problemOf(capped, StorageTerminal.REQUIREMENT, 3_000L, INTERVAL));
        TerminalLink full = TerminalLink.of(dispatched(sample, ServiceLevel.EXCELLENT, 3_000L));
        assertNull(TerminalLink.problemOf(full, StorageTerminal.REQUIREMENT, 3_000L, INTERVAL));
        assertEquals(ServiceLevel.EXCELLENT, sample.serviceLevel(), "the sample itself was never capped");
    }

    @Test
    @DisplayName("what the player is told: the reason and its figures")
    void descriptions() {
        TerminalLink weak = link(Verdict.LOW_QUALITY, ServiceLevel.FAIR, ServiceLevel.EXCELLENT, 1L);
        assertTranslation(StorageTerminal.describe(TerminalLink.Problem.WEAK_SIGNAL, weak), "weak_signal", "FAIR", "GOOD");
        TerminalLink capped = link(Verdict.LOW_QUALITY, ServiceLevel.EXCELLENT, ServiceLevel.FAIR, 1L);
        assertTranslation(StorageTerminal.describe(TerminalLink.Problem.BACKHAUL_LIMITED, capped),
                "backhaul_limited", "FAIR", "GOOD");
        TerminalLink lowTier = new TerminalLink(Verdict.LOW_TIER, 1L, ServiceLevel.GOOD, ServiceLevel.EXCELLENT, "band_900", 1);
        assertTranslation(StorageTerminal.describe(TerminalLink.Problem.LOW_TIER, lowTier), "low_tier", 2, "band_900", 1);
        assertTranslation(StorageTerminal.describe(TerminalLink.Problem.NO_READING, null), "no_reading");
        assertTranslation(StorageTerminal.describe(TerminalLink.Problem.NO_SERVICE, null), "no_service");
    }

    private static void assertTranslation(Component component, String problem, Object... args) {
        TranslatableContents contents = (TranslatableContents) component.getContents();
        assertEquals(StorageTerminal.problemKey(problem), contents.getKey());
        assertEquals(List.of(args), List.of(contents.getArgs()));
    }

    @Test
    @DisplayName("it opens 27 slots as 3 rows and 54 as 6, nothing else")
    void rows() {
        assertEquals(3, StorageTerminal.rowsFor(27));
        assertEquals(6, StorageTerminal.rowsFor(54));
        for (int size : new int[] {0, 5, 9, 26, 28, 36, 41, 53, 55}) {
            assertEquals(0, StorageTerminal.rowsFor(size), "size " + size);
        }
    }

    @Test
    @DisplayName("near a Core Site: horizontally within the fiber radius, inclusive, height ignored, nearest wins")
    void nearCore() {
        BlockPos chest = new BlockPos(100, 64, -50);
        long[] none = {};
        assertNull(StorageTerminal.nearestCore(none, chest, 24.0));

        BlockPos edge = new BlockPos(124, 200, -50);
        assertEquals(edge, StorageTerminal.nearestCore(new long[] {edge.asLong()}, chest, 24.0),
                "24 blocks away, 136 above: on fiber");
        assertNull(StorageTerminal.nearestCore(new long[] {new BlockPos(125, 64, -50).asLong()}, chest, 24.0));
        assertNull(StorageTerminal.nearestCore(new long[] {new BlockPos(117, 64, -33).asLong()}, chest, 24.0),
                "17 and 17 is 24.04 blocks: off");

        BlockPos near = new BlockPos(90, 10, -45);
        assertEquals(near, StorageTerminal.nearestCore(new long[] {edge.asLong(), near.asLong()}, chest, 24.0));
        assertEquals(chest.above(5), StorageTerminal.nearestCore(new long[] {chest.above(5).asLong()}, chest, 0.0),
                "radius 0: the same column only");

        // The same rule as the backhaul graph's fiber.
        BackhaulGraph.Topology fiber = new BackhaulGraph.Topology(24.0, 0.0);
        for (int dx = -26; dx <= 26; dx++) {
            for (int dz = -26; dz <= 26; dz++) {
                BlockPos core = chest.offset(dx, 7, dz);
                boolean graph = fiber.onFiber(new BackhaulGraph.Node(1L, chest.getX(), chest.getZ()),
                        new BackhaulGraph.Node(2L, core.getX(), core.getZ()));
                assertEquals(graph, StorageTerminal.nearestCore(new long[] {core.asLong()}, chest, 24.0) != null,
                        "offset " + dx + ", " + dz);
            }
        }
    }
}
