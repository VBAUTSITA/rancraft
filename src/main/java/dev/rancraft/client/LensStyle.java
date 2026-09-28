package dev.rancraft.client;

import dev.rancraft.rf.ServiceLevel;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * How the RF Lens's Step 2 layers look: colours, label text and where labels sit.
 *
 * <p><b>Every input here is a value the server sent</b> -- a cumulative loss, a cell id, a service
 * level, an RSRP. This class only decides how that value is drawn; it computes no propagation,
 * no obstruction and no serving-cell choice. Keeping it free of Minecraft types means those
 * drawing rules are unit-tested headless, like the rest of the lens state.
 */
public final class LensStyle {

    private LensStyle() {
    }

    // ---- link rays --------------------------------------------------------------------------

    /**
     * Cumulative loss at which a link is drawn fully red. Forty dB is the point where a link that
     * started out strong is marginal at best; past it, more red would not tell the wearer more.
     */
    public static final double LOSS_FULL_RED_DB = 40.0;

    /** The darkest a colour channel goes, so every colour stays readable against dark terrain. */
    private static final int CHANNEL_FLOOR = 64;

    /**
     * Green at 0 dB, through yellow at half of {@link #LOSS_FULL_RED_DB}, to red at or past it.
     *
     * <p>Linear in dB, because dB is already the logarithmic scale a planner reads: equal steps of
     * loss are equal steps of colour. A negative or non-numeric input is drawn as clear.
     *
     * @return 0xRRGGBB.
     */
    public static int lossRgb(double cumulativeDb) {
        double f = cumulativeDb > 0.0 ? Math.min(cumulativeDb / LOSS_FULL_RED_DB, 1.0) : 0.0;
        int span = 255 - CHANNEL_FLOOR;
        int red;
        int green;
        if (f < 0.5) {
            red = CHANNEL_FLOOR + (int) Math.round(span * f * 2.0);
            green = 255;
        } else {
            red = 255;
            green = 255 - (int) Math.round(span * (f - 0.5) * 2.0);
        }
        return rgb(red, green, CHANNEL_FLOOR);
    }

    /**
     * One uniformly coloured stretch of a link, from {@code t0} to {@code t1} along it
     * (0 = antenna, 1 = receiver), carrying the loss accumulated by its start.
     */
    public record Piece(double t0, double t1, double lossDb) {
    }

    /**
     * Splits a link at the server's breakpoints.
     *
     * <p>Breakpoint {@code k} says the loss from the antenna up to and including the voxel at
     * {@code t[k]} is {@code db[k]}. So the stretch before the first breakpoint is clear (0 dB),
     * each breakpoint starts a stretch at its own loss, and the last stretch runs to the receiver.
     * No breakpoints means one clear stretch: line of sight.
     *
     * <p>Breakpoint positions are clamped to [0, 1] and never allowed to run backwards, and
     * zero-length stretches are dropped, so the pieces tile [0, 1] in order without overlapping
     * whatever the payload holds. The losses are used exactly as sent.
     *
     * @param t  breakpoint positions, non-decreasing as the server sends them.
     * @param db cumulative loss at each breakpoint; same length as {@code t}.
     */
    public static List<Piece> pieces(float[] t, float[] db) {
        int count = Math.min(t.length, db.length);
        List<Piece> pieces = new ArrayList<>(count + 1);
        double start = 0.0;
        double loss = 0.0;
        for (int k = 0; k < count; k++) {
            double at = Math.max(start, clamp01(t[k]));
            if (at > start) {
                pieces.add(new Piece(start, at, loss));
            }
            start = at;
            loss = db[k];
        }
        if (start < 1.0 || pieces.isEmpty()) {
            pieces.add(new Piece(start, 1.0, loss));
        }
        return pieces;
    }

    /**
     * {@code PCI <pci> · <band> · <rsrp> dBm}, with {@code [serving]} appended for the serving
     * cell. RSRP to one decimal, always with a full stop, whatever the player's locale.
     */
    public static String linkLabel(int pci, String bandId, double rsrpDbm, boolean serving) {
        String text = String.format(Locale.ROOT, "PCI %d · %s · %.1f dBm", pci, bandId, rsrpDbm);
        return serving ? text + " [serving]" : text;
    }

