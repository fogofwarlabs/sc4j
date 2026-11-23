package org.fogofwarlabs.sc4j.engine.state.snapshot;

import org.fogofwarlabs.sc4j.base.exception.Exceptions;
import org.fogofwarlabs.sc4j.engine.game.Game;
import org.jetbrains.annotations.NotNull;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;

/**
 * Holds the complete simulation state of one game between two frames: the values every {@link SnapshotContributor} of the game wrote, in the
 * order of {@link Game#writeSnapshot}, with the frame and the state hash of the game when it was captured. {@link #restore} reads it into another
 * game created from the same definition and setup, which then runs every later frame as the captured game would have; a save game and a
 * replay seek are built on it.
 * <p>
 * Wire form ({@link #write}, big-endian): the frame (four bytes), the state hash (eight bytes), the length of the values (four bytes) and the
 * values ({@link StateSnapshotWriter}). A snapshot is immutable.
 */
public final class StateSnapshot {
    /**
     * Captures the state of a game between two frames.
     *
     * @param game the game
     * @return the created {@link StateSnapshot} instance
     * @throws IllegalStateException if a system of the game contributes to the state hash but writes no snapshot
     */
    public static @NotNull StateSnapshot capture(@NotNull Game game) {
        StateSnapshotWriter writer = new StateSnapshotWriter();

        game.writeSnapshot(writer);

        return new StateSnapshot(game.getClock().getFrame(), game.computeStateHash(), writer.toByteArray());
    }

    /**
     * Reads the wire form of a snapshot ({@link #write}).
     *
     * @param input the input
     * @return the created {@link StateSnapshot} instance
     * @throws IOException if the input ends early or the length is invalid
     */
    public static @NotNull StateSnapshot read(@NotNull DataInputStream input) throws IOException {
        int frame = input.readInt();
        long stateHash = input.readLong();
        int length = input.readInt();

        if (length < 0 || length > input.available()) {
            throw Exceptions.newIOException("Invalid state snapshot length {0}; {1} bytes follow.", length, input.available());
        }

        byte[] values = new byte[length];

        input.readFully(values);

        return new StateSnapshot(frame, stateHash, values);
    }


    private final int frame;
    private final long stateHash;
    private final byte @NotNull [] values;

    private StateSnapshot(int frame, long stateHash, byte @NotNull [] values) {
        this.frame     = frame;
        this.stateHash = stateHash;
        this.values    = values;
    }

    /**
     * @return the frame of the captured game: the frames that had advanced it
     */
    public int getFrame() {
        return this.frame;
    }

    /**
     * @return the state hash of the captured game, which a restored game reaches
     */
    public long getStateHash() {
        return this.stateHash;
    }

    /**
     * @return the number of bytes of the values
     */
    public int getSize() {
        return this.values.length;
    }


    /**
     * Restores the snapshot into a game created from the definition and the setup of the captured game, replacing its state. Afterwards the
     * game's state hash is the captured one.
     * <p>
     * The values do not fit the game when a part has another size, a reference is unknown or values are left over. A failed restore leaves the
     * game in a partly restored state; the caller discards it.
     *
     * @param game the game
     * @throws IOException           if the values do not fit the game or the restored game does not reach the captured state hash
     * @throws IllegalStateException if a system of the game contributes to the state hash but reads no snapshot
     */
    public void restore(@NotNull Game game) throws IOException {
        StateSnapshotReader reader = new StateSnapshotReader(game, this.values);

        game.readSnapshot(reader);

        if (!reader.isFinished()) {
            throw Exceptions.newIOException("The state snapshot holds values the game does not read.");
        }

        long restoredHash = game.computeStateHash();

        if (restoredHash != this.stateHash) {
            throw Exceptions.newIOException("The restored game has the state hash {0}, the snapshot {1}.",
                                            Long.toHexString(restoredHash), Long.toHexString(this.stateHash));
        }
    }

    /**
     * Writes the wire form of the snapshot.
     *
     * @param output the output
     * @throws IOException if the output fails
     */
    public void write(@NotNull DataOutputStream output) throws IOException {
        output.writeInt(this.frame);
        output.writeLong(this.stateHash);
        output.writeInt(this.values.length);
        output.write(this.values);
    }
}
