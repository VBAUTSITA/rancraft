package dev.rancraft.device;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.rancraft.rf.BlerModel;
import dev.rancraft.rf.SplitMix64;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Phase 3 slice 9 (§3B.4, 3B tests): one status message through the network. Both ends must be served,
 * each end's BLER applies, draws are deterministic (identical across two runs with the same seed,
 * different across ticks), and the address book drops receivers that left.
 */
class RadioLinkNetworkTest {

    private static final BlerModel BLER = BlerModel.DEFAULT;
    private static final long TX = 0x1234_5678L;
    private static final double CLEAN_DB = 40.0;

    /** A receiver that records what got through. */
    static class FakeReceiver implements RadioLinkNetwork.Receiver {

        final long key;
        int address;
        boolean attached = true;
        boolean served = true;
        double sinrDb = CLEAN_DB;
        final List<Boolean> heard = new ArrayList<>();
        final List<Long> heardAt = new ArrayList<>();
        final List<Long> forgotten = new ArrayList<>();

        FakeReceiver(long key, int address) {
            this.key = key;
            this.address = address;
        }

        @Override
        public boolean attached() {
            return attached;
        }

        @Override
        public int address() {
            return address;
        }

        @Override
        public boolean served() {
            return served;
        }

        @Override
        public double success() {
            return BLER.success(sinrDb);
        }

        @Override
        public void deliver(long txKey, boolean powered, long gameTime) {
            heard.add(powered);
            heardAt.add(gameTime);
        }

        @Override
        public void forget(long txKey) {
            forgotten.add(txKey);
        }
    }

