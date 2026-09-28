package dev.rancraft.rf;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.rancraft.rf.PciPlanner.Assignment;
import dev.rancraft.rf.PciPlanner.PciParams;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Phase 2 tests 15-16: PCI planning. */
class PciPlannerTest {

    private static final PciParams DEFAULTS = PciParams.DEFAULTS;

    private static CellParams at(long id, int x, int z, int pci, String bandId) {
        CellParams base = TestCells.sector(id, x, 80, z, 0.0, 3.0);
        return TestCells.withPci(TestCells.withBand(base, bandId), pci);
    }

    private static CellParams at(long id, int x, int z, int pci) {
        return at(id, x, z, pci, "band_900");
    }

    private static boolean has(List<PciConflict> conflicts, PciConflict.Type type) {
        return conflicts.stream().anyMatch(c -> c.type() == type);
    }

    // ---- Test 15: detection fires on a bad plan --------------------------------------------

    @Test
    @DisplayName("15a. Collision: two same-band cells share a PCI inside the planning radius")
    void collisionFires() {
        List<CellParams> plan = List.of(at(1L, 0, 0, 147), at(2L, 300, 0, 147));

        List<PciConflict> conflicts = PciPlanner.findConflicts(plan, DEFAULTS);
        assertTrue(has(conflicts, PciConflict.Type.COLLISION));

        PciConflict collision = conflicts.stream()
                .filter(c -> c.type() == PciConflict.Type.COLLISION).findFirst().orElseThrow();
        assertEquals(PciConflict.Severity.ERROR, collision.severity());
        assertEquals(147, collision.pciA());
        assertTrue(collision.describe().contains("PCI 147"), collision.describe());
        assertTrue(collision.describe().contains("300"), collision.describe());
    }

    @Test
    @DisplayName("15a2. The same PCI beyond the planning radius is legitimate reuse, not a collision")
    void distantReuseIsFine() {
        List<CellParams> plan = List.of(at(1L, 0, 0, 147), at(2L, 900, 0, 147));
        assertTrue(PciPlanner.findConflicts(plan, DEFAULTS).isEmpty(),
                "reusing a PCI far away is the entire point of having only 504 of them");
    }

    @Test
    @DisplayName("15a3. A shared PCI on a different band is not a conflict")
    void differentBandIsFine() {
        List<CellParams> plan = List.of(at(1L, 0, 0, 147, "band_900"), at(2L, 50, 0, 147, "band_1800"));
        assertTrue(PciPlanner.findConflicts(plan, DEFAULTS).isEmpty());
    }

    @Test
    @DisplayName("15b. Confusion: two cells sharing a PCI are both neighbours of a third")
    void confusionFires() {
        // A and B are 800 blocks apart -- too far to collide with each other. C sits between them
        // and is within 500 of both, so C cannot tell which "PCI 147" it is being told about.
        List<CellParams> plan = List.of(
                at(1L, -400, 0, 147),
                at(2L, 400, 0, 147),
                at(3L, 0, 0, 200));

        List<PciConflict> conflicts = PciPlanner.findConflicts(plan, DEFAULTS);
        assertFalse(has(conflicts, PciConflict.Type.COLLISION), "800 blocks apart: no collision");
        assertTrue(has(conflicts, PciConflict.Type.CONFUSION));

        PciConflict confusion = conflicts.stream()
                .filter(c -> c.type() == PciConflict.Type.CONFUSION).findFirst().orElseThrow();
        assertEquals(3L, confusion.viaCellId());
        assertEquals(PciConflict.Severity.ERROR, confusion.severity());
    }

    @Test
    @DisplayName("15c. Mod-3: two nearby same-band cells share pci % 3, as a warning")
    void mod3Fires() {
        List<CellParams> plan = List.of(at(1L, 0, 0, 147), at(2L, 100, 0, 150));

        List<PciConflict> conflicts = PciPlanner.findConflicts(plan, DEFAULTS);
        assertTrue(has(conflicts, PciConflict.Type.MOD3));
        assertFalse(has(conflicts, PciConflict.Type.COLLISION));

        PciConflict mod3 = conflicts.stream()
                .filter(c -> c.type() == PciConflict.Type.MOD3).findFirst().orElseThrow();
        assertEquals(PciConflict.Severity.WARNING, mod3.severity());
        assertTrue(mod3.describe().contains("mod-3"), mod3.describe());
        assertTrue(mod3.describe().contains("150"), mod3.describe());
    }

    @Test
    @DisplayName("15c2. Sharing pci % 3 beyond the mod-3 radius is not flagged")
    void distantMod3IsFine() {
        List<CellParams> plan = List.of(at(1L, 0, 0, 147), at(2L, 400, 0, 150));
        assertFalse(has(PciPlanner.findConflicts(plan, DEFAULTS), PciConflict.Type.MOD3),
                "400 blocks is outside the 250-block mod-3 radius");
    }

    // ---- Test 15: detection stays silent on a clean plan -----------------------------------

