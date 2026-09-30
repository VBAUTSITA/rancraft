package dev.rancraft.device;

import dev.rancraft.RanCraft;
import dev.rancraft.rf.BlerModel;
import dev.rancraft.rf.SplitMix64;
import it.unimi.dsi.fastutil.longs.Long2ObjectAVLTreeMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;

/**
 * The Radio Link's message service in one dimension (Phase 3 slice 9, §3B.4): which receivers listen
 * on which address, and the delivery of one transmitter's status message to them.
 *
 * <p><b>Two radios talk through the network, not to each other.</b> A message is sent up by the
 * transmitter to its serving cell and down by the network to each receiver's serving cell, so both
 * ends must be served, and each end's block error rate applies ({@link BlerModel#deliveryProbability}).
 * The distance between the two radios plays no part; a receiver 5,000 blocks away in the same
 * dimension hears as well as one next door, if both have service. A different dimension is a
 * different network.
 *
 * <p><b>Deterministic draws (§3B.4).</b> One message is one {@link SplitMix64} stream seeded with
 * {@code txPos.asLong() ^ gameTime} ({@link SplitMix64#seed}), drawn once per attached receiver on the
 * address, in ascending order of the receivers' {@code BlockPos.asLong()}. The same world at the same
 * game time loses the same messages; the next tick's message draws afresh. Never {@code Math.random()}.
 *
 * <p><b>The address book.</b> A receiver attaches itself on every evaluation it gets (its turn in
 * {@code world.FixedReceiverTicker}) and detaches when its block entity is removed (broken, replaced or
 * unloaded) or changes address. A detached or re-addressed receiver still in the book is dropped the
 * next time a message is sent to its old address ({@link Receiver#attached()}). Server memory only.
 *
 * <p>Everything but {@link #of} and {@link #clearAll} is free of Minecraft types, so the delivery rules
 * are unit-tested headless ({@code RadioLinkNetworkTest}). Server thread.
 */
@EventBusSubscriber(modid = RanCraft.MOD_ID)
public final class RadioLinkNetwork {

    /** Addresses are {@code 0 .. ADDRESSES - 1}. */
    public static final int ADDRESSES = 16;

    private static final Map<ResourceKey<Level>, RadioLinkNetwork> BY_LEVEL = new ConcurrentHashMap<>();

    /** A receiver as the network sees it. */
    public interface Receiver {

        /** Whether it is still in the world: false once its block entity was removed or unloaded. */
        boolean attached();

        /** The address it listens on now. */
        int address();

        /** Whether its last evaluation met its requirement: it has a serving cell good enough to decode. */
        boolean served();

        /**
         * {@code 1 - BLER} of its last evaluation's SINR ({@link BlerModel#success}), under the BLER
         * parameters of that evaluation's tick: the chance its end of a message decodes. Fixed at its
         * turn, so a message costs no power of ten per receiver.
         */
        double success();

        /** A message got through. Called on the server thread, inside the transmitter's turn. */
        void deliver(long txKey, boolean powered, long gameTime);

        /** The transmitter {@code txKey} left this address (broken, or re-addressed): forget it. */
        void forget(long txKey);
    }

    /**
     * Counters since this network was created (or {@link #resetStats}).
     *
     * @param messages    status messages transmitters tried to send.
     * @param unsent      of those, sent by a transmitter with no service: lost before the network.
     * @param attempts    deliveries tried: one per message per attached receiver on its address.
     * @param delivered   deliveries that got through.
     * @param lostUnserved deliveries lost because the receiver had no service.
     * @param lostToErrors deliveries lost to a block error at either end (both ends served).
     */
    public record Stats(long messages, long unsent, long attempts, long delivered, long lostUnserved,
                        long lostToErrors) {
    }

    @SuppressWarnings("unchecked")
    private final Long2ObjectAVLTreeMap<Receiver>[] receivers = new Long2ObjectAVLTreeMap[ADDRESSES];
    /**
     * Per address, the book as two arrays in key order, built on the first send after the book changed
     * ({@link #snapshot}) and kept while it does not: in steady state the book never changes, and a send
     * then walks two flat arrays instead of the tree. A send holds its own reference, so a book changed
     * by its deliveries leaves the loop's arrays intact.
     */
    private final long[][] snapshotKeys = new long[ADDRESSES][];
    private final Receiver[][] snapshotReceivers = new Receiver[ADDRESSES][];

    private long messages;
    private long unsent;
    private long attempts;
    private long delivered;
    private long lostUnserved;
    private long lostToErrors;

    RadioLinkNetwork() {
        for (int i = 0; i < ADDRESSES; i++) {
            receivers[i] = new Long2ObjectAVLTreeMap<>();
        }
    }

    /** The network of one dimension, created on first use. */
    public static RadioLinkNetwork of(ServerLevel level) {
        return BY_LEVEL.computeIfAbsent(level.dimension(), key -> new RadioLinkNetwork());
    }

    /** Forgets every dimension's network, so a second world load in the same JVM starts clean. */
    public static void clearAll() {
        BY_LEVEL.clear();
    }

    @SubscribeEvent
    public static void onServerStopped(ServerStoppedEvent event) {
        clearAll();
    }

    /** {@code address} wrapped into {@code 0 .. ADDRESSES - 1}. */
    public static int wrapAddress(int address) {
        return Math.floorMod(address, ADDRESSES);
    }

    // ---- the address book -------------------------------------------------------------------------

