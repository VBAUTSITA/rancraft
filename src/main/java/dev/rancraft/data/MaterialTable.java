package dev.rancraft.data;

import it.unimi.dsi.fastutil.objects.Reference2DoubleMap;
import it.unimi.dsi.fastutil.objects.Reference2DoubleOpenHashMap;
import java.util.Map;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Per-block attenuation, fully resolved ahead of time.
 *
 * <p>The whole point of this class is that the ray march never does a string or tag lookup. Every
 * block in the registry is resolved once, on reload, into a {@link Reference2DoubleMap} keyed by
 * block identity, so a voxel probe is one reference hash lookup.
 *
 * <p>Resolution order per block: exact block id, then block tag (JSON declaration order), then
 * air/non-occluding to 0, then the configured default for anything else solid.
 */
public final class MaterialTable {

    private final Reference2DoubleMap<Block> byBlock;
    private final double defaultSolidDb;

    private MaterialTable(Reference2DoubleMap<Block> byBlock, double defaultSolidDb) {
        this.byBlock = byBlock;
        this.defaultSolidDb = defaultSolidDb;
    }

    public static MaterialTable build(double defaultSolidDb,
                                      Map<ResourceLocation, Double> blocks,
                                      Map<ResourceLocation, Double> tags) {
        Reference2DoubleOpenHashMap<Block> resolved = new Reference2DoubleOpenHashMap<>();
        resolved.defaultReturnValue(0.0);

        for (Block block : BuiltInRegistries.BLOCK) {
            resolved.put(block, resolve(block, defaultSolidDb, blocks, tags));
        }
        resolved.trim();
        return new MaterialTable(resolved, defaultSolidDb);
    }

    private static double resolve(Block block,
                                  double defaultSolidDb,
                                  Map<ResourceLocation, Double> blocks,
                                  Map<ResourceLocation, Double> tags) {
        ResourceLocation id = BuiltInRegistries.BLOCK.getKey(block);
        Double exact = blocks.get(id);
        if (exact != null) {
            return exact;
        }

        BlockState state = block.defaultBlockState();

        for (Map.Entry<ResourceLocation, Double> entry : tags.entrySet()) {
            TagKey<Block> tag = TagKey.create(Registries.BLOCK, entry.getKey());
            if (state.is(tag)) {
                return entry.getValue();
            }
        }

        if (state.isAir()) {
            return 0.0;
        }
        // Panes, torches, plants, rails: they occupy a voxel but do not block it.
        if (!state.canOcclude()) {
            return 0.0;
        }
        return defaultSolidDb;
    }

    /**
     * Attenuation for one voxel, in dB.
     *
     * <p>Phase 1 keys on the block only. Waterlogging is not added on top of the host block; see
     * NOTES.md.
     */
    public double attenuationDb(BlockState state) {
        if (state.isAir()) {
            return 0.0;
        }
        return byBlock.getDouble(state.getBlock());
    }

    public double defaultSolidDb() {
        return defaultSolidDb;
    }

    public int size() {
        return byBlock.size();
    }
}
