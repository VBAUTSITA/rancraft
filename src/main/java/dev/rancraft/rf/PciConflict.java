package dev.rancraft.rf;

/**
 * One detected PCI planning problem.
 *
 * <p>The three conditions are the real ones a radio planner checks, with their real definitions:
 *
 * <ul>
 *   <li><b>Collision</b> -- two neighbouring cells on the same band share a PCI. A handset cannot
 *       tell them apart at all.
 *   <li><b>Confusion</b> -- two cells sharing a PCI are both neighbours of a third cell. The third
 *       cell is told to hand over to "PCI 147" and has two different candidates that match.
 *   <li><b>Mod-3</b> -- two nearby same-band cells share {@code pci % 3}. Their reference signals
 *       land on the same subcarriers and interfere. A warning, not an error: it degrades quality
 *       rather than breaking identification.
 * </ul>
 */
public record PciConflict(
        Type type,
        String bandId,
        long cellIdA, int ax, int ay, int az, int pciA,
        long cellIdB, int bx, int by, int bz, int pciB,
        /** For {@link Type#CONFUSION}, the third cell that sees both. {@link ReceiverState#NO_CELL} otherwise. */
        long viaCellId,
        double separationBlocks
) {

    public enum Type {
        COLLISION(Severity.ERROR),
        CONFUSION(Severity.ERROR),
        MOD3(Severity.WARNING);

        private final Severity severity;

        Type(Severity severity) {
            this.severity = severity;
        }

        public Severity severity() {
            return severity;
        }
    }

    public enum Severity {
        ERROR,
        WARNING
    }

    public Severity severity() {
        return type.severity();
    }

    /** One line, as shown in the antenna GUI and printed by {@code /rancraft pci check}. */
    public String describe() {
        String site = bx + ", " + by + ", " + bz;
        return switch (type) {
            case COLLISION -> "PCI " + pciA + " collides with site at " + site;
            case CONFUSION -> "PCI " + pciA + " is confused with site at " + site
                    + " (both neighbour the same cell)";
            case MOD3 -> "mod-3 conflict with PCI " + pciB + " at " + site;
        };
    }
}
