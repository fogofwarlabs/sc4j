package org.fogofwarlabs.sc4j.engine.lockstep;

import org.fogofwarlabs.sc4j.base.exception.Exceptions;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * Tracks the participants a lockstep session waits for at its all-turns barrier: since when each one has been awaited, which ones are
 * overdue (a client shows them in its waiting dialog), the countdown until they are dropped, and the drops a person requested. The transport
 * that owns the state updates it at every receive that misses a turn, with the time of its clock in milliseconds, and drops the participants
 * that are due ({@link #takeDueDrops()}). The dialog shows the seconds left ({@link #getCountdownSeconds()}): the count of the
 * transport's {@link WaitCountdown} when it has one, otherwise the time until the first overdue participant is dropped.
 * <p>
 * The waiting state is not part of the simulation: a drop changes the game only through the transport's departure, which every machine takes
 * at the same turn step.
 */
public final class WaitingPeers {
    private static final long MILLISECONDS_PER_SECOND = 1000L;

    /**
     * Holds one awaited participant.
     */
    private static final class Waiting {
        private final @NotNull LockstepPeer peer;
        private final long since;

        private boolean dropRequested;

        private Waiting(@NotNull LockstepPeer peer, long since) {
            this.peer  = peer;
            this.since = since;

            this.dropRequested = false;
        }
    }

    private final long overdueMillis;
    private final long dropMillis;
    private final @Nullable WaitCountdown countdown;

    private final @NotNull List<@NotNull Waiting> waiting;
    private long updateTime;

    /**
     * Creates a {@link WaitingPeers} instance that awaits nobody, whose waiting dialog counts down to the drop time.
     *
     * @param overdueMillis the time after which an awaited participant is overdue, {@code 0} or more
     * @param dropMillis    the time after which an awaited participant is dropped, at least the overdue time
     * @throws IllegalArgumentException if a time is negative or the drop time is below the overdue time
     */
    public WaitingPeers(long overdueMillis, long dropMillis) {
        this(overdueMillis, dropMillis, null);
    }

    /**
     * Creates a {@link WaitingPeers} instance that awaits nobody.
     *
     * @param overdueMillis the time after which an awaited participant is overdue, {@code 0} or more
     * @param dropMillis    the time after which an awaited participant is dropped, at least the overdue time
     * @param countdown     the countdown of the waiting dialog, which the transport advances and closes, or {@code null} for a dialog that counts down to the drop time
     * @throws IllegalArgumentException if a time is negative or the drop time is below the overdue time
     */
    public WaitingPeers(long overdueMillis, long dropMillis, @Nullable WaitCountdown countdown) {
        if (overdueMillis < 0L || dropMillis < overdueMillis) {
            throw Exceptions.newIllegalArgumentException("Invalid waiting times: overdue after {0} ms, dropped after {1} ms.", overdueMillis,
                                                         dropMillis);
        }

        this.overdueMillis = overdueMillis;
        this.dropMillis    = dropMillis;
        this.countdown     = countdown;

        this.waiting    = new ArrayList<>();
        this.updateTime = 0L;
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
     * @return {@code true} if the barrier awaits a participant, {@code false} otherwise
     */
    public boolean isWaiting() {
        return !this.waiting.isEmpty();
    }

    /**
     * @return the awaited participants in the order they were first awaited
     */
    public @NotNull List<@NotNull LockstepPeer> getAwaitedPeers() {
        List<LockstepPeer> peers = new ArrayList<>(this.waiting.size());

        for (Waiting entry : this.waiting) {
            peers.add(entry.peer);
        }

        return peers;
    }

    /**
     * @return the awaited participants that were overdue at the latest update, in the order they were first awaited
     */
    public @NotNull List<@NotNull LockstepPeer> getOverduePeers() {
        List<LockstepPeer> peers = new ArrayList<>(this.waiting.size());

        for (Waiting entry : this.waiting) {
            if (this.updateTime - entry.since >= this.overdueMillis) {
                peers.add(entry.peer);
            }
        }

        return peers;
    }

    /**
     * @return the time from the latest update until the first overdue participant is dropped, {@code 0} or more, or {@code -1} if no participant is overdue
     */
    public long getCountdownMillis() {
        long countdown = -1L;

        for (Waiting entry : this.waiting) {
            long elapsed = this.updateTime - entry.since;

            if (elapsed >= this.overdueMillis) {
                long remaining = Math.max(0L, this.dropMillis - elapsed);

                countdown = countdown < 0L ? remaining : Math.min(countdown, remaining);
            }
        }

        return countdown;
    }

    /**
     * Returns the seconds the waiting dialog shows; without a {@link WaitCountdown}, the time from the latest update until the first overdue
     * participant is dropped, rounded up to whole seconds.
     *
     * @return the seconds the waiting dialog shows, {@code 0} once the overdue participants may be dropped, {@link WaitCountdown#CLOSED} while no dialog shows
     */
    public int getCountdownSeconds() {
        WaitCountdown dialog = this.countdown;

        if (dialog != null) {
            return dialog.getCount();
        }

        long countdownMillis = this.getCountdownMillis();

        return countdownMillis < 0L ? WaitCountdown.CLOSED : (int) Math.ceilDiv(countdownMillis, MILLISECONDS_PER_SECOND);
    }


    /**
     * Updates the awaited participants after a receive that missed a turn: a participant awaited before keeps its start, a new one starts
     * now, and one no longer missing is no longer awaited.
     *
     * @param awaited the participants the receive missed
     * @param now     the time of the transport's clock
     */
    public void update(@NotNull List<@NotNull LockstepPeer> awaited, long now) {
        this.update(awaited, now, now);
    }

    /**
     * Updates the awaited participants after a receive that missed a turn, for a transport that counts the wait of its barrier from an
     * earlier time (for example its last receive that took every turn): a participant awaited before keeps its start, a new one starts at
     * the wait's start, and one no longer missing is no longer awaited.
     *
     * @param awaited   the participants the receive missed
     * @param waitStart the time of the transport's clock from which a newly awaited participant counts, at most {@code now}
     * @param now       the time of the transport's clock
     */
    public void update(@NotNull List<@NotNull LockstepPeer> awaited, long waitStart, long now) {
        this.waiting.removeIf(entry -> !awaited.contains(entry.peer));

        for (LockstepPeer peer : awaited) {
            if (this.find(peer) == null) {
                this.waiting.add(new Waiting(peer, waitStart));
            }
        }

        this.updateTime = now;
    }

    /**
     * Ends the waiting after a receive that took every turn.
     */
    public void clear() {
        this.waiting.clear();
    }

    /**
     * Requests the drop of an overdue participant before its countdown ends, for a person's drop button; the transport drops it at its next
     * receive.
     *
     * @param peer the participant
     * @return {@code true} if the participant is overdue and will be dropped, {@code false} otherwise
     */
    public boolean requestDrop(@NotNull LockstepPeer peer) {
        Waiting entry = this.find(peer);

        if (entry == null || this.updateTime - entry.since < this.overdueMillis) {
            return false;
        }

        entry.dropRequested = true;

        return true;
    }

    /**
     * Takes the participants that are due to be dropped at the latest update: awaited for the drop time, or overdue with a requested drop.
     * They are no longer awaited afterwards.
     *
     * @return the participants to drop, in the order they were first awaited
     */
    public @NotNull List<@NotNull LockstepPeer> takeDueDrops() {
        List<LockstepPeer> due = new ArrayList<>();

        for (Waiting entry : this.waiting) {
            if (entry.dropRequested || this.updateTime - entry.since >= this.dropMillis) {
                due.add(entry.peer);
            }
        }

        this.waiting.removeIf(entry -> due.contains(entry.peer));

        return due;
    }

    private @Nullable Waiting find(@NotNull LockstepPeer peer) {
        for (Waiting entry : this.waiting) {
            if (entry.peer == peer) {
                return entry;
            }
        }

        return null;
    }
}
