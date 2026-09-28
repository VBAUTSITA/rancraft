package dev.rancraft.device;

import dev.rancraft.rf.BandTable;
import dev.rancraft.rf.DeviceRequirement;
import dev.rancraft.rf.RfConfig;
import dev.rancraft.rf.SignalSample;
import net.minecraft.server.level.ServerLevel;

/**
 * Everything a {@link SignalDevice} is handed for one evaluation (Phase 3, §3A.2).
 *
 * <p>All carried devices share the same {@code sample}, {@code bands}, {@code config}, {@code level}
 * and {@code tick}; only {@code verdict} differs, because each device has its own requirement.
 *
 * @param sample  the receiver's one evaluation this interval, full cell list included (not the four
 *                cells the payload carries). A cached replay passes the replayed sample unchanged.
 * @param verdict {@code device.requirement(stack).check(sample, bands)}, computed by the ticker.
 * @param bands   the band table the verdict was checked against.
 * @param config  the config snapshot of this tick. The Network Locator's tunables are in it
 *                ({@link RfConfig#locatorParams()}).
 * @param level   the level the receiver was evaluated in.
 * @param tick    the game time of this dispatch. Equal to {@code sample.timestampTick()} for a fresh
 *                evaluation; later than it for a cached replay, except while game time is frozen
 *                ({@code /tick freeze}), when it stays equal (see {@link ReplayGuard}).
 */
public record DeviceContext(
        SignalSample sample, DeviceRequirement.Verdict verdict,
        BandTable bands, RfConfig config, ServerLevel level, long tick) {
}
