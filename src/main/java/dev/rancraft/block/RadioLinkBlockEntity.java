package dev.rancraft.block;

import dev.rancraft.device.DeviceContext;
import dev.rancraft.device.RadioLinkNetwork;
import dev.rancraft.rf.BlerModel;
import dev.rancraft.rf.DeviceRequirement;
import dev.rancraft.rf.RfConfig;
import dev.rancraft.rf.ServiceLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;

/**
 * What both Radio Link blocks share (Phase 3 slice 9, §3B.4): an address, the requirement, and the
 * service state of the last evaluation, shown as the block's {@code LIT}.
 *
 * <p>A Radio Link is a {@link dev.rancraft.device.FixedDevice}: the fixed-receiver ticker evaluates it
 * at the centre of its block about once a second ({@code evaluationIntervalTicks}) and hands it the
 * sample. It never computes RF itself: it reads the verdict and the sample's SINR, and the block error
 * rate of that SINR ({@link BlerModel}) decides whether a message gets through
 * ({@link RadioLinkNetwork}). Its end's chance of decoding, {@code 1 - BLER(sinr)}
 * ({@link #decodeSuccess()}), is fixed at each turn, with that tick's config, and used for every
 * message until the next turn.
 *
 * <p><b>Service.</b> {@link #REQUIREMENT}: POOR service or better, on a band of capacity tier 1 or
 * better. {@link #served()} is whether the last evaluation met it. {@code LIT} follows it, set by the
 * server and pushed to clients by vanilla block-state sync; the client computes nothing.
 *
 * <p><b>Address</b> 0 to 15, saved with the block. Use cycles it up, sneak + use down, and the action bar
 * shows it ({@link RadioLinkBlock}). It is called the address, not the channel, because in this mod a
 * channel is a band's radio channel.
 *
 * <p><b>Saved:</b> {@code DataVersion} ({@value #DATA_VERSION}) and {@code Address}; the receiver adds
 * what it last heard. The service state is server memory only: a reloaded block shows its saved
 * {@code LIT} until its first evaluation.
 */
public abstract class RadioLinkBlockEntity extends FixedDeviceBlockEntity {

    /** §3B.4: POOR, tier 1. */
    public static final DeviceRequirement REQUIREMENT = new DeviceRequirement(ServiceLevel.POOR, 1);

    /** The Radio Link's own save format, independent of the antennas' {@code DATA_VERSION}. */
    public static final int DATA_VERSION = 1;

    private int address;

    // ---- the last evaluation (server memory only) ----
    private boolean served;
    private double sinrDb = Double.NEGATIVE_INFINITY;
    private ServiceLevel serviceLevel = ServiceLevel.NONE;
    private long lastSampleTick = Long.MIN_VALUE;
    private long samples;
    // 1 - BLER(decodeSinrDb) under (decodeSinr50Db, decodeSlopeDb): recomputed only when one of the three
    // changes, which a replay never does. NaN never compares equal, so the first turn computes it.
    private double decodeSinrDb = Double.NaN;
    private double decodeSinr50Db = Double.NaN;
    private double decodeSlopeDb = Double.NaN;
    private double decodeSuccess;

    protected RadioLinkBlockEntity(BlockEntityType<?> type, BlockPos pos, BlockState state) {
        super(type, pos, state);
    }

    @Override
    public DeviceRequirement requirement() {
        return REQUIREMENT;
    }

    /**
     * Records the service state, shows it as {@code LIT}, then lets the transmitter send or the receiver
     * tidy up. Every dispatch, replays included: a replay is the same channel one interval later.
     */
    @Override
    public final void onSample(ServerLevel level, BlockPos pos, DeviceContext ctx) {
        served = ctx.verdict() == DeviceRequirement.Verdict.OK;
        sinrDb = ctx.sample().sinrDb();
        serviceLevel = ctx.sample().serviceLevel();
        lastSampleTick = ctx.tick();
        samples++;
        RfConfig config = ctx.config();
        if (sinrDb != decodeSinrDb || config.blerSinr50Db() != decodeSinr50Db || config.blerSlopeDb() != decodeSlopeDb) {
            decodeSinrDb = sinrDb;
            decodeSinr50Db = config.blerSinr50Db();
            decodeSlopeDb = config.blerSlopeDb();
            decodeSuccess = config.blerModel().success(sinrDb);
        }
        showService(level, pos, served);
        afterSample(level, pos, ctx);
    }

    /** The role's work after the service state is recorded. Server thread, inside the ticker. */
    protected abstract void afterSample(ServerLevel level, BlockPos pos, DeviceContext ctx);

    /** Sets {@code LIT} to {@code lit} if it differs. Clients only: service changes no redstone. */
    private void showService(ServerLevel level, BlockPos pos, boolean lit) {
        BlockState state = getBlockState();
        if (state.getBlock() instanceof RadioLinkBlock && state.getValue(RadioLinkBlock.LIT) != lit) {
            level.setBlock(pos, state.setValue(RadioLinkBlock.LIT, lit), Block.UPDATE_CLIENTS);
        }
    }

    // ---- address ----------------------------------------------------------------------------------

    public int address() {
        return address;
    }

    /**
     * Moves the address by {@code step}, wrapping within 0 to 15, and saves it.
     *
     * @return the new address.
     */
    public int cycleAddress(int step) {
        int previous = address;
        address = RadioLinkNetwork.wrapAddress(address + step);
        setChanged();
        if (address != previous) {
            addressChanged(previous);
        }
        return address;
    }

    /** Called after the address changed. */
    protected void addressChanged(int previous) {
    }

    // ---- the last evaluation ----------------------------------------------------------------------

    /** Whether the last evaluation met {@link #REQUIREMENT}. False before the first. */
    public boolean served() {
        return served;
    }

    /** The SINR of the last evaluation, in dB; {@code -Infinity} before the first. */
    public double sinrDb() {
        return sinrDb;
    }

    /**
     * The chance this end decodes a message: {@code 1 - BLER} of the last evaluation's SINR
     * ({@link BlerModel#success}), under the config of that turn; 0 before the first.
     */
    public double decodeSuccess() {
        return decodeSuccess;
    }

    /** The service level of the last evaluation; NONE before the first. */
    public ServiceLevel serviceLevel() {
        return serviceLevel;
    }

    /** Game time of the last dispatch ({@code DeviceContext.tick()}); {@code Long.MIN_VALUE} before the first. */
    public long lastSampleTick() {
        return lastSampleTick;
    }

    /** Dispatches this entity has had since it was created. For tests. */
    public long samples() {
        return samples;
    }

    // ---- persistence ------------------------------------------------------------------------------

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        tag.putInt("DataVersion", DATA_VERSION);
        tag.putInt("Address", address);
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        address = RadioLinkNetwork.wrapAddress(tag.getInt("Address"));
    }
}
