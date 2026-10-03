package dev.rancraft.gametest;

import dev.rancraft.RanCraft;
import dev.rancraft.RanCraftConfig;
import dev.rancraft.block.AntennaBlockEntity;
import dev.rancraft.block.SectorAntennaBlockEntity;
import dev.rancraft.client.ScannerHudText;
import dev.rancraft.data.RfDataLoader;
import dev.rancraft.device.DeviceContext;
import dev.rancraft.device.DeviceMemory;
import dev.rancraft.device.ProximityScanner;
import dev.rancraft.net.ScannerPayload;
import dev.rancraft.net.ScannerPayload.Status;
import dev.rancraft.registry.ModBlocks;
import dev.rancraft.registry.ModItems;
import dev.rancraft.rf.CellParams;
import dev.rancraft.rf.ReceiverState;
import dev.rancraft.rf.RfConfig;
import dev.rancraft.rf.RfEngine;
import dev.rancraft.rf.ServiceLevel;
import dev.rancraft.rf.SignalSample;
import dev.rancraft.world.LevelWorldProbe;
import dev.rancraft.world.SignalTicker;
import dev.rancraft.world.SiteRegistry;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Consumer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.AfterBatch;
import net.minecraft.gametest.framework.GameTestAssertException;
import net.minecraft.gametest.framework.GameTestGenerator;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.TestFunction;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;

/**
 * The Proximity Scanner at runtime (Phase 3 slice 14, §3C.4). {@code ProximityScannerTest} and
 * {@code ProximityScanTest} pin the rules headless; this runs them on a live server level: a real sector
 * antenna fitted with a real Wideband Radio Unit, real mobs, the ticker's own carried-device scan and
 * dispatch ({@link SignalTicker#dispatchToCarried}), and the {@link ScannerPayload} that actually reaches
 * the player's connection.
 *
 * <p>Checked: on band_3500 at GOOD or better the payload lists the hostile mobs within 24 blocks, nearest
 * first, with their distances and compass bearings, through a stone wall, with the 24-block boundary in,
 * a mob 30 blocks out and a cow left off; at most 16, the nearest kept; a replayed sample lists the mobs
 * where they are now; a scanner in the hotbar sends nothing, one in each hand sends one payload, and a dead
 * player is sent nothing; on band_1800 the payload is a refusal with no list, which the HUD words "needs
 * tier 3, you're on band_1800 (tier 2)" (the 3C done-when). The cost of the scan is logged.
 *
 * <p><b>The player.</b> A {@link SilentServerPlayer}, which keeps every custom payload sent to it. It is
 * not on the player list, so the real ticker never evaluates it: the test evaluates its eye with the engine
 * against the site registry's cells in range (the ticker's per-player evaluation, without the handover
 * state) and hands the sample to {@link SignalTicker#dispatchToCarried}, as {@code StorageTerminalGameTests}
 * does. Mobs have no AI (they stay where they are put), stand on stone, and are discarded afterwards.
 *
 * <p>The test builds far from every other (chunk 1024, 1024), in forced overworld chunks at y {@value #Y}
 * (open air in the flat test world), in a batch of its own; its {@link AfterBatch} method removes the mobs
 * and blocks and releases the chunks, also when the test failed.
 */
@GameTestHolder(RanCraft.MOD_ID)
public final class ProximityScannerGameTests {

    private static final String BATCH = "rancraft_proximity_scanner";
    private static final int TIMEOUT_TICKS = 400;
    /** Block entities run {@code onLoad} on the next tick; fresh entities join their sections. */
    private static final int SETTLE = 3;

    /** Open air in the flat test world: the player's feet and the mobs stand at this height. */
    private static final int Y = 100;
    /** Chunk (1024, 1024): 4000 blocks from the Storage Terminal tests. */
    private static final int X = 16_384;
    private static final int Z = 16_384;

    /** How often the scan is timed, after as many runs to warm up. */
    private static final int COST_RUNS = 2_000;

    /** Undone by the batch's {@link AfterBatch} method, newest first, pass or fail. Server thread. */
    private static final List<Runnable> CLEANUPS = new ArrayList<>();

