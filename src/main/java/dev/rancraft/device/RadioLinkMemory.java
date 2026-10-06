package dev.rancraft.device;

import it.unimi.dsi.fastutil.longs.Long2LongMap;
import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongIterator;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.objects.ObjectIterator;

/**
 * What one Radio Link receiver last heard (Phase 3 slice 9, §3B.4), and its output rule. Pure: no
 * Minecraft types, so the rule is pinned headless ({@code RadioLinkMemoryTest}).
 *
 * <p><b>The rule (§3B.4).</b> The receiver outputs 15 if any transmitter on its address is powered and
 * the last update from it was delivered; otherwise it holds its previous output. So it remembers, per
 * transmitter, the state in the last message from it that got through, and outputs 15 while any of
 * those says "powered". A lost message changes nothing ({@link #hear} is simply not called): the state
 * goes stale, it never toggles. Only "powered" needs remembering, so the memory is the set of
 * transmitters (their {@code BlockPos.asLong()}) last heard powered.
 *
 * <p><b>Unverified entries.</b> A transmitter that leaves the address (broken, replaced, re-addressed)
 * tells every receiver in the network's address book, and they forget it ({@link #forget}). A receiver
 * that was not in the book at the time (unloaded, or loaded but not yet through its first turn) misses
 * that news, so what it remembers from a save is <em>unverified</em> ({@link #load}) until the receiver
 * has looked each transmitter up ({@link #verify}) or heard from it again. A transmitter heard powered
 * is plainly there, so {@link #hear} verifies it. In steady state nothing is unverified, and a turn
 * costs no lookup.
 *
 * <p><b>Silent transmitters time out</b> (row 16d, owner decision on a slice 9 follow-up). Each
 * remembered transmitter keeps the game tick of its last delivered message, and one not heard from for
 * {@code radioLinkTransmitterTimeoutTicks} (1200, 60 s; 0 turns it off) is forgotten on the receiver's
 * turn ({@link #expire}), as a real network drops a terminal after an inactivity timer. That covers a
 * transmitter whose chunk stays unloaded, and one that lost service. A lost message or two still changes
 * nothing: at POOR about half get through, so 60 in a row are lost about once in 10^18. An entry loaded
 * from a save starts its clock at the receiver's first turn after loading, so coming back to a base
 * does not time out what it heard before you left.
 */
public final class RadioLinkMemory {

    /** What a lookup of a remembered transmitter found. */
    public enum Presence {
        /** A transmitter on this address is there. */
        PRESENT,
        /** Its chunk is loaded and no transmitter on this address is there: forget it. */
        GONE,
        /** Its chunk is not loaded: cannot tell yet; keep it, and look again next turn. */
        UNKNOWN
    }

    /** How the receiver looks a remembered transmitter up. */
    @FunctionalInterface
    public interface Lookup {
        Presence look(long txKey);
    }

    /** Last-heard tick of an entry loaded from a save: its clock starts at the next {@link #expire}. */
    static final long NOT_TIMED = Long.MIN_VALUE;

    /** What {@link #knownOn} returns for a transmitter it does not hold; no game tick is this large. */
    private static final long ABSENT = Long.MAX_VALUE;

    /** Transmitters whose last delivered message said "powered", to the game tick it was delivered. */
    private final Long2LongOpenHashMap knownOn = new Long2LongOpenHashMap();
    /** The subset of {@link #knownOn} not confirmed since it was loaded from a save. */
    private final LongOpenHashSet unverified = new LongOpenHashSet();

    public RadioLinkMemory() {
        knownOn.defaultReturnValue(ABSENT);
    }

    /**
     * A delivered message: {@code txKey} says it is {@code powered}. Either way the transmitter is
     * plainly there, so it is verified, and its timeout clock restarts at {@code tick}. One hash
     * operation for a repeated "powered" (the hot path: every delivery in steady state).
     *
     * @return whether the set of transmitters last heard powered changed (so the output may have).
     */
    public boolean hear(long txKey, boolean powered, long tick) {
        if (powered) {
            if (knownOn.put(txKey, tick) == ABSENT) {
                return true;
            }
            if (!unverified.isEmpty()) {
                unverified.remove(txKey);
            }
            return false;
        }
        return forget(txKey);
    }

