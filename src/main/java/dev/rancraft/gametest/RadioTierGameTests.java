package dev.rancraft.gametest;

import com.mojang.authlib.GameProfile;
import dev.rancraft.RanCraft;
import dev.rancraft.block.AntennaBlockEntity;
import dev.rancraft.block.SectorAntennaBlockEntity;
import dev.rancraft.block.SignalMastBlockEntity;
import dev.rancraft.net.UpdateCellParamsPayload;
import dev.rancraft.registry.ModBlocks;
import dev.rancraft.registry.ModItems;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.function.Consumer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTestGenerator;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.TestFunction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Clearable;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.gametest.GameTestHolder;

/**
 * Radio tiers at runtime (Phase 3 slice 10, §3C.1): the real blocks, the real configuration packet
 * handler ({@link UpdateCellParamsPayload#applyOn}), the real item use and block break paths of
 * {@code ServerPlayerGameMode}, and the real save migration. {@code RadioTierTest} pins the rule
 * itself headless.
 *
 * <ul>
 *   <li><b>{@code tier_2_sector_needs_a_wideband_unit_for_band_3500}</b> (3C done-when, 3C test): a
 *       fresh sector is tier 2 and refuses band_3500 while taking band_1800; a Wideband Radio Unit
 *       used on it (through {@code useItemOn}, so the configuration screen must not open instead)
 *       raises it to tier 3 and is consumed; a second unit is refused and kept; band_3500 is then
 *       accepted; the unit does nothing to a Signal Mast; breaking the sector with a pickaxe drops
 *       the sector and the unit.</li>
 *   <li><b>{@code v2_to_v3_migration_grandfathers_band_3500}</b> (3C test): saved data in the v2
 *       format (no {@code RadioTier}) on four fresh antennas, as a chunk load hands it over before
 *       {@code onLoad}: a sector on band_3500 comes back tier 3 and still accepts band_3500, one on
 *       band_1800 tier 2, a mast tier 1; a v3 save keeps its tier; a v2 PCI 0 is kept (not
 *       re-planned, unlike a v1 PCI 0); a sector replaced the way {@code /setblock} replaces it (its
 *       entity cleared first) drops no unit; and breaking the grandfathered sector drops one.</li>
 * </ul>
 *
 * <p>Test-harness abstraction, stated plainly: the player is a NeoForge {@link FakePlayer} built
 * directly (a {@code ServerPlayer}, which {@code applyOn} and {@code ServerPlayerGameMode} need; not on
 * the player list, its connection sends nothing, so the screen a use might open goes nowhere). The
 * packet is handed to {@code applyOn} as the payload handler would, not sent over a connection. There
 * is no Phase 2 save to load: the v2 data is the entity's own save with {@code DataVersion} 2 and no
 * {@code RadioTier}, handed to a freshly placed entity before its {@code onLoad}, the order a chunk
 * load uses (as in {@code MastColumnGameTests}).
 */
@GameTestHolder(RanCraft.MOD_ID)
public final class RadioTierGameTests {

    private static final String BATCH = "rancraft_radio_tier";
    private static final int TIMEOUT_TICKS = 100;
    private static final int SETTLE = 3;
    /** Item entities from a break land within this many blocks of the broken block. */
    private static final double DROP_RADIUS = 3.0;

    private RadioTierGameTests() {
    }

    @GameTestGenerator
    public static Collection<TestFunction> radioTierTests() {
        String prefix = RadioTierGameTests.class.getSimpleName().toLowerCase(Locale.ROOT) + ".";
        List<TestFunction> tests = new ArrayList<>();
        tests.add(test(prefix + "tier_2_sector_needs_a_wideband_unit_for_band_3500",
                RadioTierGameTests::tierTwoSectorNeedsAWidebandUnit));
        tests.add(test(prefix + "v2_to_v3_migration_grandfathers_band_3500",
                RadioTierGameTests::migrationGrandfathersBand3500));
        return tests;
    }

    private static TestFunction test(String name, Consumer<GameTestHelper> body) {
        return new TestFunction(BATCH, name, HarvestGameTests.EMPTY_TEMPLATE, TIMEOUT_TICKS, 0L, true, body);
    }

    // ---- the tests --------------------------------------------------------------------------------

