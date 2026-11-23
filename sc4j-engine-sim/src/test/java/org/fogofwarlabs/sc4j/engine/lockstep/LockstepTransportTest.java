package org.fogofwarlabs.sc4j.engine.lockstep;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.List;

/**
 * Tests the defaults of the lockstep transport model: a transport names no turn rate, and the drop button requests the drop of the overdue
 * participants of its waiting state only.
 */
final class LockstepTransportTest {
    private static final long OVERDUE_MILLIS = 100L;
    private static final long DROP_MILLIS = 500L;

    private static @NotNull LockstepTransport createTransport(@Nullable WaitingPeers waiting) {
        return new LockstepTransport() {
            /**
             * @return the local turns sent and not yet received back
             */
            @Override
            public int getTurnsInTransit() {
                return 0;
            }

            /**
             * @return the waiting state of the all-turns barrier (the awaited and overdue participants, the drop countdown), or {@code null} if the transport never drops one
             */
            @Override
            public @Nullable WaitingPeers getWaitingPeers() {
                return waiting;
            }


            /**
             * Sends the local participant's next turn.
             *
             * @param turn the turn bytes
             */
            @Override
            public void sendTurn(byte @NotNull [] turn) {
            }

            /**
             * Takes the next turn of every participant once all of them arrived.
             *
             * @return the participants' turns in execution order, or {@code null} while a participant's turn is missing
             */
            @Override
            public @Nullable List<@NotNull PeerTurn> receiveTurns() {
                return null;
            }
        };
    }

    @Test
    void drop_waiting_peers_should_request_the_drop_of_the_overdue_peers_only() {
        LockstepPeer overdue = new LockstepPeer(0);
        LockstepPeer awaited = new LockstepPeer(1);
        WaitingPeers waiting = new WaitingPeers(OVERDUE_MILLIS, DROP_MILLIS);
        LockstepTransport transport = createTransport(waiting);

        waiting.update(List.of(overdue), 0L);
        waiting.update(List.of(overdue, awaited), OVERDUE_MILLIS);
        transport.dropWaitingPeers();

        Assertions.assertEquals(List.of(overdue), waiting.takeDueDrops());
        Assertions.assertEquals(List.of(awaited), waiting.getAwaitedPeers());
        Assertions.assertSame(TurnRate.UNSPECIFIED, transport.getTurnRate());
        Assertions.assertEquals(0, transport.getTurnRate().getTurnsPerSecond());

        // A transport without waiting state ignores the button.
        createTransport(null).dropWaitingPeers();
    }
}
