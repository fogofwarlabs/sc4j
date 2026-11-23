package org.fogofwarlabs.sc4j.engine.random;

import org.fogofwarlabs.sc4j.base.exception.Exceptions;
import org.fogofwarlabs.sc4j.engine.state.StateHash;
import org.jetbrains.annotations.NotNull;

/**
 * Generates random numbers with PCG32 (the XSH RR output of a {@code 64}-bit linear congruential state), the engine
 * default {@link DeterministicRandom}.
 * <p>
 * Each step advances the state as {@code state * 6364136223846793005 + increment} with {@code 64}-bit overflow and
 * returns {@code 32} output bits from the state before the step: {@code ((state >>> 18) ^ state) >>> 27}, truncated to
 * {@code 32} bits and rotated right by {@code state >>> 59}. {@link #next} returns the high {@code 31} of these bits;
 * {@link #nextRange} maps them as {@code minimum + ((next * (maximum - minimum + 1)) >>> 31)}, computed in {@code long}.
 * Seeding follows the PCG reference: the increment is {@code (stream << 1) | 1}, the state starts at {@code 0}, one step,
 * the seed is added and one more step follows.
 * <p>
 * A new generator is inactive ({@link DeterministicRandom}). The saved state holds the state, the increment, the
 * active flag, the source count and every source counter.
 */
public final class PcgRandom implements DeterministicRandom {
    /**
     * The stream of {@link #factory(int)}: the default stream of the PCG reference (increment {@code 0x14057b7ef767814f}).
     */
    public static final long DEFAULT_STREAM = 0x14057b7ef767814fL >>> 1;
    /**
     * The number of call sources of the engine default generator.
     */
    public static final int DEFAULT_SOURCE_COUNT = 256;

    /**
     * The {@code 64}-bit multiplier of the state step.
     */
    private static final long MULTIPLIER = 6364136223846793005L;
    /**
     * The number of bits of a value of {@link #next}.
     */
    private static final int OUTPUT_BITS = 31;
    /**
     * The right shift of the output XOR of the state.
     */
    private static final int XOR_SHIFT = 18;
    /**
     * The right shift that takes the output bits of the XORed state.
     */
    private static final int OUTPUT_SHIFT = 27;
    /**
     * The right shift that takes the rotation of the output from the state.
     */
    private static final int ROTATION_SHIFT = 59;

    /**
     * The positions of the saved words before the source counters.
     */
    private static final int SAVED_STATE_LOW = 0;
    private static final int SAVED_STATE_HIGH = 1;
    private static final int SAVED_INCREMENT_LOW = 2;
    private static final int SAVED_INCREMENT_HIGH = 3;
    private static final int SAVED_ACTIVE = 4;
    private static final int SAVED_SOURCE_COUNT = 5;
    private static final int SAVED_HEADER_LENGTH = 6;
    /**
     * The mask of the unsigned {@code 32}-bit value of an {@code int}.
     */
    private static final long UNSIGNED_INT_MASK = 0xFFFFFFFFL;

    /**
     * Creates a {@link RandomFactory} instance that creates generators of the {@link #DEFAULT_STREAM}.
     *
     * @param sourceCount the number of call sources of each generator
     * @return the created {@link RandomFactory} instance
     */
    public static @NotNull RandomFactory factory(int sourceCount) {
        return seed -> new PcgRandom(seed, DEFAULT_STREAM, sourceCount);
    }

    private static long joinWords(int low, int high) {
        return (low & UNSIGNED_INT_MASK) | ((long) high << Integer.SIZE);
    }

    private final int @NotNull [] callCounts;

    private long state;
    private long increment;
    private boolean active;

    /**
     * Creates a {@link PcgRandom} instance, inactive and without counted calls.
     *
     * @param seed        the seed
     * @param stream      the stream; generators with different streams give different sequences for the same seed
     * @param sourceCount the number of call sources
     * @throws IllegalArgumentException if the source count is negative
     */
    public PcgRandom(long seed, long stream, int sourceCount) {
        if (sourceCount < 0) {
            throw Exceptions.newIllegalArgumentException("The source count must not be negative: {0}", sourceCount);
        }

        this.callCounts = new int[sourceCount];

        this.state     = 0L;
        this.increment = (stream << 1) | 1L;
        this.active    = false;

        this.step();
        this.state += seed;
        this.step();
    }

    /**
     * @return the number of bits of a value of {@link #next}: its values are {@code 0} up to {@code (1 << getOutputBits()) - 1}
     */
    @Override
    public int getOutputBits() {
        return OUTPUT_BITS;
    }

    /**
     * @return the number of call sources this generator counts; a source is {@code 0} up to this number minus one
     */
    @Override
    public int getSourceCount() {
        return this.callCounts.length;
    }

