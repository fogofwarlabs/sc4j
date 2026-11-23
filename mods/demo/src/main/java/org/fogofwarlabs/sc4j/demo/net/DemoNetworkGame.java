package org.fogofwarlabs.sc4j.demo.net;

import org.fogofwarlabs.sc4j.base.exception.IgnoreException;
import org.fogofwarlabs.sc4j.demo.client.DemoClient;
import org.fogofwarlabs.sc4j.demo.content.DemoContent;
import org.fogofwarlabs.sc4j.demo.game.DemoGames;
import org.fogofwarlabs.sc4j.demo.map.DemoMaps;
import org.fogofwarlabs.sc4j.engine.client.game.GameSession;
import org.fogofwarlabs.sc4j.engine.game.Game;
import org.fogofwarlabs.sc4j.engine.lockstep.LockstepSession;
import org.fogofwarlabs.sc4j.engine.net.DatagramLockstepTransport;
import org.fogofwarlabs.sc4j.engine.net.NetworkClock;
import org.fogofwarlabs.sc4j.engine.net.lobby.LobbyMember;
import org.fogofwarlabs.sc4j.engine.net.lobby.NetworkGameStart;
import org.fogofwarlabs.sc4j.engine.player.Player;
import org.fogofwarlabs.sc4j.engine.replay.EngineReplayFormat;
import org.fogofwarlabs.sc4j.engine.replay.ReplayRecorder;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.Closeable;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;

/**
 * Runs one machine's part of a demo network game as its room started it: the game of the start's setup, the engine's lockstep transport on
 * the room's network (the machine's game endpoint on this computer, the overlay over Beacon.Link), the engine lockstep session and the
 * network game session of the engine's
 * {@link org.fogofwarlabs.sc4j.engine.client.game.GameSessionFactory}, with a replay recorder on the session's command source. Every machine
 * records the same commands at the same game loops, so the replays of one game are equal on every machine.
 * <p>
 * The computer players of the setup run on every machine (the engine computer player system); the person at this machine plays the slot of
 * its member and submits to the session's command sink.
 */
public final class DemoNetworkGame implements Closeable {
    /**
     * The departure reason of a machine that leaves the game.
     */
    public static final int LEAVE_REASON = 1;
    /**
     * The longest time a machine that leaves answers the other machines until they acknowledged its departure.
     */
    public static final long DEPARTURE_MILLIS = 2000L;

    private static final long POLL_WAIT_NANOS = TimeUnit.MICROSECONDS.toNanos(200L);

    private static @Nullable Player findLocalPlayer(@NotNull NetworkGameStart start, @NotNull Game game) {
        for (LobbyMember member : start.getMembers()) {
            if (member.getPeer() == start.getLocalPeer() && !member.isObserver()) {
                return game.getPlayer(member.getSlotIndex());
            }
        }

        return null;
    }


    private final @NotNull NetworkGameStart start;
    private final @NotNull DemoLobby room;
    private final @NotNull DatagramLockstepTransport<?> transport;
    private final @NotNull Game game;
    private final @NotNull LockstepSession lockstep;
    private final @NotNull GameSession session;
    private final @NotNull ReplayRecorder recorder;

    private boolean left;

