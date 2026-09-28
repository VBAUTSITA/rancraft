package dev.rancraft.client;

import dev.rancraft.item.LocatorWaypoints;
import dev.rancraft.net.LocatorFixPayload;
import dev.rancraft.net.LocatorFixPayload.Kind;
import dev.rancraft.rf.LocatorFix;
import dev.rancraft.rf.LocatorSolver;
import dev.rancraft.util.Navigation;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * The Network Locator's HUD text (Phase 3 slice 5, §3A.6), laid out as the spec draws it:
 *
 * <pre>
 * Network Locator                              FIX
 * Est  x 1204   z -3391   y ~87   ±9.0 m   HDOP 1.4
 * Cells 4   best res 15 m (band_1800)
 * WP 2/3 (saved ±9.0 m)   312 m   bearing 047°
 * </pre>
 *
 * with one more line, "Last fix before death ...", while the player has an emergency record.
 *
 * <p><b>Every number is a server value</b> ({@link LocatorFixPayload}) or a saved one (the waypoint,
 * itself an earlier server estimate). The only arithmetic is unit conversion (blocks to metres at
 * the server's scale) and the map arithmetic between the estimate and a waypoint
 * ({@link Navigation}). No position, range or uncertainty is worked out here. No Minecraft types, so
 * the layout is unit-tested headless.
 *
 * <p>Coordinates are shown as the block the estimate falls in (floor, as the F3 screen's "Block"
 * line), so the two can be compared. {@code y} is prefixed "~" because it is not measured: altitude
 * aiding assumes you stand on the top surface ({@link LocatorSolver}), and the HUD shows that
 * surface (the fix's assumed eye height minus 1.62).
 *
 * <p>The "±" is the Locator's reported uncertainty (HDOP x rms sigma): quantisation only. It is not
 * a guarantee: the NLOS bias behind terrain is systematic and not in it. And quantisation is
 * deterministic: standing still, the same ranges round the same way, so the error is fixed, not
 * noise that averages out.
 */
public final class LocatorHudText {

    private LocatorHudText() {
    }

    public static final String TITLE = "Network Locator";

    public static final int TEXT_ARGB = 0xFFFFFFFF;
    public static final int DIM_ARGB = 0xFFBBBBBB;
    public static final int EMERGENCY_ARGB = 0xFFFF7777;

    /** Game ticks per second, for "how long before death". */
    private static final double TICKS_PER_SECOND = 20.0;

    /** One HUD line and its colour (0xAARRGGBB). */
    public record Line(String text, int argb) {
    }

    /**
     * What the HUD draws: the title, the state (drawn right-aligned on the title line) and the lines
     * under it.
     */
    public record Screen(String title, String state, int stateArgb, List<Line> lines) {
        public Screen {
            lines = List.copyOf(lines);
        }
    }

    /**
     * The HUD for one frame.
     *
     * @param payload   the last {@link LocatorFixPayload}, or {@code null} before the first.
     * @param stale     the payload is too old to show as current ({@link ClientLocatorState#isStale}).
     * @param waypoints the held Locator's saved waypoints.
     * @param dimension the dimension the player is in, e.g. {@code minecraft:overworld}.
     */
    public static Screen screen(LocatorFixPayload payload, boolean stale, LocatorWaypoints waypoints, String dimension) {
        List<Line> lines = new ArrayList<>(5);
        if (payload == null || stale) {
            lines.add(new Line("No reading from the network yet", DIM_ARGB));
            lines.add(waypointLine(waypoints, null, dimension, 1.0));
            return new Screen(TITLE, Kind.NO_SIGNAL.label(), LocatorStyle.stateArgb(Kind.NO_SIGNAL), lines);
        }

        double mpb = payload.metersPerBlock();
        LocatorFix fix = payload.fix();
        Kind kind = payload.kind();
        String state = kind.label();
        switch (fix) {
            case LocatorFix.Fix position -> lines.add(new Line(String.format(Locale.ROOT,
                    "Est  x %d   z %d   y ~%d   ±%s   HDOP %.1f",
                    block(position.x()), block(position.z()), surface(position.y()),
                    metres(position.errorBlocks() * mpb), position.hdop()), TEXT_ARGB));
            case LocatorFix.Ambiguous two -> {
                lines.add(new Line(String.format(Locale.ROOT, "A  x %d  z %d    or    B  x %d  z %d",
                        block(two.ax()), block(two.az()), block(two.bx()), block(two.bz())), TEXT_ARGB));
                lines.add(new Line(switch (two.likely()) {
                    case 0 -> "Likely A: nearer the last estimate";
                    case 1 -> "Likely B: nearer the last estimate";
                    default -> "No last estimate to choose between them";
                }, DIM_ARGB));
            }
            case LocatorFix.RangeOnly ring -> lines.add(new Line(String.format(Locale.ROOT,
                    "On a ring %s from the cell at x %d  z %d  (%s)",
                    metres(ring.radius() * mpb), block(ring.cx()), block(ring.cz()),
                    payload.rings().size() <= 1 ? "one cell heard" : "the two circles do not meet"), TEXT_ARGB));
            case LocatorFix.PoorGeometry poor -> {
                state = String.format(Locale.ROOT, "%s (HDOP %s)", state, hdop(poor.hdop()));
                lines.add(new Line(String.format(Locale.ROOT,
                        "No position: the cells are too close to a line (limit HDOP %.1f)", payload.maxHdop()),
                        TEXT_ARGB));
            }
            case LocatorFix.NoSignal ignored -> lines.add(new Line(String.format(Locale.ROOT,
                    "No cell at or above %.0f dBm to range to", payload.minRsrpDbm()), DIM_ARGB));
        }

        lines.add(new Line(cellsLine(payload), DIM_ARGB));
        lines.add(waypointLine(waypoints, fix, dimension, mpb));
        emergencyLine(payload.emergency(), mpb).ifPresent(lines::add);
        return new Screen(TITLE, state, LocatorStyle.stateArgb(kind), lines);
    }

    /** {@code Cells 4   best res 15 m (band_1800)}; just {@code Cells 0} with no cell used. */
    static String cellsLine(LocatorFixPayload payload) {
        int cells = payload.cellsUsed();
        if (cells == 0 || payload.bestResolutionBandId().isEmpty()) {
            return "Cells " + cells;
        }
        return String.format(Locale.ROOT, "Cells %d   best res %s (%s)",
                cells, metres(payload.bestResolutionMeters()), payload.bestResolutionBandId());
    }

    /**
     * The selected waypoint: its number, the "±" it was saved with, and distance and bearing from the
     * current FIX. No FIX, no bearing: a bearing from a guess would look like navigation.
     */
    static Line waypointLine(LocatorWaypoints waypoints, LocatorFix fix, String dimension, double mpb) {
        Optional<LocatorWaypoints.Waypoint> selected = waypoints.selectedWaypoint();
        if (selected.isEmpty()) {
            return new Line("WP -   sneak + use saves the estimate", DIM_ARGB);
        }
        LocatorWaypoints.Waypoint waypoint = selected.get();
        String head = String.format(Locale.ROOT, "WP %d/%d (saved ±%s)",
                waypoints.selected() + 1, waypoints.size(), metres(waypoint.errorBlocks() * mpb));
        if (!waypoint.dimension().equals(dimension)) {
            return new Line(head + "   in " + waypoint.dimension(), DIM_ARGB);
        }
        if (!(fix instanceof LocatorFix.Fix position)) {
            return new Line(head + "   no FIX, no bearing", DIM_ARGB);
        }
        double distance = Navigation.horizontalDistance(position.x(), position.z(), waypoint.x(), waypoint.z());
        int bearing = Navigation.wholeDegrees(
                Navigation.bearingDegrees(position.x(), position.z(), waypoint.x(), waypoint.z()));
        return new Line(String.format(Locale.ROOT, "%s   %s   bearing %03d°",
                head, metres(distance * mpb), bearing), TEXT_ARGB);
    }

    /** The frozen "last fix before death": the estimate the Locator had, never where you really were. */
    static Optional<Line> emergencyLine(Optional<LocatorFixPayload.Emergency> emergency, double mpb) {
        return emergency.map(frozen -> new Line(String.format(Locale.ROOT,
                "Last fix before death: x %d  z %d  y ~%d  ±%s, %.0f s before (%s)",
                block(frozen.x()), block(frozen.z()), surface(frozen.y()), metres(frozen.errorBlocks() * mpb),
                Math.max(0L, frozen.ageAtDeathTicks()) / TICKS_PER_SECOND, frozen.dimension()), EMERGENCY_ARGB));
    }

    /**
     * Metres to one decimal below 10, whole metres from there: {@code 8.7 m}, {@code 15 m},
     * {@code 312 m}. So a band_3500 fix's sub-metre "±" does not read as 0.
     */
    static String metres(double metres) {
        if (!(metres >= 0.0) || !Double.isFinite(metres)) {
            return "? m";
        }
        return metres < 10.0
                ? String.format(Locale.ROOT, "%.1f m", metres)
                : String.format(Locale.ROOT, "%.0f m", metres);
    }

    /** HDOP to one decimal; the solver's ceiling reads as "99.9+" (it means 99.9 or worse). */
    static String hdop(double hdop) {
        return hdop >= LocatorSolver.HDOP_CEILING
                ? String.format(Locale.ROOT, "%.1f+", LocatorSolver.HDOP_CEILING)
                : String.format(Locale.ROOT, "%.1f", hdop);
    }

    /** The block a coordinate falls in, as F3 shows it. */
    static long block(double coordinate) {
        return (long) Math.floor(coordinate);
    }

    /** The assumed surface under an assumed eye height (eye - 1.62), rounded. */
    static long surface(double eyeY) {
        return Math.round(eyeY - LocatorSolver.RECEIVER_EYE_HEIGHT);
    }
}