    private ProximityScannerGameTests() {
    }

    @GameTestGenerator
    public static Collection<TestFunction> proximityScannerTests() {
        String prefix = ProximityScannerGameTests.class.getSimpleName().toLowerCase(Locale.ROOT) + ".";
        return List.of(new TestFunction(BATCH, prefix + "lists_hostiles_on_band_3500_and_needs_tier_3_on_band_1800",
                HarvestGameTests.EMPTY_TEMPLATE, TIMEOUT_TICKS, 0L, true, (Consumer<GameTestHelper>) ProximityScannerGameTests::scanner));
    }

    @AfterBatch(batch = BATCH)
    public static void afterScanner(ServerLevel level) {
        for (int i = CLEANUPS.size() - 1; i >= 0; i--) {
            try {
                CLEANUPS.get(i).run();
            } catch (RuntimeException failure) {
                RanCraft.LOGGER.error("RANCraft proximity scanner game test cleanup failed", failure);
            }
        }
        CLEANUPS.clear();
    }

    /** A mob to place: its type and where it stands, in blocks from the player's feet (east, south). */
    private record Spot(EntityType<? extends Mob> type, int dx, int dz) {
    }

    /** The mobs of the first scan: the hostile ones within 24 blocks in the order the list must have. */
    private static final List<Spot> LISTED = List.of(
            new Spot(EntityType.CREEPER, 5, 0),       // 5 blocks, east (90)
            new Spot(EntityType.WITCH, -6, 6),        // 8.49 blocks, south-west (225)
            new Spot(EntityType.SPIDER, 0, -10),      // 10 blocks, north (0), behind a stone wall
            new Spot(EntityType.SLIME, 0, 13),        // 13 blocks, south (180): an Enemy, not a Monster
            new Spot(EntityType.CREEPER, 0, 24));     // exactly 24 blocks, south: the range is inclusive
    private static final double[] LISTED_DISTANCES = {5.0, Math.sqrt(72.0), 10.0, 13.0, 24.0};
    private static final double[] LISTED_BEARINGS = {90.0, 225.0, 0.0, 180.0, 180.0};
    /** Hostile but out of range. */
    private static final Spot FAR = new Spot(EntityType.CREEPER, 30, 0);
    /** In range but not hostile. */
    private static final Spot COW = new Spot(EntityType.COW, 3, 3);
    /** Where the first creeper is moved to for the replay check. */
    private static final int MOVED_DX = 7;
    /** Silverfish on a ring this far out, to take the count past the cap. */
    private static final int RING_RADIUS = 20;
    private static final int RING_COUNT = 14;