    /** Whether the receiver outputs 15: some transmitter was last heard powered. */
    public boolean output() {
        return !knownOn.isEmpty();
    }

    /** How many transmitters were last heard powered. */
    public int knownOnCount() {
        return knownOn.size();
    }

    /** Whether {@code txKey} was last heard powered. */
    public boolean knowsOn(long txKey) {
        return knownOn.containsKey(txKey);
    }

    /**
     * Forgets every transmitter not heard from for {@code timeoutTicks} or more, and starts the clock of
     * every entry loaded from a save at {@code now}. 0 or less forgets nothing (the clocks still start).
     *
     * @return whether the set of transmitters last heard powered changed.
     */
    public boolean expire(long now, int timeoutTicks) {
        if (knownOn.isEmpty()) {
            return false;
        }
        LongArrayList silent = null;
        for (ObjectIterator<Long2LongMap.Entry> it = knownOn.long2LongEntrySet().fastIterator(); it.hasNext(); ) {
            Long2LongMap.Entry entry = it.next();
            long heard = entry.getLongValue();
            if (heard == NOT_TIMED) {
                entry.setValue(now);
            } else if (timeoutTicks > 0 && now - heard >= timeoutTicks) {
                if (silent == null) {
                    silent = new LongArrayList(2);
                }
                silent.add(entry.getLongKey());
            }
        }
        if (silent == null) {
            return false;
        }
        for (int i = 0; i < silent.size(); i++) {
            forget(silent.getLong(i));
        }
        return true;
    }

    /** How many remembered transmitters still need a lookup ({@link #verify}). */
    public int unverifiedCount() {
        return unverified.size();
    }

    /**
     * Looks up every unverified transmitter: forgets the {@link Presence#GONE GONE} ones, confirms the
     * {@link Presence#PRESENT PRESENT} ones, and keeps the {@link Presence#UNKNOWN UNKNOWN} ones
     * unverified for next time. Does nothing, and calls nothing, when all are verified.
     *
     * @return whether the set of transmitters last heard powered changed.
     */
    public boolean verify(Lookup lookup) {
        if (unverified.isEmpty()) {
            return false;
        }
        LongArrayList settled = null;
        boolean changed = false;
        for (LongIterator it = unverified.iterator(); it.hasNext(); ) {
            long txKey = it.nextLong();
            Presence presence = lookup.look(txKey);
            if (presence == Presence.UNKNOWN) {
                continue;
            }
            if (settled == null) {
                settled = new LongArrayList(2);
            }
            settled.add(txKey);
            if (presence == Presence.GONE) {
                knownOn.remove(txKey);
                changed = true;
            }
        }
        if (settled != null) {
            for (int i = 0; i < settled.size(); i++) {
                unverified.remove(settled.getLong(i));
            }
        }
        return changed;
    }

    /** Forgets one transmitter (it was broken or moved to another address). @return whether it changed. */
    public boolean forget(long txKey) {
        if (knownOn.remove(txKey) != ABSENT) {
            unverified.remove(txKey);
            return true;
        }
        return false;
    }

    /** Forgets everything (the receiver's own address changed). @return whether it changed. */
    public boolean clear() {
        boolean had = !knownOn.isEmpty();
        knownOn.clear();
        unverified.clear();
        return had;
    }

    /**
     * The transmitters last heard powered, for saving. Whether they were verified, and when they were
     * last heard, are not saved.
     */
    public long[] toArray() {
        return knownOn.keySet().toLongArray();
    }

    /**
     * Replaces the memory with saved data; every transmitter in it is unverified, and its timeout clock
     * starts at the next {@link #expire} (the receiver's first turn).
     */
    public void load(long[] saved) {
        knownOn.clear();
        unverified.clear();
        for (long txKey : saved) {
            knownOn.put(txKey, NOT_TIMED);
            unverified.add(txKey);
        }
    }
}
