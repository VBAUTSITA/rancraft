package dev.rancraft.device;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.Optional;

/**
 * The Network Locator's emergency record, kept on the player as a NeoForge data attachment that
 * survives death ({@code registry.ModAttachments.LOCATOR_EMERGENCY}, {@code copyOnDeath()}).
 * Phase 3 slice 5, §3A.6.
 *
 * <p><b>Real-world analogue: network-derived emergency caller location.</b> When a phone calls
 * 112 / 911, the network can locate it from the same cell measurements this Locator uses: in LTE
 * and NR a location server (E-SMLC / LMF) runs E-CID, OTDOA or multi-RTT and hands the estimate to
 * the emergency service. What the dispatcher gets is that estimate, with its uncertainty, not the
 * caller's true position. So this record stores the <em>estimate</em>: a fix computed behind a hill
 * sends you back to the wrong place, exactly as a bad network fix sends responders to the wrong
 * door.
 *
 * <ul>
 *   <li>{@link #lastFix}: the latest {@code FIX} the Locator reported while carried (in a hand or
 *       the hotbar), with the game time it was last reported. Only a FIX counts: RANGE ONLY,
 *       AMBIGUOUS, POOR GEOMETRY and NO SIGNAL say nothing new about where you are, so the last
 *       known position stays the last FIX, as a location server's would.
 *   <li>{@link #beforeDeath}: frozen from {@code lastFix} at death, if that fix was younger than
 *       {@code locatorEmergencyMaxAgeTicks}. Shown on the Locator HUD after respawn, until the next
 *       death replaces (or clears) it.
 * </ul>
 *
 * <p>Robust to hand-edited data: a stamp with a non-finite number is dropped rather than failing
 * the player's load. Pure apart from the codec, so it is unit-tested headless.
 */
public record EmergencyRecord(Optional<Stamp> lastFix, Optional<Frozen> beforeDeath) {

    /** Dimension ids are clamped to this many chars (the payload's cap; far above any real id). */
    public static final int MAX_DIMENSION_LENGTH = 256;

    public static final EmergencyRecord EMPTY = new EmergencyRecord(Optional.empty(), Optional.empty());

    /**
     * One position estimate and when the Locator reported it.
     *
     * @param dimension   the dimension id, e.g. {@code minecraft:overworld}.
     * @param y           the fix's assumed eye height (altitude aiding), not a measurement.
     * @param errorBlocks the fix's reported "±" (quantisation only; see {@code LocatorFix.Fix}).
     * @param gameTime    game time the Locator last reported this fix: the evaluation's tick, or a
     *                    later replay of it while the player stood still.
     */
    public record Stamp(String dimension, double x, double y, double z, double errorBlocks, long gameTime) {

        public static final Codec<Stamp> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                Codec.STRING.fieldOf("dimension").forGetter(Stamp::dimension),
                Codec.DOUBLE.fieldOf("x").forGetter(Stamp::x),
                Codec.DOUBLE.fieldOf("y").forGetter(Stamp::y),
                Codec.DOUBLE.fieldOf("z").forGetter(Stamp::z),
                Codec.DOUBLE.fieldOf("error_blocks").forGetter(Stamp::errorBlocks),
                Codec.LONG.fieldOf("game_time").forGetter(Stamp::gameTime)
        ).apply(instance, Stamp::new));

        public Stamp {
            dimension = clampDimension(dimension);
        }

        /** Finite coordinates and a finite, non-negative "±". */
        public boolean isValid() {
            return Double.isFinite(x) && Double.isFinite(y) && Double.isFinite(z)
                    && Double.isFinite(errorBlocks) && errorBlocks >= 0.0;
        }
    }

    /**
     * The last fix before a death.
     *
     * @param deathTick game time of the death.
     */
    public record Frozen(Stamp fix, long deathTick) {

        public static final Codec<Frozen> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                Stamp.CODEC.fieldOf("fix").forGetter(Frozen::fix),
                Codec.LONG.fieldOf("death_tick").forGetter(Frozen::deathTick)
        ).apply(instance, Frozen::new));

        /** How long before the death the fix was last reported, in ticks. */
        public long ageAtDeathTicks() {
            return deathTick - fix.gameTime();
        }
    }

    public static final Codec<EmergencyRecord> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            Stamp.CODEC.optionalFieldOf("last_fix").forGetter(EmergencyRecord::lastFix),
            Frozen.CODEC.optionalFieldOf("before_death").forGetter(EmergencyRecord::beforeDeath)
    ).apply(instance, EmergencyRecord::new));

    public EmergencyRecord {
        lastFix = lastFix == null ? Optional.empty() : lastFix.filter(Stamp::isValid);
        beforeDeath = beforeDeath == null ? Optional.empty()
                : beforeDeath.filter(frozen -> frozen.fix() != null && frozen.fix().isValid());
    }

    /** Nothing recorded: not worth writing to the player's save. */
    public boolean isEmpty() {
        return lastFix.isEmpty() && beforeDeath.isEmpty();
    }

    /** The Locator reported a FIX: it becomes the last fix. The frozen record is untouched. */
    public EmergencyRecord withFix(Stamp fix) {
        return new EmergencyRecord(Optional.of(fix), beforeDeath);
    }

    /**
     * The player died at {@code deathTick}: the last fix is frozen as "last fix before death" if it
     * is younger than {@code maxAgeTicks} (reported at most {@code maxAgeTicks - 1} ticks earlier),
     * otherwise there is none. Either way it replaces the record of any earlier death, and the
     * last fix is cleared: the next life starts with no known position. {@code maxAgeTicks} of 0
     * therefore never freezes anything.
     */
    public EmergencyRecord onDeath(long deathTick, long maxAgeTicks) {
        Optional<Frozen> frozen = lastFix
                .filter(stamp -> {
                    long age = deathTick - stamp.gameTime();
                    return age >= 0L && age < maxAgeTicks;
                })
                .map(stamp -> new Frozen(stamp, deathTick));
        return new EmergencyRecord(Optional.empty(), frozen);
    }

    static String clampDimension(String dimension) {
        if (dimension == null) {
            return "";
        }
        if (dimension.length() <= MAX_DIMENSION_LENGTH) {
            return dimension;
        }
        int end = MAX_DIMENSION_LENGTH;
        if (Character.isHighSurrogate(dimension.charAt(end - 1))) {
            end--;
        }
        return dimension.substring(0, end);
    }
}
