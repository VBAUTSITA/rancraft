package dev.rancraft.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.rancraft.item.LocatorWaypoints;
import dev.rancraft.item.LocatorWaypoints.Waypoint;
import dev.rancraft.net.LocatorFixPayload;
import dev.rancraft.rf.LocatorFix;
import dev.rancraft.rf.LocatorSolver;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The Network Locator's HUD text (Phase 3 slice 5, §3A.6): the spec's layout, its five states, and
 * that every number is the server's (or a saved estimate), only formatted.
 */
class LocatorHudTextTest {

    private static final String OVERWORLD = "minecraft:overworld";

    /** The spec's mock-up: FIX at x 1204, z -3391, on ground at y 87, ±9 m, HDOP 1.4. */
    private static final LocatorFix.Fix SPEC_FIX =
            new LocatorFix.Fix(1204.3, 87.0 + LocatorSolver.RECEIVER_EYE_HEIGHT, -3390.2, 1.4, 9.0, 4);

    private static LocatorFixPayload payload(LocatorFix fix, List<LocatorFixPayload.Ring> rings, double metersPerBlock,
                                             Optional<LocatorFixPayload.Emergency> emergency) {
        return new LocatorFixPayload(LocatorFixPayload.VERSION, 100L, fix, rings, metersPerBlock,
                14.99, rings.isEmpty() ? "" : "band_1800", 6.0, -100.0, emergency);
    }

    private static LocatorFixPayload payload(LocatorFix fix, int ringCount) {
        List<LocatorFixPayload.Ring> rings = new java.util.ArrayList<>();
        for (int i = 0; i < ringCount; i++) {
            rings.add(new LocatorFixPayload.Ring(100.5 + i, 90.5, -49.5, 40.0, "band_1800"));
        }
        return payload(fix, rings, 1.0, Optional.empty());
    }

    /** A waypoint {@code metres} from the spec's fix at compass bearing {@code degrees}. */
    private static Waypoint at(double degrees, double metres) {
        double east = metres * Math.sin(Math.toRadians(degrees));
        double north = metres * Math.cos(Math.toRadians(degrees));
        return new Waypoint(OVERWORLD, SPEC_FIX.x() + east, 80.0, SPEC_FIX.z() - north, 9.0);
    }

    private static List<String> texts(LocatorHudText.Screen screen) {
        return screen.lines().stream().map(LocatorHudText.Line::text).toList();
    }

    @Test
    @DisplayName("FIX reads as the spec's mock-up: estimate, ±, HDOP; cells and best resolution; waypoint distance and bearing")
    void specLayout() {
        LocatorWaypoints waypoints = new LocatorWaypoints(List.of(at(200, 50), at(47, 312), at(300, 80)), 1);
        LocatorHudText.Screen screen = LocatorHudText.screen(payload(SPEC_FIX, 4), false, waypoints, OVERWORLD);

        assertEquals("Network Locator", screen.title());
        assertEquals("FIX", screen.state());
        assertEquals(LocatorStyle.FIX_ARGB, screen.stateArgb());
        assertEquals(List.of(
                "Est  x 1204   z -3391   y ~87   ±9.0 m   HDOP 1.4",
                "Sites 4   best res 15 m (band_1800)",
                "WP 2/3 (saved ±9.0 m)   312 m   bearing 047°"), texts(screen));
    }

    @Test
    @DisplayName("metres follow the server's scale: 2 m per block doubles the ± and the waypoint distance")
    void scale() {
        LocatorWaypoints waypoints = new LocatorWaypoints(List.of(at(90, 100)), 0);
        LocatorHudText.Screen screen = LocatorHudText.screen(
                payload(SPEC_FIX, List.of(), 2.0, Optional.empty()), false, waypoints, OVERWORLD);
        assertEquals("Est  x 1204   z -3391   y ~87   ±18 m   HDOP 1.4", texts(screen).get(0));
        assertEquals("WP 1/1 (saved ±18 m)   200 m   bearing 090°", texts(screen).get(2));
    }

    @Test
    @DisplayName("POOR GEOMETRY names the HDOP in the state, and the solver's ceiling reads as 99.9 or worse")
    void poorGeometry() {
        LocatorHudText.Screen screen = LocatorHudText.screen(
                payload(new LocatorFix.PoorGeometry(11.2, 3), 3), false, LocatorWaypoints.EMPTY, OVERWORLD);
        assertEquals("POOR GEOMETRY (HDOP 11.2)", screen.state());
        assertEquals(LocatorStyle.PARTIAL_ARGB, screen.stateArgb());
        assertEquals("No position: the sites are too close to a line (limit HDOP 6.0)", texts(screen).get(0));
        assertEquals("Sites 3   best res 15 m (band_1800)", texts(screen).get(1));

        LocatorHudText.Screen singular = LocatorHudText.screen(
                payload(new LocatorFix.PoorGeometry(LocatorSolver.HDOP_CEILING, 3), 3), false,
                LocatorWaypoints.EMPTY, OVERWORLD);
        assertEquals("POOR GEOMETRY (HDOP 99.9+)", singular.state());
    }