    // ---- coverage painting ------------------------------------------------------------------

    /** Returned by {@link #tileArgb} for a point that is not drawn. No drawn tile is fully transparent. */
    public static final int NOT_DRAWN = 0;

    /** Where there is no usable service: a dark grey, so a coverage hole reads as a hole. */
    public static final int NO_SERVICE_RGB = 0x303030;
    public static final int NO_SERVICE_ALPHA = 60;

    /**
     * 2^64 / phi, rounded to odd: the multiplier of Fibonacci hashing. Multiplying by it modulo
     * 2^64 and reading the top bits as a fraction is {@code frac(id / phi)} computed exactly,
     * with no floating-point loss however large the id.
     */
    private static final long GOLDEN_GAMMA = 0x9E3779B97F4A7C15L;

    private static final double CELL_SATURATION = 0.70;
    private static final double CELL_VALUE = 1.0;

    /**
     * A cell's hue on [0, 1): golden-ratio spacing on its id (Fibonacci hashing).
     *
     * <p>Stepping round the colour wheel by 1/phi of a turn spreads consecutive values as evenly
     * as any fixed step can. Cell ids are packed block positions with y in the lowest bits, so
     * masts stacked in a column have consecutive ids and land 0.38 of a turn apart -- never two
     * near-identical colours on one tower. (The one exception is the pair straddling y = -1 and
     * y = 0, where the packed y field wraps and the ids are not consecutive.) The hue depends on nothing but the id, so a
     * cell keeps its colour from one survey to the next and the painting does not reshuffle as
     * the wearer walks.
     *
     * <p>The id is used directly rather than through {@code Long.hashCode}: folding the high word
     * into the low one scrambles exactly the low-bit steps this spacing relies on.
     */
    public static double cellHue(long cellId) {
        return ((cellId * GOLDEN_GAMMA) >>> 11) * 0x1.0p-53;
    }

    /** The colour a cell paints its best-server area in. 0xRRGGBB. */
    public static int cellRgb(long cellId) {
        return hsvToRgb(cellHue(cellId), CELL_SATURATION, CELL_VALUE);
    }

    /**
     * Tile opacity by service level: the better the service, the bolder the paint, so the eye is
     * drawn to where a cell actually works rather than to where it merely wins.
     */
    public static int levelAlpha(ServiceLevel level) {
        return switch (level) {
            case EXCELLENT -> 110;
            case GOOD -> 90;
            case FAIR -> 70;
            case POOR -> 50;
            case NONE -> NO_SERVICE_ALPHA;
        };
    }

    /**
     * The colour of one surveyed point.
     *
     * <p>Level decides first: {@link ServiceLevel#NONE} is grey even when a cell technically wins
     * the point, because a best server nobody can decode is not service. Otherwise the serving
     * cell's colour at the level's opacity.
     *
     * @param level      the server's level, or {@code null} when the point was not surveyed.
     * @param hasServing whether the server named a serving cell for the point.
     * @param servingRgb that cell's {@link #cellRgb}; ignored when {@code hasServing} is false.
     * @return 0xAARRGGBB, or {@link #NOT_DRAWN}.
     */
    public static int tileArgb(ServiceLevel level, boolean hasServing, int servingRgb) {
        if (level == null) {
            return NOT_DRAWN;
        }
        if (level == ServiceLevel.NONE || !hasServing) {
            return argb(NO_SERVICE_ALPHA, NO_SERVICE_RGB);
        }
        return argb(levelAlpha(level), servingRgb);
    }

    /** {@code PCI <pci> · <band>}: the legend tag floated over a cell's painted area. */
    public static String cellLabel(int pci, String bandId) {
        return String.format(Locale.ROOT, "PCI %d · %s", pci, bandId);
    }

    // ---- label placement --------------------------------------------------------------------

    /**
     * Where one label goes: a shared anchor for its group, and its line in the group's stack
     * (0 = bottom line, counting upward).
     */
    public record Anchor(double x, double y, double z, int stackIndex) {
    }

