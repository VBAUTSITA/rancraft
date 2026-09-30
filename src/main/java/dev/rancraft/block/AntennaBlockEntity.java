package dev.rancraft.block;

import dev.rancraft.RanCraft;
import dev.rancraft.RanCraftConfig;
import dev.rancraft.rf.CellParams;
import dev.rancraft.rf.ParabolicPattern;
import dev.rancraft.rf.PciPlanner;
import dev.rancraft.world.SiteRegistry;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;

/**
 * Everything a radio site persists, shared by the omni Signal Mast and the Sector Antenna.
 *
 * <p>Both are independent cells as far as {@code SiteRegistry} is concerned; the only difference is
 * their defaults and whether they have a facing. (Since Phase 3 slice 6 a column of stacked masts is
 * one cell, owned by its lowest mast: see {@link SignalMastBlockEntity}.) There is deliberately no "site" container block --
 * a three-sector site is three blocks at azimuths 0/120/240, and Phase 4's planning table will group
 * them by proximity.
 *
 * <p>Passive. Antennas never tick; the receiver drives evaluation, so idle sites cost nothing.
 */
public abstract class AntennaBlockEntity extends BlockEntity {

    /**
     * Persisted shape version.
     *
     * <ul>
     *   <li><b>1</b> -- Phase 1. All fields present but azimuth/tilt/beamwidths/PCI were inert seams.
     *   <li><b>2</b> -- Phase 2. The same fields are now live, and a PCI of 0 written by Phase 1
     *       means "never assigned" rather than "deliberately PCI 0".
     * </ul>
     */
    public static final int DATA_VERSION = 2;

    /** The update tag's on-air flag (slice 6). Never saved; see {@link #getUpdateTag}. */
    public static final String ON_AIR_TAG = "OnAir";

    /** Debounce so migrating a large world logs a running total, not one line per tower. */
    private static final long MIGRATION_LOG_INTERVAL_MILLIS = 10_000L;

    private static final AtomicInteger MIGRATED_COUNT = new AtomicInteger();
    private static volatile long lastMigrationLogMillis = 0L;

    protected String bandId = CellParams.DEFAULT_BAND_ID;
    protected double txPowerDbm = CellParams.DEFAULT_TX_DBM;
    protected double gainDbi = CellParams.DEFAULT_GAIN_DBI;
    protected double azimuthDeg = 0.0;
    protected double tiltDeg = 0.0;
    protected double hBeamwidthDeg = CellParams.OMNI_H_BEAMWIDTH_DEG;
    protected double vBeamwidthDeg = CellParams.DEFAULT_V_BEAMWIDTH_DEG;
    protected int pci = 0;

    /**
     * Set when a Phase 1 site loads, or when a freshly placed antenna has not been planned yet.
     * Assignment is deferred to {@link #onLoad()} because it needs neighbouring sites, and the
     * registry is not populated while NBT is being read.
     *
     * <p>Defaults true in the constructor, which runs for every fresh placement <em>and</em> every
     * load from disk alike -- the same factory is used for both. {@link #loadAdditional} clears it
     * back to false whenever real persisted data actually arrives, so a genuine reload never
     * re-assigns a PCI a player configured deliberately. Only a fresh, never-persisted entity keeps
     * this true all the way to {@link #onLoad()}.
     */
    protected boolean needsPciAssignment = true;

    /**
     * Whether this antenna is on the air: registered as a transmitting cell. Phase 3 slice 6 (§3B.1).
     *
     * <p>Server: set by {@link #refreshRegistration()} and pushed to clients when it changes. Client:
     * the value from the server's update tag ({@code OnAir}), which the RF Lens uses to draw an
     * off-air cell greyed out. It is the antenna's own public state, like a furnace being lit: the
     * client learns <em>that</em> a cell is off the air, never why (redstone now; backhaul and power
     * in §3C) and never anything it would receive. Not persisted: the server works it out again on
     * load, so the save format and {@code DATA_VERSION} are unchanged.
     *
     * <p>Starts true, so a client that has not been told (or a server that never sends the flag)
     * draws the antenna as before.
     */
    private boolean onAir = true;

