package org.fogofwarlabs.sc4j.engine.mod;

import org.jetbrains.annotations.NotNull;

/**
 * Runs a mod's own application loop: window, devices, input and game sessions.
 * <p>
 * For a mod with its own application loop instead of the engine scenes: the runtime opens no window, it runs the registered application
 * instead of its own main loop and returns when the application returns. An engine-only mod registers its first scene and a
 * {@link HeadlessRunner} instead ({@link ModRegistration#registerFirstScene}).
 */
public interface ModApplication {
    /**
     * @return {@code true} if the application can run without a window, audio and input ({@code --headless}), {@code false} otherwise
     */
    boolean isHeadlessCapable();

    /**
     * Runs the application until the user or the launch request ends it.
     *
     * @param launch the launch request of the command line and the mod's identity
     * @throws ModException if the application refuses the launch request
     */
    void run(@NotNull ModLaunch launch) throws ModException;
}
