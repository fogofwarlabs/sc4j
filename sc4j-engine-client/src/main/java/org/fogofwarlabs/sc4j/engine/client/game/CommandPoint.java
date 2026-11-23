package org.fogofwarlabs.sc4j.engine.client.game;

import org.fogofwarlabs.sc4j.engine.controller.ControllerCommandSource;

/**
 * Says who applies a {@link GameSession}'s commands: the engine frame runner at each frame's command point, or the mod's own game loop at
 * its own command point. Either way the session's {@link ControllerCommandSource} is the one path of the commands into the game, applied once
 * per point.
 */
public enum CommandPoint {
    /**
     * The engine frame runner applies the session's command source at each frame's command point: the factory sets it as the game's command
     * source, and the client advances the game with {@link GameSession#runFrame()}.
     */
    FRAME_RUNNER,
    /**
     * The mod's own game loop applies the session's command source ({@link GameSession#getCommandSource()}) at its own command point, for
     * example before its own turn step:the game's frame runner gets no command source, so the commands are not asked a second time, and
     * the mod's loop runs the frames ({@link GameSession#runFrame()} is refused). A loop that applies the source more than once in one game
     * loop starts the loop of a set recorder more than once, so such a mod records through its own format.
     */
    MOD_LOOP
}