    @Test
    @DisplayName("15d. A clean three-sector plan raises nothing at all")
    void cleanPlanIsSilent() {
        // The classic arrangement: three co-sited sectors on 0/1/2, all residues distinct.
        List<CellParams> plan = List.of(at(1L, 0, 0, 0), at(2L, 0, 0, 1), at(3L, 0, 0, 2));
        assertTrue(PciPlanner.findConflicts(plan, DEFAULTS).isEmpty(),
                "a correct plan must not produce warnings, or the warnings stop meaning anything");
    }

    @Test
    @DisplayName("15e. findConflictsFor reports the other site, whichever side of the pair it is")
    void perCellViewIsOriented() {
        List<CellParams> plan = List.of(at(1L, 0, 0, 147), at(2L, 300, 0, 147));

        List<PciConflict> fromA = PciPlanner.findConflictsFor(1L, plan, DEFAULTS);
        List<PciConflict> fromB = PciPlanner.findConflictsFor(2L, plan, DEFAULTS);

        assertEquals(1, fromA.size());
        assertEquals(1, fromB.size());
        assertEquals(300, fromA.get(0).bx(), "A is told about the site at x=300");
        assertEquals(0, fromB.get(0).bx(), "B is told about the site at x=0");
    }

    // ---- Test 16: auto-assignment ----------------------------------------------------------

    @Test
    @DisplayName("16. Auto-assign never collides while a free PCI exists in range")
    void assignAvoidsCollisions() {
        List<CellParams> existing = new ArrayList<>();

        // Grow a tight cluster one site at a time, assigning as we go.
        for (int i = 0; i < 40; i++) {
            Assignment assignment = PciPlanner.assign("band_900", i * 5, 80, 0, existing, DEFAULTS);
            assertTrue(assignment.collisionFree(), "collision-free PCI available but not chosen at site " + i);
            assertTrue(PciPlanner.isValidPci(assignment.pci()));
            existing.add(at(100L + i, i * 5, 0, assignment.pci()));
        }

        List<PciConflict> conflicts = PciPlanner.findConflicts(existing, DEFAULTS);
        assertFalse(has(conflicts, PciConflict.Type.COLLISION), "auto-assignment produced a collision");
        assertFalse(has(conflicts, PciConflict.Type.CONFUSION), "auto-assignment produced a confusion");
    }

    @Test
    @DisplayName("16b. The first three co-channel sites get distinct mod-3 residues; the fourth cannot")
    void firstThreeAreMod3Clean() {
        List<CellParams> existing = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            Assignment assignment = PciPlanner.assign("band_900", i * 10, 80, 0, existing, DEFAULTS);
            assertTrue(assignment.isClean(), "site " + i + " should be fully clean");
            existing.add(at(100L + i, i * 10, 0, assignment.pci()));
        }
        assertEquals(List.of(0, 1, 2), existing.stream().map(CellParams::pci).toList());

        // Only three residues exist, so a fourth co-channel site in the same area must reuse one.
        // That is a real planning constraint, not a bug -- and it is the lesson the fourth tower
        // is there to teach.
        Assignment fourth = PciPlanner.assign("band_900", 30, 80, 0, existing, DEFAULTS);
        assertTrue(fourth.collisionFree(), "still no collision");
        assertFalse(fourth.mod3Free(), "a fourth co-channel site cannot avoid mod-3 reuse");
        assertEquals(3, fourth.pci());
    }

    @Test
    @DisplayName("16c. A different band is planned independently")
    void bandsArePlannedIndependently() {
        List<CellParams> existing = List.of(at(1L, 0, 0, 0, "band_900"), at(2L, 10, 0, 1, "band_900"));
        Assignment onOtherBand = PciPlanner.assign("band_1800", 5, 80, 0, existing, DEFAULTS);

        assertEquals(0, onOtherBand.pci(), "band_1800 starts from a clean slate");
        assertTrue(onOtherBand.isClean());
    }

    @Test
    @DisplayName("16d. Auto-assign flags itself when every PCI in range is taken")
    void assignFlagsUnavoidableCollision() {
        List<CellParams> existing = new ArrayList<>();
        for (int pci = PciPlanner.MIN_PCI; pci <= PciPlanner.MAX_PCI; pci++) {
            existing.add(at(1000L + pci, 0, 0, pci));
        }

        Assignment forced = PciPlanner.assign("band_900", 0, 80, 0, existing, DEFAULTS);
        assertFalse(forced.collisionFree(), "the GUI has to be told this assignment is bad");
        assertTrue(PciPlanner.isValidPci(forced.pci()), "but it still gets a usable PCI");
    }

    @Test
    @DisplayName("PCI range is the LTE 0..503")
    void pciRange() {
        assertTrue(PciPlanner.isValidPci(0));
        assertTrue(PciPlanner.isValidPci(503));
        assertFalse(PciPlanner.isValidPci(-1));
        assertFalse(PciPlanner.isValidPci(504));
        assertEquals(504, PciPlanner.PCI_COUNT);
    }
}
