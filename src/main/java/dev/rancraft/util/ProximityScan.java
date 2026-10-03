package dev.rancraft.util;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * The Proximity Scanner's list (Phase 3 slice 14, §3C.4): which of the things around a point are within
 * range, how far away, and which way, nearest first, at most a cap.
 *
 * <p><b>Not RF, and not a sensing model.</b> Nothing here propagates, attenuates or detects anything: it
 * is plain geometry over positions the server already knows (the mobs' own coordinates), the way a map
 * and a ruler would measure them. What makes the list arrive at all is the network (the scanner works
 * only on GOOD service from a tier-3 band; {@code device.ProximityScanner}); the list itself stands in
 * for a high-rate sensor feed and is labelled so at the code site that uses it. See NOTES.md, Phase 3
 * slice 14.
 *
 * <p>Distances are straight-line (3D) in blocks, inclusive of the range: a candidate exactly at the
 * range is in. Bearings are compass bearings on the horizontal plane, by {@link Navigation}: 0 north
 * (-z), 90 east (+x), clockwise; a candidate straight above or below has bearing 0.
 *
 * <p>Pure (no Minecraft types), so it is unit-tested headless; {@code PackagePurityTest} checks it.
 */
public final class ProximityScan {

    private ProximityScan() {
    }

    /** One thing near the scanner: its type (a registry id, e.g. {@code minecraft:creeper}) and position. */
    public record Candidate(String typeId, double x, double y, double z) {
        public Candidate {
            Objects.requireNonNull(typeId, "typeId");
        }
    }

    /**
     * One entry of the list.
     *
     * @param typeId         the candidate's type, unchanged.
     * @param distanceBlocks straight-line distance from the scanner, in blocks, at most the range.
     * @param bearingDegrees compass bearing from the scanner on [0, 360).
     */
    public record Contact(String typeId, double distanceBlocks, double bearingDegrees) {
        public Contact {
            Objects.requireNonNull(typeId, "typeId");
        }
    }

    /**
     * The candidates within {@code rangeBlocks} of {@code (fromX, fromY, fromZ)}, nearest first, at most
     * {@code maxContacts}. Equal distances keep the candidates' order (the sort is stable), so the same
     * input always gives the same list. A candidate with a non-finite coordinate is left out, as is
     * everything when the range is not a finite non-negative number or the cap is not positive.
     */
    public static List<Contact> nearest(double fromX, double fromY, double fromZ,
                                        List<Candidate> candidates, double rangeBlocks, int maxContacts) {
        if (maxContacts <= 0 || !Double.isFinite(rangeBlocks) || rangeBlocks < 0.0
                || !Double.isFinite(fromX) || !Double.isFinite(fromY) || !Double.isFinite(fromZ)) {
            return List.of();
        }
        double rangeSq = rangeBlocks * rangeBlocks;
        List<Measured> inRange = new ArrayList<>();
        for (Candidate candidate : candidates) {
            if (!Double.isFinite(candidate.x()) || !Double.isFinite(candidate.y()) || !Double.isFinite(candidate.z())) {
                continue;
            }
            double dx = candidate.x() - fromX;
            double dy = candidate.y() - fromY;
            double dz = candidate.z() - fromZ;
            double distanceSq = dx * dx + dy * dy + dz * dz;
            if (distanceSq <= rangeSq) {
                inRange.add(new Measured(candidate, distanceSq));
            }
        }
        inRange.sort(Comparator.comparingDouble(Measured::distanceSq));
        int count = Math.min(maxContacts, inRange.size());
        List<Contact> contacts = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            Candidate candidate = inRange.get(i).candidate();
            contacts.add(new Contact(candidate.typeId(), Math.sqrt(inRange.get(i).distanceSq()),
                    Navigation.bearingDegrees(fromX, fromZ, candidate.x(), candidate.z())));
        }
        return List.copyOf(contacts);
    }

    private record Measured(Candidate candidate, double distanceSq) {
    }
}
