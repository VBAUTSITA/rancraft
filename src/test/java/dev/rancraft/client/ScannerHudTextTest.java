package dev.rancraft.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.rancraft.net.ScannerPayload;
import dev.rancraft.net.ScannerPayload.Status;
import dev.rancraft.rf.DeviceRequirement;
import dev.rancraft.rf.ServiceLevel;
import dev.rancraft.util.ProximityScan;
import java.util.List;
import java.util.function.Function;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The Proximity Scanner's HUD text (Phase 3 slice 14, §3C.4), headless: each refusal names its own fix
 * ("needs tier 3" on band_1800, as §3C's done-when words it), the list shows the server's values in its
 * order, and the arrows turn the server's compass bearing relative to the camera and nothing else.
 */
class ScannerHudTextTest {

    private static final DeviceRequirement GOOD_TIER_3 = new DeviceRequirement(ServiceLevel.GOOD, 3);
    private static final Function<String, String> NAMES = id -> switch (id) {
        case "minecraft:creeper" -> "Creeper";
        case "minecraft:spider" -> "Spider";
        default -> null;
    };
    /** Facing north: Minecraft's yaw 180. */
    private static final float NORTH = 180.0f;

    private static ScannerPayload refusal(Status status, ServiceLevel radio, ServiceLevel cap, String band, int tier) {
        return ScannerPayload.of(status, GOOD_TIER_3, radio, cap, band, tier, 24.0, List.of());
    }

    private static ScannerPayload ok(List<ProximityScan.Contact> contacts) {
        return ScannerPayload.of(Status.OK, GOOD_TIER_3, ServiceLevel.GOOD, ServiceLevel.EXCELLENT, "band_3500", 3, 24.0,
                contacts);
    }

    @Test
    @DisplayName("§3C done-when: on band_1800 at a good signal the scanner refuses with \"needs tier 3\"")
    void needsTier3OnBand1800() {
        ScannerPayload payload = refusal(Status.LOW_TIER, ServiceLevel.EXCELLENT, ServiceLevel.EXCELLENT, "band_1800", 2);
        assertEquals("needs tier 3, you're on band_1800 (tier 2)", ScannerHudText.refusal(payload));
        ScannerHudText.Screen screen = ScannerHudText.screen(payload, false, NAMES, NORTH);
        assertEquals("OFF", screen.state());
        assertEquals(ScannerHudText.OFF_ARGB, screen.stateArgb());
        assertEquals(List.of("needs tier 3, you're on band_1800 (tier 2)",
                        "change the band: the feed needs a high-capacity one"),
                screen.lines().stream().map(ScannerHudText.Line::text).toList());
        assertTrue(screen.rows().isEmpty());
    }

    @Test
    @DisplayName("each verdict reads differently: no service, a weak signal, a backhaul cap, a low tier")
    void verdictsAreTold() {
        assertEquals("no service",
                ScannerHudText.refusal(refusal(Status.NO_SERVICE, ServiceLevel.NONE, ServiceLevel.EXCELLENT, "", 0)));
        assertEquals("signal too weak: FAIR, needs GOOD",
                ScannerHudText.refusal(refusal(Status.WEAK_SIGNAL, ServiceLevel.FAIR, ServiceLevel.EXCELLENT, "band_3500", 3)));
        assertEquals("backhaul limited: the serving cell is capped at FAIR, needs GOOD",
                ScannerHudText.refusal(refusal(Status.BACKHAUL_LIMITED, ServiceLevel.EXCELLENT, ServiceLevel.FAIR, "band_3500", 3)));

        assertEquals("fix the signal: move closer, retilt, clear the path",
                ScannerHudText.hint(refusal(Status.WEAK_SIGNAL, ServiceLevel.FAIR, ServiceLevel.EXCELLENT, "band_3500", 3)));
        assertEquals("fix the signal, then the band: needs tier 3, band_1800 is tier 2",
                ScannerHudText.hint(refusal(Status.WEAK_SIGNAL, ServiceLevel.FAIR, ServiceLevel.EXCELLENT, "band_1800", 2)),
                "the signal is judged first, so a weak band_1800 also says the band will need changing");
        assertEquals("fix the backhaul, not the antenna",
                ScannerHudText.hint(refusal(Status.BACKHAUL_LIMITED, ServiceLevel.EXCELLENT, ServiceLevel.FAIR, "band_3500", 3)));
        assertEquals("no cell serves you here",
                ScannerHudText.hint(refusal(Status.NO_SERVICE, ServiceLevel.NONE, ServiceLevel.EXCELLENT, "", 0)));
    }

    @Test
    @DisplayName("no payload, or a stale one, shows no data and no list")
    void noData() {
        ScannerPayload payload = ok(List.of(new ProximityScan.Contact("minecraft:creeper", 5.0, 0.0)));
        for (ScannerHudText.Screen screen : List.of(ScannerHudText.screen(null, false, NAMES, NORTH),
                ScannerHudText.screen(payload, true, NAMES, NORTH))) {
            assertEquals("NO DATA", screen.state());
            assertTrue(screen.rows().isEmpty(), "a stale list is of mobs that were near, not are");
        }
    }

    @Test
    @DisplayName("an OK list: the server's order, names, whole blocks, compass point and degrees; the count and range")
    void okList() {
        ScannerHudText.Screen screen = ScannerHudText.screen(ok(List.of(
                new ProximityScan.Contact("minecraft:creeper", 7.3, 47.4),
                new ProximityScan.Contact("minecraft:spider", 1.2, 178.0),
                new ProximityScan.Contact("minecraft:unknown_mob", 12.6, 359.7))), false, NAMES, NORTH);
        assertEquals("3 within 24", screen.state());
        assertEquals(ScannerHudText.OK_ARGB, screen.stateArgb());
        assertEquals(List.of("via band_3500 (tier 3)"), screen.lines().stream().map(ScannerHudText.Line::text).toList());
        assertEquals(List.of(
                        new ScannerHudText.Row("Creeper", "7 blocks", "NE 047°", 1),
                        new ScannerHudText.Row("Spider", "1 block", "S 178°", 4),
                        new ScannerHudText.Row("minecraft:unknown_mob", "13 blocks", "N 000°", 0)),
                screen.rows(), "nothing re-sorted or filtered; an unknown type shows its id");

        ScannerHudText.Screen empty = ScannerHudText.screen(ok(List.of()), false, NAMES, NORTH);
        assertEquals("0 within 24", empty.state());
        assertEquals("No hostiles within 24 blocks", empty.lines().get(1).text());
    }

    @Test
    @DisplayName("compass points: eight sectors centred on N, NE, E ...")
    void compassPoints() {
        assertEquals("N", ScannerHudText.compassPoint(0.0));
        assertEquals("N", ScannerHudText.compassPoint(22.4));
        assertEquals("NE", ScannerHudText.compassPoint(22.5));
        assertEquals("E", ScannerHudText.compassPoint(90.0));
        assertEquals("S", ScannerHudText.compassPoint(180.0));
        assertEquals("W", ScannerHudText.compassPoint(270.0));
        assertEquals("NW", ScannerHudText.compassPoint(337.4));
        assertEquals("N", ScannerHudText.compassPoint(337.5));
        assertEquals("N", ScannerHudText.compassPoint(Double.NaN));
    }

    @Test
    @DisplayName("the arrow is the server's bearing turned to the camera: Minecraft yaw 0 faces south, 90 west")
    void relativeArrows() {
        assertEquals(180.0, ScannerHudText.cameraHeadingDegrees(0.0f), 1e-9, "yaw 0 faces south");
        assertEquals(270.0, ScannerHudText.cameraHeadingDegrees(90.0f), 1e-9, "yaw 90 faces west");
        assertEquals(90.0, ScannerHudText.cameraHeadingDegrees(-90.0f), 1e-9, "yaw -90 faces east");
        assertEquals(0.0, ScannerHudText.cameraHeadingDegrees(540.0f), 1e-9, "any wrap of the yaw");

        // Facing north: a mob to the east is to the right, one to the south behind.
        assertEquals(0, ScannerHudText.relativeArrow(0.0, NORTH));
        assertEquals(2, ScannerHudText.relativeArrow(90.0, NORTH));
        assertEquals(4, ScannerHudText.relativeArrow(180.0, NORTH));
        assertEquals(6, ScannerHudText.relativeArrow(270.0, NORTH));
        // Facing east (yaw -90): the same mob to the east is ahead, one to the north on the left.
        assertEquals(0, ScannerHudText.relativeArrow(90.0, -90.0f));
        assertEquals(6, ScannerHudText.relativeArrow(0.0, -90.0f));
        assertEquals(7, ScannerHudText.relativeArrow(45.0, -90.0f), "north-east, from facing east: ahead-left");
        assertEquals(ScannerHudText.ARROWS.length, ScannerHudText.ASCII_ARROWS.length);
    }

    @Test
    @DisplayName("distances in whole blocks; a fractional range keeps one decimal")
    void numbers() {
        assertEquals("0 blocks", ScannerHudText.distance(0.4f));
        assertEquals("1 block", ScannerHudText.distance(1.4f));
        assertEquals("24 blocks", ScannerHudText.distance(23.5f));
        assertEquals("24", ScannerHudText.blocks(24.0f));
        assertEquals("12.5", ScannerHudText.blocks(12.5f));
    }
}