    /**
     * Puts a receiver in the book at its current address. Idempotent. A different receiver at the same
     * position (a replaced block entity) takes the place.
     */
    public void attach(long key, Receiver receiver) {
        int slot = wrapAddress(receiver.address());
        Long2ObjectAVLTreeMap<Receiver> book = receivers[slot];
        if (book.get(key) != receiver) {
            book.put(key, receiver);
            invalidate(slot);
        }
    }

    /** Takes a receiver out of the book at {@code address}, if it is the one there (identity). */
    public void detach(long key, int address, Receiver receiver) {
        int slot = wrapAddress(address);
        Long2ObjectAVLTreeMap<Receiver> book = receivers[slot];
        if (book.get(key) == receiver) {
            book.remove(key);
            invalidate(slot);
        }
    }

    private void invalidate(int slot) {
        snapshotKeys[slot] = null;
        snapshotReceivers[slot] = null;
    }

    /** The book at {@code slot} as arrays in ascending key order, rebuilt only after it changed. */
    private Receiver[] snapshot(int slot) {
        Receiver[] targets = snapshotReceivers[slot];
        if (targets == null) {
            Long2ObjectAVLTreeMap<Receiver> book = receivers[slot];
            snapshotKeys[slot] = book.keySet().toLongArray();
            targets = book.values().toArray(new Receiver[0]);
            snapshotReceivers[slot] = targets;
        }
        return targets;
    }

    /**
     * A transmitter left {@code address}: its block was broken or replaced, or it moved to another
     * address. Every receiver on the address forgets it at once, as a network that sees a terminal
     * detach would. (Unloading is not leaving: see {@code block.RadioLinkReceiverBlockEntity}.)
     */
    public void transmitterGone(long txKey, int address) {
        int slot = wrapAddress(address);
        if (receivers[slot].isEmpty()) {
            return;
        }
        for (Receiver receiver : snapshot(slot)) {
            if (receiver.attached()) {
                receiver.forget(txKey);
            }
        }
    }

    /** Receivers in the book at {@code address}, stale ones included until the next message there. */
    public int receiverCount(int address) {
        return receivers[wrapAddress(address)].size();
    }

    // ---- one message ------------------------------------------------------------------------------

    /**
     * One status message from a transmitter to every receiver on its address.
     *
     * <ol>
     *   <li>A transmitter with no service sends nothing: every receiver keeps what it last heard.</li>
     *   <li>Otherwise one stream is seeded with {@code txKey ^ gameTime}, and each receiver still
     *       attached and still on this address, in ascending key order, takes one draw from it (whether
     *       or not it is served, so one receiver's service never shifts another's draw).</li>
     *   <li>A receiver with no service loses the message. A served one gets it when the draw is below
     *       {@code (1 - BLER(txSinr)) x (1 - BLER(rxSinr))} ({@link BlerModel#deliveryProbability}),
     *       each factor as its end fixed it at its last turn.</li>
     * </ol>
     *
     * A lost message changes nothing at the receiver: it keeps the last state it heard (a stale state,
     * never a toggled one). Receivers no longer attached or re-addressed are dropped from the book here.
     *
     * @param txKey     the transmitter's {@code BlockPos.asLong()}.
     * @param powered   the transmitter's redstone input now: what the message says.
     * @param txServed  whether the transmitter's last evaluation met its requirement.
     * @param txSuccess {@code 1 - BLER} of the transmitter's SINR at its last evaluation
     *                  ({@link BlerModel#success}): the chance its uplink block decodes.
     * @param gameTime  the level's game time of the send.
     * @return how many receivers got the message.
     */
    public int send(long txKey, int address, boolean powered, boolean txServed, double txSuccess, long gameTime) {
        messages++;
        if (!txServed) {
            unsent++;
            return 0;
        }
        int slot = wrapAddress(address);
        if (receivers[slot].isEmpty()) {
            return 0;
        }
        // A snapshot: a delivery changes a receiver's block, and the neighbour updates that follow may
        // remove or re-address other receivers before this loop is done. The loop keeps these arrays
        // even if the book changes meanwhile.
        Receiver[] targets = snapshot(slot);
        long[] keys = snapshotKeys[slot];
        SplitMix64 draws = new SplitMix64(SplitMix64.seed(txKey, gameTime));
        int got = 0;
        for (int i = 0; i < keys.length; i++) {
            Receiver receiver = targets[i];
            if (!receiver.attached() || wrapAddress(receiver.address()) != slot) {
                detach(keys[i], slot, receiver);
                continue;
            }
            double draw = draws.nextDouble();
            attempts++;
            if (!receiver.served()) {
                lostUnserved++;
                continue;
            }
            // (1 - BLER(tx)) x (1 - BLER(rx)): BlerModel.deliveryProbability, each end's factor fixed at its turn.
            if (BlerModel.delivered(txSuccess * receiver.success(), draw)) {
                delivered++;
                got++;
                receiver.deliver(txKey, powered, gameTime);
            } else {
                lostToErrors++;
            }
        }
        return got;
    }

    // ---- stats ------------------------------------------------------------------------------------

    public Stats stats() {
        return new Stats(messages, unsent, attempts, delivered, lostUnserved, lostToErrors);
    }

    public void resetStats() {
        messages = 0;
        unsent = 0;
        attempts = 0;
        delivered = 0;
        lostUnserved = 0;
        lostToErrors = 0;
    }
}
