package dev.rancraft.device;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import dev.rancraft.device.EmergencyRecord.Frozen;
import dev.rancraft.device.EmergencyRecord.Stamp;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The Network Locator's emergency record (Phase 3 slice 5, §3A.6): the last FIX is stamped, frozen
 * at death if it is young enough, kept until the next death, and it is always the estimate.
 */
class EmergencyRecordTest {

    private static final long MAX_AGE = 1200L;

    private static Stamp fixAt(long tick) {
        return new Stamp("minecraft:overworld", 120.4, 71.62, -33.9, 9.0, tick);
    }

    @Test
    @DisplayName("empty until the Locator reports a FIX")
    void emptyByDefault() {
        assertTrue(EmergencyRecord.EMPTY.isEmpty());
        EmergencyRecord record = EmergencyRecord.EMPTY.withFix(fixAt(100));
        assertFalse(record.isEmpty());
        assertEquals(fixAt(100), record.lastFix().orElseThrow());
        assertTrue(record.beforeDeath().isEmpty());
    }

    @Test
    @DisplayName("a newer FIX replaces the last one and leaves the frozen record alone")
    void newerFixReplaces() {
        EmergencyRecord died = EmergencyRecord.EMPTY.withFix(fixAt(100)).onDeath(200, MAX_AGE);
        EmergencyRecord next = died.withFix(fixAt(500)).withFix(fixAt(520));
        assertEquals(fixAt(520), next.lastFix().orElseThrow());
        assertEquals(died.beforeDeath(), next.beforeDeath());
    }

    @Test
    @DisplayName("death freezes a young fix as the last fix before death, and the next life starts with none")
    void deathFreezesAYoungFix() {
        EmergencyRecord record = EmergencyRecord.EMPTY.withFix(fixAt(1_000)).onDeath(1_600, MAX_AGE);
        Frozen frozen = record.beforeDeath().orElseThrow();
        assertEquals(fixAt(1_000), frozen.fix(), "the estimate, exactly as stamped");
        assertEquals(1_600, frozen.deathTick());
        assertEquals(600, frozen.ageAtDeathTicks());
        assertTrue(record.lastFix().isEmpty());
    }

    @Test
    @DisplayName("younger than the limit: 1199 ticks old freezes, 1200 does not")
    void ageBoundary() {
        assertTrue(EmergencyRecord.EMPTY.withFix(fixAt(0)).onDeath(1_199, MAX_AGE).beforeDeath().isPresent());
        assertTrue(EmergencyRecord.EMPTY.withFix(fixAt(0)).onDeath(1_200, MAX_AGE).beforeDeath().isEmpty());
        assertTrue(EmergencyRecord.EMPTY.withFix(fixAt(0)).onDeath(0, 0).beforeDeath().isEmpty(),
                "a limit of 0 never freezes anything");
        assertTrue(EmergencyRecord.EMPTY.withFix(fixAt(500)).onDeath(400, MAX_AGE).beforeDeath().isEmpty(),
                "a fix stamped after the death is not trusted");
    }

    @Test
    @DisplayName("it survives until the next death, which replaces it, or clears it if there is no young fix")
    void nextDeathReplacesOrClears() {
        EmergencyRecord first = EmergencyRecord.EMPTY.withFix(fixAt(100)).onDeath(200, MAX_AGE);
        EmergencyRecord replaced = first.withFix(fixAt(5_000)).onDeath(5_100, MAX_AGE);
        assertEquals(fixAt(5_000), replaced.beforeDeath().orElseThrow().fix());

        EmergencyRecord cleared = first.onDeath(9_000, MAX_AGE);
        assertTrue(cleared.isEmpty(), "no fix in the second life: nothing to report for that death");

        EmergencyRecord stale = first.withFix(fixAt(5_000)).onDeath(9_000, MAX_AGE);
        assertTrue(stale.isEmpty(), "a fix older than the limit is not an emergency location");
    }

    @Test
    @DisplayName("non-finite numbers and a negative error are dropped rather than failing the player's load")
    void invalidStampsDropped() {
        Stamp nan = new Stamp("minecraft:overworld", Double.NaN, 0, 0, 1, 0);
        Stamp negative = new Stamp("minecraft:overworld", 0, 0, 0, -1, 0);
        assertTrue(new EmergencyRecord(Optional.of(nan), Optional.empty()).isEmpty());
        assertTrue(new EmergencyRecord(Optional.of(negative), Optional.empty()).isEmpty());
        assertTrue(new EmergencyRecord(Optional.empty(), Optional.of(new Frozen(nan, 5))).isEmpty());
        assertTrue(new EmergencyRecord(Optional.empty(), Optional.of(new Frozen(null, 5))).isEmpty());
        assertTrue(new EmergencyRecord(null, null).isEmpty());
    }

    @Test
    @DisplayName("the dimension id is clamped to the payload's cap")
    void dimensionClamped() {
        assertEquals(EmergencyRecord.MAX_DIMENSION_LENGTH, new Stamp("d".repeat(999), 0, 0, 0, 0, 0).dimension().length());
        assertEquals("", new Stamp(null, 0, 0, 0, 0, 0).dimension());
    }

    @Test
    @DisplayName("the codec round-trips both halves, and an empty record reads back empty")
    void codecRoundTrip() {
        EmergencyRecord record = EmergencyRecord.EMPTY.withFix(fixAt(100)).onDeath(200, MAX_AGE).withFix(fixAt(900));
        JsonElement json = EmergencyRecord.CODEC.encodeStart(JsonOps.INSTANCE, record).getOrThrow();
        assertEquals(record, EmergencyRecord.CODEC.parse(JsonOps.INSTANCE, json).getOrThrow());
        assertEquals(EmergencyRecord.EMPTY,
                EmergencyRecord.CODEC.parse(JsonOps.INSTANCE, JsonParser.parseString("{}")).getOrThrow());
    }
}
