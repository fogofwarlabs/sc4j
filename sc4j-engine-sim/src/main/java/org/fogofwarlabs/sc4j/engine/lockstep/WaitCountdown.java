package org.fogofwarlabs.sc4j.engine.lockstep;

import org.fogofwarlabs.sc4j.base.exception.Exceptions;

/**
 * Counts down the waiting dialog of a lockstep client in whole seconds while its transport waits for overdue participants. The transport
 * advances the countdown at every receive that waits for an overdue participant and closes it at the receive that takes every turn.
 * <p>
 * The first advance opens the dialog with the full count; when an earlier dialog closed at most the resume time before, the new one continues
 * from that dialog's last count less the resume reduction, at least {@code 0}. Every later advance more than one step time after the previous
 * count counts one second down, down to {@code 0}, at which the dialog allows a person to drop the overdue participants
 * ({@link LockstepTransport#dropWaitingPeers()}). The countdown never drops anybody by itself.
 * <p>
 * Times are milliseconds of the transport's clock. The count may be read on any thread; the transport advances and closes the countdown on
 * the thread that receives.
 */
public final class WaitCountdown {
    /**
     * The count of a closed dialog.
     */
    public static final int CLOSED = -1;

    private final int seconds;
    private final long stepMillis;
    private final long resumeMillis;
    private final int resumeReductionSeconds;

    private volatile int count;
    private int lastCount;
    private long stepTime;
    private long closeTime;
    private boolean closedBefore;

    /**
     * Creates a {@link WaitCountdown} instance whose dialog is closed.
     *
     * @param seconds                the full count of a dialog, {@code 0} or more
     * @param stepMillis             the time after which an advance counts one second down, {@code 0} or more
     * @param resumeMillis           the time after a dialog closed within which a new dialog continues its count, {@code 0} or more
     * @param resumeReductionSeconds the seconds a continued count starts below the last count, {@code 0} or more
     * @throws IllegalArgumentException if a value is negative
     */
    public WaitCountdown(int seconds, long stepMillis, long resumeMillis, int resumeReductionSeconds) {
        if (seconds < 0 || stepMillis < 0L || resumeMillis < 0L || resumeReductionSeconds < 0) {
            throw Exceptions.newIllegalArgumentException("Invalid wait countdown: {0} s, step {1} ms, resume {2} ms less {3} s.", seconds, stepMillis,
                                                         resumeMillis, resumeReductionSeconds);
        }

        this.seconds                = seconds;
        this.stepMillis             = stepMillis;
        this.resumeMillis           = resumeMillis;
        this.resumeReductionSeconds = resumeReductionSeconds;

        this.count        = CLOSED;
        this.lastCount    = 0;
        this.stepTime     = 0L;
        this.closeTime    = 0L;
        this.closedBefore = false;
    }

    /**
     * @return the seconds the dialog shows, {@code 0} once a person may drop the overdue participants, or {@link #CLOSED} while no dialog shows
     */
    public int getCount() {
        return this.count;
    }

    /**
     * @return {@code true} if the dialog shows, {@code false} otherwise
     */
    public boolean isOpen() {
        return this.count != CLOSED;
    }

    /**
     * @return {@code true} if the dialog shows its count {@code 0}, at which a person may drop the overdue participants, {@code false} otherwise
     */
    public boolean isExpired() {
        return this.count == 0;
    }


    /**
     * Advances the dialog at a receive that waits for an overdue participant: opens it if it is closed, otherwise counts one second down when
     * more than the step time passed since the previous count.
     *
     * @param now the time of the transport's clock
     * @return {@code true} if the advance opened the dialog, {@code false} otherwise
     */
    public boolean advance(long now) {
        boolean opened = this.count == CLOSED;

        if (opened) {
            boolean resumed = this.closedBefore && now - this.closeTime <= this.resumeMillis;
            int start = resumed ? Math.max(this.lastCount - this.resumeReductionSeconds, 0) : this.seconds;

            this.count    = Math.min(start, this.seconds);
            this.stepTime = now;
        } else if (now - this.stepTime > this.stepMillis) {
            this.stepTime = now;

            if (this.count > 0) {
                this.count--;
            }
        }

        this.lastCount = this.count;

        return opened;
    }

    /**
     * Closes the dialog at a receive that took every turn.
     *
     * @param now the time of the transport's clock
     * @return {@code true} if the dialog showed, {@code false} if it was closed already
     */
    public boolean close(long now) {
        if (this.count == CLOSED) {
            return false;
        }

        this.count        = CLOSED;
        this.closeTime    = now;
        this.closedBefore = true;

        return true;
    }
}
