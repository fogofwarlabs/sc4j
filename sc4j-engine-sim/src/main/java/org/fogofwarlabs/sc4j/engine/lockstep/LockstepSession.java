package org.fogofwarlabs.sc4j.engine.lockstep;

import org.fogofwarlabs.sc4j.base.exception.Exceptions;
import org.fogofwarlabs.sc4j.engine.command.CommandInput;
import org.fogofwarlabs.sc4j.engine.command.CommandRegistry;
import org.fogofwarlabs.sc4j.engine.command.CommandSink;
import org.fogofwarlabs.sc4j.engine.command.CommandType;
import org.fogofwarlabs.sc4j.engine.controller.ControllerSource;
import org.fogofwarlabs.sc4j.engine.controller.PlayerController;
import org.fogofwarlabs.sc4j.engine.game.Game;
import org.fogofwarlabs.sc4j.engine.game.frame.SyncHashListener;
import org.fogofwarlabs.sc4j.engine.player.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Runs one machine's part of an engine lockstep game: the turn buffer, the latency, the all-turns barrier, the state hash exchange and desync
 * detection over a {@link LockstepTransport}, with the turns in the engine format ({@link CommandFrame}). The session knows which player of this
 * machine's game each participant plays; a participant without one is an observer, whose turns carry no command.
 * <p>
 * The client submits the local player's commands to {@link #getOutgoingSink()} and runs each frame only once {@link #prepareFrame} returns
 * {@code true}; the session's {@link #getController()} ({@link ControllerSource#NETWORK}) is the game's command source controller and hands
 * over the commands of every participant, the local one included, at the frame their turn step runs. {@link #attach} makes the game's sync
 * hash point record the local state hash after every {@link TurnSchedule#getFramesPerTurn()}-th frame; each outgoing turn carries the latest
 * one, and every received hash is compared with the local hash of its frame ({@link #getDesyncMonitor()}).
 * <p>
 * The session takes the transport's departures at every turn step and reports them to its {@link PeerDepartureListener}; the transport's
 * waiting state ({@link #getWaitingPeers()}) tells a client whom the barrier awaits and the seconds its waiting dialog shows, and the dialog's
 * drop button drops the overdue participants ({@link #dropWaitingPeers()}).
 * <p>
 * Determinism contract: what the controller hands over at a game loop depends only on the received turns; every machine runs the same commands
 * at the same game loop, in the transport's execution order of the participants and each participant's submission order.
 */
public final class LockstepSession {
    /**
     * The local state hashes kept for the check beyond the turns in transit.
     */
    private static final int HASH_HISTORY_MARGIN = 2;

    /**
     * Keeps the latest local state hashes by frame, for the turns that come back with older hashes.
     */
    private static final class StateHashHistory {
        private final int @NotNull [] frames;
        private final long @NotNull [] hashes;

        private int count;
        private int next;

        private StateHashHistory(int capacity) {
            this.frames = new int[capacity];
            this.hashes = new long[capacity];

            this.count = 0;
            this.next  = 0;
        }

        private void record(int frame, long hash) {
            this.frames[this.next] = frame;
            this.hashes[this.next] = hash;

            this.next  = (this.next + 1) % this.frames.length;
            this.count = Math.min(this.count + 1, this.frames.length);
        }

        private int find(int frame) {
            for (int index = 0; index < this.count; index++) {
                if (this.frames[index] == frame) {
                    return index;
                }
            }

            return -1;
        }
    }

    /**
     * Compares the state hash a received turn carries with the local hash of its frame.
     */
    private final class StateHashCheck implements SyncCheck {
        private final @NotNull CommandInput input;

        private StateHashCheck() {
            this.input = new CommandInput();
        }

        /**
         * Checks one received turn.
         *
         * @param turn the received turn
         * @return the description of the mismatch, or {@code null} if the turn matches or carries no sync value
         */
        @Override
        public @Nullable String check(@NotNull PeerTurn turn) {
            byte[] bytes = turn.getTurn();
            CommandInput header = this.input.reset(bytes, 0, bytes.length);
            int frame;
            long hash;

            try {
                if ((header.readUnsignedByte() & CommandFrame.STATE_HASH_FLAG) == 0) {
                    return null;
                }

                frame = header.readInt();
                hash  = header.readUnsigned(CommandFrame.STATE_HASH_BYTES);
            } catch (IOException exception) {
                return "the turn of " + turn.getPeer() + " ends in its state hash";
            }

            StateHashHistory history = LockstepSession.this.history;
            int index = history.find(frame);

            if (index < 0) {
                return "frame " + frame + ": " + turn.getPeer() + " sent " + Long.toHexString(hash) + ", no local state hash";
            }

            if (history.hashes[index] != hash) {
                return "frame " + frame + ": " + turn.getPeer() + " sent " + Long.toHexString(hash) + ", local " +
                       Long.toHexString(history.hashes[index]);
            }

            return null;
        }
    }

    /**
     * Hands over the commands of the received turns at the game loop of their turn step.
     */
    private final class TurnController implements PlayerController {
        /**
         * @return where the commands of this controller come from: {@link ControllerSource#NETWORK}, the turns of the session
         */
        @Override
        public @NotNull ControllerSource getSource() {
            return ControllerSource.NETWORK;
        }

        /**
         * Hands over the commands due at the frame about to run, in their order: at a turn step, every received participant's commands in
         * execution order; otherwise none.
         *
         * @param game the game
         * @param sink the sink of the commands
         * @throws IllegalStateException if the frame was not prepared ({@link LockstepSession#prepareFrame})
         */
        @Override
        public void collectCommands(@NotNull Game game, @NotNull CommandSink sink) {
            LockstepSession session = LockstepSession.this;

            if (!session.prepared) {
                throw Exceptions.newIllegalStateException("Game loop {0} of the lockstep session runs before its turns arrived.", session.step);
            }

            for (CommandFrame frame : session.dueFrames) {
                frame.submitTo(sink);
            }

            session.dueFrames.clear();
            session.prepared = false;
            session.step++;
        }
    }

    private final @NotNull CommandRegistry<? extends CommandType<?>> commands;
    private final @NotNull LockstepPeer localPeer;
    private final @NotNull List<@Nullable Player> peerPlayers;
    private final @NotNull TurnSchedule schedule;

    private final @NotNull CommandFrameWriter outgoing;
    private final @NotNull TurnExchange exchange;
    private final @NotNull StateHashHistory history;
    private final @NotNull DesyncMonitor desyncMonitor;
    private final @Nullable PeerDepartureListener departureListener;
    private final @NotNull TurnController controller;
    private final @NotNull List<@NotNull CommandFrame> dueFrames;
    private final @NotNull List<@NotNull PeerDeparture> departures;

    private long step;
    private boolean prepared;

    /**
     * Creates a {@link LockstepSession} instance before the first game loop.
     *
     * @param transport         the transport of the session's participants
     * @param commands          the command types of the game by id, which decode the received turns
     * @param localPeer         the local participant, whose player submits to the outgoing sink
     * @param peerPlayers       the player of this machine's game each participant plays, by peer number, {@code null} for an observer; copied
     * @param schedule          the turn timing
     * @param desyncListener    the receiver of detected desyncs, or {@code null} to only count them
     * @param departureListener the receiver of the transport's departures, or {@code null} to only keep them ({@link #getDepartures()})
     * @throws IllegalArgumentException if the local participant has no entry in the players
     */
    public LockstepSession(@NotNull LockstepTransport transport,
                           @NotNull CommandRegistry<? extends CommandType<?>> commands,
                           @NotNull LockstepPeer localPeer, @NotNull List<@Nullable Player> peerPlayers,
                           @NotNull TurnSchedule schedule,
                           @Nullable DesyncListener desyncListener, @Nullable PeerDepartureListener departureListener) {
        if (localPeer.getNumber() >= peerPlayers.size()) {
            throw Exceptions.newIllegalArgumentException("{0} has no entry in the {1} peer players.", localPeer, peerPlayers.size());
        }

        this.commands    = commands;
        this.localPeer   = localPeer;
        this.peerPlayers = Collections.unmodifiableList(new ArrayList<>(peerPlayers));
        this.schedule    = schedule;

        this.outgoing          = new CommandFrameWriter(peerPlayers.get(localPeer.getNumber()));
        this.exchange          = new TurnExchange(transport, this.outgoing);
        this.history           = new StateHashHistory(schedule.getLatencyTurns() + HASH_HISTORY_MARGIN);
        this.desyncMonitor     = new DesyncMonitor(new StateHashCheck(), desyncListener);
        this.departureListener = departureListener;
        this.controller        = new TurnController();
        this.dueFrames         = new ArrayList<>();
        this.departures        = new ArrayList<>();

        this.step     = 0L;
        this.prepared = false;
    }

    /**
     * @return the local participant
     */
    public @NotNull LockstepPeer getLocalPeer() {
        return this.localPeer;
    }

    /**
     * Returns the player a participant plays.
     *
     * @param peer the participant
     * @return the player of this machine's game the participant plays, or {@code null} for an observer or a peer number without entry
     */
    public @Nullable Player getPlayer(@NotNull LockstepPeer peer) {
        return peer.getNumber() < this.peerPlayers.size() ? this.peerPlayers.get(peer.getNumber()) : null;
    }

    /**
     * @return the turn timing
     */
    public @NotNull TurnSchedule getSchedule() {
        return this.schedule;
    }

    /**
     * @return the sink of the local player's commands: they go out with the next turn
     */
    public @NotNull CommandSink getOutgoingSink() {
        return this.outgoing;
    }

    /**
     * @return the controller that hands over every participant's commands at their turn step, for the game's command source
     */
    public @NotNull PlayerController getController() {
        return this.controller;
    }

    /**
     * @return the desync detection of the received state hashes
     */
    public @NotNull DesyncMonitor getDesyncMonitor() {
        return this.desyncMonitor;
    }

    /**
     * @return the departures the session took from its transport so far, in their order
     */
    public @NotNull List<@NotNull PeerDeparture> getDepartures() {
        return Collections.unmodifiableList(this.departures);
    }

    /**
     * @return the transport's waiting state of the all-turns barrier, or {@code null} for a transport that never drops a participant
     */
    public @Nullable WaitingPeers getWaitingPeers() {
        return this.exchange.getTransport().getWaitingPeers();
    }

    /**
     * @return the game loops run so far: the loop {@link #prepareFrame} prepares next
     */
    public long getStep() {
        return this.step;
    }


    /**
     * Makes the game's sync hash point record the local state hash after every {@link TurnSchedule#getFramesPerTurn()}-th frame, for the
     * outgoing turns and the desync check. The session takes the game's only sync hash listener.
     *
     * @param game the game of this machine
     */
    public void attach(@NotNull Game game) {
        SyncHashListener listener = (frame, hash) -> {
            this.history.record(frame, hash);
            this.outgoing.setStateHash(frame, hash);
        };

        game.getFrameRunner().setSyncHashListener(listener, this.schedule.getFramesPerTurn());
    }

    /**
     * Prepares the next game loop: at a turn step, exchanges the turns over the transport ({@link TurnExchange}), reports the transport's
     * departures, checks every received turn's state hash and decodes its commands for the controller. The client runs the game loop only
     * after this returns {@code true}; while a participant's turn is missing it returns {@code false} and the client calls it again later.
     * Preparing a prepared loop again does nothing.
     *
     * @param game the game of this machine
     * @return {@code true} if the game loop may run, {@code false} otherwise (a participant's turn is missing)
     * @throws UncheckedIOException if a received turn is not a turn of the engine format with commands of the game
     */
    public boolean prepareFrame(@NotNull Game game) {
        if (this.prepared) {
            return true;
        }

        if (this.schedule.isTurnStep(this.step)) {
            List<PeerTurn> turns = this.exchange.exchange(this.schedule.getLatencyTurns());
            int frame = game.getClock().getFrame() + 1;

            for (PeerDeparture departure : this.exchange.getTransport().takeDepartures()) {
                PeerDepartureListener listener = this.departureListener;

                this.departures.add(departure);

                if (listener != null) {
                    listener.onDeparture(departure, frame);
                }
            }

            if (turns == null) {
                return false;
            }

            for (PeerTurn turn : turns) {
                this.desyncMonitor.check(turn, frame);

                try {
                    this.dueFrames.add(CommandFrame.read(turn, this.getPlayer(turn.getPeer()), this.commands));
                } catch (IOException exception) {
                    throw Exceptions.newUncheckedIOException(exception);
                }
            }
        }

        this.prepared = true;

        return true;
    }

    /**
     * Leaves the session through the transport ({@link LockstepTransport#leave}).
     *
     * @param reason the departure reason, the session's own code
     */
    public void leave(int reason) {
        this.exchange.getTransport().leave(reason);
    }

    /**
     * Drops the overdue participants on a person's request, the waiting dialog's drop button ({@link LockstepTransport#dropWaitingPeers}).
     */
    public void dropWaitingPeers() {
        this.exchange.getTransport().dropWaitingPeers();
    }
}