    /**
     * Creates a {@link DemoNetworkGame} instance before the game's first loop. The game keeps the room's connections open while it runs, so
     * that the start reaches every member, and closes the room with itself.
     *
     * @param start              the started game of the room
     * @param room               the machine's room, which creates the game's transport
     * @param clock              the clock of the transport's resends and timeouts
     * @param settings           the network settings with the transport's timing
     * @param content            the demo content
     * @param maps               the demo maps
     * @param games              the demo's games
     * @param format             the replay format of the recording
     * @param checkpointInterval the game loops between two state hash checkpoints of the recording
     * @throws IllegalArgumentException if the demo has no map of the start's setup
     */
    public DemoNetworkGame(@NotNull NetworkGameStart start, @NotNull DemoLobby room, @NotNull NetworkClock clock,
                           @NotNull DemoNetworkSettings settings, @NotNull DemoContent content, @NotNull DemoMaps maps, @NotNull DemoGames games,
                           @NotNull EngineReplayFormat format, int checkpointInterval) {
        Game created = games.createGame(start.getSetup());
        DatagramLockstepTransport<?> createdTransport;
        LockstepSession createdLockstep;
        GameSession createdSession;

        try {
            createdTransport = room.createTransport(start, clock, settings);
            createdLockstep  = start.createSession(created, createdTransport, content.getOrders().getCommands(), null, null);
            createdSession   = DemoClient.createSessionFactory(content, created, maps.require(start.getSetup()))
                                         .createNetwork(created, findLocalPlayer(start, created), createdLockstep, List.of());
        } catch (RuntimeException exception) {
            IgnoreException.execute(
                    room::close
            );

            throw exception;
        }

        this.start     = start;
        this.room      = room;
        this.transport = createdTransport;
        this.game      = created;
        this.lockstep  = createdLockstep;
        this.session   = createdSession;
        this.recorder  = format.createRecorder(start.getSetup(), checkpointInterval);

        this.left = false;

        this.session.getCommandSource().setRecorder(this.recorder);
    }

    /**
     * @return the started game of the room
     */
    public @NotNull NetworkGameStart getStart() {
        return this.start;
    }

    /**
     * @return the game
     */
    public @NotNull Game getGame() {
        return this.game;
    }

    /**
     * @return the network game session of the game
     */
    public @NotNull GameSession getSession() {
        return this.session;
    }

    /**
     * @return the lockstep session of this machine
     */
    public @NotNull LockstepSession getLockstep() {
        return this.lockstep;
    }

    /**
     * @return the player of the person at this machine, or {@code null} for an observer
     */
    public @Nullable Player getLocalPlayer() {
        return this.session.getLocalPlayer();
    }

    /**
     * @return the recorder of the game's replay
     */
    public @NotNull ReplayRecorder getRecorder() {
        return this.recorder;
    }

    /**
     * @return {@code true} if the game is decided, {@code false} otherwise
     */
    public boolean isOver() {
        return DemoGames.isOver(this.game);
    }


    /**
     * Runs the game's next loop once the other machines' turns for it arrived: first receives the network's datagrams and answers the
     * transport's waiting ones.
     *
     * @return {@code true} if the loop ran, {@code false} while a turn is missing or after this machine left
     */
    public boolean runFrame() {
        if (this.left) {
            return false;
        }

        this.room.pollNetwork();
        this.transport.poll();

        return this.session.runFrame();
    }

    /**
     * Leaves the game: the other machines take this machine's departure once they received its last turns. {@link #poll()} keeps answering
     * until they acknowledged it.
     */
    public void leave() {
        if (!this.left) {
            this.left = true;
            this.lockstep.leave(LEAVE_REASON);
        }
    }

    /**
     * Answers the other machines between loops, for example after leaving.
     */
    public void poll() {
        this.room.pollNetwork();
        this.transport.poll();
    }

    /**
     * Leaves the game if this machine has not left yet, then answers the other machines until they acknowledged the departure or dropped this
     * machine, at most {@value #DEPARTURE_MILLIS} ms: the others so receive every turn this machine ran, and take its departure instead of
     * dropping it.
     */
    public void finishDeparture() {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(DEPARTURE_MILLIS);

        this.leave();

        while (!this.isDepartureComplete() && System.nanoTime() < deadline) {
            this.poll();
            LockSupport.parkNanos(POLL_WAIT_NANOS);
        }
    }

    /**
     * @return {@code true} if this machine left and every other machine acknowledged it, or the others dropped this machine, {@code false} otherwise
     */
    public boolean isDepartureComplete() {
        return this.transport.isDepartureComplete() || this.transport.isLocalDropped();
    }

    /**
     * Closes the room with the game's endpoint or port. A socket that cannot be closed is logged and ignored.
     */
    @Override
    public void close() {
        IgnoreException.execute(
                this.room::close
        );
    }
}