    /**
     * Returns the number of active calls of one source since the generator was created or its state restored.
     *
     * @param source the source
     * @return the number of active calls of the source
     */
    @Override
    public int getCallCount(int source) {
        return this.callCounts[source];
    }

    /**
     * @return {@code true} if the generator draws, {@code false} if it returns fixed values without advancing
     */
    @Override
    public boolean isActive() {
        return this.active;
    }

    /**
     * Sets whether the generator draws.
     *
     * @param active whether the generator draws
     * @return the previous value
     */
    @Override
    public boolean setActive(boolean active) {
        boolean previous = this.active;

        this.active = active;

        return previous;
    }


    /**
     * Draws the next value.
     *
     * @param source the call source
     * @return the drawn value, {@code 0} up to {@code (1 << getOutputBits()) - 1}, or {@code 0} while inactive
     */
    @Override
    public int next(int source) {
        if (!this.active) {
            return 0;
        }

        this.callCounts[source]++;

        return this.step() >>> (Integer.SIZE - OUTPUT_BITS);
    }

    /**
     * Draws the next value of an inclusive range. One call draws exactly one value of {@link #next}.
     *
     * @param source  the call source
     * @param minimum the lowest value
     * @param maximum the highest value, at least {@code minimum}
     * @return the drawn value, {@code minimum} up to {@code maximum}, or {@code minimum} while inactive
     * @throws IllegalArgumentException if the maximum is lower than the minimum
     */
    @Override
    public int nextRange(int source, int minimum, int maximum) {
        if (maximum < minimum) {
            throw Exceptions.newIllegalArgumentException("The random range {0}..{1} is empty.", minimum, maximum);
        }

        long span = (long) maximum - minimum + 1L;

        return (int) (minimum + ((this.next(source) * span) >>> OUTPUT_BITS));
    }

    /**
     * Saves the complete state: the generator state, the counters and the active flag.
     *
     * @return the saved state
     */
    @Override
    public @NotNull RandomState saveState() {
        int[] words = new int[SAVED_HEADER_LENGTH + this.callCounts.length];

        words[SAVED_STATE_LOW]      = (int) this.state;
        words[SAVED_STATE_HIGH]     = (int) (this.state >>> Integer.SIZE);
        words[SAVED_INCREMENT_LOW]  = (int) this.increment;
        words[SAVED_INCREMENT_HIGH] = (int) (this.increment >>> Integer.SIZE);
        words[SAVED_ACTIVE]         = this.active ? 1 : 0;
        words[SAVED_SOURCE_COUNT]   = this.callCounts.length;

        System.arraycopy(this.callCounts, 0, words, SAVED_HEADER_LENGTH, this.callCounts.length);

        return RandomState.of(words);
    }

    /**
     * Restores a state saved by a generator of the same implementation.
     *
     * @param state the saved state
     * @throws IllegalArgumentException if the state was not saved by a generator of this implementation and source count
     */
    @Override
    public void restoreState(@NotNull RandomState state) {
        int[] words = state.toArray();

        if (words.length != SAVED_HEADER_LENGTH + this.callCounts.length || words[SAVED_SOURCE_COUNT] != this.callCounts.length ||
            (words[SAVED_INCREMENT_LOW] & 1) == 0) {
            throw Exceptions.newIllegalArgumentException("The random state was not saved by a PCG generator with {0} sources: {1}",
                                                         this.callCounts.length, state);
        }

        this.state     = joinWords(words[SAVED_STATE_LOW], words[SAVED_STATE_HIGH]);
        this.increment = joinWords(words[SAVED_INCREMENT_LOW], words[SAVED_INCREMENT_HIGH]);
        this.active    = words[SAVED_ACTIVE] != 0;

        System.arraycopy(words, SAVED_HEADER_LENGTH, this.callCounts, 0, this.callCounts.length);
    }

    /**
     * Mixes this part of the simulation state into the hash: the state, the increment, the active flag and the source
     * counters in source order.
     *
     * @param hash the hash being computed
     */
    @Override
    public void contributeStateHash(@NotNull StateHash hash) {
        hash.mixLong(this.state);
        hash.mixLong(this.increment);
        hash.mixBoolean(this.active);
        hash.mixArray(this.callCounts);
    }

    /**
     * Advances the state once.
     *
     * @return the {@code 32} output bits of the state before the step
     */
    private int step() {
        long previous = this.state;

        this.state = previous * MULTIPLIER + this.increment;

        int output = (int) (((previous >>> XOR_SHIFT) ^ previous) >>> OUTPUT_SHIFT);

        return Integer.rotateRight(output, (int) (previous >>> ROTATION_SHIFT));
    }
}
