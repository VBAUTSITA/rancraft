package dev.rancraft.block;

import dev.rancraft.RanCraft;
import dev.rancraft.RanCraftConfig;
import dev.rancraft.data.RfDataLoader;
import dev.rancraft.rf.CellParams;
import dev.rancraft.rf.ParabolicPattern;
import dev.rancraft.rf.PciPlanner;
import dev.rancraft.rf.PowerModel;
import dev.rancraft.rf.RadioTier;
import dev.rancraft.util.ColumnScan;
import dev.rancraft.util.EnergyBuffer;
import dev.rancraft.world.BackhaulNetwork;
import dev.rancraft.world.SitePower;
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
import net.neoforged.neoforge.energy.IEnergyStorage;
import org.jetbrains.annotations.Nullable;

/**
 * Everything a radio site persists, shared by the omni Signal Mast and the Sector Antenna.
 *
 * <p>Both are independent cells as far as {@code SiteRegistry} is concerned; the only difference is
 * their defaults and whether they have a facing. (Since Phase 3 slice 6 a column of stacked masts is
 * one cell, owned by its lowest mast: see {@link SignalMastBlockEntity}.) There is deliberately no "site" container block --
 * a three-sector site is three blocks at azimuths 0/120/240, and Phase 4's planning table will group
 * them by proximity.
 *
 * <p>Passive. Antennas never tick; the receiver drives evaluation, so idle sites cost nothing. With
 * {@code requirePower} on (Phase 3 slice 15, §3C.5) a cell on the air pays its energy once a tick, but
 * the antenna still does not tick: the dimension's {@link SitePower} draws from every cell's buffer.
 */
public abstract class AntennaBlockEntity extends BlockEntity {

    /**
     * Persisted shape version.
     *
     * <ul>
     *   <li><b>1</b> -- Phase 1. All fields present but azimuth/tilt/beamwidths/PCI were inert seams.
     *   <li><b>2</b> -- Phase 2. The same fields are now live, and a PCI of 0 written by Phase 1
     *       means "never assigned" rather than "deliberately PCI 0".
     *   <li><b>3</b> -- Phase 3 slice 10 (§3C.1). Appends {@code RadioTier}. An older save gets
     *       {@code max(blockDefault, tierOf(currentBand))} ({@link RadioTier#migrated}), so a sector
     *       already on band_3500 is grandfathered to tier 3. A PCI of 0 in a v2 save stays a
     *       deliberate PCI 0: only a v1 save's means "never assigned".
     *   <li><b>4</b> -- Phase 3 slice 15 (§3C.5). Appends {@code Energy} (FE in the buffer) and
     *       {@code PowerOn} (the restart latch, {@link EnergyBuffer#on()}). An older save has neither:
     *       an empty buffer, latch off. With {@code requirePower} off (the default) neither is read.
     * </ul>
     */
    public static final int DATA_VERSION = 4;

    /** The saved radio tier (v3). Also in the update tag, as all saved fields are: it is public hardware. */
    public static final String RADIO_TIER_TAG = "RadioTier";

    /** The saved buffer (v4): FE held. Server state: kept out of the update tag ({@link #getUpdateTag}). */
    public static final String ENERGY_TAG = "Energy";

    /** The saved restart latch (v4). Server state: kept out of the update tag. */
    public static final String POWER_ON_TAG = "PowerOn";

    /** The update tag's on-air flag (slice 6). Never saved; see {@link #getUpdateTag}. */
    public static final String ON_AIR_TAG = "OnAir";

    /**
     * The update tag's radiating height: the y of {@link #radiatingPoint()} as the server works it out
     * (Phase 3B review fix). Never saved; see {@link #getUpdateTag}.
     */
    public static final String RADIATING_Y_TAG = "RadiatingY";

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
     * The lowest radio tier this kind of antenna always has: 1 for a Signal Mast, 2 for a Sector
     * Antenna (§3C.1). A radio never drops below it.
     */
    private final int defaultRadioTier;

    /**
     * Which bands this antenna's radio may use: those whose {@code capacityTier} is at most this
     * ({@link RadioTier}). Phase 3 slice 10. Starts at {@link #defaultRadioTier}; a Wideband Radio
     * Unit raises a sector to {@link RadioTier#WIDEBAND} ({@link SectorAntennaBlockEntity}). Only
     * {@code UpdateCellParamsPayload.applyOn} enforces it, when a band is chosen: the engine never
     * reads it, so an antenna already on a band keeps transmitting on it.
     */
    protected int radioTier;

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

