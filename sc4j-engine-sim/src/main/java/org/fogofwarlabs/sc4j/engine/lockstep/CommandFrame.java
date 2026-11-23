package org.fogofwarlabs.sc4j.engine.lockstep;

import org.fogofwarlabs.sc4j.base.exception.Exceptions;
import org.fogofwarlabs.sc4j.engine.command.CommandInput;
import org.fogofwarlabs.sc4j.engine.command.CommandRegistry;
import org.fogofwarlabs.sc4j.engine.command.CommandSink;
import org.fogofwarlabs.sc4j.engine.command.CommandType;
import org.fogofwarlabs.sc4j.engine.player.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Holds one participant's decoded turn of the engine {@link LockstepSession}: the sender's latest state hash, if it had one, and the commands
 * of the player it plays in the order it submitted them. {@link CommandFrameWriter} writes the turns; {@link #read} decodes them with the session's
 * {@link CommandRegistry}.
 * <p>
 * The turn bytes are little-endian: a flags byte ({@link #STATE_HASH_FLAG} when a state hash follows), then the hash's frame (four bytes) and
 * the hash (eight bytes) if present, the number of commands (two bytes), and for each command its type id (two bytes), its payload length
 * (two bytes) and its payload as the type's codec writes it. A turn names no player: the commands are those of the player the sending peer
 * plays.
 */
public final class CommandFrame {
    /**
     * The flag of a turn that carries its sender's state hash.
     */
    public static final int STATE_HASH_FLAG = 1;
    /**
     * The flags a turn may carry.
     */
    static final int KNOWN_FLAGS = STATE_HASH_FLAG;
    /**
     * The width of an encoded state hash in bytes.
     */
    static final int STATE_HASH_BYTES = Long.BYTES;
    /**
     * The largest command count, type id and payload length a turn encodes (two bytes each).
     */
    static final int MAXIMUM_FIELD_VALUE = 0xFFFF;

    /**
     * Holds one decoded command with its type, so that it is handed on with its own type parameter.
     *
     * @param <C> the command class
     */
    private static final class Entry<C> {
        private final @NotNull CommandType<C> type;
        private final @NotNull C command;

        private Entry(@NotNull CommandType<C> type, @NotNull C command) {
            this.type    = type;
            this.command = command;
        }

        private void submitTo(@NotNull CommandSink sink, @NotNull Player player) {
            sink.submit(player, this.type, this.command);
        }
    }

    /**
     * Decodes a received turn of the engine format. A turn is invalid if it names a command id without type, holds a payload its type does
     * not read exactly, or carries commands of an observer.
     *
     * @param turn     the received turn
     * @param player   the player the sending peer plays, or {@code null} for an observer
     * @param commands the command types of the session by id
     * @return the decoded turn
     * @throws IOException if the bytes are not a turn of the engine format or the turn is invalid
     */
    public static @NotNull CommandFrame read(@NotNull PeerTurn turn, @Nullable Player player,
                                             @NotNull CommandRegistry<? extends CommandType<?>> commands) throws IOException {
        byte[] bytes = turn.getTurn();
        CommandInput input = new CommandInput(bytes);
        CommandInput payload = new CommandInput();
        int flags = input.readUnsignedByte();

        if ((flags & ~KNOWN_FLAGS) != 0) {
            throw Exceptions.newIOException("Unknown turn flags of {0}: 0x{1}", turn.getPeer(), Integer.toHexString(flags));
        }

        boolean hasStateHash = (flags & STATE_HASH_FLAG) != 0;
        int hashFrame = hasStateHash ? input.readInt() : 0;
        long stateHash = hasStateHash ? input.readUnsigned(STATE_HASH_BYTES) : 0L;
        int count = input.readUnsignedShort();

        if (count > 0 && player == null) {
            throw Exceptions.newIOException("{0} plays no player but its turn carries {1} commands.", turn.getPeer(), count);
        }

        List<Entry<?>> entries = new ArrayList<>(count);

        for (int index = 0; index < count; index++) {
            int typeId = input.readUnsignedShort();
            int length = input.readUnsignedShort();
            CommandType<?> type = commands.find(typeId);

            if (type == null) {
                throw Exceptions.newIOException("The turn of {0} names the command id {1}, which has no type.", turn.getPeer(), typeId);
            }

            if (length > input.getRemaining()) {
                throw Exceptions.newIOException("The command {0} of {1} runs past its turn.", index, turn.getPeer());
            }

            int payloadOffset = input.getPosition();

            payload.reset(bytes, payloadOffset, length);
            entries.add(readEntry(type, payload));

            if (payload.getRemaining() != 0) {
                throw Exceptions.newIOException("The command {0} of {1} has {2} bytes its type does not read.", index, turn.getPeer(),
                                                payload.getRemaining());
            }

            input.reset(bytes, payloadOffset + length, bytes.length - payloadOffset - length);
        }

        if (input.getRemaining() != 0) {
            throw Exceptions.newIOException("The turn of {0} has {1} bytes after its commands.", turn.getPeer(), input.getRemaining());
        }

        return new CommandFrame(turn.getPeer(), player, hasStateHash, hashFrame, stateHash, entries);
    }

    private static <C> @NotNull Entry<C> readEntry(@NotNull CommandType<C> type, @NotNull CommandInput payload) throws IOException {
        return new Entry<>(type, type.read(payload));
    }


    private final @NotNull LockstepPeer peer;
    private final @Nullable Player player;
    private final boolean stateHashPresent;
    private final int hashFrame;
    private final long stateHash;
    private final @NotNull List<@NotNull Entry<?>> entries;

    private CommandFrame(@NotNull LockstepPeer peer, @Nullable Player player, boolean stateHashPresent, int hashFrame, long stateHash,
                         @NotNull List<@NotNull Entry<?>> entries) {
        this.peer             = peer;
        this.player           = player;
        this.stateHashPresent = stateHashPresent;
        this.hashFrame        = hashFrame;
        this.stateHash        = stateHash;
        this.entries          = Collections.unmodifiableList(entries);
    }

    /**
     * @return the sending peer
     */
    public @NotNull LockstepPeer getPeer() {
        return this.peer;
    }

    /**
     * @return the player the sending peer plays, or {@code null} for an observer, whose turn carries no command
     */
    public @Nullable Player getPlayer() {
        return this.player;
    }

    /**
     * @return {@code true} if the turn carries its sender's state hash, {@code false} otherwise
     */
    public boolean hasStateHash() {
        return this.stateHashPresent;
    }

    /**
     * @return the frame the carried state hash belongs to, {@code 0} without one
     */
    public int getHashFrame() {
        return this.hashFrame;
    }

    /**
     * @return the carried state hash, {@code 0} without one
     */
    public long getStateHash() {
        return this.stateHash;
    }

    /**
     * @return the number of commands of the turn
     */
    public int getCommandCount() {
        return this.entries.size();
    }


    /**
     * Hands the commands of the turn to a sink, in their order, as commands of the player the sending peer plays.
     *
     * @param sink the sink of the commands
     */
    public void submitTo(@NotNull CommandSink sink) {
        Player player = this.player;

        if (player == null) {
            return;
        }

        for (Entry<?> entry : this.entries) {
            entry.submitTo(sink, player);
        }
    }

    /**
     * @return the peer, the state hash and the command count, for diagnostics
     */
    @Override
    public @NotNull String toString() {
        String hash = this.stateHashPresent ? this.hashFrame + ":" + Long.toHexString(this.stateHash) : "none";

        return "CommandFrame[" + this.peer + ", hash " + hash + ", commands " + this.entries.size() + "]";
    }
}
