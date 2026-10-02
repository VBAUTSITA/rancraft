package dev.rancraft.device;

import dev.rancraft.rf.BandTable;
import dev.rancraft.rf.DeviceRequirement;
import dev.rancraft.rf.RfConfig;
import dev.rancraft.rf.ServiceLevel;
import dev.rancraft.rf.SignalSample;
import net.minecraft.server.level.ServerLevel;

/**
 * Everything a {@link SignalDevice} is handed for one evaluation (Phase 3, §3A.2).
 *
 * <p>All carried devices share the same {@code sample}, {@code bands}, {@code config}, {@code level},
 * {@code tick} and {@code serviceCap}; only {@code verdict} differs, because each device has its own
 * requirement.
 *
 * <p><b>Backhaul cap (Phase 3 slice 12, §3C.2).</b> A device served by a backhaul-limited cell has its
 * <em>effective</em> service level capped: {@link #effectiveServiceLevel()} is the sample's level, but
 * never above {@code serviceCap}. The cap lives here, in the network layer, and nowhere else: the
 * {@link #sample()} is the evaluation exactly as the RF engine produced it (pure RF, untouched), so the
 * meter still reads the real signal. The verdict is checked against the effective level
 * ({@link DeviceRequirement#check(SignalSample, BandTable, ServiceLevel)}). <b>Game abstraction,
 * labelled (§6):</b> the cap is a flat FAIR ceiling ({@code BackhaulGraph.LIMITED_SERVICE_CAP}), not a
 * throughput shared among the cell's users.
 *
 * @param sample     the receiver's one evaluation this interval, full cell list included (not the four
 *                   cells the payload carries). A cached replay passes the replayed sample unchanged.
 *                   Never modified by the backhaul cap.
 * @param verdict    {@code device.requirement(stack).check(sample, bands, serviceCap)}, computed by the
 *                   ticker.
 * @param bands      the band table the verdict was checked against.
 * @param config     the config snapshot of this tick. The Network Locator's tunables are in it
 *                   ({@link RfConfig#locatorParams()}).
 * @param level      the level the receiver was evaluated in.
 * @param tick       the game time of this dispatch. Equal to {@code sample.timestampTick()} for a fresh
 *                   evaluation; later than it for a cached replay, except while game time is frozen
 *                   ({@code /tick freeze}), when it stays equal (see {@link ReplayGuard}).
 * @param serviceCap <b>Slice 12 (appended).</b> The ceiling the serving cell's backhaul puts on the
 *                   service level, read when the sample is dispatched (a replay gets the cap of now,
 *                   not of the evaluation): {@code BackhaulGraph.serviceCap}, FAIR for a LIMITED cell
 *                   while {@code requireBackhaul} is on. {@link ServiceLevel#EXCELLENT} caps nothing.
 */
public record DeviceContext(
        SignalSample sample, DeviceRequirement.Verdict verdict,
        BandTable bands, RfConfig config, ServerLevel level, long tick,
        ServiceLevel serviceCap) {

    public DeviceContext {
        if (serviceCap == null) {
            serviceCap = ServiceLevel.EXCELLENT;
        }
    }

    /** No backhaul cap: the context as it was before slice 12. */
    public DeviceContext(SignalSample sample, DeviceRequirement.Verdict verdict,
                         BandTable bands, RfConfig config, ServerLevel level, long tick) {
        this(sample, verdict, bands, config, level, tick, ServiceLevel.EXCELLENT);
    }

    /**
     * The service level the device actually gets: the sample's, capped at {@link #serviceCap()}. The
     * sample's own {@code serviceLevel()} is the radio link alone.
     */
    public ServiceLevel effectiveServiceLevel() {
        return ServiceLevel.worstOf(sample.serviceLevel(), serviceCap);
    }

    /**
     * Whether the serving cell's backhaul caps this device ({@link #serviceCap()} below EXCELLENT),
     * whether or not the cap bites on this sample. A device's HUD reads it to say "backhaul limited"
     * rather than "weak signal" when its verdict is LOW_QUALITY.
     */
    public boolean backhaulLimited() {
        return serviceCap.bars() < ServiceLevel.EXCELLENT.bars();
    }
}
