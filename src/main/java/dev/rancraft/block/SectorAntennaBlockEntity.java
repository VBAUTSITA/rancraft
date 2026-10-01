package dev.rancraft.block;

import dev.rancraft.registry.ModBlockEntities;
import dev.rancraft.rf.CellParams;
import dev.rancraft.rf.RadioTier;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.Clearable;
import net.minecraft.world.level.block.state.BlockState;

/**
 * A directional sector antenna. Each one is its own cell -- a three-sector site is three of these
 * at azimuths 0/120/240, not one block pretending to be three.
 *
 * <p>Defaults are a real 65 x 10 degree sector panel: 15 dBi, 3 degrees of downtilt, band_900.
 * {@code azimuthDeg} is set from the block facing on placement so it works before the player ever
 * opens the GUI.
 *
 * <p><b>Radio tier (Phase 3 slice 10, §3C.1).</b> A sector's radio is tier {@link #RADIO_TIER} (2):
 * bands of capacity tier 2 or less (band_700, band_900, band_1800). A Wideband Radio Unit used on it
 * raises it to {@link RadioTier#WIDEBAND} (3, adds band_3500) and is consumed; breaking the antenna
 * drops the unit again ({@link SectorAntennaBlock#onRemove}). A sector at tier 3 or above counts as
 * holding one unit, whether it was installed or granted by the v2 to v3 migration (a Phase 2 sector
 * already on band_3500): that one drops a unit too, so moving it keeps band_3500 working.
 */
public class SectorAntennaBlockEntity extends AntennaBlockEntity implements Clearable {

    public static final double DEFAULT_H_BEAMWIDTH_DEG = 65.0;
    public static final double DEFAULT_V_BEAMWIDTH_DEG = 10.0;
    public static final double DEFAULT_TILT_DEG = 3.0;

    /** A sector's own radio tier (§3C.1): capacity tiers 1 and 2. */
    public static final int RADIO_TIER = 2;

    public SectorAntennaBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.SECTOR_ANTENNA.get(), pos, state, RADIO_TIER);

        bandId = CellParams.DEFAULT_BAND_ID;
        txPowerDbm = CellParams.DEFAULT_TX_DBM;
        hBeamwidthDeg = DEFAULT_H_BEAMWIDTH_DEG;
        vBeamwidthDeg = DEFAULT_V_BEAMWIDTH_DEG;
        tiltDeg = DEFAULT_TILT_DEG;
        gainDbi = derivedGainDbi();

        // needsPciAssignment already defaults true from AntennaBlockEntity's field initializer,
        // which is what covers a fresh placement -- no need to repeat it here. (It used to be set
        // here explicitly, which is exactly how the Signal Mast ended up never requesting a PCI at
        // all: the same line was never added to its constructor. It now lives in the base class so
        // it cannot be forgotten by a subclass again.)
    }

    /** Whether this sector holds a Wideband Radio Unit: its radio is at {@link RadioTier#WIDEBAND} or above. */
    public boolean hasWidebandUnit() {
        return radioTier >= RadioTier.WIDEBAND;
    }

    /**
     * Fits a Wideband Radio Unit: the radio becomes tier {@link RadioTier#WIDEBAND}. Server side; the
     * caller consumes the item. Returns false (and changes nothing) when one is already fitted.
     *
     * <p>Nothing about the cell changes: the antenna keeps its band until the player picks another,
     * so the registry is not touched. The new tier reaches clients in the update tag, and the next
     * configuration screen opened shows band_3500 unlocked.
     */
    public boolean installWidebandUnit() {
        if (hasWidebandUnit()) {
            return false;
        }
        radioTier = RadioTier.WIDEBAND;
        setChanged();
        syncToClients();
        return true;
    }

    /**
     * Takes the unit out without dropping it, as vanilla clears a chest: {@code /setblock},
     * {@code /fill}, {@code /clone} and structure placement call this before they replace the block,
     * so a command does not spill the unit the way breaking does.
     */
    @Override
    public void clearContent() {
        radioTier = defaultRadioTier();
        setChanged();
    }

    /**
     * Points the antenna the way the player faced it.
     *
     * <p>Compass convention, not vanilla yaw: N=0, E=90, S=180, W=270. See
     * {@code AntennaGeometry} -- getting this mapping wrong is a silent 90-degree azimuth error.
     */
    public void setAzimuthFromFacing(Direction facing) {
        azimuthDeg = switch (facing) {
            case NORTH -> 0.0;
            case EAST -> 90.0;
            case SOUTH -> 180.0;
            case WEST -> 270.0;
            default -> 0.0;
        };
        setChanged();
        syncToClients();
    }
}
