package org.fogofwarlabs.sc4j.engine.client.host;

import org.jetbrains.annotations.NotNull;

/**
 * Reads and sets the speed of the system pointer for the running session, for a client whose options scale the mouse sensitivity. A speed set
 * this way is not saved to the user's settings; a client gives the original speed back when it loses the focus or ends.
 * <p>
 * The engine default ({@link #getDefault}) uses the operating system's pointer speed on Windows and {@link FixedSystemPointerSpeed} (no speed
 * to read or set) elsewhere.
 */
public interface SystemPointerSpeed {
    /**
     * The slowest pointer speed.
     */
    int MINIMUM_SPEED = 1;
    /**
     * The fastest pointer speed.
     */
    int MAXIMUM_SPEED = 20;
    /**
     * The speed a system starts with, the middle of the range.
     */
    int DEFAULT_SPEED = 10;

    /**
     * Creates a {@link SystemPointerSpeed} instance of the platform the client runs on: the Windows pointer speed on Windows when its system
     * function can be bound, a {@link FixedSystemPointerSpeed} otherwise.
     *
     * @return the created {@link SystemPointerSpeed} instance
     */
    static @NotNull SystemPointerSpeed getDefault() {
        SystemPointerSpeed windows = WindowsSystemPointerSpeed.create();

        return windows != null ? windows : new FixedSystemPointerSpeed();
    }

    /**
     * Reads the current system pointer speed.
     *
     * @return the speed from {@link #MINIMUM_SPEED} to {@link #MAXIMUM_SPEED}, or {@code -1} if it cannot be read
     * @throws IllegalStateException if the platform call fails
     */
    int readSpeed();

    /**
     * Sets the system pointer speed for the session (not saved).
     *
     * @param speed the speed from {@link #MINIMUM_SPEED} to {@link #MAXIMUM_SPEED}
     * @throws IllegalStateException if the platform refuses the speed or the call fails
     */
    void writeSpeed(int speed);
}
