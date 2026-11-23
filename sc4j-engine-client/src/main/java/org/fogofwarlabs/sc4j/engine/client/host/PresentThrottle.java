package org.fogofwarlabs.sc4j.engine.client.host;

import org.fogofwarlabs.sc4j.base.exception.Exceptions;

/**
 * Decides which passes of a frame loop draw and present while the simulation runs faster than the display: while the throttle is active, a
 * pass presents only when at least the draw interval passed since the previous presented pass (or since the start), and the other passes run
 * only input and simulation, so several simulation steps fall between two presented frames. While the throttle is inactive every pass presents.
 * A frame rate limit ({@link #setFrameRateLimit}) lengthens the draw interval to the limit's period when that is longer, so an active throttle
 * never presents more often than the limit.
 * <p>
 * Only presentation is decided; the simulation advances on its own clock.
 */
public final class PresentThrottle {
    private static final int MILLISECONDS_PER_SECOND = 1000;

    private final int drawIntervalMilliseconds;

    private int frameRateLimit;

    private boolean active;
    private long lastPresentMilliseconds;

    /**
     * Creates an inactive {@link PresentThrottle} instance.
     *
     * @param drawIntervalMilliseconds the shortest time between two presented passes while the throttle is active
     * @throws IllegalArgumentException if the interval is not positive
     */
    public PresentThrottle(int drawIntervalMilliseconds) {
        if (drawIntervalMilliseconds <= 0) {
            throw Exceptions.newIllegalArgumentException("The draw interval must be positive: {0}", drawIntervalMilliseconds);
        }

        this.drawIntervalMilliseconds = drawIntervalMilliseconds;

        this.frameRateLimit = 0;

        this.active                  = false;
        this.lastPresentMilliseconds = 0;
    }

    /**
     * @return the shortest time between two presented passes while the throttle is active
     */
    public int getDrawIntervalMilliseconds() {
        return this.drawIntervalMilliseconds;
    }

    /**
     * @return the frame rate limit the presented passes keep while the throttle is active, {@code 0} for none
     */
    public int getFrameRateLimit() {
        return this.frameRateLimit;
    }

    /**
     * Sets the frame rate limit the presented passes keep while the throttle is active: the time between two presented passes is at least the
     * limit's period, rounded up to whole milliseconds, when that is longer than the draw interval.
     *
     * @param frameRateLimit the frames per second, {@code 0} for none
     * @throws IllegalArgumentException if the limit is negative
     */
    public void setFrameRateLimit(int frameRateLimit) {
        if (frameRateLimit < 0) {
            throw Exceptions.newIllegalArgumentException("The frame rate limit must not be negative: {0}", frameRateLimit);
        }

        this.frameRateLimit = frameRateLimit;
    }

    /**
     * Returns the shortest time between two presented passes while the throttle is active: the draw interval, or the frame rate limit's period
     * rounded up to whole milliseconds when that is longer.
     *
     * @return the present interval in milliseconds
     */
    public int getPresentIntervalMilliseconds() {
        int limit = this.frameRateLimit;

        return limit == 0 ? this.drawIntervalMilliseconds : Math.max(this.drawIntervalMilliseconds, Math.ceilDiv(MILLISECONDS_PER_SECOND, limit));
    }

    /**
     * @return {@code true} if passes present only at the draw interval, {@code false} if every pass presents
     */
    public boolean isActive() {
        return this.active;
    }

    /**
     * Activates or deactivates the throttle. Activation starts the draw interval at the given time, so the first presented pass comes one
     * interval later; a call that does not change the state does nothing.
     *
     * @param active              whether passes present only at the draw interval
     * @param currentMilliseconds the host clock
     */
    public void setActive(boolean active, long currentMilliseconds) {
        if (active == this.active) {
            return;
        }

        this.active                  = active;
        this.lastPresentMilliseconds = currentMilliseconds;
    }


    /**
     * Tests whether the current pass presents, and starts the next draw interval when it does. A pass presents while the throttle is inactive,
     * or when the present interval ({@link #getPresentIntervalMilliseconds()}) passed since the previous presented pass.
     *
     * @param currentMilliseconds the host clock
     * @return {@code true} if the current pass presents, {@code false} otherwise
     */
    public boolean takePresentDue(long currentMilliseconds) {
        if (!this.active) {
            return true;
        }

        if (currentMilliseconds - this.lastPresentMilliseconds < this.getPresentIntervalMilliseconds()) {
            return false;
        }

        this.lastPresentMilliseconds = currentMilliseconds;

        return true;
    }
}
