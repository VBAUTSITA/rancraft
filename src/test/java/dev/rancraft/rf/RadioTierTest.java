package dev.rancraft.rf;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.OptionalInt;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Phase 3 slice 10 (§3C.1, 3C tests): which bands a radio tier may use, and the v2 to v3 migration.
 * The band table is the shipped JSON's tiers: band_700 and band_900 tier 1, band_1800 tier 2,
 * band_3500 tier 3.
 */
class RadioTierTest {

    private static final int MAST = 1;
    private static final int SECTOR = 2;

    private static final BandTable BANDS = BandTable.of(
            Band.DEFAULT_900,                                            // tier 1
            new Band("band_700", 700.0, 3.2, 0.7, -112.0, 1),
            new Band("band_1800", 1800.0, 3.6, 1.1, -108.0, 2),
            new Band("band_3500", 3500.0, 4.0, 1.8, -106.0, 3));

    @Test
    @DisplayName("3C test: a band_3500 request on a tier-2 sector is rejected; tier 2 bands and below are allowed")
    void tierTwoSectorRejectsBand3500() {
        assertFalse(RadioTier.allows(SECTOR, BANDS, "band_3500"));
        assertTrue(RadioTier.allows(SECTOR, BANDS, "band_1800"));
        assertTrue(RadioTier.allows(SECTOR, BANDS, "band_900"));
        assertTrue(RadioTier.allows(SECTOR, BANDS, "band_700"));
    }

    @Test
    @DisplayName("A Wideband Radio Unit (tier 3) opens band_3500 and keeps every lower band")
    void widebandOpensEverything() {
        for (String id : BANDS.all().keySet()) {
            assertTrue(RadioTier.allows(RadioTier.WIDEBAND, BANDS, id), id);
        }
    }

    @Test
    @DisplayName("A mast's tier-1 radio takes only the tier-1 bands")
    void mastTakesTierOneOnly() {
        assertTrue(RadioTier.allows(MAST, BANDS, "band_900"));
        assertTrue(RadioTier.allows(MAST, BANDS, "band_700"));
        assertFalse(RadioTier.allows(MAST, BANDS, "band_1800"));
        assertFalse(RadioTier.allows(MAST, BANDS, "band_3500"));
    }

    @Test
    @DisplayName("A band the table does not hold is never allowed (no fallback band)")
    void unknownBandRejected() {
        assertFalse(RadioTier.allows(RadioTier.WIDEBAND, BANDS, "band_2600"));
        assertFalse(RadioTier.allows(Integer.MAX_VALUE, BANDS, ""));
    }

    @Test
    @DisplayName("allows(tier, capacityTier) is capacityTier <= tier, at the boundary too")
    void boundary() {
        assertTrue(RadioTier.allows(2, 2));
        assertTrue(RadioTier.allows(2, 1));
        assertFalse(RadioTier.allows(2, 3));
        assertTrue(RadioTier.allows(3, 0), "a tier-0 band (none ships) is open to every radio");
    }

    @Test
    @DisplayName("3C test: the v2 to v3 migration grandfathers a sector on band_3500 to tier 3")
    void migrationGrandfathersBand3500() {
        assertEquals(3, RadioTier.migrated(SECTOR, BANDS, "band_3500"));
        // ... which then accepts band_3500 again: nothing that worked before stops working.
        assertTrue(RadioTier.allows(RadioTier.migrated(SECTOR, BANDS, "band_3500"), BANDS, "band_3500"));
    }

    @Test
    @DisplayName("Migration never lowers a block's own tier: max(blockDefault, tierOf(currentBand))")
    void migrationKeepsTheBlockDefault() {
        assertEquals(SECTOR, RadioTier.migrated(SECTOR, BANDS, "band_1800"));
        assertEquals(SECTOR, RadioTier.migrated(SECTOR, BANDS, "band_900"));
        assertEquals(SECTOR, RadioTier.migrated(SECTOR, BANDS, "band_700"));
        assertEquals(MAST, RadioTier.migrated(MAST, BANDS, "band_900"));
        // A mast that NBT editing put on band_1800 keeps it.
        assertEquals(2, RadioTier.migrated(MAST, BANDS, "band_1800"));
    }

    @Test
    @DisplayName("Migration of a band the table no longer holds grants nothing beyond the block default")
    void migrationOfAnUnknownBand() {
        assertEquals(SECTOR, RadioTier.migrated(SECTOR, BANDS, "band_2600"));
        assertEquals(SECTOR, RadioTier.migrated(SECTOR, OptionalInt.empty()));
        assertEquals(4, RadioTier.migrated(SECTOR, OptionalInt.of(4)), "a datapack tier-4 band is grandfathered too");
    }

    @Test
    @DisplayName("A saved tier below the block default is raised to it; above is kept")
    void loadedTier() {
        assertEquals(SECTOR, RadioTier.loaded(SECTOR, 0));
        assertEquals(SECTOR, RadioTier.loaded(SECTOR, -7));
        assertEquals(SECTOR, RadioTier.loaded(SECTOR, 2));
        assertEquals(3, RadioTier.loaded(SECTOR, 3));
        assertEquals(4, RadioTier.loaded(SECTOR, 4));
        assertEquals(MAST, RadioTier.loaded(MAST, 1));
    }

    @Test
    @DisplayName("The screen's reason: a Wideband Radio Unit opens tiers up to 3, nothing opens tier 4")
    void unlockedByWideband() {
        assertTrue(RadioTier.unlockedByWideband(SECTOR, 3));
        assertFalse(RadioTier.unlockedByWideband(SECTOR, 2), "not locked at all");
        assertFalse(RadioTier.unlockedByWideband(SECTOR, 4), "no unit reaches tier 4");
        assertFalse(RadioTier.unlockedByWideband(RadioTier.WIDEBAND, 3), "already open");
    }
}
