package dev.rancraft.block;

import dev.rancraft.RanCraftConfig;
import net.neoforged.neoforge.energy.IEnergyStorage;
import org.jetbrains.annotations.Nullable;

/**
 * An antenna's FE capability (Phase 3 slice 15, §3C.5): NeoForge's {@code IEnergyStorage} over the
 * buffer of the antenna that owns the cell ({@link AntennaBlockEntity#bufferOwner()}), so a generator
 * or any other mod's cable can fill it. Registered on every side for Signal Masts and Sector Antennas
 * ({@code registry.ModCapabilities}).
 *
 * <ul>
 *   <li><b>Receive-only.</b> An antenna is a load: nothing can extract from it.</li>
 *   <li><b>Nothing with {@code requirePower} off</b> (the default): {@link #canReceive()} is false and
 *       {@link #receiveEnergy} takes nothing, so a generator next to an antenna does not burn fuel into
 *       a buffer no cell reads, and a Phase 2 world plays exactly as before.</li>
 *   <li>The owner is looked up on every call, so a column that grew, shrank or became a mounting pole
 *       is never fed through a stale answer.</li>
 * </ul>
 */
public final class SiteEnergyStorage implements IEnergyStorage {

    private final AntennaBlockEntity antenna;

    SiteEnergyStorage(AntennaBlockEntity antenna) {
        this.antenna = antenna;
    }

    private @Nullable AntennaBlockEntity owner() {
        return antenna.isRemoved() ? null : antenna.bufferOwner();
    }

    @Override
    public int receiveEnergy(int toReceive, boolean simulate) {
        if (!RanCraftConfig.requirePower()) {
            return 0;
        }
        AntennaBlockEntity owner = owner();
        if (owner == null || owner.isRemoved()) {
            return 0;
        }
        int taken = owner.energyBuffer().receive(toReceive, RanCraftConfig.powerBufferFe(), simulate);
        if (taken > 0 && !simulate) {
            owner.markEnergyChanged();
        }
        return taken;
    }

    @Override
    public int extractEnergy(int toExtract, boolean simulate) {
        return 0;
    }

    @Override
    public int getEnergyStored() {
        AntennaBlockEntity owner = owner();
        return owner == null ? 0 : Math.min(owner.energyBuffer().stored(), getMaxEnergyStored());
    }

    @Override
    public int getMaxEnergyStored() {
        return RanCraftConfig.powerBufferFe();
    }

    @Override
    public boolean canExtract() {
        return false;
    }

    @Override
    public boolean canReceive() {
        return RanCraftConfig.requirePower();
    }
}
