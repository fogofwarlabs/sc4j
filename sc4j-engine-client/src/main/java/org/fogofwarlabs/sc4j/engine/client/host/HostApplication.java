package org.fogofwarlabs.sc4j.engine.client.host;

import org.jetbrains.annotations.NotNull;

import java.io.IOException;

/**
 * Runs the frames of a client on a {@link ClientHost}: the host calls {@link #beginFrame}, polls the window events (which
 * reach the application through its window callbacks), calls {@link #runFrame} and then paces a presented frame under
 * {@link #getFrameRateLimit}.
 */
public interface HostApplication {
    /**
     * Called at the start of every frame, before the window events are polled.
     */
    default void beginFrame() {
    }

    /**
     * Runs one frame: input, simulation, drawing and presenting.
     *
     * @return what the frame did
     * @throws IOException if the frame cannot read or write a file it needs
     */
    @NotNull FrameResult runFrame() throws IOException;

    /**
     * Returns the frame rate limit of the frame just run. It is read after {@link #runFrame} returned a presented frame.
     *
     * @return the frames per second the host paces to, or {@code 0} to present without waiting (for example while the display's vertical sync paces the frames)
     */
    int getFrameRateLimit();
}
