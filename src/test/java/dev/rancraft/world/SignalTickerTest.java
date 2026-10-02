package dev.rancraft.world;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.rancraft.device.DeviceContext;
import dev.rancraft.device.DeviceMemory;
import dev.rancraft.device.ReplayGuard;
import dev.rancraft.device.SignalDevice;
import dev.rancraft.item.LensLayers;
import dev.rancraft.item.LensSettings;
import dev.rancraft.rf.Band;
import dev.rancraft.rf.BandTable;
import dev.rancraft.rf.CellSample;
import dev.rancraft.rf.DeviceRequirement;
import dev.rancraft.rf.DeviceRequirement.Verdict;
import dev.rancraft.rf.RfConfig;
import dev.rancraft.rf.ServiceLevel;
import dev.rancraft.rf.SignalSample;
import dev.rancraft.world.SignalTicker.CarriedDevice;
import dev.rancraft.world.SignalTicker.Found;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The ticker's pure rules (Phase 3 slice 4 regression gate): who is evaluated, which is also who is
 * sent the sample ({@link SignalTicker#sendsSample}); which carried stacks are devices and which of
 * them are held ({@link SignalTicker#scanCarried}); how the one sample reaches every device
 * ({@link SignalTicker#dispatch}); and what {@link SignalTicker#forget} clears.
 *
 * <p>The drive-test log marks a handover where the server's counter goes up between two samples it
 * received. So every evaluation that can move that counter must reach the client, whichever view or
 * device asked for it. Otherwise the handovers of an unsent stretch land together, as one false
 * pillar, on the next sample the client does get. A LINKS-only lens used to be evaluated without
 * being sent the sample; slice 4 adds devices in the hotbar to the evaluated set, and they must be
 * sent it too.
 *
 * <p>Headless: game objects are never built. Carried devices use a null stack and a recording fake
 * device; the scan is generic and runs on stand-in stacks. Loading the ticker starts no game.
 */
class SignalTickerTest {

    private static LensSettings lens(LensLayers layers) {
        return LensSettings.DEFAULT.withLayers(layers);
    }

    /** A device that records what it is handed. */
    private static final class RecordingDevice implements SignalDevice {
        final DeviceRequirement requirement;
        final List<DeviceContext> contexts = new ArrayList<>();
        final List<Boolean> held = new ArrayList<>();
        final List<SignalDevice> order;

        RecordingDevice(DeviceRequirement requirement, List<SignalDevice> order) {
            this.requirement = requirement;
            this.order = order;
        }

        RecordingDevice(DeviceRequirement requirement) {
            this(requirement, new ArrayList<>());
        }

        @Override
        public DeviceRequirement requirement(ItemStack stack) {
            return requirement;
        }

        @Override
        public void onSample(ServerPlayer player, ItemStack stack, boolean held, DeviceContext ctx) {
            contexts.add(ctx);
            this.held.add(held);
            order.add(this);
        }
    }

    private static final SignalDevice ANY_DEVICE = new RecordingDevice(DeviceRequirement.NONE);

    private static List<CarriedDevice> inHand() {
        return List.of(new CarriedDevice(null, ANY_DEVICE, true));
    }

    private static List<CarriedDevice> inHotbarOnly() {
        return List.of(new CarriedDevice(null, ANY_DEVICE, false));
    }

    private static final List<CarriedDevice> NOTHING = List.of();

    // ---- who is evaluated, and so sent the sample -------------------------------------------------

    @Test
    @DisplayName("a device in a hand is sent the sample with no lens, and with the lens on any preset")
    void heldDeviceIsAlwaysSent() {
        assertTrue(SignalTicker.sendsSample(inHand(), null));
        for (LensLayers layers : LensLayers.values()) {
            assertTrue(SignalTicker.sendsSample(inHand(), lens(layers)), layers.name());
        }
    }

    @Test
    @DisplayName("a device only in the hotbar is evaluated, so it is sent the sample too: its handovers reach the log")
    void hotbarDeviceIsSent() {
        assertTrue(SignalTicker.sendsSample(inHotbarOnly(), null),
                "evaluated but not sent: the log would mark this walk's handovers at the next sample it gets");
        assertTrue(SignalTicker.sendsSample(inHotbarOnly(), lens(LensLayers.ANTENNAS)));
        assertTrue(SignalTicker.sendsSample(inHotbarOnly(), lens(LensLayers.COVERAGE)));
    }

    @Test
    @DisplayName("ALL and TRAIL wearers are sent the sample: the trail is drawn from it")
    void trailPresetsAreSent() {
        assertTrue(SignalTicker.sendsSample(NOTHING, lens(LensLayers.ALL)));
        assertTrue(SignalTicker.sendsSample(NOTHING, lens(LensLayers.TRAIL)));
    }

    @Test
    @DisplayName("a LINKS wearer is evaluated for the rays, so is sent the sample too: its handovers reach the log")
    void linksPresetIsSent() {
        assertTrue(SignalTicker.sendsSample(NOTHING, lens(LensLayers.LINKS)),
                "evaluated but not sent: the log would mark this walk's handovers at the next sample it gets");
    }

    @Test
    @DisplayName("ANTENNAS and COVERAGE wearers without a device are not evaluated, so their handover state cannot move unseen")
    void idlePresetsAreNotEvaluated() {
        assertFalse(SignalTicker.sendsSample(NOTHING, lens(LensLayers.ANTENNAS)));
        assertFalse(SignalTicker.sendsSample(NOTHING, lens(LensLayers.COVERAGE)));
        assertFalse(SignalTicker.sendsSample(NOTHING, null), "no device and no lens costs nothing");
    }

    @Test
    @DisplayName("the band filter does not matter: links filtered to a band nobody transmits on still move the counter")
    void bandFilterDoesNotGateTheSample() {
        assertTrue(SignalTicker.sendsSample(NOTHING, lens(LensLayers.LINKS).withBandFilter("band_3500")));
        assertTrue(SignalTicker.sendsSample(NOTHING, lens(LensLayers.TRAIL).withBandFilter("band_700")));
        assertFalse(SignalTicker.sendsSample(NOTHING, lens(LensLayers.ANTENNAS).withBandFilter("band_900")));
    }

    @Test
    @DisplayName("the stale-candidate threshold is one evaluation interval, never below one tick")
    void staleCandidateThresholdIsOneInterval() {
        assertEquals(20L, SignalTicker.staleCandidateGapTicks(20), "the default interval");
        assertEquals(1L, SignalTicker.staleCandidateGapTicks(1));
        assertEquals(200L, SignalTicker.staleCandidateGapTicks(200), "the config maximum");
        assertEquals(1L, SignalTicker.staleCandidateGapTicks(0), "the ticker already clamps the interval to 1");
    }

    // ---- the stagger, and why one interval is the right stale threshold ---------------------------

    @Test
    @DisplayName("stagger: each player is due exactly once per interval, so continuous observation never trips the stale threshold")
    void staggerSpacingIsOneInterval() {
        for (int interval : new int[] {1, 2, 7, 20, 200}) {
            for (int id : new int[] {0, 1, 5, 19, 20, 123_457}) {
                List<Integer> due = new ArrayList<>();
                for (int tick = 1_000; tick < 1_000 + 5 * interval; tick++) {
                    if (SignalTicker.isDue(id, tick, interval)) {
                        due.add(tick);
                    }
                }
                assertEquals(5, due.size(), "interval " + interval + ", id " + id);
                for (int i = 1; i < due.size(); i++) {
                    int gap = due.get(i) - due.get(i - 1);
                    assertEquals(interval, gap);
                    assertTrue(gap <= SignalTicker.staleCandidateGapTicks(interval),
                            "a player evaluated on every due tick keeps an armed candidate");
                }
            }
        }
    }

    @Test
    @DisplayName("stagger: consecutive entity ids land on consecutive ticks, one per tick")
    void staggerSpreadsPlayers() {
        int interval = 20;
        for (int tick = 0; tick < 3 * interval; tick++) {
            int dueCount = 0;
            for (int id = 100; id < 100 + interval; id++) {
                if (SignalTicker.isDue(id, tick, interval)) {
                    dueCount++;
                }
            }
            assertEquals(1, dueCount, "tick " + tick);
        }
    }

    @Test
    @DisplayName("a live change of the interval stretches one gap to at most old + new - gcd: a one-off delay, never an early handover")
    void liveIntervalChangeStretchesOneGap() {
        int[][] changes = {{20, 10}, {10, 20}, {20, 20}, {7, 3}, {3, 7}, {20, 30}, {1, 20}, {20, 1}};
        for (int[] change : changes) {
            int before = change[0];
            int after = change[1];
            int period = before * after; // covers every residue of the id and of the switch tick
            int worstGap = 0;
            for (int id = 0; id < period; id++) {
                for (int switchTick = 5_000; switchTick < 5_000 + period; switchTick++) {
                    int last = -1;
                    for (int t = switchTick - 1; t >= switchTick - before; t--) {
                        if (SignalTicker.isDue(id, t, before)) {
                            last = t;
                            break;
                        }
                    }
                    int next = -1;
                    for (int t = switchTick; t < switchTick + after; t++) {
                        if (SignalTicker.isDue(id, t, after)) {
                            next = t;
                            break;
                        }
                    }
                    assertTrue(last >= 0 && next >= 0, "every window of one interval has a due tick");
                    worstGap = Math.max(worstGap, next - last);
                }
            }
            int gcd = java.math.BigInteger.valueOf(before).gcd(java.math.BigInteger.valueOf(after)).intValue();
            assertEquals(before + after - gcd, worstGap, before + " -> " + after);
        }
        // The case the javadoc names: 20 -> 10 can leave a 20-tick gap, over the new threshold of 10,
        // so an armed candidate is dropped once and re-armed. 10 -> 20 cannot (10 divides 20).
        assertTrue(20 > SignalTicker.staleCandidateGapTicks(10));
    }

    @Test
    @DisplayName("a hand-edited lens with links but no trail is sent the sample; lobes and coverage alone are not evaluated")
    void handEditedCombinations() {
        // Neither combination is a preset (both read as ALL through LensLayers.of): the rule is on
        // the flags, not on the preset name.
        LensSettings lobesAndLinks = new LensSettings(LensSettings.ALL_BANDS, true, true, false, false);
        LensSettings lobesAndCoverage = new LensSettings(LensSettings.ALL_BANDS, true, false, true, false);
        assertTrue(SignalTicker.sendsSample(NOTHING, lobesAndLinks));
        assertFalse(SignalTicker.sendsSample(NOTHING, lobesAndCoverage));
    }

    // ---- which carried stacks are devices ---------------------------------------------------------

    /** Stands in for an ItemStack: compared by identity, like the inventory's own stacks. */
    private static final class Stack {
        final String item;

        Stack(String item) {
            this.item = item;
        }

        @Override
        public String toString() {
            return item;
        }
    }

    private static final Set<String> DEVICE_ITEMS = Set.of("meter", "locator");

    /** The item name when it is a device, like {@code stack.getItem() instanceof SignalDevice}. */
    private static String deviceOf(Stack stack) {
        return DEVICE_ITEMS.contains(stack.item) ? stack.item : null;
    }

    /** Vanilla fills empty slots with the one {@code ItemStack.EMPTY} object; so does this. */
    private static final Stack AIR = new Stack("air");

    private static List<Stack> hotbar(Stack... slots) {
        List<Stack> hotbar = new ArrayList<>(Collections.nCopies(9, AIR));
        for (int i = 0; i < slots.length; i++) {
            hotbar.set(i, slots[i]);
        }
        return hotbar;
    }

    private static List<Found<Stack, String>> scan(Stack main, Stack off, List<Stack> hotbar) {
        return SignalTicker.scanCarried(main, off, hotbar, SignalTickerTest::deviceOf);
    }

    @Test
    @DisplayName("the main-hand stack is also its hotbar slot: dispatched once, as held")
    void mainHandIsNotCountedTwice() {
        Stack meter = new Stack("meter");
        // Selected slot 3: the main hand IS hotbar slot 3, the same object.
        List<Found<Stack, String>> found = scan(meter, AIR, hotbar(AIR, AIR, AIR, meter));

        assertEquals(1, found.size(), "a held device must not also run as a pocketed one");
        assertSame(meter, found.get(0).stack());
        assertTrue(found.get(0).held());
    }

    @Test
    @DisplayName("order is main hand, offhand, then hotbar left to right; only the hands are held")
    void orderAndHeld() {
        Stack mainMeter = new Stack("meter");
        Stack offLocator = new Stack("locator");
        Stack pocketLocator = new Stack("locator");
        Stack pocketMeter = new Stack("meter");
        List<Found<Stack, String>> found = scan(mainMeter, offLocator,
                hotbar(new Stack("dirt"), pocketMeter, AIR, AIR, mainMeter, AIR, AIR, AIR, pocketLocator));

        assertEquals(List.of(mainMeter, offLocator, pocketMeter, pocketLocator),
                found.stream().map(Found::stack).toList());
        assertEquals(List.of(true, true, false, false), found.stream().map(Found::held).toList());
        assertEquals(List.of("meter", "locator", "meter", "locator"), found.stream().map(Found::device).toList());
    }

    @Test
    @DisplayName("two separate meters are two devices: identity, not equality, decides what is the main hand")
    void twoMetersAreTwoDevices() {
        Stack held = new Stack("meter");
        Stack pocket = new Stack("meter");
        List<Found<Stack, String>> found = scan(held, AIR, hotbar(held, AIR, AIR, AIR, AIR, pocket));

        assertEquals(2, found.size());
        assertTrue(found.get(0).held());
        assertFalse(found.get(1).held());
        assertSame(pocket, found.get(1).stack());
    }

    @Test
    @DisplayName("a device only in the hotbar is found, not held; an empty main hand hides nothing")
    void hotbarOnly() {
        Stack locator = new Stack("locator");
        // The selected slot is empty: the main hand is the shared AIR object, like every empty slot.
        List<Found<Stack, String>> found = scan(AIR, AIR, hotbar(AIR, AIR, AIR, AIR, AIR, AIR, AIR, locator));

        assertEquals(1, found.size());
        assertSame(locator, found.get(0).stack());
        assertFalse(found.get(0).held(), "a pocketed device runs but draws no HUD");
    }

    @Test
    @DisplayName("no device anywhere: nothing found; non-devices, air and null slots are skipped")
    void nothingCarried() {
        assertTrue(scan(AIR, AIR, hotbar()).isEmpty());
        assertTrue(scan(new Stack("dirt"), new Stack("torch"), hotbar(new Stack("bread"))).isEmpty());
        assertTrue(scan(null, null, Collections.nCopies(9, (Stack) null)).isEmpty());
    }

    // ---- dispatch ---------------------------------------------------------------------------------

    private static final BandTable BANDS = BandTable.of(
            Band.DEFAULT_900,
            new Band("band_3500", 3500.0, 4.0, 1.8, -106.0, 3));

    private static SignalSample servedOn900(long tick) {
        CellSample cell = new CellSample(7L, 0, 70, 0, -75.0, 90.0, 95.0, 0.0,
                "band_900", 4, 6.0, 0.0, 0.0);
        return new SignalSample(List.of(cell), tick, 7L, 15.0, -100.0, -104.0, ServiceLevel.GOOD, 2);
    }

    @Test
    @DisplayName("every carried device gets the same sample, its own verdict, its held flag, in carried order")
    void dispatchHandsTheOneSampleToEveryDevice() {
        List<SignalDevice> order = new ArrayList<>();
        RecordingDevice meter = new RecordingDevice(DeviceRequirement.NONE, order);
        RecordingDevice scanner = new RecordingDevice(new DeviceRequirement(ServiceLevel.GOOD, 3), order);
        RecordingDevice terminal = new RecordingDevice(new DeviceRequirement(ServiceLevel.GOOD, 1), order);
        SignalSample sample = servedOn900(1_000L);

        SignalTicker.dispatch(null,
                List.of(new CarriedDevice(null, meter, true),
                        new CarriedDevice(null, scanner, false),
                        new CarriedDevice(null, terminal, false)),
                sample, BANDS, RfConfig.DEFAULTS, null, 1_000L);

        assertEquals(List.of(meter, scanner, terminal), order);
        assertEquals(Verdict.OK, meter.contexts.get(0).verdict());
        assertEquals(Verdict.LOW_TIER, scanner.contexts.get(0).verdict(), "band_900 is tier 1, the scanner needs 3");
        assertEquals(Verdict.OK, terminal.contexts.get(0).verdict());
        for (RecordingDevice device : List.of(meter, scanner, terminal)) {
            DeviceContext ctx = device.contexts.get(0);
            assertSame(sample, ctx.sample(), "one evaluation, handed on as is: no device gets its own");
            assertSame(BANDS, ctx.bands());
            assertSame(RfConfig.DEFAULTS, ctx.config());
            assertEquals(1_000L, ctx.tick());
        }
        assertEquals(List.of(true), meter.held);
        assertEquals(List.of(false), scanner.held);
    }

    @Test
    @DisplayName("no service reaches a NONE device as NO_SERVICE, so it can show its own degraded state")
    void noServiceIsStillDispatched() {
        RecordingDevice meter = new RecordingDevice(DeviceRequirement.NONE);
        SignalTicker.dispatch(null, List.of(new CarriedDevice(null, meter, true)),
                SignalSample.empty(500L, 1), BANDS, RfConfig.DEFAULTS, null, 500L);

        assertEquals(1, meter.contexts.size());
        assertEquals(Verdict.NO_SERVICE, meter.contexts.get(0).verdict());
    }

    @Test
    @DisplayName("nothing carried: dispatch does nothing")
    void dispatchToNobody() {
        SignalTicker.dispatch(null, List.of(), servedOn900(1L), BANDS, RfConfig.DEFAULTS, null, 1L);
    }

    @Test
    @DisplayName("3C test: a LIMITED cell's FAIR cap reaches each verdict and context; the sample is untouched")
    void backhaulCapIsAppliedInTheNetworkLayerOnly() {
        RecordingDevice terminal = new RecordingDevice(new DeviceRequirement(ServiceLevel.GOOD, 1));
        RecordingDevice radioLink = new RecordingDevice(new DeviceRequirement(ServiceLevel.POOR, 1));
        SignalSample sample = servedOn900(2_000L);
        SignalSample copy = servedOn900(2_000L);
        assertEquals(ServiceLevel.GOOD, sample.serviceLevel());

        SignalTicker.dispatch(null,
                List.of(new CarriedDevice(null, terminal, true), new CarriedDevice(null, radioLink, false)),
                sample, BANDS, RfConfig.DEFAULTS, null, 2_000L, ServiceLevel.FAIR);

        DeviceContext terminalCtx = terminal.contexts.get(0);
        DeviceContext linkCtx = radioLink.contexts.get(0);
        assertEquals(Verdict.LOW_QUALITY, terminalCtx.verdict(), "GOOD is above the FAIR cap: the terminal stops");
        assertEquals(Verdict.OK, linkCtx.verdict(), "POOR is below it: the radio link keeps working");
        for (DeviceContext ctx : List.of(terminalCtx, linkCtx)) {
            assertSame(sample, ctx.sample(), "the one evaluation, handed on as is");
            assertEquals(ServiceLevel.GOOD, ctx.sample().serviceLevel(), "the radio link's own level, uncapped");
            assertEquals(ServiceLevel.FAIR, ctx.serviceCap());
            assertEquals(ServiceLevel.FAIR, ctx.effectiveServiceLevel());
            assertTrue(ctx.backhaulLimited());
        }
        assertEquals(copy, sample, "nothing in the sample changed");

        // No cap (FULL backhaul, or requireBackhaul off): exactly the uncapped dispatch.
        RecordingDevice uncapped = new RecordingDevice(new DeviceRequirement(ServiceLevel.GOOD, 1));
        SignalTicker.dispatch(null, List.of(new CarriedDevice(null, uncapped, true)),
                sample, BANDS, RfConfig.DEFAULTS, null, 2_000L);
        assertEquals(Verdict.OK, uncapped.contexts.get(0).verdict());
        assertEquals(ServiceLevel.EXCELLENT, uncapped.contexts.get(0).serviceCap());
        assertFalse(uncapped.contexts.get(0).backhaulLimited());
        assertEquals(ServiceLevel.GOOD, uncapped.contexts.get(0).effectiveServiceLevel());
    }

    /** A device that changes state once per evaluation and outputs on every dispatch, as {@link ReplayGuard} says. */
    private static final class CountingDevice implements SignalDevice {
        final ReplayGuard fresh = new ReplayGuard("test.counting");
        final UUID owner;
        int evaluationsCounted;
        int dispatches;

        CountingDevice(UUID owner) {
            this.owner = owner;
        }

        @Override
        public DeviceRequirement requirement(ItemStack stack) {
            return DeviceRequirement.NONE;
        }

        @Override
        public void onSample(ServerPlayer player, ItemStack stack, boolean held, DeviceContext ctx) {
            if (fresh.firstSighting(owner, ctx.sample())) {
                evaluationsCounted++;
            }
            dispatches++;
        }
    }

    @Test
    @DisplayName("a cached replay is dispatched again with the same timestampTick; a guarded device counts it once")
    void replaysAreDispatchedAndDevicesStayIdempotent() {
        CountingDevice device = new CountingDevice(UUID.randomUUID());
        List<CarriedDevice> carried = List.of(new CarriedDevice(null, device, false));
        SignalSample evaluated = servedOn900(1_000L);

        // Fresh at 1000, then the player stands still: the ticker replays the cached sample at 1020
        // and 1040 (dispatch tick = now, sample tick = the evaluation's), then evaluates at 1060.
        SignalTicker.dispatch(null, carried, evaluated, BANDS, RfConfig.DEFAULTS, null, 1_000L);
        SignalTicker.dispatch(null, carried, evaluated, BANDS, RfConfig.DEFAULTS, null, 1_020L);
        SignalTicker.dispatch(null, carried, evaluated, BANDS, RfConfig.DEFAULTS, null, 1_040L);
        SignalTicker.dispatch(null, carried, servedOn900(1_060L), BANDS, RfConfig.DEFAULTS, null, 1_060L);

        assertEquals(4, device.dispatches, "replays are dispatched: the device sees every interval");
        assertEquals(2, device.evaluationsCounted, "but only two evaluations happened");
    }

    // ---- forget -----------------------------------------------------------------------------------

    @Test
    @DisplayName("forget (logout, dimension change, respawn) also forgets every device's state for that player only")
    void forgetClearsDeviceState() {
        DeviceMemory<String> lastFix = DeviceMemory.create("test.last_fix");
        UUID leaving = UUID.randomUUID();
        UUID staying = UUID.randomUUID();
        lastFix.put(leaving, "fix A");
        lastFix.put(staying, "fix B");

        SignalTicker.forget(leaving);

        assertNull(lastFix.get(leaving));
        assertEquals("fix B", lastFix.get(staying));
    }
}