    private static List<FakeReceiver> receivers(RadioLinkNetwork network, int count, int address, double sinrDb) {
        List<FakeReceiver> list = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            FakeReceiver receiver = new FakeReceiver(10_000L + 37L * i, address);
            receiver.sinrDb = sinrDb;
            network.attach(receiver.key, receiver);
            list.add(receiver);
        }
        return list;
    }

    @Test
    @DisplayName("clean links at both ends: every receiver on the address hears the state, others nothing")
    void cleanLinksDeliver() {
        RadioLinkNetwork network = new RadioLinkNetwork();
        List<FakeReceiver> onAddress = receivers(network, 3, 5, CLEAN_DB);
        FakeReceiver elsewhere = new FakeReceiver(99L, 6);
        network.attach(elsewhere.key, elsewhere);

        assertEquals(3, network.send(TX, 5, true, true, BLER.success(CLEAN_DB), 100L));
        for (FakeReceiver receiver : onAddress) {
            assertEquals(List.of(true), receiver.heard);
            assertEquals(List.of(100L), receiver.heardAt);
        }
        assertTrue(elsewhere.heard.isEmpty(), "another address hears nothing");
        network.send(TX, 5, false, true, BLER.success(CLEAN_DB), 120L);
        assertEquals(List.of(true, false), onAddress.get(0).heard);
        RadioLinkNetwork.Stats stats = network.stats();
        assertEquals(2, stats.messages());
        assertEquals(6, stats.attempts());
        assertEquals(6, stats.delivered());
    }

    @Test
    @DisplayName("both ends must be served: an unserved sender sends nothing, an unserved receiver hears nothing")
    void bothEndsServed() {
        RadioLinkNetwork network = new RadioLinkNetwork();
        FakeReceiver receiver = receivers(network, 1, 0, CLEAN_DB).get(0);

        assertEquals(0, network.send(TX, 0, true, false, BLER.success(CLEAN_DB), 1L));
        assertTrue(receiver.heard.isEmpty(), "sender without service: nothing leaves it");
        assertEquals(1, network.stats().unsent());
        assertEquals(0, network.stats().attempts());

        receiver.served = false;
        assertEquals(0, network.send(TX, 0, true, true, BLER.success(CLEAN_DB), 2L));
        assertTrue(receiver.heard.isEmpty(), "receiver without service: lost");
        assertEquals(1, network.stats().lostUnserved());

        receiver.served = true;
        assertEquals(1, network.send(TX, 0, true, true, BLER.success(CLEAN_DB), 3L));
    }

    @Test
    @DisplayName("each end's BLER applies: the delivered fraction matches (1 - BLER(tx)) x (1 - BLER(rx))")
    void deliveredFractionMatchesTheModel() {
        double txSinr = 1.0;
        double rxSinr = 2.0;
        double expected = BLER.deliveryProbability(txSinr, rxSinr);
        RadioLinkNetwork network = new RadioLinkNetwork();
        List<FakeReceiver> list = receivers(network, 20, 3, rxSinr);
        int messages = 1_000;
        for (int tick = 0; tick < messages; tick++) {
            network.send(TX, 3, tick % 2 == 0, true, BLER.success(txSinr), 5_000L + 20L * tick);
        }
        RadioLinkNetwork.Stats stats = network.stats();
        assertEquals(20_000, stats.attempts());
        double fraction = stats.delivered() / (double) stats.attempts();
        // 20,000 Bernoulli draws at p = 0.69: sd 0.0033.
        assertEquals(expected, fraction, 0.015, "delivered " + fraction + ", model " + expected);
        assertEquals(stats.attempts(), stats.delivered() + stats.lostToErrors() + stats.lostUnserved());
        int total = 0;
        for (FakeReceiver receiver : list) {
            total += receiver.heard.size();
        }
        assertEquals(stats.delivered(), total);
    }

    @Test
    @DisplayName("draws are identical across two runs with the same seed and differ across ticks")
    void deterministicDraws() {
        List<Integer> first = deliveredSet(777L);
        List<Integer> again = deliveredSet(777L);
        assertEquals(first, again, "same world, same game time: the same messages get through");
        assertNotEquals(first, deliveredSet(778L), "the next tick draws afresh");
        // At 0 dB each end one message in four gets through: a quarter of 64 receivers, give or take.
        assertTrue(first.size() > 4 && first.size() < 32, "delivered to " + first.size() + " of 64");
    }

    /** Which of 64 receivers at 0 dB hear one 0 dB transmitter's message at {@code gameTime}. */
    private static List<Integer> deliveredSet(long gameTime) {
        RadioLinkNetwork network = new RadioLinkNetwork();
        List<FakeReceiver> list = receivers(network, 64, 9, 0.0);
        network.send(TX, 9, true, true, BLER.success(0.0), gameTime);
        List<Integer> got = new ArrayList<>();
        for (int i = 0; i < list.size(); i++) {
            if (!list.get(i).heard.isEmpty()) {
                got.add(i);
            }
        }
        return got;
    }

    @Test
    @DisplayName("the draws are SplitMix64(txPos ^ gameTime), one per receiver in ascending key order")
    void drawOrder() {
        long gameTime = 4_242L;
        RadioLinkNetwork network = new RadioLinkNetwork();
        // Attached in descending order: the draws still go in ascending key order.
        FakeReceiver high = new FakeReceiver(500L, 1);
        FakeReceiver low = new FakeReceiver(-500L, 1);
        high.sinrDb = 0.0;
        low.sinrDb = 0.0;
        network.attach(high.key, high);
        network.attach(low.key, low);
        network.send(TX, 1, true, true, BLER.success(0.0), gameTime);

        SplitMix64 draws = new SplitMix64(TX ^ gameTime);
        double p = BLER.deliveryProbability(0.0, 0.0);
        assertEquals(draws.nextDouble() < p, !low.heard.isEmpty(), "the lower key takes the first draw");
        assertEquals(draws.nextDouble() < p, !high.heard.isEmpty(), "the higher key the second");
    }

    @Test
    @DisplayName("an unserved receiver still takes its draw, so it never shifts another's")
    void unservedTakesItsDraw() {
        for (long gameTime = 0; gameTime < 200; gameTime++) {
            RadioLinkNetwork both = new RadioLinkNetwork();
            List<FakeReceiver> a = receivers(both, 2, 2, 0.0);
            RadioLinkNetwork firstUnserved = new RadioLinkNetwork();
            List<FakeReceiver> b = receivers(firstUnserved, 2, 2, 0.0);
            b.get(0).served = false;
            both.send(TX, 2, true, true, BLER.success(0.0), gameTime);
            firstUnserved.send(TX, 2, true, true, BLER.success(0.0), gameTime);
            assertEquals(a.get(1).heard, b.get(1).heard, "tick " + gameTime);
        }
    }

    @Test
    @DisplayName("the address book: idempotent attach, detach by identity, receivers that left are dropped")
    void addressBook() {
        RadioLinkNetwork network = new RadioLinkNetwork();
        FakeReceiver receiver = new FakeReceiver(42L, 4);
        network.attach(42L, receiver);
        network.attach(42L, receiver);
        assertEquals(1, network.receiverCount(4));

        FakeReceiver successor = new FakeReceiver(42L, 4);
        network.attach(42L, successor);
        network.detach(42L, 4, receiver);
        assertEquals(1, network.receiverCount(4), "the old entity's late detach keeps its successor");
        network.send(TX, 4, true, true, BLER.success(CLEAN_DB), 1L);
        assertTrue(receiver.heard.isEmpty());
        assertEquals(List.of(true), successor.heard);

        successor.attached = false;
        network.send(TX, 4, true, true, BLER.success(CLEAN_DB), 2L);
        assertEquals(1, successor.heard.size(), "removed or unloaded: no more messages");
        assertEquals(0, network.receiverCount(4), "and dropped from the book");

        FakeReceiver moved = new FakeReceiver(43L, 4);
        network.attach(43L, moved);
        moved.address = 11;
        network.send(TX, 4, true, true, BLER.success(CLEAN_DB), 3L);
        assertTrue(moved.heard.isEmpty(), "re-addressed: the old address no longer reaches it");
        assertEquals(0, network.receiverCount(4));
        network.attach(43L, moved);
        assertEquals(1, network.send(TX, 11, true, true, BLER.success(CLEAN_DB), 4L));
    }

    @Test
    @DisplayName("a transmitter that leaves an address is forgotten by every attached receiver on it")
    void transmitterGone() {
        RadioLinkNetwork network = new RadioLinkNetwork();
        List<FakeReceiver> onAddress = receivers(network, 3, 7, CLEAN_DB);
        FakeReceiver elsewhere = new FakeReceiver(99L, 8);
        network.attach(elsewhere.key, elsewhere);
        onAddress.get(2).attached = false;
        network.transmitterGone(TX, 7);
        assertEquals(List.of(TX), onAddress.get(0).forgotten);
        assertEquals(List.of(TX), onAddress.get(1).forgotten);
        assertTrue(onAddress.get(2).forgotten.isEmpty(), "a removed receiver is not called");
        assertTrue(elsewhere.forgotten.isEmpty(), "another address is not told");
    }

    @Test
    @DisplayName("addresses wrap within 0-15")
    void addressesWrap() {
        assertEquals(0, RadioLinkNetwork.wrapAddress(16));
        assertEquals(15, RadioLinkNetwork.wrapAddress(-1));
        assertEquals(7, RadioLinkNetwork.wrapAddress(7));
        assertEquals(16, RadioLinkNetwork.ADDRESSES);
    }

    @Test
    @DisplayName("a delivery that changes the book mid-send does not break the send")
    void bookChangedDuringSend() {
        RadioLinkNetwork network = new RadioLinkNetwork();
        FakeReceiver later = new FakeReceiver(300L, 0);
        FakeReceiver first = new FakeReceiver(100L, 0) {
            @Override
            public void deliver(long txKey, boolean powered, long gameTime) {
                super.deliver(txKey, powered, gameTime);
                // Its redstone output broke the other receiver.
                later.attached = false;
                network.detach(later.key, 0, later);
            }
        };
        network.attach(first.key, first);
        network.attach(later.key, later);
        assertEquals(1, network.send(TX, 0, true, true, BLER.success(CLEAN_DB), 1L));
        assertTrue(later.heard.isEmpty());
        // A receiver attached during a send is not in that send's snapshot, but is in the next one.
        FakeReceiver joiner = new FakeReceiver(200L, 0);
        FakeReceiver trigger = new FakeReceiver(50L, 0) {
            @Override
            public void deliver(long txKey, boolean powered, long gameTime) {
                super.deliver(txKey, powered, gameTime);
                network.attach(joiner.key, joiner);
            }
        };
        network.attach(trigger.key, trigger);
        assertEquals(2, network.send(TX, 0, true, true, BLER.success(CLEAN_DB), 2L), "trigger and first");
        assertTrue(joiner.heard.isEmpty(), "joined mid-send: not in this send");
        assertEquals(3, network.send(TX, 0, true, true, BLER.success(CLEAN_DB), 3L), "trigger, joiner, first");
        assertEquals(List.of(true), joiner.heard);
    }

    @Test
    @DisplayName("the book's snapshot follows every change between sends")
    void snapshotFollowsTheBook() {
        RadioLinkNetwork network = new RadioLinkNetwork();
        FakeReceiver a = new FakeReceiver(10L, 5);
        FakeReceiver b = new FakeReceiver(20L, 5);
        network.attach(a.key, a);
        assertEquals(1, network.send(TX, 5, true, true, BLER.success(CLEAN_DB), 1L));
        network.attach(b.key, b);
        assertEquals(2, network.send(TX, 5, true, true, BLER.success(CLEAN_DB), 2L), "b attached after the first send");
        network.attach(b.key, b);
        assertEquals(2, network.send(TX, 5, true, true, BLER.success(CLEAN_DB), 3L), "attaching again changes nothing");
        network.detach(a.key, 5, a);
        assertEquals(1, network.send(TX, 5, true, true, BLER.success(CLEAN_DB), 4L), "a detached");
        assertEquals(List.of(true, true, true), a.heard);
        assertEquals(List.of(true, true, true), b.heard);
        network.transmitterGone(TX, 5);
        assertEquals(List.of(TX), b.forgotten);
        assertTrue(a.forgotten.isEmpty(), "a is no longer in the book");
    }
}