    /**
     * Client: the server's radiating height from the update tag ({@code RadiatingY}), or
     * {@link ColumnScan#UNKNOWN_Y} before one arrives. Only a mast column's lens drawing reads it
     * ({@link SignalMastBlockEntity#radiatingPoint()}); the server never does (it would also hold a
     * value from a crafted {@code block_entity_data} here, which is why it is not used there). Phase 3B
     * review fix: {@code maxMastHeight} is COMMON and not synced, so the client cannot work the
     * height out of a tall column itself.
     */
    private int toldRadiatingY = ColumnScan.UNKNOWN_Y;

    /**
     * Server: the radiating height last written into an update tag, so {@link #refreshRegistration()}
     * can tell clients when it moves (a mast added on top of a column). {@link ColumnScan#UNKNOWN_Y}
     * until a tag is written: then no client holds an old value, and the next tag is written fresh.
     */
    private int sentRadiatingY = ColumnScan.UNKNOWN_Y;

    /** Set by {@link #onChunkUnloaded()}: the coming {@link #setRemoved()} is an unload, not a removal. */
    private boolean unloading;

    /**
     * This antenna's energy buffer and on-air latch (Phase 3 slice 15, §3C.5). Only a cell's owner uses
     * its own: a Sector Antenna, or a mast column's base. Structure masts route energy to their owner
     * ({@link #bufferOwner()}) and hold none, except for the moment a base becomes structure, when it
     * hands its energy down ({@link SignalMastBlockEntity}). Server state; the client's stays empty.
     */
    private final EnergyBuffer energy = new EnergyBuffer();

    /** What {@code Capabilities.EnergyStorage.BLOCK} returns for this antenna, on every side. */
    private final SiteEnergyStorage energyStorage = new SiteEnergyStorage(this);

    protected AntennaBlockEntity(BlockEntityType<?> type, BlockPos pos, BlockState state, int defaultRadioTier) {
        super(type, pos, state);
        this.defaultRadioTier = defaultRadioTier;
        this.radioTier = defaultRadioTier;
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
        return cellParamsAt(radiatingPoint());
    }

