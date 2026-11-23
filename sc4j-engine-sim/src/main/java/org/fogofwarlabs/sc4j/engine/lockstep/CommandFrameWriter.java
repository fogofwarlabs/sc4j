package org.fogofwarlabs.sc4j.engine.lockstep;

import org.fogofwarlabs.sc4j.base.exception.Exceptions;
import org.fogofwarlabs.sc4j.engine.command.CommandOutput;
import org.fogofwarlabs.sc4j.engine.command.CommandSink;
import org.fogofwarlabs.sc4j.engine.command.CommandType;
import org.fogofwarlabs.sc4j.engine.player.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Queues the local participant's commands into its next outgoing turn of the engine format ({@link CommandFrame}): the client submits them as
 * a {@link CommandSink} between turn steps, and as the session's {@link TurnSource} the writer takes them as the next turn, with the latest
 * state hash it was given. The commands run when the turn comes back through the transport, on every machine at the same step.
 */
public final class CommandFrameWriter implements CommandSink, TurnSource {
    private final @Nullable Player localPlayer;

    private final @NotNull CommandOutput commands;
    private final @NotNull CommandOutput payload;
    private final @NotNull CommandOutput turn;
    private int commandCount;
    private boolean stateHashPresent;
    private int hashFrame;
    private long stateHash;

    /**
     * Creates a {@link CommandFrameWriter} instance without commands and without state hash.
     *
     * @param localPlayer the player the local participant plays, the only one that submits commands, or {@code null} for an observer, which submits none
     */
    public CommandFrameWriter(@Nullable Player localPlayer) {
        this.localPlayer = localPlayer;

        this.commands         = new CommandOutput();
        this.payload          = new CommandOutput();
        this.turn             = new CommandOutput();
        this.commandCount     = 0;
        this.stateHashPresent = false;
        this.hashFrame        = 0;
        this.stateHash        = 0L;
    }

    /**
     * @return the number of commands waiting for the next turn
     */
    public int getQueuedCount() {
        return this.commandCount;
    }

    /**
     * Sets the state hash the next turns carry: the local hash of the latest sync hash point.
     *
     * @param frame the frame the hash belongs to
     * @param hash  the state hash after the frame
     */
    public void setStateHash(int frame, long hash) {
        this.stateHashPresent = true;
        this.hashFrame        = frame;
        this.stateHash        = hash;
    }


    /**
     * Submits one command of a player: queues it into the next turn. The command is rejected if the player is not the player the local
     * participant plays, the type id or the payload does not fit the turn format, or the turn holds the most commands it can.
     *
     * @param <C>     the command class
     * @param player  the issuing player, the player the local participant plays
     * @param type    the command type
     * @param command the command
     * @throws IllegalArgumentException if the command is rejected
     */
    @Override
    public <C> void submit(@NotNull Player player, @NotNull CommandType<C> type, @NotNull C command) {
        if (player != this.localPlayer) {
            throw Exceptions.newIllegalArgumentException("Only {0} submits here, not {1}.", this.localPlayer, player);
        }

        if (type.getId() > CommandFrame.MAXIMUM_FIELD_VALUE || this.commandCount == CommandFrame.MAXIMUM_FIELD_VALUE) {
            throw Exceptions.newIllegalArgumentException("The command id {0} does not fit the turn of {1} commands.", type.getId(),
                                                         this.commandCount);
        }

        this.payload.clear();
        type.write(command, this.payload);

        if (this.payload.getLength() > CommandFrame.MAXIMUM_FIELD_VALUE) {
            throw Exceptions.newIllegalArgumentException("The payload of {0} bytes of command id {1} does not fit a turn.", this.payload.getLength(),
                                                         type.getId());
        }

        this.commands.writeShort(type.getId())
                     .writeShort(this.payload.getLength())
                     .writeBytes(this.payload.toByteArray());
        this.commandCount++;
    }

    /**
     * Takes the next outgoing turn: the queued commands, which the source no longer holds afterwards.
     * <p>
     * The turn carries the latest state hash, if one was set, and every command queued since the previous turn in submission order.
     *
     * @return the turn bytes
     */
    @Override
    public byte @NotNull [] takeTurn() {
        CommandOutput output = this.turn;

        output.clear();
        output.writeByte(this.stateHashPresent ? CommandFrame.STATE_HASH_FLAG : 0);

        if (this.stateHashPresent) {
            output.writeInt(this.hashFrame)
                  .writeUnsigned(this.stateHash, CommandFrame.STATE_HASH_BYTES);
        }

        output.writeShort(this.commandCount)
              .writeBytes(this.commands.toByteArray());

        this.commands.clear();
        this.commandCount = 0;

        return output.toByteArray();
    }
}
