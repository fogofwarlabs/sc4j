package org.fogofwarlabs.sc4j.engine.client.game;

import org.fogofwarlabs.sc4j.base.exception.Exceptions;
import org.fogofwarlabs.sc4j.engine.client.view.GameView;
import org.fogofwarlabs.sc4j.engine.command.CommandSink;
import org.fogofwarlabs.sc4j.engine.controller.ControllerCommandSource;
import org.fogofwarlabs.sc4j.engine.game.Game;
import org.fogofwarlabs.sc4j.engine.lockstep.LockstepSession;
import org.fogofwarlabs.sc4j.engine.player.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Holds one game a client runs, as a {@link GameSessionFactory} creates it: the game, the read-only {@link GameView} the client draws from,
 * the local player and the {@link CommandSink} the client's commands go to (a local queue, the mod's own turn buffer or the network session's
 * outgoing turns), or no sink for a replay. The session's {@link ControllerCommandSource} is the one path of the commands into the game; its
 * {@link CommandPoint} says who applies it. With {@link CommandPoint#FRAME_RUNNER} the client advances the game with {@link #runFrame} at the
 * game speed it chooses, and a network game over the engine's {@link LockstepSession} runs a frame only once its turns arrived; with
 * {@link CommandPoint#MOD_LOOP} the mod's own game loop applies the source and runs the frames.
 */
public final class GameSession {
    /**
     * Says where a session's commands come from.
     */
    public enum Kind {
        /**
         * A game on this machine: the local player's commands and the computer players' decisions.
         */
        LOCAL,
        /**
         * A recorded game: every command comes from the replay; there is no local player.
         */
        REPLAY,
        /**
         * A network game: every player's commands, the local player's included, arrive with the turns of the network session.
         */
        NETWORK
    }

    private final @NotNull Kind kind;
    private final @NotNull Game game;
    private final @Nullable Player localPlayer;
    private final @NotNull GameView view;
    private final @NotNull CommandPoint commandPoint;
    private final @NotNull ControllerCommandSource commandSource;
    private final @Nullable CommandSink commandSink;
    private final @Nullable LockstepSession lockstepSession;

    /**
     * Creates a {@link GameSession} instance.
     *
     * @param kind            where the session's commands come from
     * @param game            the game, whose frame runner already has the session's command source if the frame runner applies it
     * @param localPlayer     the player of the person at this machine, or {@code null} for a session without local player
     * @param view            the read-only view of the game
     * @param commandPoint    who applies the session's commands
     * @param commandSource   the game's command source over the session's controllers
     * @param commandSink     the sink of the local player's commands, or {@code null} for a session without local player
     * @param lockstepSession the engine lockstep session whose turns the frames wait for, or {@code null} for a session without one
     */
    GameSession(@NotNull Kind kind,
                @NotNull Game game,
                @Nullable Player localPlayer,
                @NotNull GameView view,
                @NotNull CommandPoint commandPoint,
                @NotNull ControllerCommandSource commandSource,
                @Nullable CommandSink commandSink,
                @Nullable LockstepSession lockstepSession) {
        this.kind            = kind;
        this.game            = game;
        this.localPlayer     = localPlayer;
        this.view            = view;
        this.commandPoint    = commandPoint;
        this.commandSource   = commandSource;
        this.commandSink     = commandSink;
        this.lockstepSession = lockstepSession;
    }

    /**
     * @return where the session's commands come from
     */
    public @NotNull Kind getKind() {
        return this.kind;
    }

    /**
     * @return the game; client code reads it only through {@link #getView} and changes it only through commands
     */
    public @NotNull Game getGame() {
        return this.game;
    }

    /**
     * @return the read-only view of the game
     */
    public @NotNull GameView getView() {
        return this.view;
    }

    /**
     * @return the player this client plays, or {@code null} for a replay and an observer
     */
    public @Nullable Player getLocalPlayer() {
        return this.localPlayer;
    }

    /**
     * @return who applies the session's commands: the game's frame runner or the mod's own game loop
     */
    public @NotNull CommandPoint getCommandPoint() {
        return this.commandPoint;
    }

    /**
     * Returns the game's command source over the session's controllers, the one path of the commands into the game. A replay recorder of the
     * game is set on it ({@link ControllerCommandSource#setRecorder}) before the first frame.
     *
     * @return the command source
     */
    public @NotNull ControllerCommandSource getCommandSource() {
        return this.commandSource;
    }

    /**
     * @return the sink of the local player's commands, or {@code null} for a replay
     */
    public @Nullable CommandSink getCommandSink() {
        return this.commandSink;
    }

    /**
     * @return the engine lockstep session of a network game (its waiting state and departures), or {@code null} for a session without one
     */
    public @Nullable LockstepSession getLockstepSession() {
        return this.lockstepSession;
    }


    /**
     * Runs one frame of the game if it may run: the frame runner applies the due commands and runs the frame's phases. A session with a
     * lockstep session first prepares the frame ({@link LockstepSession#prepareFrame}) and runs nothing while a participant's turn is missing.
     *
     * @return {@code true} if the frame ran, {@code false} otherwise (a participant's turn is missing)
     * @throws IllegalStateException if the mod's own game loop runs the session's frames ({@link CommandPoint#MOD_LOOP})
     */
    public boolean runFrame() {
        if (this.commandPoint != CommandPoint.FRAME_RUNNER) {
            throw Exceptions.newIllegalStateException("The own game loop of the mod runs the frames of this session ({0}).", this.commandPoint);
        }

        LockstepSession lockstep = this.lockstepSession;

        if (lockstep != null && !lockstep.prepareFrame(this.game)) {
            return false;
        }

        this.game.getFrameRunner().runFrame();

        return true;
    }
}