    private static void scanner(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos feet = new BlockPos(X, Y, Z);
        // The sector 12 blocks west of the player, a block up, aimed east at the player's eye.
        BlockPos sectorPos = feet.offset(-12, 1, 0);
        List<Spot> ring = ring();

        List<BlockPos> blocks = new ArrayList<>();
        blocks.add(sectorPos);
        List<Spot> first = new ArrayList<>(LISTED);
        first.add(FAR);
        first.add(COW);
        for (Spot spot : first) {
            blocks.add(feet.offset(spot.dx(), -1, spot.dz()));
        }
        for (Spot spot : ring) {
            blocks.add(feet.offset(spot.dx(), -1, spot.dz()));
        }
        blocks.add(feet.offset(MOVED_DX, -1, 0));
        // A 3x3 stone wall between the player and the spider: the list is not line of sight.
        for (int dx = -1; dx <= 1; dx++) {
            for (int dy = 0; dy <= 2; dy++) {
                blocks.add(feet.offset(dx, dy, -5));
            }
        }

        Set<Long> chunks = new TreeSet<>();
        for (BlockPos pos : blocks) {
            chunks.add(ChunkPos.asLong(pos));
        }
        List<Long> forced = new ArrayList<>();
        CLEANUPS.add(() -> BackhaulGameTests.release(level, forced));
        BackhaulGameTests.force(level, chunks, forced);
        CLEANUPS.add(() -> BackhaulGameTests.removeAll(level, blocks));

        for (BlockPos pos : blocks) {
            if (!pos.equals(sectorPos)) {
                level.setBlock(pos, Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
            }
        }
        level.setBlock(sectorPos, ModBlocks.SECTOR_ANTENNA.get().defaultBlockState(), Block.UPDATE_ALL);

        SilentServerPlayer player = new SilentServerPlayer(level, "rancraft_scanner");
        CLEANUPS.add(() -> DeviceMemory.forget(player.getUUID()));
        player.moveTo(feet.getX() + 0.5, Y, feet.getZ() + 0.5);
        ItemStack scanner = new ItemStack(ModItems.PROXIMITY_SCANNER.get());
        player.setItemInHand(InteractionHand.MAIN_HAND, scanner);

        List<Entity> spawned = new ArrayList<>();
        CLEANUPS.add(() -> spawned.forEach(Entity::discard));
        Mob[] firstCreeper = new Mob[1];
        SignalSample[] onBand3500 = new SignalSample[1];

        helper.startSequence()
                .thenIdle(SETTLE)
                .thenExecute(() -> {
                    // Tier 3 through the real Wideband Radio Unit, then band_3500 (tier 3), aimed at the player.
                    if (!(level.getBlockEntity(sectorPos) instanceof SectorAntennaBlockEntity sector)) {
                        helper.fail("no sector antenna at " + sectorPos);
                        return;
                    }
                    // In the hand first: NeoForge's hook uses the context's stack, which is the hand's.
                    ItemStack unit = new ItemStack(ModItems.WIDEBAND_RADIO_UNIT.get());
                    player.setItemInHand(InteractionHand.MAIN_HAND, unit);
                    BlockHitResult hit = new BlockHitResult(Vec3.atCenterOf(sectorPos), Direction.UP, sectorPos, false);
                    InteractionResult fitted = unit.useOn(new UseOnContext(player, InteractionHand.MAIN_HAND, hit));
                    player.setItemInHand(InteractionHand.MAIN_HAND, scanner);
                    helper.assertTrue(fitted.consumesAction() && sector.radioTier() == 3 && sector.hasWidebandUnit(),
                            "the Wideband Radio Unit made the sector tier 3: " + fitted + ", tier " + sector.radioTier());
                    configure(helper, sector, "band_3500");

                    for (Spot spot : first) {
                        Mob mob = spawn(helper, level, spot, feet, spawned);
                        if (spot == LISTED.get(0)) {
                            firstCreeper[0] = mob;
                        }
                    }
                })
                .thenIdle(SETTLE)
                .thenExecute(() -> {
                    RfConfig config = RanCraftConfig.snapshot();
                    helper.assertTrue(RanCraftConfig.scannerRangeBlocks() == 24.0,
                            "the default range is the spec's 24 blocks: " + RanCraftConfig.scannerRangeBlocks());

                    // 1. band_3500 at GOOD or better: the list, nearest first (here the 5 hostiles in range).
                    SignalSample sample = evaluate(level, player);
                    onBand3500[0] = sample;
                    helper.assertTrue(sample.serviceLevel().atLeast(ServiceLevel.GOOD) && sample.servingCellId() == sectorPos.asLong()
                                    && sample.serving().map(cell -> cell.bandId().equals("band_3500")).orElse(false),
                            "fixture: GOOD or better on the band_3500 sector, got " + sample.serviceLevel());
                    ScannerPayload ok = dispatchOne(helper, player, sample, config, "band_3500");
                    helper.assertTrue(ok.status() == Status.OK, "band_3500 at " + sample.serviceLevel() + " is OK: " + ok.status());
                    helper.assertTrue(ok.servingBandId().equals("band_3500") && ok.servingBandTier() == 3
                            && ok.rangeBlocks() == 24.0f, "the band, its tier and the range are the server's: " + ok);
                    helper.assertTrue(ok.contacts().size() == LISTED.size(),
                            "the 5 hostiles in range, not the cow or the creeper 30 blocks out: " + ok.contacts());
                    assertListed(helper, ok, LISTED.size());

                    // A replayed sample (the ticker dispatches its cached evaluation unchanged) lists the mobs
                    // where they are now: the first creeper moved from 5 to 7 blocks east, now nearest.
                    firstCreeper[0].moveTo(feet.getX() + MOVED_DX + 0.5, Y, feet.getZ() + 0.5, 0.0F, 0.0F);
                    ScannerPayload replay = dispatchOne(helper, player, sample, config, "a replay");
                    helper.assertTrue(replay.contacts().size() == LISTED.size()
                                    && replay.contacts().get(0).typeId().equals("minecraft:creeper")
                                    && near(replay.contacts().get(0).distanceBlocks(), MOVED_DX)
                                    && replay.contacts().get(1).typeId().equals("minecraft:witch"),
                            "a replay lists the moved creeper at 7 blocks: " + replay.contacts());
                    firstCreeper[0].moveTo(feet.getX() + 5.5, Y, feet.getZ() + 0.5, 0.0F, 0.0F);

                    // 2. The cap: 14 silverfish on a ring 20 blocks out make 19 hostiles in range.
                    for (Spot spot : ring) {
                        spawn(helper, level, spot, feet, spawned);
                    }
                })
                .thenIdle(SETTLE)
                .thenExecute(() -> {
                    RfConfig config = RanCraftConfig.snapshot();
                    SignalSample sample = onBand3500[0];
                    // 16 are listed, the 4 nearest first, and the creeper at exactly 24 blocks (the farthest)
                    // is the one dropped, with two silverfish.
                    ScannerPayload capped = dispatchOne(helper, player, sample, config, "19 hostiles");
                    helper.assertTrue(capped.contacts().size() == ScannerPayload.MAX_CONTACTS,
                            "at most 16 of 19: " + capped.contacts().size());
                    assertListed(helper, capped, LISTED.size() - 1);
                    for (int i = 1; i < capped.contacts().size(); i++) {
                        helper.assertTrue(capped.contacts().get(i).distanceBlocks() >= capped.contacts().get(i - 1).distanceBlocks(),
                                "nearest first: " + capped.contacts());
                    }
                    for (int i = LISTED.size() - 1; i < capped.contacts().size(); i++) {
                        ScannerPayload.Contact contact = capped.contacts().get(i);
                        helper.assertTrue(contact.typeId().equals("minecraft:silverfish") && contact.distanceBlocks() < 21.0f,
                                "the rest are silverfish on the ring, not the creeper at 24: " + contact);
                    }

                    // The cost of one dispatch to a held scanner with 20 mobs around (19 hostile).
                    measureCost(level, player, sample, config);

                    // 3. Which scanners send: one in the hotbar sends nothing, one in each hand sends once.
                    player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.STICK));
                    player.getInventory().setItem(4, scanner);
                    player.payloads.clear();
                    SignalTicker.dispatchToCarried(player, sample, RfDataLoader.bands(), config);
                    helper.assertTrue(scannerPayloads(player).isEmpty(), "a scanner in the hotbar sends nothing");
                    player.getInventory().setItem(4, ItemStack.EMPTY);
                    player.setItemInHand(InteractionHand.MAIN_HAND, scanner);
                    player.setItemInHand(InteractionHand.OFF_HAND, new ItemStack(ModItems.PROXIMITY_SCANNER.get()));
                    dispatchOne(helper, player, sample, config, "a scanner in each hand");
                    player.setItemInHand(InteractionHand.OFF_HAND, ItemStack.EMPTY);

                    // A dead player (the ticker keeps evaluating one on the death screen) is sent nothing.
                    player.setHealth(0.0F);
                    player.payloads.clear();
                    SignalTicker.dispatchToCarried(player, sample, RfDataLoader.bands(), config);
                    helper.assertTrue(scannerPayloads(player).isEmpty(), "a dead player is sent nothing");
                    player.setHealth(player.getMaxHealth());

                    // 4. The 3C done-when: band_1800 (tier 2) at GOOD or better refuses with "needs tier 3".
                    if (!(level.getBlockEntity(sectorPos) instanceof AntennaBlockEntity sector)) {
                        helper.fail("the sector antenna went missing");
                        return;
                    }
                    configure(helper, sector, "band_1800");
                    SignalSample on1800 = evaluate(level, player);
                    helper.assertTrue(on1800.serviceLevel().atLeast(ServiceLevel.GOOD),
                            "fixture: GOOD or better on band_1800, got " + on1800.serviceLevel());
                    ScannerPayload refused = dispatchOne(helper, player, on1800, config, "band_1800");
                    helper.assertTrue(refused.status() == Status.LOW_TIER && refused.contacts().isEmpty()
                                    && refused.servingBandId().equals("band_1800") && refused.servingBandTier() == 2
                                    && refused.neededTier() == 3 && refused.neededLevel() == ServiceLevel.GOOD
                                    && refused.radioLevel().atLeast(ServiceLevel.GOOD),
                            "band_1800 at " + on1800.serviceLevel() + " is refused for its tier, with no list: " + refused);
                    String told = ScannerHudText.refusal(refused);
                    helper.assertTrue(told.equals("needs tier 3, you're on band_1800 (tier 2)"),
                            "the HUD says \"needs tier 3\": " + told);
                })
                .thenSucceed();
    }

    // ---- helpers ------------------------------------------------------------------------------------

    /** 14 spots on a circle of 20 blocks, off the axes, rounded to whole blocks. */
    private static List<Spot> ring() {
        List<Spot> ring = new ArrayList<>(RING_COUNT);
        for (int i = 0; i < RING_COUNT; i++) {
            double angle = Math.toRadians(10.0 + i * 360.0 / RING_COUNT);
            ring.add(new Spot(EntityType.SILVERFISH, (int) Math.round(RING_RADIUS * Math.sin(angle)),
                    (int) Math.round(-RING_RADIUS * Math.cos(angle))));
        }
        return ring;
    }

    /** A mob with no AI (it stays put), kept from despawning, standing at {@code spot} on its stone. */
    private static Mob spawn(GameTestHelper helper, ServerLevel level, Spot spot, BlockPos feet, List<Entity> spawned) {
        Mob mob = spot.type().create(level);
        if (mob == null) {
            helper.fail("could not create a " + spot.type());
            throw new IllegalStateException("unreachable");
        }
        mob.moveTo(feet.getX() + spot.dx() + 0.5, Y, feet.getZ() + spot.dz() + 0.5, 0.0F, 0.0F);
        mob.setNoAi(true);
        mob.setPersistenceRequired();
        helper.assertTrue(level.addFreshEntity(mob), "the " + spot.type() + " joined the level");
        spawned.add(mob);
        return mob;
    }

    /** Points the sector east, level, on {@code bandId} (as the config screen would, validated there). */
    private static void configure(GameTestHelper helper, AntennaBlockEntity sector, String bandId) {
        sector.applyConfiguration(bandId, sector.txPowerDbm(), 90.0, 0.0, sector.hBeamwidthDeg(),
                sector.vBeamwidthDeg(), sector.pci());
        helper.assertTrue(sector.bandId().equals(bandId) && sector.onAir(), "the sector is on the air on " + bandId);
    }

    /** The ticker's evaluation of the player's eye: the site registry's cells in range, no handover state. */
    private static SignalSample evaluate(ServerLevel level, SilentServerPlayer player) {
        RfConfig config = RanCraftConfig.snapshot();
        Vec3 eye = player.getEyePosition();
        Collection<CellParams> candidates = SiteRegistry.of(level).near(eye.x, eye.y, eye.z, config.maxEvaluationRangeBlocks());
        return RfEngine.evaluate(new LevelWorldProbe(level, RfDataLoader.materials()), eye.x, eye.y, eye.z, candidates,
                RfDataLoader.bands(), config, level.getGameTime(), ReceiverState.NONE).sample();
    }

    /** One dispatch through the ticker's own scan; exactly one scanner payload must reach the player. */
    private static ScannerPayload dispatchOne(GameTestHelper helper, SilentServerPlayer player, SignalSample sample,
                                              RfConfig config, String what) {
        player.payloads.clear();
        SignalTicker.dispatchToCarried(player, sample, RfDataLoader.bands(), config);
        List<ScannerPayload> sent = scannerPayloads(player);
        helper.assertTrue(sent.size() == 1, what + ": one scanner payload per dispatch, got " + sent.size());
        return sent.get(0);
    }

    private static List<ScannerPayload> scannerPayloads(SilentServerPlayer player) {
        List<ScannerPayload> sent = new ArrayList<>();
        for (CustomPacketPayload payload : player.payloads) {
            if (payload instanceof ScannerPayload scanner) {
                sent.add(scanner);
            }
        }
        return sent;
    }

    /** The first {@code count} contacts are {@link #LISTED}'s, in order, at their distances and bearings. */
    private static void assertListed(GameTestHelper helper, ScannerPayload payload, int count) {
        helper.assertTrue(payload.contacts().size() >= count, "at least " + count + " listed: " + payload.contacts());
        for (int i = 0; i < count; i++) {
            ScannerPayload.Contact contact = payload.contacts().get(i);
            String type = EntityType.getKey(LISTED.get(i).type()).toString();
            helper.assertTrue(contact.typeId().equals(type) && near(contact.distanceBlocks(), LISTED_DISTANCES[i])
                            && near(contact.bearingDegrees(), LISTED_BEARINGS[i]),
                    "entry " + i + " is the " + type + " at " + LISTED_DISTANCES[i] + " blocks, bearing "
                            + LISTED_BEARINGS[i] + ": " + contact);
        }
        for (ScannerPayload.Contact contact : payload.contacts()) {
            helper.assertTrue(!contact.typeId().equals("minecraft:cow"), "a cow is not hostile: " + payload.contacts());
            helper.assertTrue(contact.distanceBlocks() <= 24.0f, "nothing beyond 24 blocks: " + contact);
        }
    }

    private static boolean near(float actual, double expected) {
        return Math.abs(actual - expected) < 0.01;
    }

    /**
     * Times one dispatch to the held scanner (the ticker's carried scan, the cap lookup, the entity query,
     * the list and the payload) and the scan alone, and logs both. Asserts only a generous ceiling, the
     * server's per-evaluation budget of 1 ms.
     */
    private static void measureCost(ServerLevel level, SilentServerPlayer player, SignalSample sample, RfConfig config) {
        double range = RanCraftConfig.scannerRangeBlocks();
        DeviceContext ctx = new DeviceContext(sample, ProximityScanner.REQUIREMENT.check(sample, RfDataLoader.bands()),
                RfDataLoader.bands(), config, level, level.getGameTime());
        int listed = 0;
        for (int i = 0; i < COST_RUNS; i++) {
            listed += ProximityScanner.payloadFor(player, ctx, range).contacts().size();
        }
        long start = System.nanoTime();
        for (int i = 0; i < COST_RUNS; i++) {
            listed += ProximityScanner.payloadFor(player, ctx, range).contacts().size();
        }
        double scanMicros = (System.nanoTime() - start) / 1_000.0 / COST_RUNS;

        start = System.nanoTime();
        for (int i = 0; i < COST_RUNS; i++) {
            player.payloads.clear();
            SignalTicker.dispatchToCarried(player, sample, RfDataLoader.bands(), config);
        }
        double dispatchMicros = (System.nanoTime() - start) / 1_000.0 / COST_RUNS;
        player.payloads.clear();
        RanCraft.LOGGER.info(String.format(Locale.ROOT,
                "RANCraft proximity scanner cost: %.2f us per scan (entity query, list, payload), %.2f us per dispatch "
                        + "to a held scanner, 20 mobs within the box, 16 listed (%d runs, checksum %d)",
                scanMicros, dispatchMicros, COST_RUNS, listed));
        if (scanMicros > 1_000.0 || dispatchMicros > 1_000.0) {
            throw new GameTestAssertException(String.format(Locale.ROOT,
                    "the scan costs %.1f us, the dispatch %.1f us: over the 1 ms evaluation budget", scanMicros, dispatchMicros));
        }
    }
}
