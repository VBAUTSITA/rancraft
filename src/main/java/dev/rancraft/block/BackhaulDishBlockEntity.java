package dev.rancraft.block;

import dev.rancraft.registry.ModBlockEntities;
import dev.rancraft.world.BackhaulNetwork;
import java.util.Objects;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

/**
 * One end of a point-to-point microwave backhaul hop (Phase 3 slice 12, §3C.2): the Backhaul Dish's
 * entity. It stores its partner's position (§3C.2: "both block entities store the partner's position"),
 * set by the Link Tool, and tells the dimension's {@link BackhaulNetwork} that a dish is here.
 *
 * <p><b>Game abstraction, labelled (§6, "automatic dish alignment"):</b> pairing is all it takes. The
 * two dishes are taken as perfectly aimed at each other whatever the geometry, so both keep their full
 * gain ({@code MicrowaveLink}). Real alignment is fiddly field work: at 18 GHz a 32 dBi dish has a beam
 * about two degrees wide, and a dish a degree off loses several dB.
 *
 * <p><b>Who is paired with whom</b> is the network's record, saved with the dimension; this entity
 * mirrors it. A dish paired while its partner's chunk was unloaded cannot have its entity updated
 * then, so when an entity loads it takes the network's partner ({@link BackhaulNetwork#dishLoaded}).
 *
 * <p>Lifecycle, as {@link CoreSiteBlockEntity}: {@link #clearRemoved()} (and {@link #onLoad()}) announce
 * the dish; {@link #setRemoved()} removes it, and unpairs its partner, when the block is broken or
 * replaced, but not when its chunk unloads.
 *
 * <p><b>Saved:</b> {@code DataVersion} ({@value #DATA_VERSION}) and {@code Partner} (a packed position,
 * absent when not paired).
 */
public class BackhaulDishBlockEntity extends BlockEntity {

    /** The dish's own save format. <b>1</b>: Phase 3 slice 12. */
    public static final int DATA_VERSION = 1;

    private static final String PARTNER_TAG = "Partner";

    private @Nullable BlockPos partner;
    private boolean unloading;
    /** Set when the network's partner replaced the saved one; saved at the next {@link #onLoad()}. */
    private boolean adopted;

    public BackhaulDishBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.BACKHAUL_DISH.get(), pos, state);
    }

    /** The paired dish, or {@code null}. */
    public @Nullable BlockPos partner() {
        return partner;
    }

    /**
     * The network changed this dish's pairing (the Link Tool, or the partner was broken). Server side;
     * marks the entity for saving.
     */
    public void partnerFromNetwork(@Nullable BlockPos newPartner) {
        if (!Objects.equals(partner, newPartner)) {
            partner = newPartner == null ? null : newPartner.immutable();
            setChanged();
        }
    }

    @Override
    public void clearRemoved() {
        super.clearRemoved();
        announce();
    }

    @Override
    public void onLoad() {
        super.onLoad();
        announce();
        if (adopted) {
            // Not from clearRemoved: that runs while the chunk is being promoted, and setChanged looks
            // the chunk up again. Here the chunk is loaded.
            adopted = false;
            setChanged();
        }
    }

    /** Joins the network, taking its record of the partner if it has one. */
    private void announce() {
        if (level instanceof ServerLevel serverLevel) {
            unloading = false;
            BlockPos fromNetwork = BackhaulNetwork.of(serverLevel).dishLoaded(getBlockPos(), partner);
            if (!Objects.equals(partner, fromNetwork)) {
                partner = fromNetwork;
                adopted = true;
            }
        }
    }

    @Override
    public void onChunkUnloaded() {
        super.onChunkUnloaded();
        unloading = true;
    }

    @Override
    public void setRemoved() {
        if (!unloading && level instanceof ServerLevel serverLevel) {
            BackhaulNetwork.of(serverLevel).dishRemoved(serverLevel, getBlockPos());
        }
        super.setRemoved();
    }

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        tag.putInt("DataVersion", DATA_VERSION);
        if (partner != null) {
            tag.putLong(PARTNER_TAG, partner.asLong());
        }
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        partner = tag.contains(PARTNER_TAG) ? BlockPos.of(tag.getLong(PARTNER_TAG)) : null;
    }
}
