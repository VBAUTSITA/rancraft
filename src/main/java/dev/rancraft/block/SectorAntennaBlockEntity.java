package dev.rancraft.block;

import dev.rancraft.registry.ModBlockEntities;
import dev.rancraft.rf.CellParams;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.state.BlockState;

/**
 * A directional sector antenna. Each one is its own cell -- a three-sector site is three of these
 * at azimuths 0/120/240, not one block pretending to be three.
 *
 * <p>Defaults are a real 65 x 10 degree sector panel: 15 dBi, 3 degrees of downtilt, band_900.
 * {@code azimuthDeg} is set from the block facing on placement so it works before the player ever
 * opens the GUI.
 */
public class SectorAntennaBlockEntity extends AntennaBlockEntity {

    public static final double DEFAULT_H_BEAMWIDTH_DEG = 65.0;
    public static final double DEFAULT_V_BEAMWIDTH_DEG = 10.0;
    public static final double DEFAULT_TILT_DEG = 3.0;

    public SectorAntennaBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.SECTOR_ANTENNA.get(), pos, state);

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
