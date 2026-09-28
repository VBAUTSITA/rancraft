package dev.rancraft.rf;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Immutable band lookup, rebuilt once per datapack reload.
 *
 * <p>Phase 2 adds bands by dropping JSON into {@code data/rancraft/rf/bands/}; nothing here
 * changes.
 */
public final class BandTable {

    private final Map<String, Band> bands;
    private final Band fallback;

    public BandTable(Map<String, Band> bands, Band fallback) {
        this.bands = Collections.unmodifiableMap(new LinkedHashMap<>(bands));
        this.fallback = fallback;
    }

    public static BandTable of(Band... bands) {
        Map<String, Band> map = new LinkedHashMap<>();
        for (Band band : bands) {
            map.put(band.id(), band);
        }
        return new BandTable(map, bands.length > 0 ? bands[0] : Band.DEFAULT_900);
    }

    public Optional<Band> get(String id) {
        return Optional.ofNullable(bands.get(id));
    }

    /** Never null: an unknown band id degrades to the fallback rather than killing the sample. */
    public Band getOrFallback(String id) {
        Band band = bands.get(id);
        return band != null ? band : fallback;
    }

    public Map<String, Band> all() {
        return bands;
    }

    public boolean isEmpty() {
        return bands.isEmpty();
    }
}