    @Test
    @DisplayName("AMBIGUOUS lists both candidates and says which is likely, or that there is no preference")
    void ambiguous() {
        LocatorHudText.Screen likelyB = LocatorHudText.screen(
                payload(new LocatorFix.Ambiguous(10.2, 20.9, -4.5, 7.0, 1), 2), false, LocatorWaypoints.EMPTY, OVERWORLD);
        assertEquals("AMBIGUOUS", likelyB.state());
        assertEquals("A  x 10  z 20    or    B  x -5  z 7", texts(likelyB).get(0));
        assertEquals("Likely B: nearer the last estimate", texts(likelyB).get(1));
        assertEquals("Sites 2   best res 15 m (band_1800)", texts(likelyB).get(2));

        LocatorHudText.Screen none = LocatorHudText.screen(
                payload(new LocatorFix.Ambiguous(10.2, 20.9, -4.5, 7.0, LocatorFix.Ambiguous.NO_PREFERENCE), 2),
                false, LocatorWaypoints.EMPTY, OVERWORLD);
        assertEquals("No last estimate to choose between them", texts(none).get(1));
    }

    @Test
    @DisplayName("RANGE ONLY: a ring round one cell, and why (one cell, or two circles that do not meet)")
    void rangeOnly() {
        LocatorFix ring = new LocatorFix.RangeOnly(100.5, -49.5, 40.0);
        assertEquals("On a ring 40 m from the site at x 100  z -50  (one site heard)",
                texts(LocatorHudText.screen(payload(ring, 1), false, LocatorWaypoints.EMPTY, OVERWORLD)).get(0));
        LocatorHudText.Screen two = LocatorHudText.screen(payload(ring, 2), false, LocatorWaypoints.EMPTY, OVERWORLD);
        assertEquals("RANGE ONLY", two.state());
        assertEquals("On a ring 40 m from the site at x 100  z -50  (the two circles do not meet)", texts(two).get(0));
    }

    @Test
    @DisplayName("NO SIGNAL says what was missing; no payload or a stale one reads NO SIGNAL too")
    void noSignal() {
        LocatorHudText.Screen screen = LocatorHudText.screen(
                payload(new LocatorFix.NoSignal(), 0), false, LocatorWaypoints.EMPTY, OVERWORLD);
        assertEquals("NO SIGNAL", screen.state());
        assertEquals(LocatorStyle.NO_SIGNAL_ARGB, screen.stateArgb());
        assertEquals(List.of("No cell at or above -100 dBm to range to", "Sites 0",
                "WP -   sneak + use saves the estimate"), texts(screen));

        LocatorHudText.Screen nothing = LocatorHudText.screen(null, true, LocatorWaypoints.EMPTY, OVERWORLD);
        assertEquals("NO SIGNAL", nothing.state());
        assertEquals("No reading from the network yet", texts(nothing).get(0));

        LocatorHudText.Screen stale = LocatorHudText.screen(payload(SPEC_FIX, 4), true, LocatorWaypoints.EMPTY, OVERWORLD);
        assertEquals("NO SIGNAL", stale.state(), "an old FIX is not shown as current");
        assertFalse(texts(stale).get(0).startsWith("Est"));
    }

    @Test
    @DisplayName("no bearing without a FIX, and none to a waypoint saved in another dimension")
    void waypointGuards() {
        LocatorWaypoints here = new LocatorWaypoints(List.of(at(47, 312)), 0);
        LocatorHudText.Screen poor = LocatorHudText.screen(
                payload(new LocatorFix.PoorGeometry(11.2, 3), 3), false, here, OVERWORLD);
        assertEquals("WP 1/1 (saved ±9.0 m)   no FIX, no bearing", texts(poor).get(2));

        LocatorWaypoints nether = new LocatorWaypoints(
                List.of(new Waypoint("minecraft:the_nether", 0, 70, 0, 9.0)), 0);
        LocatorHudText.Screen elsewhere = LocatorHudText.screen(payload(SPEC_FIX, 4), false, nether, OVERWORLD);
        assertEquals("WP 1/1 (saved ±9.0 m)   in minecraft:the_nether", texts(elsewhere).get(2));

        // No payload at all: the server's scale is unknown, so the saved ± is left out.
        LocatorHudText.Screen stale = LocatorHudText.screen(null, true, here, OVERWORLD);
        assertEquals("WP 1/1   no FIX, no bearing", texts(stale).get(1));
    }

