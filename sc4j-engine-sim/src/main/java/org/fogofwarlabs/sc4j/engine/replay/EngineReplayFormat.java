package org.fogofwarlabs.sc4j.engine.replay;

import org.fogofwarlabs.sc4j.base.exception.Exceptions;
import org.fogofwarlabs.sc4j.engine.command.CommandInput;
import org.fogofwarlabs.sc4j.engine.command.CommandRegistry;
import org.fogofwarlabs.sc4j.engine.command.CommandType;
import org.fogofwarlabs.sc4j.engine.game.GameSetup;
import org.fogofwarlabs.sc4j.engine.game.GameSetupCodec;
import org.fogofwarlabs.sc4j.engine.mod.identity.IdentityFileFrame;
import org.fogofwarlabs.sc4j.engine.mod.identity.ModIdentity;
import org.fogofwarlabs.sc4j.engine.mod.identity.ModIdentityException;
import org.jetbrains.annotations.NotNull;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Writes and reads the engine replay format, the default {@link ReplayFormat} of a mod without its own: the identity of the recording mod,
 * the game setup ({@link GameSetupCodec}), the commands with their game loop, player and payload (each command type's codec), and the state
 * hash checkpoints. A mod creates one format with its identity, its command types and its setup codec; {@link #createRecorder} records a
 * game, {@link #write} writes the replay, {@link #read} reads it back and refuses a replay of another identity
 * ({@link ModIdentityException}) or a damaged one; {@link EngineReplay#createController} plays it back.
 * <p>
 * File layout ({@link DataOutputStream}, big-endian), inside the frame of the engine file formats ({@link IdentityFileFrame}): the magic
 * {@code SC4R} (four bytes), the format version (two bytes), the mod identity ({@link ModIdentity#write}), the setup
 * ({@link GameSetupCodec#write}), the game loop count (eight bytes), then the records in game loop order, each a kind byte and the game loops
 * since the previous record (four bytes, unsigned; the first counts from loop {@code 0}): a command ({@value #COMMAND_RECORD}) adds the
 * player's slot index (one byte), the command id (two bytes), the payload length (two bytes) and the payload; a checkpoint
 * ({@value #CHECKPOINT_RECORD}) adds the state hash at the command point of its loop (eight bytes), before the loop's commands. The end
 * record ({@value #END_RECORD}, kind byte only) closes the records; the last four bytes are the CRC-32 of everything before them.
 */
public final class EngineReplayFormat implements ReplayFormat {
    /**
     * The name of the format for people.
     */
    public static final @NotNull String NAME = "SC4J engine replay";
    /**
     * The file name extension of engine replays, without the dot.
     */
    public static final @NotNull String FILE_EXTENSION = "sc4r";
    /**
     * The largest player slot index a command record encodes (one byte).
     */
    static final int MAXIMUM_PLAYER_INDEX = 0xFF;
    /**
     * The largest command id and payload length a command record encodes (two bytes each).
     */
    static final int MAXIMUM_FIELD_VALUE = 0xFFFF;

    /**
     * The kind of file the identity refusal names.
     */
    private static final @NotNull String FILE_KIND = "replay";
    /**
     * The name of the format in the messages.
     */
    private static final @NotNull String FORMAT_DESCRIPTION = "engine replay";
    /**
     * The first four bytes of an engine replay: {@code SC4R}.
     */
    private static final int MAGIC = 0x5343_3452;
    /**
     * The format version this engine writes and reads.
     */
    private static final int FORMAT_VERSION = 1;
    /**
     * The frame of engine replays: magic, format version, identity and checksum.
     */
    private static final @NotNull IdentityFileFrame FRAME = new IdentityFileFrame(MAGIC, FORMAT_VERSION, FORMAT_DESCRIPTION, FILE_KIND);
    /**
     * The record kind that closes the records.
     */
    private static final int END_RECORD = 0;
    /**
     * The record kind of a command.
     */
    private static final int COMMAND_RECORD = 1;
    /**
     * The record kind of a state hash checkpoint.
     */
    private static final int CHECKPOINT_RECORD = 2;
    /**
     * The most game loops between two records (four bytes, unsigned).
     */
    private static final long MAXIMUM_LOOP_DELTA = 0xFFFF_FFFFL;

    private static long readLoopCount(@NotNull DataInputStream input) throws IOException {
        long loopCount = input.readLong();

        if (loopCount < 0) {
            throw Exceptions.newIOException("Invalid game loop count of the engine replay: {0}", loopCount);
        }

        return loopCount;
    }

    private static void writeRecordStart(@NotNull DataOutputStream output, int kind, long loopDelta) throws IOException {
        if (loopDelta < 0 || loopDelta > MAXIMUM_LOOP_DELTA) {
            throw Exceptions.newIllegalArgumentException("The records of the replay are {0} game loops apart.", loopDelta);
        }

        output.writeByte(kind);
        output.writeInt((int) loopDelta);
    }


    private final @NotNull ModIdentity identity;
    private final @NotNull CommandRegistry<? extends CommandType<?>> commands;
    private final @NotNull GameSetupCodec setupCodec;

    /**
     * Creates an {@link EngineReplayFormat} instance.
     *
     * @param identity   the identity of the running mod: the recorders record it, a replay of another identity is refused
     * @param commands   the command types of the mod's games by id, which decode the recorded commands
     * @param setupCodec the codec of the mod's game setups
     */
    public EngineReplayFormat(@NotNull ModIdentity identity, @NotNull CommandRegistry<? extends CommandType<?>> commands,
                              @NotNull GameSetupCodec setupCodec) {
        this.identity   = identity;
        this.commands   = commands;
        this.setupCodec = setupCodec;
    }

    /**
     * @return the identity of the running mod, which the recorders record and the reader requires
     */
    public @NotNull ModIdentity getIdentity() {
        return this.identity;
    }

    /**
     * @return the name of the format for people: {@value #NAME}
     */
    @Override
    public @NotNull String getName() {
        return NAME;
    }

    /**
     * @return the file name extension of the format's replay files, without the dot: {@value #FILE_EXTENSION}
     */
    @Override
    public @NotNull String getFileExtension() {
        return FILE_EXTENSION;
    }

    /**
     * Creates a recorder of a game created from a setup, set on the game's command source before its first loop.
     *
     * @param setup              the setup the game was created from
     * @param checkpointInterval the game loops between two state hash checkpoints, {@code 0} for none
     * @return the created {@link ReplayRecorder} instance
     * @throws IllegalArgumentException if the interval is negative
     */
    public @NotNull ReplayRecorder createRecorder(@NotNull GameSetup setup, int checkpointInterval) {
        return new ReplayRecorder(this.identity, setup, checkpointInterval);
    }

    /**
     * Tests whether file contents start like a replay of this format: with the magic {@code SC4R}; the contents may still be damaged.
     *
     * @param contents the file contents
     * @return {@code true} if the contents start like a replay of this format, {@code false} otherwise
     */
    @Override
    public boolean isReplay(byte @NotNull [] contents) {
        return FRAME.matches(contents);
    }

    /**
     * Writes a replay in the engine replay format.
     *
     * @param replay the replay
     * @return the file contents
     * @throws IllegalArgumentException if the replay's setup has no wire form ({@link GameSetupCodec#write})
     */
    public byte @NotNull [] write(@NotNull EngineReplay replay) {
        return FRAME.write(replay.getIdentity(), output -> this.writeBody(replay, output));
    }

    /**
     * Reads a replay of the engine replay format recorded by the running mod's identity. A command is unknown when the mod's command types do
     * not read its id.
     *
     * @param contents the file contents
     * @return the created {@link EngineReplay} instance
     * @throws ModIdentityException if the replay was recorded by another mod identity
     * @throws IOException          if the contents are no engine replay, have another format version, are truncated or damaged, or name an unknown command
     */
    public @NotNull EngineReplay read(byte @NotNull [] contents) throws IOException {
        DataInputStream input = FRAME.openBody(contents, this.identity);

        try {
            GameSetup setup = this.setupCodec.read(input);
            long loopCount = readLoopCount(input);
            List<RecordedCommand> recordedCommands = new ArrayList<>();
            List<StateCheckpoint> checkpoints = new ArrayList<>();

            this.readRecords(input, loopCount, recordedCommands, checkpoints);

            if (input.available() != 0) {
                throw Exceptions.newIOException("The engine replay has {0} bytes after its end record.", input.available());
            }

            return new EngineReplay(this.identity, setup, loopCount, recordedCommands, checkpoints);
        } catch (EOFException exception) {
            throw Exceptions.newIOException(exception, "The engine replay ends inside its records.");
        }
    }

    /**
     * Reads what a replay list shows of a replay: its map, its length and the slots of the players who played (not the neutral slot). The
     * summary of a replay of another mod identity is refused like the replay.
     *
     * @param contents the file contents
     * @return the created {@link ReplaySummary} instance, whose frame count is the replay's game loop count
     * @throws ModIdentityException if the replay was recorded by another mod identity
     * @throws IOException          if the contents are no engine replay, have another format version, or are truncated or damaged
     */
    @Override
    public @NotNull ReplaySummary readSummary(byte @NotNull [] contents) throws IOException {
        DataInputStream input = FRAME.openBody(contents, this.identity);

        try {
            GameSetup setup = this.setupCodec.read(input);

            return new ReplaySummary(setup.getMap().getName(), readLoopCount(input), ReplaySummary.findPlayingSlots(setup.getPlayers()));
        } catch (EOFException exception) {
            throw Exceptions.newIOException(exception, "The engine replay ends inside its header.");
        }
    }

    private void writeBody(@NotNull EngineReplay replay, @NotNull DataOutputStream output) throws IOException {
        List<RecordedCommand> recorded = replay.getCommands();
        List<StateCheckpoint> checkpoints = replay.getCheckpoints();
        int commandIndex = 0;
        int checkpointIndex = 0;
        long loop = 0L;

        this.setupCodec.write(replay.getSetup(), output);
        output.writeLong(replay.getLoopCount());

        // A checkpoint goes before the commands of its loop: it was taken at the loop's command point.
        while (commandIndex < recorded.size() || checkpointIndex < checkpoints.size()) {
            if (checkpointIndex < checkpoints.size() &&
                (commandIndex == recorded.size() || checkpoints.get(checkpointIndex).getLoop() <= recorded.get(commandIndex).getLoop())) {
                StateCheckpoint checkpoint = checkpoints.get(checkpointIndex++);

                writeRecordStart(output, CHECKPOINT_RECORD, checkpoint.getLoop() - loop);
                output.writeLong(checkpoint.getStateHash());
                loop = checkpoint.getLoop();
            } else {
                RecordedCommand command = recorded.get(commandIndex++);

                writeRecordStart(output, COMMAND_RECORD, command.getLoop() - loop);
                output.writeByte(command.getPlayerIndex());
                output.writeShort(command.getType().getId());
                output.writeShort(command.getPayload().length);
                output.write(command.getPayload());
                loop = command.getLoop();
            }
        }

        output.writeByte(END_RECORD);
    }

    private void readRecords(@NotNull DataInputStream input, long loopCount, @NotNull List<@NotNull RecordedCommand> recordedCommands,
                             @NotNull List<@NotNull StateCheckpoint> checkpoints) throws IOException {
        CommandInput payloadInput = new CommandInput();
        long loop = 0L;

        while (true) {
            int kind = input.readUnsignedByte();

            if (kind == END_RECORD) {
                return;
            }

            loop += Integer.toUnsignedLong(input.readInt());

            if (loop >= loopCount) {
                throw Exceptions.newIOException("A record of the engine replay lies at game loop {0}, after its {1} loops.", loop, loopCount);
            }

            switch (kind) {
                case CHECKPOINT_RECORD -> checkpoints.add(new StateCheckpoint(loop, input.readLong()));
                case COMMAND_RECORD -> {
                    int playerIndex = input.readUnsignedByte();
                    int typeId = input.readUnsignedShort();
                    byte[] payload = new byte[input.readUnsignedShort()];
                    CommandType<?> type = this.commands.find(typeId);

                    input.readFully(payload);

                    if (type == null) {
                        throw Exceptions.newIOException("The engine replay names the command id {0} at game loop {1}, which has no type.", typeId,
                                                        loop);
                    }

                    type.read(payloadInput.reset(payload, 0, payload.length));

                    if (payloadInput.getRemaining() != 0) {
                        throw Exceptions.newIOException("The command id {0} at game loop {1} has {2} bytes its type does not read.", typeId, loop,
                                                        payloadInput.getRemaining());
                    }

                    recordedCommands.add(new RecordedCommand(loop, playerIndex, type, payload));
                }
                default -> throw Exceptions.newIOException("Unknown record kind {0} of the engine replay at game loop {1}", kind, loop);
            }
        }
    }
}
