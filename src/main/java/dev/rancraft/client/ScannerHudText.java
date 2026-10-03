package dev.rancraft.client;

import dev.rancraft.net.ScannerPayload;
import dev.rancraft.util.Navigation;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Function;

/**
 * The Proximity Scanner's HUD text (Phase 3 slice 14, §3C.4): a list, no world render.
 *
 * <pre>
 * Proximity Scanner                 3 within 24
 * via band_3500 (tier 3)
 * ↗  Creeper        7 blocks   NE 047°
 * ↓  Spider        12 blocks   S 178°
 * </pre>
 *
 * or, when the scanner is off, the reason, worded so the player fixes the right thing:
 *
 * <pre>
 * Proximity Scanner                         OFF
 * needs tier 3, you're on band_1800 (tier 2)
 * change the band: the feed needs a high-capacity one
 * </pre>
 *
 * <p><b>Every value is the server's</b> ({@link ScannerPayload}): the status, the levels, the band and its
 * tier, the range and each contact's type, distance and compass bearing. The one thing worked out here is
 * the arrow: the server's bearing turned relative to where the camera faces now, so "behind you" reads at a
 * glance. That is a compass needle, not detection: it moves nothing on the list and adds nothing to it. No
 * Minecraft types (the type ids are named through a function the overlay supplies), so the layout is
 * unit-tested headless.
 */
public final class ScannerHudText {

    private ScannerHudText() {
    }

    public static final String TITLE = "Proximity Scanner";

    public static final int TEXT_ARGB = 0xFFFFFFFF;
    public static final int DIM_ARGB = 0xFFBBBBBB;
    /** The meter's warning colour. */
    public static final int OFF_ARGB = 0xFFFFAA00;
    /** A hostile mob: the list's own colour. */
    public static final int CONTACT_ARGB = 0xFFFF7777;
    public static final int OK_ARGB = 0xFF55FF55;

    /** Relative directions, clockwise from straight ahead: ahead, ahead-right, right, ... ahead-left. */
    public static final String[] ARROWS = {"↑", "↗", "→", "↘", "↓", "↙", "←", "↖"};
    /** The same directions in ASCII, for a font without the arrows. */
    public static final String[] ASCII_ARROWS = {"^", "^>", ">", "v>", "v", "<v", "<", "<^"};

    private static final String[] COMPASS = {"N", "NE", "E", "SE", "S", "SW", "W", "NW"};

    /** One HUD message and its colour (0xAARRGGBB). */
    public record Line(String text, int argb) {
    }

    /**
     * One hostile mob, as columns: its name, its distance, its compass direction, and which way to look
     * ({@code arrow}, an index into {@link #ARROWS}, 0 straight ahead, clockwise).
     */
    public record Row(String name, String distance, String direction, int arrow) {
    }

    /**
     * What the HUD draws: the title, the state (drawn right-aligned on the title line), the messages and
     * then the rows.
     */
    public record Screen(String title, String state, int stateArgb, List<Line> lines, List<Row> rows) {
        public Screen {
            lines = List.copyOf(lines);
            rows = List.copyOf(rows);
        }
    }

    /**
     * The HUD for one frame.
     *
     * @param payload    the last {@link ScannerPayload}, or {@code null} before the first (or since it was
     *                   dropped, {@link ClientScannerState#putAway()}).
     * @param stale      too old to show as current ({@link ClientScannerState#isStale}).
     * @param names      an entity type id's display name (the overlay's registry lookup).
     * @param cameraYRot the camera's yaw, Minecraft's {@code yRot} (0 south, 90 west), for the arrows.
     */
    public static Screen screen(ScannerPayload payload, boolean stale, Function<String, String> names,
                                float cameraYRot) {
        List<Line> lines = new ArrayList<>(2);
        if (payload == null || stale) {
            lines.add(new Line("No reading from the network yet", DIM_ARGB));
            return new Screen(TITLE, "NO DATA", DIM_ARGB, lines, List.of());
        }
        if (payload.status() != ScannerPayload.Status.OK) {
            lines.add(new Line(refusal(payload), OFF_ARGB));
            lines.add(new Line(hint(payload), DIM_ARGB));
            return new Screen(TITLE, "OFF", OFF_ARGB, lines, List.of());
        }

        String range = blocks(payload.rangeBlocks());
        lines.add(new Line(String.format(Locale.ROOT, "via %s (tier %d)",
                payload.servingBandId(), payload.servingBandTier()), DIM_ARGB));
        List<Row> rows = new ArrayList<>(payload.contacts().size());
        for (ScannerPayload.Contact contact : payload.contacts()) {
            String name = names.apply(contact.typeId());
            rows.add(new Row(name == null || name.isEmpty() ? contact.typeId() : name,
                    distance(contact.distanceBlocks()),
                    String.format(Locale.ROOT, "%s %03d°", compassPoint(contact.bearingDegrees()),
                            Navigation.wholeDegrees(contact.bearingDegrees())),
                    relativeArrow(contact.bearingDegrees(), cameraYRot)));
        }
        if (rows.isEmpty()) {
            lines.add(new Line("No hostiles within " + range + " blocks", DIM_ARGB));
        }
        String state = String.format(Locale.ROOT, "%d within %s", rows.size(), range);
        return new Screen(TITLE, state, OK_ARGB, lines, rows);
    }

