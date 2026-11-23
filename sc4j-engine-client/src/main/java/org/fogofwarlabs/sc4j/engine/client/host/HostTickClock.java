package org.fogofwarlabs.sc4j.engine.client.host;

import org.jetbrains.annotations.NotNull;

/**
 * Reads the host's millisecond tick counter: the operating system's uptime clock that the platform's own display and input scheduling use. The
 * value is 32 bits wide and wraps, so only unsigned differences of two readings are meaningful.
 * <p>
 * The engine default ({@link #getDefault}) reads the Windows tick count on Windows and {@link MonotonicHostTickClock} elsewhere.
 */
public interface HostTickClock {
    /**
     * Creates a {@link HostTickClock} instance of the platform the client runs on.
     *
     * @return the created {@link HostTickClock} instance: the Windows tick count on Windows when its system function can be bound, a {@link MonotonicHostTickClock} otherwise
     */
    static @NotNull HostTickClock getDefault() {
        HostTickClock windows = WindowsHostTickClock.create();

        return windows != null ? windows : new MonotonicHostTickClock();
    }

    /**
     * Reads the tick counter.
     *
     * @return the milliseconds counter, wrapping at {@code 2^32}
     * @throws IllegalStateException if the platform call fails
     */
    int readTicks();
}
