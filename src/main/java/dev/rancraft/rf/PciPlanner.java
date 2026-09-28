package dev.rancraft.rf;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Physical Cell Identity assignment and conflict detection.
 *
 * <p>Pure: it takes cells and gives back integers and findings, so the whole of PCI planning is
 * testable with no world. {@code /rancraft pci check} and the antenna GUI are thin wrappers over
 * this class.
 *
 * <p>PCI range is 0..503, the LTE convention (168 cell-identity groups x 3 identities).
 * That {@code x 3} is why {@code pci % 3} matters at all: the residue picks which of the three
 * reference-signal subcarrier offsets a cell transmits on.
 */
public final class PciPlanner {

    private PciPlanner() {
    }

    public static final int MIN_PCI = 0;
    public static final int MAX_PCI = 503;
    public static final int PCI_COUNT = MAX_PCI - MIN_PCI + 1;

    public static boolean isValidPci(int pci) {
        return pci >= MIN_PCI && pci <= MAX_PCI;
    }

    public record PciParams(double planningRadiusBlocks, double mod3RadiusBlocks) {
        public static final PciParams DEFAULTS = new PciParams(500.0, 250.0);
    }

    /**
     * @param collisionFree false only when every PCI in range was already taken nearby, i.e. the
     *                      assignment is knowingly bad and the GUI should say so.
     * @param mod3Free      false when the assignment had to reuse a {@code pci % 3} residue that is
     *                      already present nearby. Common and often unavoidable.
     */
    public record Assignment(int pci, boolean collisionFree, boolean mod3Free) {
        public boolean isClean() {
            return collisionFree && mod3Free;
        }
    }

    /**
     * Picks a PCI for a new cell.
     *
     * <p>Prefers a PCI that is both unused within the planning radius and whose {@code mod 3}
     * residue is unused within the (smaller) mod-3 radius. Falls back to merely unused, and only
     * then to a knowing collision.
     *
     * <p>Deterministic -- it always returns the lowest qualifying PCI. A fresh cluster therefore
     * gets 0, 1, 2, and the fourth co-channel site is forced into a mod-3 reuse, which is exactly
     * the real constraint and exactly the lesson the fourth tower is meant to teach.
     */
    public static Assignment assign(
            String bandId,
            int x, int y, int z,
            Collection<CellParams> existing,
            PciParams params) {

        Set<Integer> usedNearby = new HashSet<>();
        Set<Integer> mod3Nearby = new HashSet<>();

        for (CellParams cell : existing) {
            if (!cell.bandId().equals(bandId)) {
                continue;
            }
            double distance = distanceBlocks(cell, x + 0.5, y + 0.5, z + 0.5);
            if (distance <= params.planningRadiusBlocks()) {
                usedNearby.add(cell.pci());
            }
            if (distance <= params.mod3RadiusBlocks()) {
                mod3Nearby.add(Math.floorMod(cell.pci(), 3));
            }
        }

        for (int pci = MIN_PCI; pci <= MAX_PCI; pci++) {
            if (!usedNearby.contains(pci) && !mod3Nearby.contains(Math.floorMod(pci, 3))) {
                return new Assignment(pci, true, true);
            }
        }
        for (int pci = MIN_PCI; pci <= MAX_PCI; pci++) {
            if (!usedNearby.contains(pci)) {
                return new Assignment(pci, true, false);
            }
        }
        // All 504 taken within the radius. Assign anyway and flag it, per the spec: refusing to
        // place the antenna would be a worse outcome than placing a flagged one.
        return new Assignment(MIN_PCI, false, false);
    }

