package dev.rancraft.item;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import io.netty.buffer.Unpooled;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.RegistryFriendlyByteBuf;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * RF Vision Step 3a appended {@code showTrail} to the lens settings. These pin the save migration
 * (a lens saved before 3a) and the wire round trip. Only DFU, Gson and a Netty buffer are touched,
 * so this runs headless.
 */
class LensSettingsTest {

    private static LensSettings decode(String json) {
        return LensSettings.CODEC.parse(JsonOps.INSTANCE, JsonParser.parseString(json)).getOrThrow();
    }

    private static JsonElement encode(LensSettings settings) {
        return LensSettings.CODEC.encodeStart(JsonOps.INSTANCE, settings).getOrThrow();
    }

    @Test
    @DisplayName("an empty component decodes to DEFAULT, which shows every layer including the trail")
    void emptyIsDefault() {
        assertEquals(LensSettings.DEFAULT, decode("{}"));
        assertEquals(LensLayers.ALL, LensSettings.DEFAULT.layers());
        assertTrue(LensSettings.DEFAULT.showTrail());
    }

    @Test
    @DisplayName("a pre-3a lens left on ALL gains the trail, because ALL now includes it")
    void legacyAllGainsTrail() {
        LensSettings settings = decode("{\"show_lobes\":true,\"show_links\":true,\"show_coverage\":true}");
        assertTrue(settings.showTrail());
        assertEquals(LensLayers.ALL, settings.layers());
    }

    @Test
    @DisplayName("a pre-3a lens narrowed to one layer stays on that preset instead of gaining the trail")
    void legacySingleLayersStayPut() {
        // Written as a pre-3a item would be: default-valued fields omitted, no show_trail at all.
        assertEquals(LensLayers.ANTENNAS, decode("{\"show_links\":false,\"show_coverage\":false}").layers());
        assertEquals(LensLayers.LINKS, decode("{\"show_lobes\":false,\"show_coverage\":false}").layers());
        assertEquals(LensLayers.COVERAGE, decode("{\"show_lobes\":false,\"show_links\":false}").layers());
    }

    @Test
    @DisplayName("the band filter of a pre-3a lens survives the migration")
    void legacyBandFilterKept() {
        LensSettings settings = decode("{\"band_filter\":\"band_1800\",\"show_lobes\":false,\"show_coverage\":false}");
        assertEquals("band_1800", settings.bandFilter());
        assertEquals(LensLayers.LINKS, settings.layers());
    }

    @Test
    @DisplayName("every preset survives a save and load, and show_trail is always written")
    void presetsRoundTrip() {
        for (LensLayers layers : LensLayers.values()) {
            LensSettings settings = LensSettings.DEFAULT.withBandFilter("band_3500").withLayers(layers);
            JsonElement saved = encode(settings);
            assertTrue(saved.getAsJsonObject().has("show_trail"), layers + " did not write show_trail");
            assertEquals(settings, decode(saved.toString()), layers + " changed on reload");
        }
    }

    @Test
    @DisplayName("the network codec round-trips all five fields")
    void streamCodecRoundTrip() {
        for (LensLayers layers : LensLayers.values()) {
            LensSettings settings = LensSettings.DEFAULT.withBandFilter("band_700").withLayers(layers);
            RegistryFriendlyByteBuf buf = new RegistryFriendlyByteBuf(Unpooled.buffer(), RegistryAccess.EMPTY);
            LensSettings.STREAM_CODEC.encode(buf, settings);
            assertEquals(settings, LensSettings.STREAM_CODEC.decode(buf), layers + " changed on the wire");
            assertEquals(0, buf.readableBytes(), layers + " left bytes unread");
        }
    }

    @Test
    @DisplayName("withBandFilter and withShowLobes carry the trail flag through")
    void withersKeepTrail() {
        LensSettings trailOnly = LensSettings.DEFAULT.withLayers(LensLayers.TRAIL);
        assertTrue(trailOnly.withBandFilter("band_900").showTrail());
        assertTrue(trailOnly.withShowLobes(true).showTrail());
    }
}
