package dev.rancraft.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.rancraft.util.ProximityScan.Candidate;
import dev.rancraft.util.ProximityScan.Contact;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The Proximity Scanner's list (Phase 3 slice 14, §3C.4), headless: in range (inclusive, straight line),
 * nearest first, at most the cap, with compass bearings, deterministic on ties, and nothing non-finite.
 */
class ProximityScanTest {

    private static final double EPS = 1e-9;

    @Test
    @DisplayName("within the range, straight-line and inclusive: 24 is in, 24.001 and a far one are out")
    void inclusiveRange() {
        List<Candidate> candidates = List.of(
                new Candidate("minecraft:creeper", 24.0, 0.0, 0.0),
                new Candidate("minecraft:spider", 0.0, 0.0, 24.001),
                new Candidate("minecraft:witch", 12.0, 16.0, 0.0),   // 20 in 3D
                new Candidate("minecraft:slime", 0.0, 30.0, 0.0));   // straight above, out of range
        List<Contact> contacts = ProximityScan.nearest(0.0, 0.0, 0.0, candidates, 24.0, 16);
        assertEquals(2, contacts.size());
        assertEquals("minecraft:witch", contacts.get(0).typeId());
        assertEquals(20.0, contacts.get(0).distanceBlocks(), EPS);
        assertEquals("minecraft:creeper", contacts.get(1).typeId());
        assertEquals(24.0, contacts.get(1).distanceBlocks(), EPS);
    }

    @Test
    @DisplayName("nearest first, cut at the cap: 20 in range, the 16 nearest kept")
    void nearestFirstAndCapped() {
        List<Candidate> candidates = new ArrayList<>();
        for (int d = 20; d >= 1; d--) {
            candidates.add(new Candidate("minecraft:zombie_" + d, 100.0 + d, 64.0, -50.0));
        }
        List<Contact> contacts = ProximityScan.nearest(100.0, 64.0, -50.0, candidates, 24.0, 16);
        assertEquals(16, contacts.size());
        for (int i = 0; i < contacts.size(); i++) {
            assertEquals("minecraft:zombie_" + (i + 1), contacts.get(i).typeId());
            assertEquals(i + 1.0, contacts.get(i).distanceBlocks(), EPS);
        }
    }

    @Test
    @DisplayName("compass bearings: 0 north (-z), 90 east (+x), 180 south, 270 west; straight above is 0")
    void bearings() {
        List<Candidate> candidates = List.of(
                new Candidate("n", 5.0, 0.0, 4.0),
                new Candidate("e", 7.0, 0.0, 5.0),
                new Candidate("s", 5.0, 0.0, 8.0),
                new Candidate("w", 1.0, 0.0, 5.0),
                new Candidate("up", 5.0, 9.0, 5.0),
                new Candidate("ne", 11.0, 0.0, -1.0));
        List<Contact> contacts = ProximityScan.nearest(5.0, 0.0, 5.0, candidates, 24.0, 16);
        assertEquals(List.of("n", "e", "s", "w", "ne", "up"), contacts.stream().map(Contact::typeId).toList());
        assertEquals(0.0, bearingOf(contacts, "n"), EPS);
        assertEquals(90.0, bearingOf(contacts, "e"), EPS);
        assertEquals(180.0, bearingOf(contacts, "s"), EPS);
        assertEquals(270.0, bearingOf(contacts, "w"), EPS);
        assertEquals(45.0, bearingOf(contacts, "ne"), EPS);
        assertEquals(0.0, bearingOf(contacts, "up"), EPS);
        for (Contact contact : contacts) {
            assertTrue(contact.bearingDegrees() >= 0.0 && contact.bearingDegrees() < 360.0);
        }
    }

    @Test
    @DisplayName("equal distances keep the input order: the same mobs always give the same list")
    void stableOnTies() {
        List<Candidate> candidates = List.of(
                new Candidate("b", 3.0, 0.0, 0.0),
                new Candidate("a", -3.0, 0.0, 0.0),
                new Candidate("c", 0.0, 3.0, 0.0),
                new Candidate("near", 0.0, 0.0, 1.0));
        assertEquals(List.of("near", "b", "a", "c"),
                ProximityScan.nearest(0.0, 0.0, 0.0, candidates, 24.0, 16).stream().map(Contact::typeId).toList());
    }

    @Test
    @DisplayName("non-finite candidates are left out; a bad range, origin or cap lists nothing")
    void nonFinite() {
        List<Candidate> candidates = List.of(
                new Candidate("nan", Double.NaN, 0.0, 0.0),
                new Candidate("inf", 0.0, Double.POSITIVE_INFINITY, 0.0),
                new Candidate("ok", 1.0, 0.0, 0.0));
        assertEquals(List.of("ok"),
                ProximityScan.nearest(0.0, 0.0, 0.0, candidates, 24.0, 16).stream().map(Contact::typeId).toList());
        assertEquals(List.of("ok"),
                ProximityScan.nearest(0.0, 0.0, 0.0, candidates, 1e200, 16).stream().map(Contact::typeId).toList(),
                "a range whose square overflows still leaves the infinite one out");
        assertTrue(ProximityScan.nearest(0.0, 0.0, 0.0, candidates, Double.NaN, 16).isEmpty());
        assertTrue(ProximityScan.nearest(0.0, 0.0, 0.0, candidates, -1.0, 16).isEmpty());
        assertTrue(ProximityScan.nearest(Double.NaN, 0.0, 0.0, candidates, 24.0, 16).isEmpty());
        assertTrue(ProximityScan.nearest(0.0, 0.0, 0.0, candidates, 24.0, 0).isEmpty());
        assertTrue(ProximityScan.nearest(0.0, 0.0, 0.0, List.of(), 24.0, 16).isEmpty());
    }

    private static double bearingOf(List<Contact> contacts, String type) {
        return contacts.stream().filter(c -> c.typeId().equals(type)).findFirst().orElseThrow().bearingDegrees();
    }
}
