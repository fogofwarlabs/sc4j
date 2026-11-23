package org.fogofwarlabs.sc4j.beaconlink.client;

import org.fogofwarlabs.sc4j.beaconlink.protocol.message.NodeEndpoint;
import org.fogofwarlabs.sc4j.beaconlink.protocol.message.SealedMessage;
import org.fogofwarlabs.sc4j.engine.net.beaconlink.community.Whisper;
import org.fogofwarlabs.sc4j.engine.net.beaconlink.community.WhisperResult;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.*;

/**
 * Holds one whisper a client sends: one sealed copy for each live connection of the target (each sealed for that connection's whisper key and
 * sent to that connection's home peers, from its presence) and the state of the requests to them. Every home peer of a copy gets the copy
 * until one answers that it delivered it, every one answered that it holds no registration of the connection, or the attempts are used up.
 * The whisper is delivered as soon as one copy is; the other copies are still sent until each has its outcome, so that every connection of
 * the target gets the whisper.
 */
final class ClientWhisper {
    /**
     * Holds one sealed copy of the whisper for one connection of the target: the message, the connection's home peers, the ones that
     * refused it, and whether it was delivered.
     */
    static final class Copy {
        private final @NotNull SealedMessage message;
        private final @NotNull List<@NotNull NodeEndpoint> homePeers;
        private final @NotNull Set<@NotNull NodeEndpoint> refusals;

        private boolean delivered;

        /**
         * Creates a {@link Copy} instance.
         *
         * @param message   the copy sealed for the connection
         * @param homePeers the connection's home peers
         */
        Copy(@NotNull SealedMessage message, @NotNull List<@NotNull NodeEndpoint> homePeers) {
            this.message   = message;
            this.homePeers = List.copyOf(homePeers);
            this.refusals  = new LinkedHashSet<>();

            this.delivered = false;
        }

        private boolean isRefused() {
            return this.refusals.size() >= this.homePeers.size();
        }
    }

    /**
     * Holds a request of the whisper: the copy it carried and the home peer it went to.
     */
    private static final class Request {
        private final @NotNull Copy copy;
        private final @NotNull NodeEndpoint homePeer;

        private Request(@NotNull Copy copy, @NotNull NodeEndpoint homePeer) {
            this.copy     = copy;
            this.homePeer = homePeer;
        }
    }


    private final @NotNull Whisper whisper;
    private final @NotNull List<@NotNull Copy> copies;
    private final @NotNull Map<@NotNull Long, @NotNull Request> requests;

    private int sendsLeft;
    private long nextSendMillis;
    private boolean exhausted;
    private @Nullable WhisperResult result;
    private boolean reported;

    /**
     * Creates a {@link ClientWhisper} instance; the first maintenance sends it.
     *
     * @param whisper   the whisper as the session hands it out
     * @param copies    a copy for every live connection of the target; none if the target is not online
     * @param attempts  how often each copy is sent to each of its home peers
     * @param nowMillis the protocol clock time
     */
    ClientWhisper(@NotNull Whisper whisper, @NotNull List<@NotNull Copy> copies, int attempts, long nowMillis) {
        this.whisper  = whisper;
        this.copies   = new ArrayList<>();
        this.requests = new LinkedHashMap<>();

        for (Copy copy : copies) {
            if (!copy.homePeers.isEmpty()) {
                this.copies.add(copy);
            }
        }

        this.sendsLeft      = attempts;
        this.nextSendMillis = nowMillis;
        this.exhausted      = false;
        this.result         = this.copies.isEmpty() ? WhisperResult.NOT_ONLINE : null;
        this.reported       = false;
    }

    /**
     * @return the whisper as the session hands it out
     */
    @NotNull Whisper getWhisper() {
        return this.whisper;
    }

    /**
     * Takes the outcome once it is known: delivered as soon as one copy is, not online when no copy can be.
     *
     * @return the outcome, or {@code null} while it is not known or after it was taken
     */
    @Nullable WhisperResult takeResult() {
        if (this.result == null || this.reported) {
            return null;
        }

        this.reported = true;

        return this.result;
    }

    /**
     * Checks whether the whisper is finished. A copy has its outcome once it is delivered, refused by every home peer of its connection or out
     * of attempts.
     *
     * @return {@code true} if every copy has its outcome and the outcome was taken, {@code false} otherwise
     */
    boolean isFinished() {
        return this.reported && (this.exhausted || !this.isPending());
    }

    /**
     * Finds the home peer a request went to.
     *
     * @param requestNumber the request number of a reply
     * @return the home peer, or {@code null} if no request of this whisper has the number
     */
    @Nullable NodeEndpoint findRequest(long requestNumber) {
        Request request = this.requests.get(requestNumber);

        return request == null ? null : request.homePeer;
    }


    /**
     * Sends every copy without an outcome to each of its home peers that did not refuse it, when a send is due. When the attempts are used up,
     * the copies without an outcome have none: the whisper is not online unless a copy was delivered.
     *
     * @param client    the client that sends
     * @param nowMillis the protocol clock time
     */
    void maintain(@NotNull BeaconLinkClient client, long nowMillis) {
        if (this.exhausted || nowMillis < this.nextSendMillis || !this.isPending()) {
            return;
        }

        if (this.sendsLeft <= 0) {
            this.exhausted = true;

            if (this.result == null) {
                this.result = WhisperResult.NOT_ONLINE;
            }

            return;
        }

        this.sendsLeft--;
        this.nextSendMillis = nowMillis + client.getConfiguration().getResendMillis();

        for (Copy copy : this.copies) {
            if (copy.delivered) {
                continue;
            }

            for (NodeEndpoint homePeer : copy.homePeers) {
                if (!copy.refusals.contains(homePeer)) {
                    long number = client.sendDirect(copy.message, homePeer.getAddress());

                    this.requests.put(number, new Request(copy, homePeer));
                }
            }
        }
    }

    private boolean isPending() {
        for (Copy copy : this.copies) {
            if (!copy.delivered && !copy.isRefused()) {
                return true;
            }
        }

        return false;
    }

    /**
     * Takes the answer of a home peer to a request of the whisper.
     *
     * @param requestNumber the request number of the answer
     * @param delivered     whether the home peer delivered the copy
     */
    void handleAnswer(long requestNumber, boolean delivered) {
        Request request = this.requests.get(requestNumber);

        if (request == null) {
            return;
        }

        if (delivered) {
            request.copy.delivered = true;

            if (this.result == null) {
                this.result = WhisperResult.DELIVERED;
            }

            return;
        }

        request.copy.refusals.add(request.homePeer);

        boolean refused = true;

        for (Copy copy : this.copies) {
            refused &= !copy.delivered && copy.isRefused();
        }

        if (refused && this.result == null) {
            this.result = WhisperResult.NOT_ONLINE;
        }
    }
}
