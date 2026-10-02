package dev.rancraft.device;

import dev.rancraft.rf.CellSample;
import dev.rancraft.rf.DeviceRequirement;
import dev.rancraft.rf.ServiceLevel;
import java.util.Objects;
import org.jetbrains.annotations.Nullable;

/**
 * What the Wireless Storage Terminal last heard from the network, for one player (Phase 3 slice 13,
 * §3C.3): the verdict the ticker computed for the terminal's requirement, when, and enough of the
 * sample to tell the player why it failed.
 *
 * <p><b>Devices never compute RF.</b> This is copied from the {@link DeviceContext} the ticker hands
 * the terminal ({@link #of}); nothing here evaluates a signal. The open remote-storage menu checks this
 * record every tick ({@code menu.RemoteContainerMenu#stillValid}), never a fresh evaluation: §3C.3
 * "stillValid checks the last verdict for that player".
 *
 * <p><b>How old a verdict may be.</b> The ticker dispatches to a carried terminal once per evaluation
 * interval, a cached replay included, so a terminal in a hand or the hotbar gets a new record every
 * interval. A record older than {@link #maxAgeTicks two intervals} means the terminal stopped being
 * carried (put away, dropped, or moved by the player while the menu is open): it no longer counts.
 * Two, not one, so a single missed dispatch (the terminal held on the cursor at that tick) does not
 * cut the session.
 *
 * @param verdict         {@code requirement.check(sample, bands, serviceCap)}, as the ticker computed it.
 * @param tick            the game time of the dispatch ({@link DeviceContext#tick()}), not the sample's
 *                        timestamp: a replay of an old evaluation is still a current verdict.
 * @param radio           the sample's own service level, the radio link alone (uncapped).
 * @param serviceCap      the serving cell's backhaul cap at the dispatch ({@link DeviceContext#serviceCap()}).
 * @param servingBandId   the serving cell's band, or {@code null} with no service.
 * @param servingBandTier that band's capacity tier, or 0 with no service.
 */
public record TerminalLink(
        DeviceRequirement.Verdict verdict,
        long tick,
        ServiceLevel radio,
        ServiceLevel serviceCap,
        @Nullable String servingBandId,
        int servingBandTier) {

    public TerminalLink {
        Objects.requireNonNull(verdict, "verdict");
        Objects.requireNonNull(radio, "radio");
        Objects.requireNonNull(serviceCap, "serviceCap");
    }

    /** The record of one dispatch. */
    public static TerminalLink of(DeviceContext ctx) {
        String bandId = null;
        int tier = 0;
        CellSample serving = ctx.sample().serving().orElse(null);
        if (serving != null) {
            bandId = serving.bandId();
            tier = ctx.bands().getOrFallback(bandId).capacityTier();
        }
        return new TerminalLink(ctx.verdict(), ctx.tick(), ctx.sample().serviceLevel(), ctx.serviceCap(),
                bandId, tier);
    }

    /** The service level the terminal actually gets: the radio's, capped by the backhaul. */
    public ServiceLevel effective() {
        return ServiceLevel.worstOf(radio, serviceCap);
    }

    /** The oldest a record may be and still count: two evaluation intervals. */
    public static long maxAgeTicks(int intervalTicks) {
        return 2L * Math.max(1, intervalTicks);
    }

    /** Whether this record is recent enough to count at {@code now}. */
    public boolean fresh(long now, int intervalTicks) {
        return now - tick <= maxAgeTicks(intervalTicks);
    }

    /** Why the terminal cannot carry a session, from what the network last said. */
    public enum Problem {
        /** No record, or one too old: the terminal is not being carried (or has only just been). */
        NO_READING,
        /** No serving cell at all. */
        NO_SERVICE,
        /** The radio link itself is below the requirement: fix the signal. */
        WEAK_SIGNAL,
        /**
         * The radio link alone would do, but the serving cell's backhaul caps it below the requirement
         * (§3C.2's backhaul-limited cell): fix the backhaul, not the antenna.
         */
        BACKHAUL_LIMITED,
        /** Good enough signal on a band of too low a capacity tier: change the band. */
        LOW_TIER
    }

    /**
     * Why a session cannot run on {@code link} at {@code now}, or {@code null} when it can (the verdict
     * is OK and the record fresh). A LOW_QUALITY verdict is {@link Problem#BACKHAUL_LIMITED} when the
     * radio alone reaches {@code requirement}'s level (only the cap failed it), otherwise
     * {@link Problem#WEAK_SIGNAL}: a player told "weak signal" under a capped cell would retilt an
     * antenna that is fine.
     */
    public static @Nullable Problem problemOf(@Nullable TerminalLink link, DeviceRequirement requirement,
                                              long now, int intervalTicks) {
        if (link == null || !link.fresh(now, intervalTicks)) {
            return Problem.NO_READING;
        }
        return switch (link.verdict()) {
            case OK -> null;
            case NO_SERVICE -> Problem.NO_SERVICE;
            case LOW_QUALITY -> link.radio().atLeast(requirement.minServiceLevel())
                    ? Problem.BACKHAUL_LIMITED
                    : Problem.WEAK_SIGNAL;
            case LOW_TIER -> Problem.LOW_TIER;
        };
    }
}