    private static void tierTwoSectorNeedsAWidebandUnit(GameTestHelper helper) {
        BlockPos sectorRel = new BlockPos(1, 1, 1);
        BlockPos mastRel = new BlockPos(0, 1, 0);
        helper.setBlock(sectorRel, ModBlocks.SECTOR_ANTENNA.get());
        helper.setBlock(mastRel, ModBlocks.SIGNAL_MAST.get());
        ServerLevel level = helper.getLevel();
        BlockPos sectorPos = helper.absolutePos(sectorRel);
        BlockPos mastPos = helper.absolutePos(mastRel);
        if (!(level.getBlockEntity(sectorPos) instanceof SectorAntennaBlockEntity sector)
                || !(level.getBlockEntity(mastPos) instanceof SignalMastBlockEntity mast)) {
            helper.fail("no sector antenna or signal mast entity placed");
            return;
        }
        FakePlayer player = playerNear(level, sectorPos, "rancraft_radio_tier");
        if (player.isCreative()) {
            helper.fail("fixture: the fake player is in creative, which would keep the unit");
            return;
        }

        // A fresh sector is tier 2: band_3500 refused, band_1800 taken.
        check(helper, sector.radioTier() == SectorAntennaBlockEntity.RADIO_TIER && !sector.hasWidebandUnit(),
                "a fresh sector is tier 2 with no unit, got tier " + sector.radioTier());
        check(helper, !apply(player, sector, sectorPos, "band_3500"), "a tier-2 sector accepted band_3500");
        check(helper, sector.bandId().equals("band_900"), "the refused request changed the band to " + sector.bandId());
        check(helper, apply(player, sector, sectorPos, "band_1800"), "a tier-2 sector refused band_1800");
        check(helper, sector.bandId().equals("band_1800"), "band_1800 not applied: " + sector.bandId());

        // The unit does nothing to a mast and is kept.
        ItemStack units = new ItemStack(ModItems.WIDEBAND_RADIO_UNIT.get(), 2);
        player.setItemInHand(InteractionHand.MAIN_HAND, units);
        InteractionResult onMast = use(player, level, mastPos);
        check(helper, !onMast.consumesAction() && units.getCount() == 2 && mast.radioTier() == SignalMastBlockEntity.RADIO_TIER,
                "the unit acted on a mast: " + onMast + ", count " + units.getCount() + ", mast tier " + mast.radioTier());

        // Fitted through the real use path: tier 3, one unit consumed.
        InteractionResult fitted = use(player, level, sectorPos);
        check(helper, fitted.consumesAction(), "using the unit on the sector did not act: " + fitted);
        check(helper, sector.radioTier() == 3 && sector.hasWidebandUnit(), "not tier 3 after fitting: " + sector.radioTier());
        check(helper, units.getCount() == 1, "the unit was not consumed: " + units.getCount() + " left of 2");
        check(helper, sector.saveWithoutMetadata(level.registryAccess()).getInt(AntennaBlockEntity.RADIO_TIER_TAG) == 3,
                "the fitted tier is not saved");

        // A second unit is refused and kept.
        InteractionResult again = use(player, level, sectorPos);
        check(helper, !again.consumesAction() && units.getCount() == 1, "a second unit was taken: " + again
                + ", " + units.getCount() + " left");

        check(helper, apply(player, sector, sectorPos, "band_3500"), "the tier-3 sector refused band_3500");
        check(helper, sector.bandId().equals("band_3500"), "band_3500 not applied: " + sector.bandId());

        // A survival break with a pickaxe: the sector drops itself and the unit.
        player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.IRON_PICKAXE));
        check(helper, player.gameMode.destroyBlock(sectorPos), "the fake player could not break the sector");
        check(helper, level.getBlockState(sectorPos).isAir(), "the sector is still there");
        helper.assertItemEntityCountIs(ModItems.WIDEBAND_RADIO_UNIT.get(), sectorRel, DROP_RADIUS, 1);
        helper.assertItemEntityCountIs(ModItems.SECTOR_ANTENNA.get(), sectorRel, DROP_RADIUS, 1);
        helper.killAllEntities();
        helper.succeed();
    }

    private static void migrationGrandfathersBand3500(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos onBand3500Rel = new BlockPos(0, 1, 0);
        BlockPos onBand1800Rel = new BlockPos(2, 1, 0);
        BlockPos savedV3Rel = new BlockPos(0, 1, 2);
        BlockPos mastRel = new BlockPos(2, 1, 2);
        helper.setBlock(onBand3500Rel, ModBlocks.SECTOR_ANTENNA.get());
        helper.setBlock(onBand1800Rel, ModBlocks.SECTOR_ANTENNA.get());
        helper.setBlock(savedV3Rel, ModBlocks.SECTOR_ANTENNA.get());
        helper.setBlock(mastRel, ModBlocks.SIGNAL_MAST.get());
        BlockPos onBand3500Pos = helper.absolutePos(onBand3500Rel);
        if (!(level.getBlockEntity(onBand3500Pos) instanceof SectorAntennaBlockEntity onBand3500)
                || !(level.getBlockEntity(helper.absolutePos(onBand1800Rel)) instanceof SectorAntennaBlockEntity onBand1800)
                || !(level.getBlockEntity(helper.absolutePos(savedV3Rel)) instanceof SectorAntennaBlockEntity savedV3)
                || !(level.getBlockEntity(helper.absolutePos(mastRel)) instanceof SignalMastBlockEntity mast)) {
            helper.fail("antenna entities not placed");
            return;
        }

        // Saved data, as the chunk load hands it over before onLoad.
        loadSaved(level, onBand3500, 2, "band_3500", 0, null);
        loadSaved(level, onBand1800, 2, "band_1800", 7, null);
        loadSaved(level, savedV3, 3, "band_900", 9, 3);
        loadSaved(level, mast, 2, "band_900", 11, null);

        check(helper, onBand3500.radioTier() == 3 && onBand3500.hasWidebandUnit(),
                "a v2 sector on band_3500 was not grandfathered to tier 3: " + onBand3500.radioTier());
        check(helper, onBand3500.bandId().equals("band_3500"), "the grandfathered sector lost its band: " + onBand3500.bandId());
        check(helper, onBand1800.radioTier() == SectorAntennaBlockEntity.RADIO_TIER,
                "a v2 sector on band_1800 is not tier 2: " + onBand1800.radioTier());
        check(helper, savedV3.radioTier() == 3, "a v3 save's tier 3 was not kept: " + savedV3.radioTier());
        check(helper, mast.radioTier() == SignalMastBlockEntity.RADIO_TIER, "a v2 mast is not tier 1: " + mast.radioTier());
        check(helper, !onBand3500.pciPlanPending() && onBand3500.pci() == 0,
                "a v2 PCI 0 was marked for re-planning (only a v1 PCI 0 means unassigned)");

        // Nothing that worked before stops working: the grandfathered sector takes band_3500 again.
        FakePlayer player = playerNear(level, onBand3500Pos, "rancraft_radio_tier_migration");
        check(helper, apply(player, onBand3500, onBand3500Pos, "band_3500"),
                "the grandfathered sector refused its own band_3500");
        check(helper, !apply(player, onBand1800, helper.absolutePos(onBand1800Rel), "band_3500"),
                "the migrated tier-2 sector accepted band_3500");

        helper.startSequence()
                .thenIdle(SETTLE)
                .thenExecute(() -> {
                    check(helper, onBand3500.pci() == 0, "the v2 PCI 0 was re-planned to " + onBand3500.pci());
                    // A command replacing a sector does not spill its unit: /setblock clears a
                    // Clearable entity first, as it does a chest.
                    BlockPos savedV3Pos = helper.absolutePos(savedV3Rel);
                    Clearable.tryClear(level.getBlockEntity(savedV3Pos));
                    level.setBlock(savedV3Pos, Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS);
                    helper.assertItemEntityNotPresent(ModItems.WIDEBAND_RADIO_UNIT.get(), savedV3Rel, DROP_RADIUS);
                    // The grandfathered tier counts as a fitted unit: breaking the sector drops one.
                    level.destroyBlock(onBand3500Pos, false);
                    helper.assertItemEntityCountIs(ModItems.WIDEBAND_RADIO_UNIT.get(), onBand3500Rel, 1.5, 1);
                    helper.killAllEntities();
                })
                .thenSucceed();
    }

    // ---- helpers ----------------------------------------------------------------------------------

    private static void check(GameTestHelper helper, boolean condition, String failure) {
        helper.assertTrue(condition, failure);
    }

    /** A survival fake player two blocks from {@code pos}, within the configuration reach. */
    private static FakePlayer playerNear(ServerLevel level, BlockPos pos, String name) {
        UUID id = UUID.nameUUIDFromBytes(name.getBytes(StandardCharsets.UTF_8));
        FakePlayer player = new FakePlayer(level, new GameProfile(id, name));
        player.setPos(pos.getX() + 0.5, pos.getY(), pos.getZ() + 2.5);
        return player;
    }

    /** The antenna's own settings with another band, through the real server-side handler. */
    private static boolean apply(FakePlayer player, AntennaBlockEntity antenna, BlockPos pos, String bandId) {
        return UpdateCellParamsPayload.applyOn(player, new UpdateCellParamsPayload(
                pos, bandId, antenna.txPowerDbm(), antenna.azimuthDeg(), antenna.tiltDeg(),
                antenna.hBeamwidthDeg(), antenna.vBeamwidthDeg(), antenna.pci()));
    }

    /** A right click on the block's top face with the main hand, through {@code ServerPlayerGameMode}. */
    private static InteractionResult use(FakePlayer player, ServerLevel level, BlockPos pos) {
        BlockHitResult hit = new BlockHitResult(Vec3.atCenterOf(pos).add(0.0, 0.5, 0.0), Direction.UP, pos, false);
        return player.gameMode.useItemOn(player, level, player.getMainHandItem(), InteractionHand.MAIN_HAND, hit);
    }

    /**
     * Hands the antenna saved data before its {@code onLoad}: its own save with the given format
     * version, band and PCI, and {@code RadioTier} only when given (v3).
     */
    private static void loadSaved(ServerLevel level, AntennaBlockEntity antenna, int dataVersion, String bandId,
                                  int pci, Integer radioTier) {
        CompoundTag data = antenna.saveWithoutMetadata(level.registryAccess());
        data.putInt("DataVersion", dataVersion);
        data.putString("BandId", bandId);
        data.putInt("Pci", pci);
        data.remove(AntennaBlockEntity.RADIO_TIER_TAG);
        if (radioTier != null) {
            data.putInt(AntennaBlockEntity.RADIO_TIER_TAG, radioTier);
        }
        antenna.loadWithComponents(data, level.registryAccess());
    }
}
