package dev.rancraft.rf;

/** Terse {@link CellParams} construction for tests, so the 12-argument record stays readable. */
final class TestCells {

    private TestCells() {
    }

    static CellParams sector(long id, int x, int y, int z, double azimuthDeg, double tiltDeg) {
        return new CellParams(id, x, y, z, "band_900", 20.0, 15.0,
                azimuthDeg, tiltDeg, 65.0, 10.0, 0);
    }

    /**
     * A minimal evaluated sample. Selection only reads the cell id and the RSRP, so the rest is
     * filler -- this is the one place to touch when CellSample gains fields.
     */
    static CellSample sample(long id, double rsrpDbm) {
        return sample(id, rsrpDbm, "band_900", 0);
    }

    static CellSample sample(long id, double rsrpDbm, String bandId, int pci) {
        return new CellSample(id, 0, 64, 0, rsrpDbm, 100.0, 100.0, 0.0,
                bandId, pci, 15.0, 0.0, 0.0);
    }

    static CellParams omni(long id, int x, int y, int z) {
        return CellParams.omniDefaults(id, x, y, z);
    }

    /** Same cell with a different horizontal beamwidth. */
    static CellParams withHBeamwidth(CellParams cell, double hBeamwidthDeg) {
        return new CellParams(cell.cellId(), cell.x(), cell.y(), cell.z(), cell.bandId(),
                cell.txPowerDbm(), cell.gainDbi(), cell.azimuthDeg(), cell.tiltDeg(),
                hBeamwidthDeg, cell.vBeamwidthDeg(), cell.pci());
    }

    /** Same cell with a different vertical beamwidth. */
    static CellParams withVBeamwidth(CellParams cell, double vBeamwidthDeg) {
        return new CellParams(cell.cellId(), cell.x(), cell.y(), cell.z(), cell.bandId(),
                cell.txPowerDbm(), cell.gainDbi(), cell.azimuthDeg(), cell.tiltDeg(),
                cell.hBeamwidthDeg(), vBeamwidthDeg, cell.pci());
    }

    static CellParams withTilt(CellParams cell, double tiltDeg) {
        return new CellParams(cell.cellId(), cell.x(), cell.y(), cell.z(), cell.bandId(),
                cell.txPowerDbm(), cell.gainDbi(), cell.azimuthDeg(), tiltDeg,
                cell.hBeamwidthDeg(), cell.vBeamwidthDeg(), cell.pci());
    }

    static CellParams withAzimuth(CellParams cell, double azimuthDeg) {
        return new CellParams(cell.cellId(), cell.x(), cell.y(), cell.z(), cell.bandId(),
                cell.txPowerDbm(), cell.gainDbi(), azimuthDeg, cell.tiltDeg(),
                cell.hBeamwidthDeg(), cell.vBeamwidthDeg(), cell.pci());
    }

    static CellParams withBand(CellParams cell, String bandId) {
        return new CellParams(cell.cellId(), cell.x(), cell.y(), cell.z(), bandId,
                cell.txPowerDbm(), cell.gainDbi(), cell.azimuthDeg(), cell.tiltDeg(),
                cell.hBeamwidthDeg(), cell.vBeamwidthDeg(), cell.pci());
    }

    static CellParams withPci(CellParams cell, int pci) {
        return new CellParams(cell.cellId(), cell.x(), cell.y(), cell.z(), cell.bandId(),
                cell.txPowerDbm(), cell.gainDbi(), cell.azimuthDeg(), cell.tiltDeg(),
                cell.hBeamwidthDeg(), cell.vBeamwidthDeg(), pci);
    }

    static CellParams withGain(CellParams cell, double gainDbi) {
        return new CellParams(cell.cellId(), cell.x(), cell.y(), cell.z(), cell.bandId(),
                cell.txPowerDbm(), gainDbi, cell.azimuthDeg(), cell.tiltDeg(),
                cell.hBeamwidthDeg(), cell.vBeamwidthDeg(), cell.pci());
    }
}
