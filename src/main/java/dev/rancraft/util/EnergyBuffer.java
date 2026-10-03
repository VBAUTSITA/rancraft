package dev.rancraft.util;

/**
 * A cell's energy buffer and its on-air latch. Phase 3 slice 15 (§3C.5). Pure; the game side wraps
 * it as NeoForge's {@code IEnergyStorage} ({@code block.SiteEnergyStorage}) and drives it once a
 * tick ({@code world.SitePower}).
 *
 * <p><b>The rule (§3C.5):</b> out of energy, off the air; back on the air only when the buffer is
 * above a restart fraction (10 %) of its capacity, so a cell on a feeble supply does not flap on and
 * off every tick. The latch ({@link #on()}) is the hysteresis: once off, the buffer must refill past
 * the threshold before the cell may transmit again; once on, it stays on until it cannot pay a tick.
 *
 * <p>Energy is whole FE (as NeoForge's {@code IEnergyStorage} counts it); a cell's draw is fractional
 * (10.67 FE/t), so the fraction owed is carried from tick to tick ({@link #draw}) and over many ticks
 * the buffer pays exactly the rate. The fraction is not saved: a reload forgives less than 1 FE.
 *
 * <p>Not RF (the model that sets the rate is {@code rf.PowerModel}), so it lives in {@code util}.
 * The capacity is a parameter of each call, not a field: it is a server setting that may change while
 * a world runs, and a buffer above a lowered capacity simply takes nothing until it drains below it.
 */
public final class EnergyBuffer {

    private int stored;
    /** FE owed but not yet taken, in [0, 1). */
    private double owed;
    private boolean on;

    /** Energy held, in FE. */
    public int stored() {
        return stored;
    }

    /** Whether the latch allows transmitting: true from a restart until the buffer cannot pay a tick. */
    public boolean on() {
        return on;
    }

    /** The saved state. A negative amount reads as empty. */
    public void load(int stored, boolean on) {
        this.stored = Math.max(0, stored);
        this.on = on;
        this.owed = 0.0;
    }

    /**
     * Takes up to {@code amount} FE, never past {@code capacity}. Returns what was (or, simulating,
     * would be) taken. Nothing for a non-positive amount.
     */
    public int receive(int amount, int capacity, boolean simulate) {
        if (amount <= 0) {
            return 0;
        }
        int room = Math.max(0, capacity - stored);
        int taken = Math.min(amount, room);
        if (!simulate) {
            stored += taken;
        }
        return taken;
    }

    /**
     * Gives up up to {@code amount} FE. Returns what was (or would be) given. Used to move a mast
     * column's energy to its new base; a cell's own storage never lets a neighbour extract.
     */
    public int extract(int amount, boolean simulate) {
        if (amount <= 0) {
            return 0;
        }
        int given = Math.min(amount, stored);
        if (!simulate) {
            stored -= given;
        }
        return given;
    }

    /**
     * One tick on the air at {@code fePerTick}. The whole FE owed so far is taken; when the buffer
     * cannot pay it, the buffer empties, the latch goes off and this returns false (out of energy).
     * A non-positive rate costs nothing; a rate that is not finite cannot be paid.
     *
     * @return whether the cell may stay on the air.
     */
    public boolean draw(double fePerTick) {
        if (!on) {
            return false;
        }
        if (Double.isNaN(fePerTick) || Double.isInfinite(fePerTick)) {
            goOut();
            return false;
        }
        if (fePerTick <= 0.0) {
            return true;
        }
        double due = owed + fePerTick;
        double whole = Math.floor(due);
        if (whole > stored) {
            goOut();
            return false;
        }
        stored -= (int) whole;
        owed = due - whole;
        return true;
    }

    private void goOut() {
        stored = 0;
        owed = 0.0;
        on = false;
    }

    /**
     * An off latch comes back on when the buffer is above {@code restartFraction} of
     * {@code capacity} ({@link #aboveRestart}). Returns true only when it switched on just now.
     */
    public boolean restart(int capacity, double restartFraction) {
        if (on || !aboveRestart(stored, capacity, restartFraction)) {
            return false;
        }
        on = true;
        owed = 0.0;
        return true;
    }

    /** {@code stored > restartFraction x capacity}: strictly above, so an empty buffer never restarts. */
    public static boolean aboveRestart(int stored, int capacity, double restartFraction) {
        return stored > 0 && stored > restartFraction * capacity;
    }

    /** Clears everything: empty, off. */
    public void clear() {
        load(0, false);
    }
}
