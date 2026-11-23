package org.fogofwarlabs.sc4j.engine.client.input;

import org.fogofwarlabs.sc4j.base.exception.Exceptions;
import org.jetbrains.annotations.NotNull;

import java.util.Arrays;

/**
 * Pairs presses of the left, right and middle buttons at the same pixel within a fixed interval into double presses. The position is shared by
 * the three buttons, the press times are tracked per button; a double press moves that button's time back by one interval, so the next presses
 * form new pairs. The other buttons never double-press and do not change the state.
 */
public final class DoublePressDetector {
    /**
     * The default double-press interval in milliseconds.
     */
    public static final long DEFAULT_TIME_LIMIT = 500;

    /**
     * The buttons tracked separately: left, right, middle.
     */
    private static final int TRACKED_BUTTON_COUNT = 3;
    /**
     * The press time of a button without an earlier press: far enough in the past that no press pairs with it.
     */
    private static final long NO_PRESS_TIME = Long.MIN_VALUE / 2;

    private final long timeLimit;

    private final long @NotNull [] lastTimes;
    private int lastX;
    private int lastY;

    /**
     * Creates a {@link DoublePressDetector} instance with the {@link #DEFAULT_TIME_LIMIT} interval and without earlier presses.
     */
    public DoublePressDetector() {
        this(DEFAULT_TIME_LIMIT);
    }

    /**
     * Creates a {@link DoublePressDetector} instance without earlier presses.
     *
     * @param timeLimit the double-press interval in milliseconds; a second press must come sooner
     * @throws IllegalArgumentException if the interval is not positive
     */
    public DoublePressDetector(long timeLimit) {
        if (timeLimit <= 0) {
            throw Exceptions.newIllegalArgumentException("Invalid double-press interval: {0}", timeLimit);
        }

        this.timeLimit = timeLimit;

        this.lastTimes = new long[TRACKED_BUTTON_COUNT];

        Arrays.fill(this.lastTimes, NO_PRESS_TIME);

        this.lastX = Integer.MIN_VALUE;
        this.lastY = Integer.MIN_VALUE;
    }

    /**
     * @return the double-press interval in milliseconds
     */
    public long getTimeLimit() {
        return this.timeLimit;
    }


    /**
     * Registers a button press and tells whether it is a double press: a press of the left, right or middle button at the pixel of the last
     * press, sooner than the interval after this button's last press.
     *
     * @param button the button
     * @param time   the press time in milliseconds
     * @param x      the pixel column the presses compare
     * @param y      the pixel row the presses compare
     * @return {@code true} if the press is a double press, {@code false} otherwise
     */
    public boolean press(@NotNull PointerButton button, long time, int x, int y) {
        int index = button.ordinal();

        if (index >= TRACKED_BUTTON_COUNT) {
            return false;
        }

        boolean samePixel = x == this.lastX && y == this.lastY;
        boolean doubled = samePixel && time - this.lastTimes[index] < this.timeLimit;

        this.lastX            = x;
        this.lastY            = y;
        this.lastTimes[index] = doubled ? time - this.timeLimit : time;

        return doubled;
    }
}
