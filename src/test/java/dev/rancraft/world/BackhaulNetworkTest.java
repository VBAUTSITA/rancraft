package dev.rancraft.world;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import it.unimi.dsi.fastutil.longs.LongList;
import java.util.Set;
import java.util.stream.Collectors;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The backhaul network's bookkeeping (Phase 3 slice 12): pairings stay mutual, a loaded dish takes the
 * network's record of its partner, the saved topology round-trips, and the lens's distance rule.
 * Headless: no level is touched (the level-facing halves are covered by {@code BackhaulGameTests}).
 */
class BackhaulNetworkTest {

    private static final BlockPos A = new BlockPos(0, 70, 0);
    private static final BlockPos B = new BlockPos(100, 72, 0);
    private static final BlockPos C = new BlockPos(-50, 64, 30_000);

    private static Set<BlockPos> positions(LongList keys) {
        return keys.longStream().mapToObj(BlockPos::of).collect(Collectors.toSet());
    }

    @Test
    @DisplayName("pairing is mutual; re-pairing one end unpairs its old partner; unpairing clears both")
    void pairingStaysMutual() {
        BackhaulNetwork network = new BackhaulNetwork();
        assertEquals(Set.of(A, B), positions(network.pairEntries(A.asLong(), B.asLong())));
        assertEquals(B, network.partnerOf(A));
        assertEquals(A, network.partnerOf(B));
        assertTrue(network.isDish(A) && network.isDish(B), "pairing makes both dishes known");
        assertTrue(network.pairEntries(A.asLong(), B.asLong()).isEmpty(), "the same pair again changes nothing");
        assertTrue(network.pairEntries(B.asLong(), A.asLong()).isEmpty(), "in either order");

        // A to C: B loses its partner (it pointed at A), and every changed dish is reported.
        assertEquals(Set.of(A, B, C), positions(network.pairEntries(A.asLong(), C.asLong())));
        assertEquals(C, network.partnerOf(A));
        assertEquals(A, network.partnerOf(C));
        assertNull(network.partnerOf(B));

        assertEquals(Set.of(C, A), positions(network.unpairEntries(C.asLong())));
        assertNull(network.partnerOf(A));
        assertNull(network.partnerOf(C));
        assertTrue(network.unpairEntries(C.asLong()).isEmpty(), "not paired: nothing to do");
        assertThrows(IllegalArgumentException.class, () -> network.pairEntries(A.asLong(), A.asLong()));
    }

    @Test
    @DisplayName("a loaded dish takes the network's partner; an unknown one brings its own")
    void dishLoadedAdoptsTheNetworkRecord() {
        BackhaulNetwork network = new BackhaulNetwork();
        // Unknown to the network (its file was lost): the entity's saved partner is adopted.
        assertEquals(B, network.dishLoaded(A, B));
        assertEquals(B, network.partnerOf(A));
        // B's entity saved an older partner (C); the network paired B with A since: the network wins.
        network.pairEntries(A.asLong(), B.asLong());
        assertEquals(A, network.dishLoaded(B, C));
        // Unpaired while unloaded: the entity's stale partner is dropped.
        network.unpairEntries(A.asLong());
        assertNull(network.dishLoaded(A, B));
        // A dish naming itself is not paired.
        assertNull(new BackhaulNetwork().partnerOf(C));
        BackhaulNetwork other = new BackhaulNetwork();
        other.dishLoaded(C, C);
        assertNull(other.partnerOf(C));
        assertTrue(other.isDish(C));
    }

    @Test
    @DisplayName("the saved topology round-trips: cores, dishes, pairings, cells; a stray pairing is dropped")
    void saveRoundTrips() {
        BackhaulNetwork network = new BackhaulNetwork();
        assertFalse(network.isDirty());
        network.coreLoaded(C);
        assertTrue(network.isDirty(), "any change marks the save dirty");
        network.pairEntries(A.asLong(), B.asLong());
        network.dishLoaded(C.above(), null);
        network.noteCell(C.east().asLong(), true);
        network.noteCell(A.west().asLong(), true);
        network.noteCell(A.west().asLong(), false);

        CompoundTag tag = network.save(new CompoundTag(), null);
        assertEquals(BackhaulNetwork.DATA_VERSION, tag.getInt("DataVersion"));
        BackhaulNetwork loaded = BackhaulNetwork.load(tag, null);
        assertTrue(loaded.isCore(C));
        assertEquals(1, loaded.coreCount());
        assertEquals(3, loaded.dishCount());
        assertEquals(B, loaded.partnerOf(A));
        assertEquals(A, loaded.partnerOf(B));
        assertNull(loaded.partnerOf(C.above()));
        assertTrue(loaded.isCell(C.east().asLong()));
        assertFalse(loaded.isCell(A.west().asLong()), "a cell that left is not saved");
        assertEquals(network.save(new CompoundTag(), null), loaded.save(new CompoundTag(), null));

        // A pairing that names a dish the file does not list is dropped.
        CompoundTag stray = new CompoundTag();
        stray.putLongArray("Dishes", new long[] {A.asLong()});
        stray.putLongArray("Partners", new long[] {A.asLong(), B.asLong(), C.asLong(), A.asLong()});
        BackhaulNetwork strayLoaded = BackhaulNetwork.load(stray, null);
        assertEquals(B, strayLoaded.partnerOf(A));
        assertNull(strayLoaded.partnerOf(C));
        assertFalse(strayLoaded.isDish(C));
    }

    @Test
    @DisplayName("cores and cells come and go; a repeat changes nothing")
    void coresAndCells() {
        BackhaulNetwork network = new BackhaulNetwork();
        network.coreLoaded(A);
        network.coreLoaded(A);
        assertEquals(1, network.coreCount());
        network.coreRemoved(A);
        assertFalse(network.isCore(A));
        network.noteCell(B.asLong(), true);
        assertEquals(1, network.cells().size());
        assertNull(network.cells().get(0).state(), "not judged before the first solve");
        network.removeCell(B.asLong());
        assertTrue(network.cells().isEmpty());
        assertFalse(network.solved());
        assertTrue(network.hops().isEmpty());
    }

    @Test
    @DisplayName("distance to a hop: perpendicular in the middle, to the nearer end beyond it")
    void distanceToSegment() {
        assertEquals(25.0, BackhaulNetwork.distanceSqToSegment(50, 5, 0, 0, 0, 0, 100, 0, 0), 1e-12);
        assertEquals(100.0 + 9.0, BackhaulNetwork.distanceSqToSegment(-10, 3, 0, 0, 0, 0, 100, 0, 0), 1e-12);
        assertEquals(16.0, BackhaulNetwork.distanceSqToSegment(104, 0, 0, 0, 0, 0, 100, 0, 0), 1e-12);
        assertEquals(3.0, BackhaulNetwork.distanceSqToSegment(1, 1, 1, 0, 0, 0, 0, 0, 0), 1e-12, "a point hop");
        assertEquals(100.0 * 100.0 + 2.0 * 2.0 + 0.0, BackhaulNetwork.distanceSq(A.asLong(), B.asLong()), 1e-9);
        assertEquals(-Float.MAX_VALUE, BackhaulNetwork.finiteFloat(Double.NEGATIVE_INFINITY));
        assertEquals(-12.5f, BackhaulNetwork.finiteFloat(-12.5));
    }
}
