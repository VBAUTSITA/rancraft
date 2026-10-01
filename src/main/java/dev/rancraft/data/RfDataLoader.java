package dev.rancraft.data;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.rancraft.RanCraft;
import dev.rancraft.rf.Band;
import dev.rancraft.rf.BandTable;
import dev.rancraft.rf.CellParams;
import dev.rancraft.rf.MicrowaveLink;
import java.util.LinkedHashMap;
import java.util.Map;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimpleJsonResourceReloadListener;
import net.minecraft.util.profiling.ProfilerFiller;

/**
 * Loads {@code data/rancraft/rf/bands/*.json}, {@code data/rancraft/rf/materials/attenuation.json}
 * and (Phase 3 slice 11) {@code data/rancraft/rf/backhaul/microwave.json}.
 *
 * <p>Defaults ship inside the mod jar but are still read through this loader, so there is exactly
 * one code path and Phase 2 can add a band by dropping in a file.
 *
 * <p>One listener is registered on the {@code rf} directory rather than two on its children, so
 * entries arrive as {@code rancraft:bands/band_900}, {@code rancraft:materials/attenuation} and
 * {@code rancraft:backhaul/microwave}. Only paths under {@code bands/} become cellular bands, so the
 * microwave link's 18 GHz, in its own folder on purpose (§3C.2), never appears as one.
 */
public final class RfDataLoader extends SimpleJsonResourceReloadListener {

    private static final Gson GSON = new Gson();
    private static final String DIRECTORY = "rf";

    private static final String BANDS_PREFIX = "bands/";
    private static final String MATERIALS_PATH = "materials/attenuation";
    static final String MICROWAVE_PATH = "backhaul/microwave";

    private static volatile BandTable bands = BandTable.of(Band.DEFAULT_900);

    private static volatile MicrowaveLink microwave = MicrowaveLink.DEFAULT;

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
     * The microwave backhaul link's parameters (§3C.2), from {@code rf/backhaul/microwave.json};
     * {@link MicrowaveLink#DEFAULT} until data loads, and when the file is missing or invalid.
     */
    public static MicrowaveLink microwave() {
        return microwave;
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
        MicrowaveLink loadedMicrowave = null;

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
                } else if (path.equals(MICROWAVE_PATH)) {
                    loadedMicrowave = parseMicrowave(entry.getValue().getAsJsonObject());
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

        if (loadedMicrowave == null) {
            RanCraft.LOGGER.warn("RANCraft loaded no valid rf/{}.json; the microwave backhaul uses its built-in figures",
                    MICROWAVE_PATH);
            loadedMicrowave = MicrowaveLink.DEFAULT;
        }
        microwave = loadedMicrowave;

        RanCraft.LOGGER.info("RANCraft loaded {} band(s), {} block override(s), {} tag override(s)",
                loadedBands.size(), blocks.size(), tags.size());
        RanCraft.LOGGER.info("RANCraft microwave backhaul: {} MHz, {} dBm, {} dBi dishes, UP >= {} dBm, DEGRADED >= {} dBm",
                loadedMicrowave.frequencyMhz(), loadedMicrowave.txPowerDbm(), loadedMicrowave.dishGainDbi(),
                loadedMicrowave.upThresholdDbm(), loadedMicrowave.degradedThresholdDbm());
    }

    /**
     * Phase 3 slice 11: the microwave backhaul link (§3C.2). Every member is optional and falls back to
     * {@link MicrowaveLink#DEFAULT}'s figure, as a band's optional members do; members it does not know
     * (such as {@code _comment}) are ignored. Values that make no sense (a non-positive frequency, the
     * DEGRADED threshold above the UP one, a negative loss) throw, so {@link #apply} logs the file as
     * unparseable and the defaults stay in force.
     */
    static MicrowaveLink parseMicrowave(JsonObject json) {
        MicrowaveLink d = MicrowaveLink.DEFAULT;
        return new MicrowaveLink(
                number(json, "frequency_mhz", d.frequencyMhz()),
                number(json, "tx_power_dbm", d.txPowerDbm()),
                number(json, "dish_gain_dbi", d.dishGainDbi()),
                number(json, "penetration_factor", d.penetrationFactor()),
                number(json, "up_threshold_dbm", d.upThresholdDbm()),
                number(json, "degraded_threshold_dbm", d.degradedThresholdDbm()),
                number(json, "fresnel_penalty_db", d.fresnelPenaltyDb()),
                number(json, "rain_db_per_km", d.rainDbPerKm()),
                number(json, "thunder_db_per_km", d.thunderDbPerKm()));
    }

    private static double number(JsonObject json, String member, double fallback) {
        return json.has(member) ? json.get(member).getAsDouble() : fallback;
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
                        ? json.get("capacity_tier").getAsInt() : Band.DEFAULT_CAPACITY_TIER,
                parseBandwidthMhz(bandId, json));
    }

    /**
     * Phase 3: {@code bandwidth_mhz}, defaulting to {@link Band#DEFAULT_BANDWIDTH_MHZ} when absent,
     * so pre-Phase-3 band JSON keeps loading. A value that is not a positive finite number would make
     * the ranging resolution (c / bandwidth) meaningless, so it is replaced by the default with a
     * warning rather than dropping the whole band.
     */
    private static double parseBandwidthMhz(String bandId, JsonObject json) {
        if (!json.has("bandwidth_mhz")) {
            return Band.DEFAULT_BANDWIDTH_MHZ;
        }
        double bandwidthMhz = json.get("bandwidth_mhz").getAsDouble();
        if (!Double.isFinite(bandwidthMhz) || bandwidthMhz <= 0.0) {
            RanCraft.LOGGER.warn("RANCraft band {}: bandwidth_mhz {} is not a positive number; using {}",
                    bandId, bandwidthMhz, Band.DEFAULT_BANDWIDTH_MHZ);
            return Band.DEFAULT_BANDWIDTH_MHZ;
        }
        return bandwidthMhz;
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