    /**
     * Every collision, confusion and mod-3 conflict among the given cells.
     *
     * <p>O(n^2) in the number of cells, and O(n^3) for confusion. That is fine because the caller
     * always bounds the set by a radius first -- this never runs over a whole world.
     */
    public static List<PciConflict> findConflicts(Collection<CellParams> cells, PciParams params) {
        List<CellParams> list = new ArrayList<>(cells);
        List<PciConflict> conflicts = new ArrayList<>();

        for (int i = 0; i < list.size(); i++) {
            for (int j = i + 1; j < list.size(); j++) {
                CellParams a = list.get(i);
                CellParams b = list.get(j);
                if (!a.bandId().equals(b.bandId())) {
                    continue;
                }

                double separation = distanceBlocks(a, b.centerX(), b.centerY(), b.centerZ());
                boolean samePci = a.pci() == b.pci();

                if (samePci && separation <= params.planningRadiusBlocks()) {
                    conflicts.add(conflict(PciConflict.Type.COLLISION, a, b, ReceiverState.NO_CELL, separation));
                }

                if (samePci) {
                    long via = findConfusingThirdCell(list, a, b, params);
                    if (via != ReceiverState.NO_CELL) {
                        conflicts.add(conflict(PciConflict.Type.CONFUSION, a, b, via, separation));
                    }
                }

                // An identical PCI trivially shares its residue, but that is already reported as a
                // collision; adding a mod-3 warning on top would be noise on the same finding.
                if (!samePci
                        && separation <= params.mod3RadiusBlocks()
                        && SinrCalculator.collidesOnMod3(a.pci(), b.pci())) {
                    conflicts.add(conflict(PciConflict.Type.MOD3, a, b, ReceiverState.NO_CELL, separation));
                }
            }
        }
        return conflicts;
    }

    /** Conflicts involving one specific cell, which is what the antenna GUI shows. */
    public static List<PciConflict> findConflictsFor(
            long cellId, Collection<CellParams> cells, PciParams params) {

        List<PciConflict> mine = new ArrayList<>();
        for (PciConflict conflict : findConflicts(cells, params)) {
            if (conflict.cellIdA() == cellId) {
                mine.add(conflict);
            } else if (conflict.cellIdB() == cellId) {
                mine.add(flip(conflict));
            }
        }
        return mine;
    }

    /**
     * A third same-band cell that has both {@code a} and {@code b} inside its planning radius, and
     * so cannot tell which of them a report of their shared PCI refers to.
     */
    private static long findConfusingThirdCell(
            List<CellParams> cells, CellParams a, CellParams b, PciParams params) {

        for (CellParams c : cells) {
            if (c.cellId() == a.cellId() || c.cellId() == b.cellId()) {
                continue;
            }
            if (!c.bandId().equals(a.bandId())) {
                continue;
            }
            boolean seesA = distanceBlocks(a, c.centerX(), c.centerY(), c.centerZ()) <= params.planningRadiusBlocks();
            boolean seesB = distanceBlocks(b, c.centerX(), c.centerY(), c.centerZ()) <= params.planningRadiusBlocks();
            if (seesA && seesB) {
                return c.cellId();
            }
        }
        return ReceiverState.NO_CELL;
    }

    private static PciConflict conflict(
            PciConflict.Type type, CellParams a, CellParams b, long via, double separation) {
        return new PciConflict(
                type, a.bandId(),
                a.cellId(), a.x(), a.y(), a.z(), a.pci(),
                b.cellId(), b.x(), b.y(), b.z(), b.pci(),
                via, separation);
    }

    /** Same finding seen from the other cell, so the GUI always describes "the other site". */
    private static PciConflict flip(PciConflict c) {
        return new PciConflict(
                c.type(), c.bandId(),
                c.cellIdB(), c.bx(), c.by(), c.bz(), c.pciB(),
                c.cellIdA(), c.ax(), c.ay(), c.az(), c.pciA(),
                c.viaCellId(), c.separationBlocks());
    }

    private static double distanceBlocks(CellParams cell, double x, double y, double z) {
        double dx = cell.centerX() - x;
        double dy = cell.centerY() - y;
        double dz = cell.centerZ() - z;
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }
}
