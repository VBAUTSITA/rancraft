package dev.rancraft.net;

import dev.rancraft.RanCraft;
import dev.rancraft.rf.CellParams;
import dev.rancraft.rf.DeviceRequirement;
import dev.rancraft.rf.ServiceLevel;
import dev.rancraft.util.ProximityScan;
import io.netty.handler.codec.DecoderException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * Server to client: what the held Proximity Scanner shows (Phase 3 slice 14, §3C.4).
 *
 * <p>Sent once per dispatch (each evaluation interval, a cached replay included) while a scanner is
 * <em>held</em>, by one held scanner ({@code device.ProximityScanner}). The list of hostile mobs is in it
 * only with an OK verdict; otherwise the payload says why the scanner is off, so the HUD can tell "needs
 * tier 3" (change the band) from "signal too weak" (fix the signal) from "backhaul limited" (fix the
 * backhaul). A scanner in the hotbar sends nothing: it has no HUD there.
 *
 * <p><b>The client computes nothing from this.</b> The status, the levels, the band and its tier, the
 * range and every contact's type, distance and bearing are server values. The client draws them (and
 * turns each bearing into an arrow relative to where its camera faces, which is presentation, not
 * detection).
 *
 * <p>Every count is checked before anything is allocated for it, strings are capped, and malformed input
 * is rejected with a {@link DecoderException}: an unknown version, status or service level, a negative
 * tier, more than {@value #MAX_CONTACTS} contacts, a contact on a refusal, a non-finite or negative
 * distance or range, a bearing outside [0, 360).
 *
 * @param version         wire version, written first; {@value #VERSION}.
 * @param status          OK, or why the scanner is off.
 * @param neededLevel     the scanner's requirement: the least service level.
 * @param neededTier      the scanner's requirement: the least capacity tier of the serving band.
 * @param radioLevel      the sample's own service level, the radio link alone (uncapped).
 * @param serviceCap      the serving cell's backhaul cap ({@link ServiceLevel#EXCELLENT} caps nothing).
 * @param servingBandId   the serving cell's band, {@code ""} with no service; clamped to
 *                        {@link CellParams#MAX_BAND_ID_LENGTH}.
 * @param servingBandTier that band's capacity tier, 0 with no service.
 * @param rangeBlocks     the server's scan range ({@code scannerRangeBlocks}), so the HUD can say "none
 *                        within 24 blocks".
 * @param contacts        nearest first, at most {@value #MAX_CONTACTS}; empty unless {@code status} is OK.
 */
public record ScannerPayload(
        int version,
        Status status,
        ServiceLevel neededLevel,
        int neededTier,
        ServiceLevel radioLevel,
        ServiceLevel serviceCap,
        String servingBandId,
        int servingBandTier,
        float rangeBlocks,
        List<Contact> contacts
) implements CustomPacketPayload {

    /** Wire version, written as the first field. <b>1</b>: Phase 3 slice 14, new. */
    public static final int VERSION = 1;

    /** §3C.4: at most 16 entries. A count above it is refused when built and rejected when read. */
    public static final int MAX_CONTACTS = 16;

    /** Wire cap on an entity type id ({@code namespace:path}). */
    public static final int MAX_TYPE_ID_LENGTH = 256;

    private static final int MAX_BAND_ID = CellParams.MAX_BAND_ID_LENGTH;

    private static final Status[] STATUSES = Status.values();
    private static final ServiceLevel[] LEVELS = ServiceLevel.values();

    public static final CustomPacketPayload.Type<ScannerPayload> TYPE =
            new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(RanCraft.MOD_ID, "scanner"));

    public static final StreamCodec<FriendlyByteBuf, ScannerPayload> STREAM_CODEC =
            StreamCodec.of(ScannerPayload::write, ScannerPayload::read);

    /**
     * Whether the scanner works, and if not, which fix it needs. The ordinal is the wire byte: append
     * only. The reasons are the Storage Terminal's ({@code device.TerminalLink.Problem}) without "no
     * reading": the scanner is judged on the dispatch that sends this, so it always has one.
     */
    public enum Status {
        /** GOOD or better on a band of tier 3 or higher: the list is in the payload. */
        OK,
        /** No serving cell at all. */
        NO_SERVICE,
        /** The radio link itself is below the requirement: fix the signal. */
        WEAK_SIGNAL,
        /** The radio link would do, but the serving cell's backhaul caps it below the requirement. */
        BACKHAUL_LIMITED,
        /** Good enough signal on a band of too low a capacity tier: change the band. */
        LOW_TIER
    }

    /**
     * One hostile mob: its entity type id (e.g. {@code minecraft:creeper}, clamped to
     * {@value #MAX_TYPE_ID_LENGTH} chars), straight-line distance in blocks, and compass bearing from the
     * player on [0, 360) (0 north, 90 east). Floats on the wire: the HUD shows whole blocks and degrees.
     */
    public record Contact(String typeId, float distanceBlocks, float bearingDegrees) {
        public Contact {
            typeId = WireText.clamp(typeId, MAX_TYPE_ID_LENGTH);
        }

        boolean isValid() {
            return Float.isFinite(distanceBlocks) && distanceBlocks >= 0.0f
                    && Float.isFinite(bearingDegrees) && bearingDegrees >= 0.0f && bearingDegrees < 360.0f;
        }
    }

    public ScannerPayload {
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(neededLevel, "neededLevel");
        Objects.requireNonNull(radioLevel, "radioLevel");
        Objects.requireNonNull(serviceCap, "serviceCap");
        servingBandId = WireText.clamp(servingBandId, MAX_BAND_ID);
        contacts = List.copyOf(contacts);
        if (contacts.size() > MAX_CONTACTS) {
            throw new IllegalArgumentException(contacts.size() + " contacts exceed the cap of " + MAX_CONTACTS);
        }
        if (status != Status.OK && !contacts.isEmpty()) {
            throw new IllegalArgumentException("a refusal (" + status + ") carries no contacts");
        }
    }

    /**
     * Builds the payload for one dispatch. Defensive, because a payload the client rejects disconnects
     * it: the contacts are dropped unless {@code status} is OK, a contact with a non-finite or
     * out-of-range number is left out, only the first {@value #MAX_CONTACTS} are kept, a negative tier
     * reads 0 and a range that is not a finite non-negative number reads 0.
     *
     * @param requirement the scanner's requirement, sent so the HUD's "needs" never drifts from the server.
     * @param contacts    nearest first ({@link ProximityScan#nearest}).
     */
    public static ScannerPayload of(Status status, DeviceRequirement requirement, ServiceLevel radioLevel,
                                    ServiceLevel serviceCap, String servingBandId, int servingBandTier,
                                    double rangeBlocks, List<ProximityScan.Contact> contacts) {
        List<Contact> wire = new ArrayList<>(Math.min(contacts.size(), MAX_CONTACTS));
        if (status == Status.OK) {
            for (ProximityScan.Contact contact : contacts) {
                if (wire.size() >= MAX_CONTACTS) {
                    break;
                }
                // A bearing just under 360 can round up to 360.0f as a float: that is north, 0.
                float bearing = (float) contact.bearingDegrees();
                Contact entry = new Contact(contact.typeId(), (float) contact.distanceBlocks(),
                        bearing >= 360.0f ? 0.0f : bearing);
                if (entry.isValid()) {
                    wire.add(entry);
                }
            }
        }
        float range = (float) rangeBlocks;
        return new ScannerPayload(VERSION, status, requirement.minServiceLevel(),
                Math.max(0, requirement.minCapacityTier()), radioLevel, serviceCap, servingBandId,
                Math.max(0, servingBandTier), Float.isFinite(range) && range >= 0.0f ? range : 0.0f, wire);
    }

    // ---- wire ------------------------------------------------------------------------------------

    private static void write(FriendlyByteBuf buf, ScannerPayload payload) {
        buf.writeVarInt(payload.version);
        buf.writeByte(payload.status.ordinal());
        buf.writeByte(payload.neededLevel.ordinal());
        buf.writeVarInt(payload.neededTier);
        buf.writeByte(payload.radioLevel.ordinal());
        buf.writeByte(payload.serviceCap.ordinal());
        buf.writeUtf(payload.servingBandId, MAX_BAND_ID);
        buf.writeVarInt(payload.servingBandTier);
        buf.writeFloat(payload.rangeBlocks);
        buf.writeVarInt(payload.contacts.size());
        for (Contact contact : payload.contacts) {
            buf.writeUtf(contact.typeId(), MAX_TYPE_ID_LENGTH);
            buf.writeFloat(contact.distanceBlocks());
            buf.writeFloat(contact.bearingDegrees());
        }
    }

    /**
     * Arguments are evaluated left to right (JLS 15.7.4), which is the wire order. The contact count is
     * checked before anything is allocated for it, and rejected rather than clamped: the entries past the
     * cap would still be on the wire and desynchronise every field after them.
     */
    private static ScannerPayload read(FriendlyByteBuf buf) {
        int version = buf.readVarInt();
        if (version != VERSION) {
            throw malformed("version " + version + " (this client reads " + VERSION + ")");
        }
        Status status = STATUSES[index(buf.readByte(), STATUSES.length, "status")];
        ServiceLevel neededLevel = level(buf, "needed level");
        int neededTier = nonNegative(buf.readVarInt(), "needed tier");
        ServiceLevel radioLevel = level(buf, "radio level");
        ServiceLevel serviceCap = level(buf, "service cap");
        String bandId = buf.readUtf(MAX_BAND_ID);
        int bandTier = nonNegative(buf.readVarInt(), "band tier");
        float range = buf.readFloat();
        if (!Float.isFinite(range) || range < 0.0f) {
            throw malformed("range " + range);
        }
        int count = buf.readVarInt();
        if (count < 0 || count > MAX_CONTACTS) {
            throw malformed(count + " contacts (max " + MAX_CONTACTS + ")");
        }
        if (status != Status.OK && count > 0) {
            throw malformed(count + " contacts on a refusal (" + status + ")");
        }
        List<Contact> contacts = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            Contact contact = new Contact(buf.readUtf(MAX_TYPE_ID_LENGTH), buf.readFloat(), buf.readFloat());
            if (!contact.isValid()) {
                throw malformed("contact " + contact);
            }
            contacts.add(contact);
        }
        return new ScannerPayload(version, status, neededLevel, neededTier, radioLevel, serviceCap, bandId, bandTier,
                range, contacts);
    }

    private static ServiceLevel level(FriendlyByteBuf buf, String what) {
        return LEVELS[index(buf.readByte(), LEVELS.length, what)];
    }

    private static int index(byte value, int size, String what) {
        if (value < 0 || value >= size) {
            throw malformed("unknown " + what + " " + value);
        }
        return value;
    }

    private static int nonNegative(int value, String what) {
        if (value < 0) {
            throw malformed(what + " is negative (" + value + ")");
        }
        return value;
    }

    private static DecoderException malformed(String detail) {
        return new DecoderException("RANCraft scanner: " + detail);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
