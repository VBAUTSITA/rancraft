package dev.rancraft.net;

import dev.rancraft.RanCraft;
import dev.rancraft.rf.CellParams;
import dev.rancraft.rf.LocatorFix;
import dev.rancraft.rf.LocatorParams;
import dev.rancraft.rf.RangeMeasurement;
import io.netty.handler.codec.DecoderException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * Server to client: what the Network Locator worked out. Phase 3 slice 5, §3A.6.
 *
 * <p>Sent once per evaluation while a Locator is <em>held</em> (main hand or offhand), from the same
 * evaluation as the {@link SignalSamplePayload} that precedes it. A Locator in the hotbar still
 * runs on the server (its emergency record keeps working) but sends nothing: it has no HUD to feed.
 *
 * <p><b>The client computes nothing from this.</b> The fix, its HDOP and "±", the candidates, the
 * rings' ranges and the best resolution are all server values. The client draws them (and measures
 * distance and bearing from the estimate to a waypoint, which is map arithmetic between two values
 * it was handed, not positioning).
 *
 * <p>Every count is clamped before anything is allocated for it, strings are capped (band ids at
 * {@link CellParams#MAX_BAND_ID_LENGTH}, as every payload does), and malformed input is rejected
 * with a {@link DecoderException}: an unknown version or fix type, a count over its cap, a
 * non-finite number, a negative range, HDOP or "±", a {@code likely} outside -1..1.
 *
 * @param version              wire version, written first; {@value #VERSION}.
 * @param timestampTick        game time of the evaluation the fix came from.
 * @param fix                  the Locator's answer: its type is the fix type on the wire, followed by
 *                             the estimate or candidates, HDOP, "±" and cells used as it has them.
 * @param rings                one per cell that went into the fix, strongest first, at most
 *                             {@value #MAX_RINGS}: where it radiates from and the range measured to it.
 * @param metersPerBlock       the server's scale, so the HUD can print metres.
 * @param bestResolutionMeters the finest timing resolution among the cells used ({@code c / BW}), 0
 *                             when none.
 * @param bestResolutionBandId its band, {@code ""} when none.
 * @param maxHdop              the server's POOR GEOMETRY threshold, so the HUD can say what HDOP was
 *                             too high against.
 * @param minRsrpDbm           the server's ranging threshold, so NO SIGNAL can say what was missing.
 * @param emergency            the frozen "last fix before death", if the player has one.
 */
public record LocatorFixPayload(
        int version,
        long timestampTick,
        LocatorFix fix,
        List<Ring> rings,
        double metersPerBlock,
        double bestResolutionMeters,
        String bestResolutionBandId,
        double maxHdop,
        double minRsrpDbm,
        Optional<Emergency> emergency
) implements CustomPacketPayload {

    /** Wire version, written as the first field. 1: Phase 3 slice 5, new. */
    public static final int VERSION = 1;

    /**
     * Wire cap on rings: one per cell used in a fix. {@code locatorMaxCells} in the config is bounded
     * by this, so every cell that went into a fix gets its ring.
     */
    public static final int MAX_RINGS = 8;

    /** Sanity cap on a received {@code cellsUsed}. The server never sends more than {@link #MAX_RINGS}. */
    public static final int MAX_CELLS_USED = 64;

    /** Wire cap on the emergency record's dimension id. */
    public static final int MAX_DIMENSION_LENGTH = 256;

    private static final int MAX_BAND_ID = CellParams.MAX_BAND_ID_LENGTH;

    public static final CustomPacketPayload.Type<LocatorFixPayload> TYPE =
            new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(RanCraft.MOD_ID, "locator_fix"));

    public static final StreamCodec<FriendlyByteBuf, LocatorFixPayload> STREAM_CODEC =
            StreamCodec.of(LocatorFixPayload::write, LocatorFixPayload::read);

    /** The fix type, as the wire and the HUD name it. The ordinal is the wire byte: append only. */
    public enum Kind {
        NO_SIGNAL("NO SIGNAL"),
        RANGE_ONLY("RANGE ONLY"),
        AMBIGUOUS("AMBIGUOUS"),
        POOR_GEOMETRY("POOR GEOMETRY"),
        FIX("FIX");

        private final String label;

        Kind(String label) {
            this.label = label;
        }

        /** The HUD's name for this state. */
        public String label() {
            return label;
        }

        public static Kind of(LocatorFix fix) {
            return switch (fix) {
                case LocatorFix.NoSignal ignored -> NO_SIGNAL;
                case LocatorFix.RangeOnly ignored -> RANGE_ONLY;
                case LocatorFix.Ambiguous ignored -> AMBIGUOUS;
                case LocatorFix.PoorGeometry ignored -> POOR_GEOMETRY;
                case LocatorFix.Fix ignored -> FIX;
            };
        }
    }

    /**
     * One contributing cell's measured range.
     *
     * @param cx     radiating centre (block + 0.5), as the range was measured from.
     * @param radius the measured slant (3D) range in blocks: quantised, NLOS-biased, exactly what the
     *               solver was given. The renderer draws its horizontal cross-section.
     * @param bandId clamped to {@link CellParams#MAX_BAND_ID_LENGTH}, the wire cap.
     */
    public record Ring(double cx, double cy, double cz, double radius, String bandId) {
        public Ring {
            bandId = WireText.clamp(bandId, MAX_BAND_ID);
        }

        static Ring of(RangeMeasurement range) {
            return new Ring(range.x(), range.y(), range.z(), range.rangeBlocks(), range.bandId());
        }

        boolean isFinite() {
            return Double.isFinite(cx) && Double.isFinite(cy) && Double.isFinite(cz)
                    && Double.isFinite(radius) && radius >= 0.0;
        }
    }

    /**
     * The player's frozen "last fix before death" (the emergency record,
     * {@code device.EmergencyRecord}). An estimate, never the true position.
     *
     * @param fixTick   game time the Locator last reported that fix.
     * @param deathTick game time of the death.
     */
    public record Emergency(String dimension, double x, double y, double z, double errorBlocks,
                            long fixTick, long deathTick) {
        public Emergency {
            dimension = WireText.clamp(dimension, MAX_DIMENSION_LENGTH);
        }

        /** Ticks between the fix and the death. */
        public long ageAtDeathTicks() {
            return deathTick - fixTick;
        }

        boolean isFinite() {
            return Double.isFinite(x) && Double.isFinite(y) && Double.isFinite(z)
                    && Double.isFinite(errorBlocks) && errorBlocks >= 0.0;
        }
    }

    public LocatorFixPayload {
        Objects.requireNonNull(fix, "fix");
        rings = List.copyOf(rings);
        if (rings.size() > MAX_RINGS) {
            throw new IllegalArgumentException(rings.size() + " rings exceed the cap of " + MAX_RINGS);
        }
        bestResolutionBandId = WireText.clamp(bestResolutionBandId, MAX_BAND_ID);
        emergency = emergency == null ? Optional.empty() : emergency;
    }

    /**
     * Builds the payload for one Locator reading. Defensive, because a payload the client rejects
     * disconnects it: rings with a non-finite value are left out (never produced by
     * {@code Ranging}), a fix with one becomes NO SIGNAL (never produced by {@code LocatorSolver}),
     * and only the first {@value #MAX_RINGS} cells get a ring.
     *
     * @param used the ranges the fix was computed from, strongest first
     *             ({@code LocatorSolver.cellsUsed}).
     */
    public static LocatorFixPayload of(
            long timestampTick,
            LocatorFix fix,
            List<RangeMeasurement> used,
            double bestResolutionMeters,
            String bestResolutionBandId,
            double metersPerBlock,
            LocatorParams params,
            Optional<Emergency> emergency) {

        List<Ring> rings = new ArrayList<>(Math.min(used.size(), MAX_RINGS));
        for (RangeMeasurement range : used) {
            if (rings.size() >= MAX_RINGS) {
                break;
            }
            Ring ring = Ring.of(range);
            if (ring.isFinite()) {
                rings.add(ring);
            }
        }
        return new LocatorFixPayload(
                VERSION,
                timestampTick,
                isFinite(fix) ? fix : new LocatorFix.NoSignal(),
                rings,
                metersPerBlock > 0.0 && Double.isFinite(metersPerBlock) ? metersPerBlock : 1.0,
                bestResolutionMeters >= 0.0 && Double.isFinite(bestResolutionMeters) ? bestResolutionMeters : 0.0,
                bestResolutionBandId,
                params.maxHdop() >= 0.0 && Double.isFinite(params.maxHdop()) ? params.maxHdop() : 0.0,
                Double.isFinite(params.minRsrpDbm()) ? params.minRsrpDbm() : 0.0,
                emergency == null ? Optional.empty() : emergency.filter(Emergency::isFinite));
    }

    public Kind kind() {
        return Kind.of(fix);
    }

    /** Cells that went into the answer: the fix's own count for FIX and POOR GEOMETRY, else the rings. */
    public int cellsUsed() {
        return switch (fix) {
            case LocatorFix.Fix position -> position.cellsUsed();
            case LocatorFix.PoorGeometry poor -> poor.cellsUsed();
            default -> rings.size();
        };
    }

    // ---- wire ------------------------------------------------------------------------------------

    private static void write(FriendlyByteBuf buf, LocatorFixPayload payload) {
        buf.writeVarInt(payload.version);
        buf.writeVarLong(payload.timestampTick);
        buf.writeByte(Kind.of(payload.fix).ordinal());
        switch (payload.fix) {
            case LocatorFix.NoSignal ignored -> {
            }
            case LocatorFix.RangeOnly ring -> {
                buf.writeDouble(ring.cx());
                buf.writeDouble(ring.cz());
                buf.writeDouble(ring.radius());
            }
            case LocatorFix.Ambiguous ambiguous -> {
                buf.writeDouble(ambiguous.ax());
                buf.writeDouble(ambiguous.az());
                buf.writeDouble(ambiguous.bx());
                buf.writeDouble(ambiguous.bz());
                buf.writeByte(ambiguous.likely());
            }
            case LocatorFix.PoorGeometry poor -> {
                buf.writeDouble(poor.hdop());
                buf.writeVarInt(poor.cellsUsed());
            }
            case LocatorFix.Fix position -> {
                buf.writeDouble(position.x());
                buf.writeDouble(position.y());
                buf.writeDouble(position.z());
                buf.writeDouble(position.hdop());
                buf.writeDouble(position.errorBlocks());
                buf.writeVarInt(position.cellsUsed());
            }
        }
        buf.writeVarInt(payload.rings.size());
        for (Ring ring : payload.rings) {
            buf.writeDouble(ring.cx());
            buf.writeDouble(ring.cy());
            buf.writeDouble(ring.cz());
            buf.writeDouble(ring.radius());
            buf.writeUtf(ring.bandId(), MAX_BAND_ID);
        }
        buf.writeDouble(payload.metersPerBlock);
        buf.writeDouble(payload.bestResolutionMeters);
        buf.writeUtf(payload.bestResolutionBandId, MAX_BAND_ID);
        buf.writeDouble(payload.maxHdop);
        buf.writeDouble(payload.minRsrpDbm);
        buf.writeBoolean(payload.emergency.isPresent());
        if (payload.emergency.isPresent()) {
            Emergency emergency = payload.emergency.get();
            buf.writeUtf(emergency.dimension(), MAX_DIMENSION_LENGTH);
            buf.writeDouble(emergency.x());
            buf.writeDouble(emergency.y());
            buf.writeDouble(emergency.z());
            buf.writeDouble(emergency.errorBlocks());
            buf.writeVarLong(emergency.fixTick());
            buf.writeVarLong(emergency.deathTick());
        }
    }

    /**
     * Arguments are evaluated left to right (JLS 15.7.4), which is the wire order. Every count is
     * checked before anything is allocated for it; an out-of-range count is rejected rather than
     * clamped, because the entries past the cap would still be on the wire and desynchronise every
     * field after them.
     */
    private static LocatorFixPayload read(FriendlyByteBuf buf) {
        int version = buf.readVarInt();
        if (version != VERSION) {
            throw malformed("version " + version + " (this client reads " + VERSION + ")");
        }
        long tick = buf.readVarLong();
        byte kindByte = buf.readByte();
        Kind[] kinds = Kind.values();
        if (kindByte < 0 || kindByte >= kinds.length) {
            throw malformed("unknown fix type " + kindByte);
        }
        LocatorFix fix = switch (kinds[kindByte]) {
            case NO_SIGNAL -> new LocatorFix.NoSignal();
            case RANGE_ONLY -> new LocatorFix.RangeOnly(finite(buf, "cx"), finite(buf, "cz"), nonNegative(buf, "radius"));
            case AMBIGUOUS -> new LocatorFix.Ambiguous(
                    finite(buf, "ax"), finite(buf, "az"), finite(buf, "bx"), finite(buf, "bz"), likely(buf));
            case POOR_GEOMETRY -> new LocatorFix.PoorGeometry(
                    nonNegative(buf, "hdop"), count(buf.readVarInt(), MAX_CELLS_USED, "cells used"));
            case FIX -> new LocatorFix.Fix(
                    finite(buf, "x"), finite(buf, "y"), finite(buf, "z"),
                    nonNegative(buf, "hdop"), nonNegative(buf, "error"),
                    count(buf.readVarInt(), MAX_CELLS_USED, "cells used"));
        };

        int ringCount = count(buf.readVarInt(), MAX_RINGS, "rings");
        List<Ring> rings = new ArrayList<>(ringCount);
        for (int i = 0; i < ringCount; i++) {
            rings.add(new Ring(finite(buf, "ring x"), finite(buf, "ring y"), finite(buf, "ring z"),
                    nonNegative(buf, "ring radius"), buf.readUtf(MAX_BAND_ID)));
        }
        double metersPerBlock = finite(buf, "metres per block");
        if (!(metersPerBlock > 0.0)) {
            throw malformed("metres per block " + metersPerBlock);
        }
        double bestResolution = nonNegative(buf, "best resolution");
        String bestBand = buf.readUtf(MAX_BAND_ID);
        double maxHdop = nonNegative(buf, "max HDOP");
        double minRsrp = finite(buf, "min RSRP");
        Optional<Emergency> emergency = Optional.empty();
        if (buf.readBoolean()) {
            emergency = Optional.of(new Emergency(
                    buf.readUtf(MAX_DIMENSION_LENGTH),
                    finite(buf, "emergency x"), finite(buf, "emergency y"), finite(buf, "emergency z"),
                    nonNegative(buf, "emergency error"),
                    buf.readVarLong(), buf.readVarLong()));
        }
        return new LocatorFixPayload(version, tick, fix, rings, metersPerBlock, bestResolution, bestBand,
                maxHdop, minRsrp, emergency);
    }

    private static boolean isFinite(LocatorFix fix) {
        return switch (fix) {
            case LocatorFix.NoSignal ignored -> true;
            case LocatorFix.RangeOnly ring -> Double.isFinite(ring.cx()) && Double.isFinite(ring.cz())
                    && Double.isFinite(ring.radius()) && ring.radius() >= 0.0;
            case LocatorFix.Ambiguous a -> Double.isFinite(a.ax()) && Double.isFinite(a.az())
                    && Double.isFinite(a.bx()) && Double.isFinite(a.bz()) && a.likely() >= -1 && a.likely() <= 1;
            case LocatorFix.PoorGeometry poor -> Double.isFinite(poor.hdop()) && poor.hdop() >= 0.0
                    && poor.cellsUsed() >= 0 && poor.cellsUsed() <= MAX_CELLS_USED;
            case LocatorFix.Fix f -> Double.isFinite(f.x()) && Double.isFinite(f.y()) && Double.isFinite(f.z())
                    && Double.isFinite(f.hdop()) && f.hdop() >= 0.0
                    && Double.isFinite(f.errorBlocks()) && f.errorBlocks() >= 0.0
                    && f.cellsUsed() >= 0 && f.cellsUsed() <= MAX_CELLS_USED;
        };
    }

    private static double finite(FriendlyByteBuf buf, String what) {
        double value = buf.readDouble();
        if (!Double.isFinite(value)) {
            throw malformed(what + " is " + value);
        }
        return value;
    }

    private static double nonNegative(FriendlyByteBuf buf, String what) {
        double value = finite(buf, what);
        if (value < 0.0) {
            throw malformed(what + " is negative (" + value + ")");
        }
        return value;
    }

    private static int likely(FriendlyByteBuf buf) {
        byte likely = buf.readByte();
        if (likely < LocatorFix.Ambiguous.NO_PREFERENCE || likely > 1) {
            throw malformed("likely candidate " + likely);
        }
        return likely;
    }

    private static int count(int count, int max, String what) {
        if (count < 0 || count > max) {
            throw malformed(count + " " + what + " (max " + max + ")");
        }
        return count;
    }

    private static DecoderException malformed(String detail) {
        return new DecoderException("RANCraft locator fix: " + detail);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
