package org.fogofwarlabs.sc4j.engine.net;

import org.fogofwarlabs.sc4j.base.exception.Exceptions;
import org.jetbrains.annotations.NotNull;

/**
 * Holds the timing of a {@link DatagramLockstepTransport} in milliseconds of its {@link NetworkClock}: how long a sent turn or notice waits
 * for its acknowledgement before it is sent again, after how long an awaited participant is overdue (a client shows its waiting dialog), and
 * after how long it is dropped.
 */
public final class TransportTimeouts {
    /**
     * The default resend interval: a few game loops.
     */
    public static final long DEFAULT_RESEND_MILLIS = 100L;
    /**
     * The default time after which an awaited participant is overdue.
     */
    public static final long DEFAULT_OVERDUE_MILLIS = 2000L;
    /**
     * The default time after which an awaited participant is dropped.
     */
    public static final long DEFAULT_DROP_MILLIS = 60000L;
    /**
     * The default timing.
     */
    public static final @NotNull TransportTimeouts DEFAULTS = new TransportTimeouts(DEFAULT_RESEND_MILLIS, DEFAULT_OVERDUE_MILLIS,
                                                                                    DEFAULT_DROP_MILLIS);

    private final long resendMillis;
    private final long overdueMillis;
    private final long dropMillis;

    /**
     * Creates a {@link TransportTimeouts} instance.
     *
     * @param resendMillis  the time a datagram waits for its acknowledgement before it is sent again, at least {@code 1}
     * @param overdueMillis the time after which an awaited participant is overdue, {@code 0} or more
     * @param dropMillis    the time after which an awaited participant is dropped, at least the overdue time
     * @throws IllegalArgumentException if the resend time is below {@code 1}, the overdue time is negative or the drop time is below the overdue time
     */
    public TransportTimeouts(long resendMillis, long overdueMillis, long dropMillis) {
        if (resendMillis < 1L || overdueMillis < 0L || dropMillis < overdueMillis) {
            throw Exceptions.newIllegalArgumentException("Invalid UDP transport timing: resend after {0} ms, overdue after {1} ms, dropped after {2} ms.",
                                                         resendMillis, overdueMillis, dropMillis);
        }

        this.resendMillis  = resendMillis;
        this.overdueMillis = overdueMillis;
        this.dropMillis    = dropMillis;
    }

    /**
     * @return the time a datagram waits for its acknowledgement before it is sent again
     */
    public long getResendMillis() {
        return this.resendMillis;
    }

    /**
     * @return the time after which an awaited participant is overdue
     */
    public long getOverdueMillis() {
        return this.overdueMillis;
    }

    /**
     * @return the time after which an awaited participant is dropped
     */
    public long getDropMillis() {
        return this.dropMillis;
    }


    /**
     * @return the timing, for diagnostics
     */
    @Override
    public @NotNull String toString() {
        return "TransportTimeouts[resend " + this.resendMillis + " ms, overdue " + this.overdueMillis + " ms, drop " + this.dropMillis + " ms]";
    }
}