    protected AntennaBlockEntity(BlockEntityType<?> type, BlockPos pos, BlockState state) {
        super(type, pos, state);
    }

    // ---- identity -----------------------------------------------------------

    /** Stable per-position id. */
    public long cellId() {
        return getBlockPos().asLong();
    }

    /**
     * The radiating point: the top face of the block, not the block itself. Consistent with Phase 1
     * and with sector antennas. A mast column's base overrides it with the top of its column
     * (Phase 3 slice 6, {@link SignalMastBlockEntity#radiatingPoint()}), so nothing may assume the
     * radiating point equals the block position or the block above it.
     */
    public BlockPos radiatingPoint() {
        return getBlockPos().above();
    }

    public CellParams toCellParams() {
        BlockPos point = radiatingPoint();
        return new CellParams(
                cellId(), point.getX(), point.getY(), point.getZ(),
                bandId, txPowerDbm, gainDbi,
                azimuthDeg, tiltDeg, hBeamwidthDeg, vBeamwidthDeg, pci);
    }

    // ---- accessors ----------------------------------------------------------

    public String bandId() {
        return bandId;
    }

    public double txPowerDbm() {
        return txPowerDbm;
    }

    public double gainDbi() {
        return gainDbi;
    }

    public double azimuthDeg() {
        return azimuthDeg;
    }

    public double tiltDeg() {
        return tiltDeg;
    }

    public double hBeamwidthDeg() {
        return hBeamwidthDeg;
    }

    public double vBeamwidthDeg() {
        return vBeamwidthDeg;
    }

    public int pci() {
        return pci;
    }

    /** See {@link #onAir}: on the server whether the cell is registered, on the client what the server said. */
    public boolean onAir() {
        return onAir;
    }

    /**
     * Applies a validated configuration. The caller is responsible for range-checking every value
     * first -- see {@code UpdateCellParamsPayload}. Never hand this a client packet unchecked.
     */
    public void applyConfiguration(
            String bandId, double txPowerDbm, double azimuthDeg, double tiltDeg,
            double hBeamwidthDeg, double vBeamwidthDeg, int pci) {

        this.bandId = bandId;
        this.txPowerDbm = txPowerDbm;
        this.azimuthDeg = azimuthDeg;
        this.tiltDeg = tiltDeg;
        this.hBeamwidthDeg = hBeamwidthDeg;
        this.vBeamwidthDeg = vBeamwidthDeg;
        this.pci = pci;
        this.gainDbi = derivedGainDbi();

        setChanged();
        syncToClients();
        refreshRegistration();
    }

    /**
     * Gain is derived from the beamwidths, never set independently -- otherwise "360 degrees wide
     * and 20 dBi" would be free and the whole tradeoff collapses. An omni mast is the exception:
     * it keeps its fixed low gain as the cheap early-game option.
     */
    protected double derivedGainDbi() {
        if (hBeamwidthDeg >= CellParams.OMNI_H_BEAMWIDTH_DEG) {
            return CellParams.DEFAULT_GAIN_DBI;
        }
        return ParabolicPattern.gainFromBeamwidthDbi(hBeamwidthDeg, vBeamwidthDeg);
    }

    // ---- registry lifecycle -------------------------------------------------

    public boolean isTransmitting() {
        if (!RanCraftConfig.REQUIRE_REDSTONE.get()) {
            return true;
        }
        // BlockStateProperties.POWERED is a singleton, so this covers masts and sector antennas
        // alike without the base class needing to know which subclass it is on.
        BlockState state = getBlockState();
        return state.hasProperty(BlockStateProperties.POWERED)
                && state.getValue(BlockStateProperties.POWERED);
    }

    @Override
    public void onLoad() {
        super.onLoad();
        assignPciIfNeeded();
        refreshRegistration();
    }

    @Override
    public void setRemoved() {
        unregister();
        super.setRemoved();
    }