    /**
     * Gathers labels whose points stand within {@code radius} blocks of each other horizontally
     * into one stack, so co-sited antennas get a readable list instead of a pile of overlapping
     * text.
     *
     * <p>This is the stacked-mast case VISION.md opens with: nine masts in one column are nine
     * lines on one stack above the tower, rather than nine labels drawn over each other. A
     * three-sector site, whose antennas sit on neighbouring blocks, is also one stack.
     *
     * <p>Points are taken in the order given and each joins the first group whose seed (its
     * first member) is within the radius, so the result is deterministic. Within a group the
     * lines are ordered by height, lowest at the bottom, so the stack reads top to bottom like the
     * tower does; equal heights keep their input order upward. The anchor is the group's
     * horizontal centroid, {@code lift} blocks above its highest point.
     *
     * @return one anchor per input point, in input order.
     */
    public static Anchor[] stack(double[] x, double[] y, double[] z, double radius, double lift) {
        int n = x.length;
        int[] group = new int[n];
        List<double[]> seeds = new ArrayList<>();
        double radiusSq = radius * radius;
        for (int p = 0; p < n; p++) {
            int found = -1;
            for (int g = 0; g < seeds.size(); g++) {
                double dx = x[p] - seeds.get(g)[0];
                double dz = z[p] - seeds.get(g)[1];
                if (dx * dx + dz * dz <= radiusSq) {
                    found = g;
                    break;
                }
            }
            if (found < 0) {
                found = seeds.size();
                seeds.add(new double[] {x[p], z[p]});
            }
            group[p] = found;
        }

        Anchor[] anchors = new Anchor[n];
        for (int g = 0; g < seeds.size(); g++) {
            List<Integer> members = new ArrayList<>();
            double sumX = 0.0;
            double sumZ = 0.0;
            double top = Double.NEGATIVE_INFINITY;
            for (int p = 0; p < n; p++) {
                if (group[p] == g) {
                    members.add(p);
                    sumX += x[p];
                    sumZ += z[p];
                    top = Math.max(top, y[p]);
                }
            }
            // Stable sort: equal heights keep input order.
            Integer[] order = members.toArray(new Integer[0]);
            Arrays.sort(order, (a, b) -> Double.compare(y[a], y[b]));
            double anchorX = sumX / members.size();
            double anchorZ = sumZ / members.size();
            for (int rank = 0; rank < order.length; rank++) {
                anchors[order[rank]] = new Anchor(anchorX, top + lift, anchorZ, rank);
            }
        }
        return anchors;
    }

    // ---- colour arithmetic ------------------------------------------------------------------

    /** Standard HSV to RGB, all inputs on [0, 1]; the hue wraps. 0xRRGGBB. */
    public static int hsvToRgb(double hue, double saturation, double value) {
        double h = (hue - Math.floor(hue)) * 6.0;
        int sector = Math.min((int) h, 5);
        double f = h - sector;
        double p = value * (1.0 - saturation);
        double q = value * (1.0 - f * saturation);
        double t = value * (1.0 - (1.0 - f) * saturation);
        return switch (sector) {
            case 0 -> rgb(value, t, p);
            case 1 -> rgb(q, value, p);
            case 2 -> rgb(p, value, t);
            case 3 -> rgb(p, q, value);
            case 4 -> rgb(t, p, value);
            default -> rgb(value, p, q);
        };
    }

    public static int argb(int alpha, int rgb) {
        return (Math.clamp(alpha, 0, 255) << 24) | (rgb & 0xFFFFFF);
    }

    private static int rgb(double red, double green, double blue) {
        return rgb(channel(red), channel(green), channel(blue));
    }

    private static int rgb(int red, int green, int blue) {
        return (red << 16) | (green << 8) | blue;
    }

    private static int channel(double unit) {
        return (int) Math.round(Math.clamp(unit, 0.0, 1.0) * 255.0);
    }

    private static double clamp01(double value) {
        return value > 0.0 ? Math.min(value, 1.0) : 0.0;
    }
}
