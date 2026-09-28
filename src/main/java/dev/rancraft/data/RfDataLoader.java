package dev.rancraft.data;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.rancraft.RanCraft;
import dev.rancraft.rf.Band;
import dev.rancraft.rf.BandTable;
import dev.rancraft.rf.CellParams;
import java.util.LinkedHashMap;
import java.util.Map;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimpleJsonResourceReloadListener;
import net.minecraft.util.profiling.ProfilerFiller;

/**
 * Loads {@code data/rancraft/rf/bands/*.json} and {@code data/rancraft/rf/materials/attenuation.json}.
 *
 * <p>Defaults ship inside the mod jar but are still read through this loader, so there is exactly
 * one code path and Phase 2 can add a band by dropping in a file.
 *
 * <p>One listener is registered on the {@code rf} directory rather than two on its children, so
 * entries arrive as {@code rancraft:bands/band_900} and {@code rancraft:materials/attenuation}.
 */
public final class RfDataLoader extends SimpleJsonResourceReloadListener {

    private static final Gson GSON = new Gson();
    private static final String DIRECTORY = "rf";

    private static final String BANDS_PREFIX = "bands/";
    private static final String MATERIALS_PATH = "materials/attenuation";

    private static volatile BandTable bands = BandTable.of(Band.DEFAULT_900);

    // Raw material data, kept unresolved until tags are bound. See invalidateMaterials().
    private static volatile double defaultSolidDb = 8.0;
    private static volatile Map<ResourceLocation, Double> rawBlocks = new LinkedHashMap<>();
    private static volatile Map<ResourceLocation, Double> rawTags = new LinkedHashMap<>();
    private static volatile MaterialTable materials;

    public RfDataLoader() {
        super(GSON, DIRECTORY);
    }

    public static BandTable bands() {
        return bands;
    }

    /**
     * Resolved material table.
     *
     * <p>Built lazily rather than during {@link #apply}, because block tags are not bound yet when
     * reload listeners run. {@link #invalidateMaterials()} is called once tags are available.
     */
    public static MaterialTable materials() {
        MaterialTable local = materials;
        if (local == null) {
            synchronized (RfDataLoader.class) {
                local = materials;
                if (local == null) {
                    local = MaterialTable.build(defaultSolidDb, rawBlocks, rawTags);
                    materials = local;
                    RanCraft.LOGGER.info("RANCraft material table resolved for {} blocks (default {} dB)",
                            local.size(), local.defaultSolidDb());
                }
            }
        }
        return local;
    }

    public static void invalidateMaterials() {
        materials = null;
    }

    @Override
    protected void apply(Map<ResourceLocation, JsonElement> entries,
                         ResourceManager resourceManager,
                         ProfilerFiller profiler) {

        Map<String, Band> loadedBands = new LinkedHashMap<>();
        Map<ResourceLocation, Double> blocks = new LinkedHashMap<>();
        Map<ResourceLocation, Double> tags = new LinkedHashMap<>();
        double defaultDb = 8.0;

        for (Map.Entry<ResourceLocation, JsonElement> entry : entries.entrySet()) {
            ResourceLocation key = entry.getKey();
            String path = key.getPath();
            try {
                if (path.startsWith(BANDS_PREFIX)) {
                    String bandId = path.substring(BANDS_PREFIX.length());
                    if (bandId.length() > CellParams.MAX_BAND_ID_LENGTH) {
                        // Every payload carries band ids under this cap; a longer one would make
                        // the encoder throw and disconnect whoever hears such a cell.
                        RanCraft.LOGGER.warn("RANCraft skipped band {}: its id is longer than {} characters",
                                key, CellParams.MAX_BAND_ID_LENGTH);
                        continue;
                    }
                    loadedBands.put(bandId, parseBand(bandId, entry.getValue().getAsJsonObject()));
                } else if (path.equals(MATERIALS_PATH)) {
                    JsonObject json = entry.getValue().getAsJsonObject();
                    if (json.has("default_solid_db")) {
                        defaultDb = json.get("default_solid_db").getAsDouble();
                    }
                    readAttenuationMap(json, "blocks", blocks);
                    readAttenuationMap(json, "tags", tags);
                }
            } catch (RuntimeException failure) {
                RanCraft.LOGGER.error("RANCraft could not parse {}: {}", key, failure.toString());
            }
        }

        if (loadedBands.isEmpty()) {
            RanCraft.LOGGER.warn("RANCraft loaded no bands; falling back to {}", Band.DEFAULT_900.id());
            loadedBands.put(Band.DEFAULT_900.id(), Band.DEFAULT_900);
        }

        Band fallback = loadedBands.getOrDefault(Band.DEFAULT_900.id(), loadedBands.values().iterator().next());
        bands = new BandTable(loadedBands, fallback);

        defaultSolidDb = defaultDb;
        rawBlocks = blocks;
        rawTags = tags;
        invalidateMaterials();

        RanCraft.LOGGER.info("RANCraft loaded {} band(s), {} block override(s), {} tag override(s)",
                loadedBands.size(), blocks.size(), tags.size());
    }

    private static Band parseBand(String bandId, JsonObject json) {
        return new Band(
                bandId,
                json.get("frequency_mhz").getAsDouble(),
                json.has("path_loss_exponent") ? json.get("path_loss_exponent").getAsDouble() : 3.5,
                json.has("penetration_factor") ? json.get("penetration_factor").getAsDouble() : 1.0,
                json.has("noise_floor_dbm")
                        ? json.get("noise_floor_dbm").getAsDouble() : Band.DEFAULT_NOISE_FLOOR_DBM,
                json.has("capacity_tier")
                        ? json.get("capacity_tier").getAsInt() : Band.DEFAULT_CAPACITY_TIER);
    }

    private static void readAttenuationMap(JsonObject json, String member, Map<ResourceLocation, Double> into) {
        if (!json.has(member)) {
            return;
        }
        for (Map.Entry<String, JsonElement> entry : json.getAsJsonObject(member).entrySet()) {
            ResourceLocation id = ResourceLocation.parse(entry.getKey());
            into.put(id, entry.getValue().getAsDouble());
        }
    }
}