    /**
     * Re-evaluates whether this antenna should currently be in the registry, and tells clients when
     * that changes its {@link #onAir()} flag.
     */
    public void refreshRegistration() {
        if (level instanceof ServerLevel serverLevel) {
            SiteRegistry registry = SiteRegistry.of(serverLevel);
            boolean transmitting = isTransmitting();
            if (transmitting) {
                registry.register(toCellParams());
            } else {
                registry.unregister(cellId());
            }
            if (transmitting != onAir) {
                onAir = transmitting;
                syncToClients();
            }
        }
    }

    public void unregister() {
        if (level instanceof ServerLevel serverLevel) {
            SiteRegistry.of(serverLevel).unregister(cellId());
        }
    }

    /**
     * Picks a PCI that does not collide with same-band neighbours, preferring one whose {@code mod 3}
     * residue is also free. Runs for migrated Phase 1 sites and for newly placed antennas.
     */
    protected void assignPciIfNeeded() {
        if (!needsPciAssignment || !(level instanceof ServerLevel serverLevel) || !readyForPciPlan()) {
            return;
        }
        needsPciAssignment = false;

        var params = RanCraftConfig.snapshot().pciParams();
        BlockPos point = radiatingPoint();

        // Exclude this cell from its own plan. refreshRegistration() may already have registered it
        // (SectorAntennaBlock.setPlacedBy does so), in which case near() returns this very cell and
        // the planner would treat our own placeholder PCI as a taken neighbour value.
        long selfId = cellId();
        var neighbours = SiteRegistry.of(serverLevel)
                .near(point.getX(), point.getY(), point.getZ(), params.planningRadiusBlocks())
                .stream()
                .filter(cell -> cell.cellId() != selfId)
                .toList();

        PciPlanner.Assignment assignment = PciPlanner.assign(
                bandId, point.getX(), point.getY(), point.getZ(), neighbours, params);

        pci = assignment.pci();

        RanCraft.LOGGER.info(
                "RANCraft PCI plan at {} on {}: {} neighbour(s) in {} blocks -> PCI {} (collisionFree={}, mod3Free={})",
                point, bandId, neighbours.size(), (long) params.planningRadiusBlocks(),
                pci, assignment.collisionFree(), assignment.mod3Free());

        if (!assignment.collisionFree()) {
            RanCraft.LOGGER.warn(
                    "RANCraft: no free PCI within {} blocks of {} on {}; assigned {} anyway",
                    (long) params.planningRadiusBlocks(), point, bandId, pci);
        }
        setChanged();

        // The registry may already hold this cell with its pre-assignment PCI, so publish the new
        // one immediately rather than waiting for a later refresh.
        refreshRegistration();
    }

    /**
     * Whether this antenna may be planned now. Always, except for a Signal Mast that is structure in
     * a column: it keeps {@link #needsPciAssignment} until it becomes a column's base (slice 6).
     */
    protected boolean readyForPciPlan() {
        return true;
    }

    // ---- client sync --------------------------------------------------------

    /**
     * Antenna configuration is public data, so it is synced to the client in full.
     *
     * <p>This is what lets the RF Lens draw a real radiation pattern without asking the server
     * anything: azimuth, tilt, beamwidths, gain and band are the antenna's own specification, in
     * the same way a furnace's contents are. Measurements are <em>not</em> sent this way and never
     * should be -- see VISION.md for where that line sits.
     *
     * <p>Phase 3 slice 6 appends {@code OnAir} (see {@link #onAir}), in the update tag only: it is
     * written here, not in {@link #saveAdditional}, so it never reaches the save.
     */
    @Override
    public CompoundTag getUpdateTag(HolderLookup.Provider registries) {
        CompoundTag tag = saveWithoutMetadata(registries);
        tag.putBoolean(ON_AIR_TAG, onAir);
        return tag;
    }

    @Override
    public Packet<ClientGamePacketListener> getUpdatePacket() {
        return ClientboundBlockEntityDataPacket.create(this);
    }

