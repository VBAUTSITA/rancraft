package dev.rancraft.rf;

/**
 * SplitMix64 (Steele, Lea and Flood, 2014; the constants of Vigna's reference {@code splitmix64.c}):
 * a small, fast, well-mixed 64-bit generator whose whole state is one {@code long}. Phase 3 slice 9,
 * §3B.4: the Radio Link's delivery draws.
 *
 * <p><b>Why not {@code Math.random()} or the level's {@code RandomSource}.</b> A delivery draw must be
 * reproducible: the same world at the same game time loses the same messages, so a bug report can be
 * replayed and a test can pin the outcome. §3B.4 fixes the seed: {@code pos.asLong() ^ gameTime}
 * ({@link #seed}). Two draws with equal seeds are equal; seeds one tick apart give unrelated streams,
 * because every output passes through the full 64-bit finaliser.
 *
 * <p>Not thread-safe (it does not need to be: one instance per message, on the server thread). Not
 * cryptographic.
 */
public final class SplitMix64 {

    private static final long GOLDEN_GAMMA = 0x9E3779B97F4A7C15L;
    private static final double DOUBLE_UNIT = 0x1.0p-53;

    private long state;

    public SplitMix64(long seed) {
        this.state = seed;
    }

    /**
     * The seed §3B.4 prescribes for a draw made at a block at a game time: {@code pos.asLong() ^ gameTime}.
     *
     * @param posKey   {@code BlockPos.asLong()} of the block the draw belongs to.
     * @param gameTime the level's game time of the draw.
     */
    public static long seed(long posKey, long gameTime) {
        return posKey ^ gameTime;
    }

    /** The next 64 uniformly distributed bits. */
    public long nextLong() {
        state += GOLDEN_GAMMA;
        long z = state;
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        return z ^ (z >>> 31);
    }

    /** The next uniform double in {@code [0, 1)}: the top 53 bits of {@link #nextLong()}. */
    public double nextDouble() {
        return (nextLong() >>> 11) * DOUBLE_UNIT;
    }
}
