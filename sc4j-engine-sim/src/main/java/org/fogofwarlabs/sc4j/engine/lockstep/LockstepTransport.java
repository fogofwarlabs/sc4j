package org.fogofwarlabs.sc4j.engine.lockstep;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * Carries the turns of a lockstep session between its participants: the local participant sends one turn per turn step, and a receive takes
 * the next turn of every participant once all of them arrived (the all-turns barrier). The transport carries turn bytes unchanged; what a turn
 * holds is the session's format (the engine {@link CommandFrame}s, or a mod's own protocol).
 * <p>
 * The local participant's own turns come back to it through the receive like every other participant's, so every machine executes the same
 * turns in the same order. A turn sent now is received after the turns already in transit ({@link #getTurnsInTransit()}): the number of turns
 * the session keeps in transit is its latency.
 * <p>
 * Determinism contract: the received turns of one turn step are the same on every machine, in the same order (the transport's execution
 * order). A transport may use a clock for its timeouts and retransmissions; what it delivers does not depend on it.
 */
public interface LockstepTransport {
    /**
     * @return the local turns sent and not yet received back
     */
    int getTurnsInTransit();


    /**
     * Sends the local participant's next turn.
     *
     * @param turn the turn bytes
     */
    void sendTurn(byte @NotNull [] turn);

    /**
     * Takes the next turn of every participant once all of them arrived.
     *
     * @return the participants' turns in execution order, or {@code null} while a participant's turn is missing
     */
    @Nullable List<@NotNull PeerTurn> receiveTurns();

    /**
     * Takes the departures of the participants that the receives since the previous call no longer waited for: a departure is reported at
     * the first receive without a turn of the participant, on every machine the same turn step. A transport without departures reports none.
     *
     * @return the departures in the order of the receives, the participants' execution order within one receive; empty if there is none
     */
    default @NotNull List<@NotNull PeerDeparture> takeDepartures() {
        return List.of();
    }

    /**
     * @return the waiting state of the all-turns barrier (the awaited and overdue participants, the drop countdown), or {@code null} if the transport never drops one
     */
    default @Nullable WaitingPeers getWaitingPeers() {
        return null;
    }

    /**
     * @return the turn rate of the session and of the network it runs on, {@link TurnRate#UNSPECIFIED} for a transport that names none
     */
    default @NotNull TurnRate getTurnRate() {
        return TurnRate.UNSPECIFIED;
    }

    /**
     * Leaves the session: the other participants take the local participant's departure ({@link #takeDepartures()}) once they received every
     * turn sent before it. A transport without departures ignores it.
     *
     * @param reason the departure reason, the session's own code
     */
    default void leave(int reason) {
    }

    /**
     * Drops the overdue participants on a person's request, the waiting dialog's drop button. A transport without waiting state ignores it.
     *
     * @implSpec Requests the drop of every overdue participant of the waiting state ({@link WaitingPeers#requestDrop}); the transport drops
     *         them at its next receive.
     */
    default void dropWaitingPeers() {
        WaitingPeers waiting = this.getWaitingPeers();

        if (waiting == null) {
            return;
        }

        for (LockstepPeer peer : waiting.getOverduePeers()) {
            waiting.requestDrop(peer);
        }
    }
}