    /**
     * Phase 3A review, round 1: with no current reading the waypoint's saved ± used to be converted
     * at 1 m per block, so on a server at 2 m per block it read half what the save message said.
     * Now a stale payload still supplies the server's scale, and with no payload at all the ± is left
     * out rather than guessed.
     */
    @Test
    @DisplayName("no current reading: the waypoint's saved ± uses the last payload's scale, and is left out before any payload")
    void waypointScaleWithoutACurrentReading() {
        LocatorWaypoints nine = new LocatorWaypoints(List.of(new Waypoint(OVERWORLD, 0, 70, 0, 9.0)), 0);
        LocatorHudText.Screen staleNine = LocatorHudText.screen(
                payload(SPEC_FIX, List.of(), 2.0, Optional.empty()), true, nine, OVERWORLD);
        assertEquals("NO SIGNAL", staleNine.state());
        assertEquals("WP 1/1 (saved ±18 m)   no FIX, no bearing", texts(staleNine).get(1));

        LocatorWaypoints five = new LocatorWaypoints(List.of(new Waypoint(OVERWORLD, 0, 70, 0, 5.0)), 0);
        LocatorHudText.Screen staleScaled = LocatorHudText.screen(
                payload(SPEC_FIX, List.of(), 2.0, Optional.empty()), true, five, OVERWORLD);
        assertEquals("WP 1/1 (saved ±10 m)   no FIX, no bearing", texts(staleScaled).get(1));

        LocatorHudText.Screen none = LocatorHudText.screen(null, true, nine, OVERWORLD);
        assertEquals("WP 1/1   no FIX, no bearing", texts(none).get(1));
        assertFalse(texts(none).get(1).contains(" m"), "no metres value from an assumed scale");
        assertFalse(texts(none).get(1).contains("±"));

        // A scale that makes no sense is treated as unknown too, never printed as "? m".
        assertEquals("WP 1/1   no FIX, no bearing",
                LocatorHudText.waypointLine(nine, null, OVERWORLD, 0.0).text());
        assertEquals("WP 1/1   in minecraft:the_nether", LocatorHudText.waypointLine(
                new LocatorWaypoints(List.of(new Waypoint("minecraft:the_nether", 0, 70, 0, 9.0)), 0),
                null, OVERWORLD, Double.NaN).text());
    }

    @Test
    @DisplayName("the emergency record shows the frozen estimate and how long before death it was taken")
    void emergency() {
        LocatorFixPayload.Emergency frozen = new LocatorFixPayload.Emergency(OVERWORLD,
                500.7, 70.0 + LocatorSolver.RECEIVER_EYE_HEIGHT, -20.2, 12.0, 1_000L, 1_600L);
        LocatorHudText.Screen screen = LocatorHudText.screen(
                payload(new LocatorFix.NoSignal(), List.of(), 1.0, Optional.of(frozen)), false,
                LocatorWaypoints.EMPTY, OVERWORLD);
        LocatorHudText.Line last = screen.lines().get(screen.lines().size() - 1);
        assertEquals("Last fix before death: x 500  z -21  y ~70  ±12 m, 30 s before (minecraft:overworld)", last.text());
        assertEquals(LocatorHudText.EMERGENCY_ARGB, last.argb());

        LocatorHudText.Screen without = LocatorHudText.screen(payload(SPEC_FIX, 4), false, LocatorWaypoints.EMPTY, OVERWORLD);
        assertTrue(texts(without).stream().noneMatch(text -> text.startsWith("Last fix")));
    }

    @Test
    @DisplayName("metres: one decimal below 10 so a sub-metre ± does not read 0, whole metres above; nonsense is '?'")
    void metres() {
        assertEquals("0.9 m", LocatorHudText.metres(0.865));
        assertEquals("8.7 m", LocatorHudText.metres(8.66));
        assertEquals("15 m", LocatorHudText.metres(14.99));
        assertEquals("312 m", LocatorHudText.metres(312.2));
        assertEquals("? m", LocatorHudText.metres(-1.0));
        assertEquals("? m", LocatorHudText.metres(Double.NaN));
        assertEquals("? m", LocatorHudText.metres(Double.POSITIVE_INFINITY));
    }

    @Test
    @DisplayName("coordinates are the block the estimate falls in (floor, as F3), y the assumed surface")
    void coordinates() {
        assertEquals(-1L, LocatorHudText.block(-0.2));
        assertEquals(12L, LocatorHudText.block(12.99));
        assertEquals(64L, LocatorHudText.surface(64.0 + LocatorSolver.RECEIVER_EYE_HEIGHT));
    }
}