    /** Pushes a configuration change to every client tracking this chunk. */
    protected void syncToClients() {
        if (level != null && !level.isClientSide()) {
            level.sendBlockUpdated(getBlockPos(), getBlockState(), getBlockState(), Block.UPDATE_CLIENTS);
        }
    }

    // ---- persistence --------------------------------------------------------

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        tag.putInt("DataVersion", DATA_VERSION);
        tag.putString("BandId", bandId);
        tag.putDouble("TxPowerDbm", txPowerDbm);
        tag.putDouble("GainDbi", gainDbi);
        tag.putDouble("AzimuthDeg", azimuthDeg);
        tag.putDouble("TiltDeg", tiltDeg);
        tag.putDouble("HBeamwidthDeg", hBeamwidthDeg);
        tag.putDouble("VBeamwidthDeg", vBeamwidthDeg);
        tag.putInt("Pci", pci);
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);

        int dataVersion = tag.contains("DataVersion") ? tag.getInt("DataVersion") : 1;

        if (tag.contains("BandId")) {
            bandId = tag.getString("BandId");
            // NBT is not trusted: block_entity_data on a placed item reaches here unchecked. An
            // empty id names nothing, and one over the wire cap would make every payload naming
            // this cell throw in the encoder, disconnecting everyone who hears it.
            if (bandId.isEmpty() || bandId.length() > CellParams.MAX_BAND_ID_LENGTH) {
                bandId = CellParams.DEFAULT_BAND_ID;
            }
        }
        if (tag.contains("TxPowerDbm")) {
            txPowerDbm = tag.getDouble("TxPowerDbm");
        }
        if (tag.contains("GainDbi")) {
            gainDbi = tag.getDouble("GainDbi");
        }
        if (tag.contains("AzimuthDeg")) {
            azimuthDeg = tag.getDouble("AzimuthDeg");
        }
        if (tag.contains("TiltDeg")) {
            tiltDeg = tag.getDouble("TiltDeg");
        }
        if (tag.contains("HBeamwidthDeg")) {
            hBeamwidthDeg = tag.getDouble("HBeamwidthDeg");
        }
        if (tag.contains("VBeamwidthDeg")) {
            vBeamwidthDeg = tag.getDouble("VBeamwidthDeg");
        }
        if (tag.contains("Pci")) {
            pci = tag.getInt("Pci");
        }
        // Only an update tag carries it (the client's copy). On the server it is recomputed by the
        // next refreshRegistration(), so a crafted block_entity_data value does not stick there.
        if (tag.contains(ON_AIR_TAG)) {
            onAir = tag.getBoolean(ON_AIR_TAG);
        }

        // Real persisted data just arrived, so this is not a fresh placement -- the constructor's
        // default no longer applies. migrate() re-arms this below when a Phase 1 site genuinely
        // needs a PCI plan; otherwise whatever was saved (or explicitly set to 0 in the GUI) stays.
        needsPciAssignment = false;

        if (dataVersion < DATA_VERSION) {
            migrate(dataVersion);
        }
    }

    /**
     * Phase 1 -> Phase 2. Every existing value is kept; only genuinely absent ones are filled.
     * A Phase 1 world must load with every tower intact and reading identically.
     */
    protected void migrate(int fromVersion) {
        // Phase 1 wrote PCI 0 for every site because it had no planner. Treat that as unassigned
        // rather than as a deliberate choice, or a migrated world is one giant PCI collision.
        if (pci == 0) {
            needsPciAssignment = true;
        }
        noteMigration(fromVersion);
    }

    private static void noteMigration(int fromVersion) {
        int total = MIGRATED_COUNT.incrementAndGet();
        long now = System.currentTimeMillis();
        if (now - lastMigrationLogMillis < MIGRATION_LOG_INTERVAL_MILLIS) {
            return;
        }
        lastMigrationLogMillis = now;
        RanCraft.LOGGER.info(
                "RANCraft migrated {} site(s) from dataVersion {} to {}",
                total, fromVersion, DATA_VERSION);
    }
}
