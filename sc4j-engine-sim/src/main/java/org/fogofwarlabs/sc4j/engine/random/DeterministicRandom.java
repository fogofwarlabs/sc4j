package org.fogofwarlabs.sc4j.engine.random;

import org.fogofwarlabs.sc4j.base.exception.Exceptions;
import org.fogofwarlabs.sc4j.engine.state.StateHashContributor;
import org.fogofwarlabs.sc4j.engine.state.snapshot.SnapshotContributor;
import org.fogofwarlabs.sc4j.engine.state.snapshot.StateSnapshotReader;
import org.fogofwarlabs.sc4j.engine.state.snapshot.StateSnapshotWriter;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;

/**
 * Generates the random numbers of one game.
 * <p>
 * Determinism contract:
 * <ul>
 *     <li>The sequence depends only on the seed the generator was created with and on the calls made since; never on a
 *     clock, the platform or an identity hash code. Implementations use integer arithmetic only.</li>
 *     <li>Every call names its source, a small index of the call site. The source counts the call for diagnostics and
 *     never changes the drawn value.</li>
 *     <li>The generator draws only while it is active. An inactive generator returns {@code 0} from {@link #next} and
 *     the minimum from {@link #nextRange} and changes neither its state nor its counters. The frame runner activates
 *     the generator for each frame and deactivates it afterwards; outside frames, code that must draw opens its own
 *     active range with {@link #setActive} and restores the previous value.</li>
 *     <li>{@link #restoreState} of a {@link #saveState} result makes every later draw, counter and the active flag equal
 *     to what they were when the state was saved.</li>
 * </ul>
 */
public interface DeterministicRandom extends StateHashContributor, SnapshotContributor {
    /**
     * @return the number of bits of a value of {@link #next}: its values are {@code 0} up to {@code (1 << getOutputBits()) - 1}
     */
    int getOutputBits();

    /**
     * @return the number of call sources this generator counts; a source is {@code 0} up to this number minus one
     */
    int getSourceCount();

    /**
     * Returns the number of active calls of one source since the generator was created or its state restored.
     *
     * @param source the source
     * @return the number of active calls of the source
     */
    int getCallCount(int source);

    /**
     * @return {@code true} if the generator draws, {@code false} if it returns fixed values without advancing
     */
    boolean isActive();

    /**
     * Sets whether the generator draws.
     *
     * @param active whether the generator draws
     * @return the previous value
     */
    boolean setActive(boolean active);


    /**
     * Draws the next value.
     *
     * @param source the call source
     * @return the drawn value, {@code 0} up to {@code (1 << getOutputBits()) - 1}, or {@code 0} while inactive
     */
    int next(int source);

    /**
     * Draws the next value of an inclusive range. One call draws exactly one value of {@link #next}.
     *
     * @param source  the call source
     * @param minimum the lowest value
     * @param maximum the highest value, at least {@code minimum}
     * @return the drawn value, {@code minimum} up to {@code maximum}, or {@code minimum} while inactive
     */
    int nextRange(int source, int minimum, int maximum);

    /**
     * Saves the complete state: the generator state, the counters and the active flag.
     *
     * @return the saved state
     */
    @NotNull RandomState saveState();

    /**
     * Restores a state saved by a generator of the same implementation.
     *
     * @param state the saved state
     * @throws IllegalArgumentException if the state was not saved by a generator of this implementation and source count
     */
    void restoreState(@NotNull RandomState state);

    /**
     * Writes this part of the simulation state: the words of {@link #saveState()}.
     *
     * @param writer the writer of the snapshot
     */
    @Override
    default void writeSnapshot(@NotNull StateSnapshotWriter writer) {
        writer.writeInts(this.saveState().toArray());
    }

    /**
     * Reads this part of the simulation state back, replacing the current values: the saved words, restored with {@link #restoreState}.
     *
     * @param reader the reader of the snapshot
     * @throws IOException if the snapshot ends early or the words are no state of this implementation
     */
    @Override
    default void readSnapshot(@NotNull StateSnapshotReader reader) throws IOException {
        RandomState state = RandomState.of(reader.readIntArray());

        try {
            this.restoreState(state);
        } catch (IllegalArgumentException exception) {
            throw Exceptions.newIOException(exception, "The state snapshot holds no random state of this generator.");
        }
    }
}