    /**
     * Why the scanner is off, in the player's words. Each reason names its own fix, so a player on
     * band_1800 at EXCELLENT reads "needs tier 3", not "no signal": §3C "refuses on band_1800 with
     * 'needs tier 3'".
     */
    public static String refusal(ScannerPayload payload) {
        String needed = payload.neededLevel().label();
        return switch (payload.status()) {
            case OK -> "";
            case NO_SERVICE -> "no service";
            case WEAK_SIGNAL -> String.format(Locale.ROOT, "signal too weak: %s, needs %s",
                    payload.radioLevel().label(), needed);
            case BACKHAUL_LIMITED -> String.format(Locale.ROOT, "backhaul limited: the serving cell is capped at %s, needs %s",
                    payload.serviceCap().label(), needed);
            case LOW_TIER -> String.format(Locale.ROOT, "needs tier %d, you're on %s (tier %d)",
                    payload.neededTier(), payload.servingBandId(), payload.servingBandTier());
        };
    }

    /**
     * The fix each reason calls for, as a dim second line. The verdict checks the signal before the band
     * ({@code DeviceRequirement}: LOW_QUALITY comes before LOW_TIER), so a weak signal on a band below the
     * tier says that the band will need changing too, from the server's own band tier, rather than leave
     * "needs tier 3" as a surprise once the signal is fixed.
     */
    static String hint(ScannerPayload payload) {
        return switch (payload.status()) {
            case OK -> "";
            case NO_SERVICE -> "no cell serves you here";
            case WEAK_SIGNAL -> payload.servingBandTier() < payload.neededTier()
                    ? String.format(Locale.ROOT, "fix the signal, then the band: needs tier %d, %s is tier %d",
                            payload.neededTier(), payload.servingBandId(), payload.servingBandTier())
                    : "fix the signal: move closer, retilt, clear the path";
            case BACKHAUL_LIMITED -> "fix the backhaul, not the antenna";
            case LOW_TIER -> "change the band: the feed needs a high-capacity one";
        };
    }

    /** One of the eight compass points for a bearing: N for 337.5 up to 22.5, NE from there, and so on. */
    public static String compassPoint(double bearingDegrees) {
        return COMPASS[octant(bearingDegrees)];
    }

    /**
     * The camera's compass heading from Minecraft's yaw: {@code yRot} 0 faces south (+z), 90 west, 180
     * north, 270 east; the compass heading is {@code yRot + 180}, on [0, 360).
     */
    public static double cameraHeadingDegrees(float cameraYRot) {
        double heading = ((double) cameraYRot + 180.0) % 360.0;
        return heading < 0.0 ? heading + 360.0 : heading;
    }

    /**
     * Which way to look for something at {@code bearingDegrees}, relative to the camera: an index into
     * {@link #ARROWS}, 0 straight ahead, 2 to the right, 4 behind, 6 to the left.
     */
    public static int relativeArrow(double bearingDegrees, float cameraYRot) {
        return octant(bearingDegrees - cameraHeadingDegrees(cameraYRot));
    }

    /** The 45-degree sector an angle falls in, 0 centred on 0 and clockwise; any finite angle. */
    private static int octant(double degrees) {
        if (!Double.isFinite(degrees)) {
            return 0;
        }
        double wrapped = degrees % 360.0;
        if (wrapped < 0.0) {
            wrapped += 360.0;
        }
        return (int) Math.floor((wrapped + 22.5) / 45.0) % 8;
    }

    /** {@code 7 blocks}, {@code 1 block}; whole blocks, as the list needs no more. */
    static String distance(float blocks) {
        long whole = Math.round(blocks);
        return whole == 1 ? "1 block" : whole + " blocks";
    }

    /** {@code 24} for a whole range, {@code 12.5} otherwise. */
    static String blocks(float value) {
        return value == Math.rint(value)
                ? String.format(Locale.ROOT, "%.0f", value)
                : String.format(Locale.ROOT, "%.1f", value);
    }
}
