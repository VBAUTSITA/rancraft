package dev.rancraft.data;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.rancraft.rf.MicrowaveLink;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Phase 3 slice 11 (§3C.2): {@code rf/backhaul/microwave.json} loads through {@link RfDataLoader},
 * equals the spec's figures, and is never read as a cellular band.
 *
 * <p>{@link RfDataLoader} keeps its tables in statics, so every test that calls {@code apply} puts back
 * the state a unit test starts with (an empty load: band_900 only, default materials, the default link).
 */
class RfDataLoaderMicrowaveTest {

    private static final String SHIPPED = "data/rancraft/rf/";

    @AfterEach
    void restore() {
        new RfDataLoader().apply(Map.of(), null, null);
    }

    private static JsonObject shipped(String path) throws IOException {
        try (InputStream in = RfDataLoaderMicrowaveTest.class.getClassLoader().getResourceAsStream(SHIPPED + path + ".json")) {
            assertNotNull(in, "missing resource " + path);
            return JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
        }
    }

    private static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath("rancraft", path);
    }

    @Test
    @DisplayName("the shipped microwave.json is exactly the §3C.2 figures")
    void shippedFileIsTheSpec() throws IOException {
        assertEquals(MicrowaveLink.DEFAULT, RfDataLoader.parseMicrowave(shipped(RfDataLoader.MICROWAVE_PATH)));
        assertEquals(new MicrowaveLink(18000.0, 20.0, 32.0, 3.0, -50.0, -70.0, 6.0, 2.5, 6.0), MicrowaveLink.DEFAULT);
    }

    @Test
    @DisplayName("loaded beside the bands, the link is the loader's microwave figures and never a cellular band")
    void neverACellularBand() throws IOException {
        Map<ResourceLocation, JsonElement> entries = new LinkedHashMap<>();
        for (String band : new String[] {"band_700", "band_900", "band_1800", "band_3500"}) {
            entries.put(id("bands/" + band), shipped("bands/" + band));
        }
        JsonObject faster = shipped(RfDataLoader.MICROWAVE_PATH);
        faster.addProperty("frequency_mhz", 23000);
        entries.put(id(RfDataLoader.MICROWAVE_PATH), faster);

        new RfDataLoader().apply(entries, null, null);

        assertEquals(Set.of("band_700", "band_900", "band_1800", "band_3500"), RfDataLoader.bands().all().keySet());
        assertFalse(RfDataLoader.bands().all().values().stream().anyMatch(b -> b.frequencyMhz() > 10_000.0),
                "no cellular band at microwave frequencies");
        assertEquals(23000.0, RfDataLoader.microwave().frequencyMhz());
        assertEquals(MicrowaveLink.DEFAULT.txPowerDbm(), RfDataLoader.microwave().txPowerDbm());
    }

    @Test
    @DisplayName("missing members take the defaults; an invalid or missing file leaves the defaults in force")
    void defaultsAndInvalidFiles() {
        JsonObject partial = new JsonObject();
        partial.addProperty("rain_db_per_km", 3.0);
        MicrowaveLink parsed = RfDataLoader.parseMicrowave(partial);
        assertEquals(3.0, parsed.rainDbPerKm());
        assertEquals(MicrowaveLink.DEFAULT.thunderDbPerKm(), parsed.thunderDbPerKm());
        assertEquals(MicrowaveLink.DEFAULT.frequencyMhz(), parsed.frequencyMhz());

        JsonObject inverted = new JsonObject();
        inverted.addProperty("up_threshold_dbm", -80);
        assertThrows(IllegalArgumentException.class, () -> RfDataLoader.parseMicrowave(inverted));

        new RfDataLoader().apply(Map.of(id(RfDataLoader.MICROWAVE_PATH), inverted), null, null);
        assertSame(MicrowaveLink.DEFAULT, RfDataLoader.microwave());

        new RfDataLoader().apply(Map.of(id(RfDataLoader.MICROWAVE_PATH), partial), null, null);
        assertEquals(3.0, RfDataLoader.microwave().rainDbPerKm());
        // A reload without the file goes back to the defaults.
        new RfDataLoader().apply(Map.of(), null, null);
        assertSame(MicrowaveLink.DEFAULT, RfDataLoader.microwave());
    }
}