    private CellParams cellParamsAt(BlockPos point) {
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

    /** See {@link #radioTier}. On the client, the server's value from the update tag. */
    public int radioTier() {
        return radioTier;
    }

    /** See {@link #defaultRadioTier}. */
    public int defaultRadioTier() {
        return defaultRadioTier;
    }

    /**
     * Whether a PCI plan is still pending ({@link #needsPciAssignment}): for a fresh placement until
     * its {@code onLoad}, for a migrated Phase 1 site whose saved PCI was 0. Read by the game tests
     * (a v2 save's PCI 0 must not be re-planned by the v3 migration).
     */
    public boolean pciPlanPending() {
        return needsPciAssignment;
    }

    /** See {@link #onAir}: on the server whether the cell is registered, on the client what the server said. */
    public boolean onAir() {
        return onAir;
    }

    /**
     * Client: the radiating height the server last reported in the update tag, or
     * {@link ColumnScan#UNKNOWN_Y}. See {@link #toldRadiatingY}.
     */
    public int toldRadiatingY() {
        return toldRadiatingY;
    }

    // ---- power (Phase 3 slice 15, §3C.5) ------------------------------------

    /** This antenna's own buffer; see {@link #energy}. Driven by {@link SitePower}. */
    public EnergyBuffer energyBuffer() {
        return energy;
    }

    /**
     * The antenna's FE capability ({@code Capabilities.EnergyStorage.BLOCK}, registered in
     * {@code registry.ModCapabilities}): receive-only, into {@link #bufferOwner()}'s buffer. One object
     * per antenna for its lifetime, so a capability cache holding it stays right; NeoForge invalidates
     * caches when the block entity comes or goes.
     */
    public IEnergyStorage energyStorage() {
        return energyStorage;
    }

    /**
     * The antenna whose buffer energy offered here goes into: this one (a Sector Antenna, or a mast
     * column's base). A Signal Mast overrides it: any mast of a column feeds the column's base, or the
     * Sector Antenna on top of a mounting pole. {@code null} when there is none (never for a sector).
     */
    public @Nullable AntennaBlockEntity bufferOwner() {
        return this;
    }

    /** Whether this antenna's radio is a sector's (its base load, {@link PowerModel#baseFe}). */
    protected boolean sectorRadio() {
        return false;
    }

    /**
     * FE per tick this cell draws while on the air: {@link PowerModel#fePerTick(boolean, int, double)}
     * for its kind of radio, its radio tier and its Tx power.
     */
    public double fePerTick(PowerModel model) {
        return model.fePerTick(sectorRadio(), radioTier, txPowerDbm);
    }

    /**
     * Marks the chunk for saving after the buffer changed. Not {@link #setChanged()}: that also runs the
     * comparator neighbour update ({@code updateNeighbourForOutputSignal}), every tick on a cell on the
     * air, and an antenna has no comparator output.
     */
    public void markEnergyChanged() {
        if (level != null) {
            level.blockEntityChanged(getBlockPos());
        }
    }

    /**
     * The power's verdict on this cell: always yes with {@code requirePower} off (the default), on the
     * client and with no level; otherwise the buffer's latch ({@link EnergyBuffer#on()}).
     */
    public boolean powerAllows() {
        return !(level instanceof ServerLevel) || !RanCraftConfig.requirePower() || energy.on();
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

    /**
     * Whether this antenna is on the air: it would transmit by its own rules
     * ({@link #eligibleToTransmit()}), its backhaul allows it and its power does. Phase 3 slice 12
     * (§3C.2): with {@code requireBackhaul} on, a cell with no path to a Core Site (backhaul NONE) is
     * not transmitting ({@code world.BackhaulNetwork}). Phase 3 slice 15 (§3C.5): with
     * {@code requirePower} on, nor is a cell whose buffer ran out and has not refilled past the restart
     * fraction ({@link #powerAllows()}). With both off (the default) this is exactly
     * {@link #eligibleToTransmit()}, as before. The client has no backhaul or power verdict and asks
     * nothing.
     *
     * <p>Power sits beside backhaul here, not in {@link #eligibleToTransmit()} (NOTES.md, slice 15,
     * decision 1): a cell out of energy stays in the backhaul graph, so its site keeps relaying between
     * its dishes. The dishes are the site's transport and are not on the power budget.
     */
    public boolean isTransmitting() {
        return eligibleToTransmit() && backhaulAllows() && powerAllows();
    }

    /**
     * Whether this antenna would transmit by its own rules, backhaul aside: {@code requireRedstone}
     * here, the mast column's rules in {@link SignalMastBlockEntity}. Until slice 12 this was
     * {@link #isTransmitting()}; the backhaul graph needs the cells that pass it, including those its
     * own verdict keeps off the air.
     */
    protected boolean eligibleToTransmit() {
        if (!RanCraftConfig.REQUIRE_REDSTONE.get()) {
            return true;
        }
        // BlockStateProperties.POWERED is a singleton, so this covers masts and sector antennas
        // alike without the base class needing to know which subclass it is on.
        BlockState state = getBlockState();
        return state.hasProperty(BlockStateProperties.POWERED)
                && state.getValue(BlockStateProperties.POWERED);
    }

    /** The backhaul's verdict on this cell (server); always yes on the client or with no level. */
    private boolean backhaulAllows() {
        return !(level instanceof ServerLevel serverLevel) || BackhaulNetwork.allowsOnAir(serverLevel, cellId());
    }

    @Override
    public void onLoad() {
        super.onLoad();
        assignPciIfNeeded();
        refreshRegistration();
    }

    /**
     * The chunk is unloading: {@link #setRemoved()} follows, and must not take the cell out of the
     * backhaul topology (slice 12: a relay site in an unloaded chunk still relays). NeoForge calls this
     * from {@code LevelChunk.clearAllBlockEntities}, just before {@code setRemoved}; a broken or
     * replaced antenna gets {@code setRemoved} alone.
     */
    @Override
    public void onChunkUnloaded() {
        super.onChunkUnloaded();
        unloading = true;
    }

    @Override
    public void setRemoved() {
        unregister();
        if (level instanceof ServerLevel serverLevel) {
            if (!unloading) {
                BackhaulNetwork.of(serverLevel).removeCell(cellId());
            }
            SitePower.of(serverLevel).untrack(this);
        }
        super.setRemoved();
    }

    /**
     * Re-evaluates whether this antenna should currently be in the registry, and tells clients when
     * that changes its {@link #onAir()} flag or moves its radiating point up or down (a mast column
     * that grew or shrank; Phase 3B review fix, so the lens draws where the server radiates).
     */
    public void refreshRegistration() {
        if (level instanceof ServerLevel serverLevel) {
            SiteRegistry registry = SiteRegistry.of(serverLevel);
            // Slice 12: the backhaul graph learns every cell that passes its own rules, on or off the
            // air, and its verdict decides the rest. The network refreshes this antenna when its
            // verdict changes, so the flag below is never written from anywhere else.
            boolean eligible = eligibleToTransmit();
            BackhaulNetwork backhaul = BackhaulNetwork.of(serverLevel);
            backhaul.noteCell(cellId(), eligible);
            // Slice 15: the dimension's power ticker draws from every loaded cell that passes its own
            // rules, and refreshes it here when its latch changes, so OnAir still has one writer.
            SitePower.of(serverLevel).track(this, eligible);
            boolean transmitting = eligible && backhaul.allowsOnAir(cellId()) && powerAllows();
            BlockPos point = radiatingPoint();
            if (transmitting) {
                registry.register(cellParamsAt(point));
            } else {
                registry.unregister(cellId());
            }
            boolean pointMoved = sentRadiatingY != ColumnScan.UNKNOWN_Y && point.getY() != sentRadiatingY;
            if (transmitting != onAir || pointMoved) {
                onAir = transmitting;
                syncToClients();
            }
        }
    }

    /**
     * Off the air: the antenna was removed or its chunk unloaded. The backhaul topology keeps the cell
     * on an unload ({@link #setRemoved()} drops it only for a broken or replaced antenna).
     */
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
     * written here, not in {@link #saveAdditional}, so it never reaches the save. The Phase 3B review
     * appends {@code RadiatingY} the same way (see {@link #toldRadiatingY}): where the cell radiates
     * from is the antenna's declared, public configuration, worked out on the server with the
     * server's {@code maxMastHeight}.
     *
     * <p>Slice 15 takes the buffer ({@code Energy}, {@code PowerOn}) out again: it is server state that
     * changes every tick, and a client copy would be stale at once. Whether the cell is on the air is
     * {@code OnAir}, which the client already has; so the update tag, and the wire, are as before.
     */
    @Override
    public CompoundTag getUpdateTag(HolderLookup.Provider registries) {
        CompoundTag tag = saveWithoutMetadata(registries);
        tag.remove(ENERGY_TAG);
        tag.remove(POWER_ON_TAG);
        tag.putBoolean(ON_AIR_TAG, onAir);
        if (level != null && !level.isClientSide()) {
            int radiatingY = radiatingPoint().getY();
            tag.putInt(RADIATING_Y_TAG, radiatingY);
            sentRadiatingY = radiatingY;
        }
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
        tag.putInt(RADIO_TIER_TAG, radioTier);
        tag.putInt(ENERGY_TAG, energy.stored());
        tag.putBoolean(POWER_ON_TAG, energy.on());
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
        // v3 and later. Absent from older saves: the constructor's default stands until migrate().
        if (tag.contains(RADIO_TIER_TAG)) {
            radioTier = RadioTier.loaded(defaultRadioTier, tag.getInt(RADIO_TIER_TAG));
        }
        // v4 and later. Absent from older saves: an empty buffer with the latch off (the field's start).
        // A crafted value is harmless: a buffer cannot pay out more than it holds.
        if (tag.contains(ENERGY_TAG)) {
            energy.load(tag.getInt(ENERGY_TAG), tag.getBoolean(POWER_ON_TAG));
        }
        // Only an update tag carries it (the client's copy). On the server it is recomputed by the
        // next refreshRegistration(), so a crafted block_entity_data value does not stick there.
        if (tag.contains(ON_AIR_TAG)) {
            onAir = tag.getBoolean(ON_AIR_TAG);
        }
        // Also only in an update tag; the server never reads it back (see toldRadiatingY).
        if (tag.contains(RADIATING_Y_TAG)) {
            toldRadiatingY = tag.getInt(RADIATING_Y_TAG);
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
     * Older saves to {@link #DATA_VERSION}. Every existing value is kept; only genuinely absent ones
     * are filled. A Phase 1 or Phase 2 world must load with every tower intact and reading identically.
     *
     * <ul>
     *   <li>v1 to v2: a PCI of 0 is "never assigned", so it is planned in {@link #onLoad()}.
     *   <li>v2 (and v1) to v3: the radio tier is {@code max(blockDefault, tierOf(currentBand))}
     *       ({@link RadioTier#migrated}), from the band table loaded now. The datapacks load before
     *       any level, so on the server this is the world's table.
     *   <li>v3 (and older) to v4: nothing to do. The buffer starts empty with its latch off, as a
     *       newly placed antenna's does: turning {@code requirePower} on is a server's choice, and a
     *       cell then needs a generator before it transmits.
     * </ul>
     */
    protected void migrate(int fromVersion) {
        // Phase 1 wrote PCI 0 for every site because it had no planner. Treat that as unassigned
        // rather than as a deliberate choice, or a migrated world is one giant PCI collision. Only a
        // v1 save: from v2 on, PCI 0 is a value the planner or the player chose (slice 10 keeps it).
        if (fromVersion < 2 && pci == 0) {
            needsPciAssignment = true;
        }
        // Grandfathering: whatever band the antenna was on keeps working, and stays selectable.
        if (fromVersion < 3) {
            radioTier = RadioTier.migrated(defaultRadioTier, RfDataLoader.bands(), bandId);
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
