package org.fogofwarlabs.sc4j.beaconlink.peer.reachability;

import org.fogofwarlabs.sc4j.base.log.Logger;
import org.fogofwarlabs.sc4j.beaconlink.peer.DatagramSink;
import org.fogofwarlabs.sc4j.beaconlink.peer.DropReason;
import org.fogofwarlabs.sc4j.beaconlink.peer.configuration.PeerConfiguration;
import org.fogofwarlabs.sc4j.beaconlink.peer.limit.RateLimiter;
import org.fogofwarlabs.sc4j.beaconlink.peer.routing.PeerRouting;
import org.fogofwarlabs.sc4j.beaconlink.protocol.coordinate.CoordinateEstimator;
import org.fogofwarlabs.sc4j.beaconlink.protocol.identity.IdentityKey;
import org.fogofwarlabs.sc4j.beaconlink.protocol.message.*;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.random.RandomGenerator;

/**
 * Runs a peer's reachability self-test and its part in the self-tests of other peers.
 * <p>
 * As requester, the peer asks up to two random known peers (helpers) for a dial-back; a helper forwards the request with the requester's observed
 * address to a third known peer, which sends an unrequested probe there. The request is padded like a ping, so that a newcomer can ask its seed at
 * once, before the seed has greeted it and before it has contacted anybody else. A probe that arrives from an address the peer never sent to proves
 * that strangers reach the peer ({@link Reachability#REACHABLE}); a probe from an address it sent to proves nothing; an accepted dial-back whose
 * probe does not arrive in time means that a NAT or a firewall drops strangers ({@link Reachability#NOT_REACHABLE}). The first unrequested hello from
 * an address the peer never sent to proves reachability as well: the only proof for the very first peer, which has no helper. A reachable peer stays
 * reachable; any other result is tested again later.
 * <p>
 * As helper, the peer serves any padded dial-back request whose sender's card carries a valid proof: its answer and the probe it causes are
 * shorter than the request, like the answer to a ping, and every probe costs the requester a peer ID. As third peer, it probes only for a
 * helper known at its proved address; probes to one IP address are rate limited, and a probe is shorter than the forwarded request.
 * <p>
 * The peer remembers the addresses its socket sent to (bounded, for longer than a NAT keeps a mapping). A NAT that filters by host lets
 * every port of a contacted host through, so an address counts as contacted by its IP address; on the loopback address, where no NAT is
 * between the sockets, every port counts by itself. When the memory overflows with addresses younger than its time, nothing counts as
 * unrequested until that time has passed.
 * <p>
 * A leaf-only peer ({@link PeerConfiguration#isLeafOnly()}) is {@link Reachability#NOT_REACHABLE} from the start: it never asks for a
 * dial-back and takes neither a probe nor an unrequested hello as a proof; it still serves the self-tests of other peers.
 */
public final class ReachabilityCheck {
    private static final @NotNull Logger LOGGER = Logger.of(ReachabilityCheck.class);
    private static final int HELPERS_PER_TEST = 2;
    private static final int PROBE_BURST = 4;
    private static final int PROBES_PER_SECOND = 1;
    private static final int ANY_PORT = 0;
    private static final long REQUEST_NUMBER_MASK = 0xffffffffL;

    /**
     * Holds one dial-back request waiting for its helper's answer and its probe.
     */
    private static final class PendingDialBack {
        private final @NotNull NodeEndpoint helper;
        private final long nonce;

        private long deadlineMillis;
        private boolean accepted;

        private PendingDialBack(@NotNull NodeEndpoint helper, long nonce, long deadlineMillis) {
            this.helper = helper;
            this.nonce  = nonce;

            this.deadlineMillis = deadlineMillis;
            this.accepted       = false;
        }
    }

    private static @NotNull InetSocketAddress toContactKey(@NotNull InetSocketAddress address) {
        InetAddress host = address.getAddress();

        return host.isLoopbackAddress() ? address : new InetSocketAddress(host, ANY_PORT);
    }


    private final @NotNull PeerConfiguration configuration;
    private final @NotNull MessageEncoder encoder;
    private final long proofNonce;
    private final @NotNull CoordinateEstimator coordinates;
    private final @NotNull DatagramSink sink;
    private final @NotNull RandomGenerator random;

    private final @NotNull LinkedHashMap<@NotNull InetSocketAddress, @NotNull Long> contacted;
    private final @NotNull LinkedHashMap<@NotNull Long, @NotNull PendingDialBack> pending;
    private final @NotNull RateLimiter<@NotNull InetAddress> probeLimits;
    private long contactOverflowUntilMillis;
    private long nextTestMillis;
    private boolean tested;
    private long nextRequestNumber;

    private @NotNull Reachability reachability;
    private @Nullable InetSocketAddress observedAddress;

    /**
     * Creates a {@link ReachabilityCheck} instance: not verified, nothing contacted, the first test due at once.
     *
     * @param configuration the peer's limits and timings
     * @param encoder       the encoder of the peer's messages
     * @param proofNonce    the peer's proof nonce, sent in its card
     * @param coordinates   the estimator of the peer's network coordinate, sent in its card
     * @param sink          the peer's socket
     * @param random        the source of nonces and helper choices
     */
    public ReachabilityCheck(@NotNull PeerConfiguration configuration, @NotNull MessageEncoder encoder, long proofNonce,
                             @NotNull CoordinateEstimator coordinates, @NotNull DatagramSink sink, @NotNull RandomGenerator random) {
        this.configuration = configuration;
        this.encoder       = encoder;
        this.proofNonce    = proofNonce;
        this.coordinates   = coordinates;
        this.sink          = sink;
        this.random        = random;

        this.contacted                  = new LinkedHashMap<>();
        this.pending                    = new LinkedHashMap<>();
        this.probeLimits                = new RateLimiter<>(configuration.getRateLimiterCapacity(), PROBE_BURST, PROBES_PER_SECOND);
        this.contactOverflowUntilMillis = Long.MIN_VALUE;
        this.nextTestMillis             = Long.MIN_VALUE;
        this.tested                     = false;
        this.nextRequestNumber          = random.nextLong() & REQUEST_NUMBER_MASK;

        this.reachability    = configuration.isLeafOnly() ? Reachability.NOT_REACHABLE : Reachability.NOT_VERIFIED;
        this.observedAddress = null;
    }

    /**
     * @return the result of the self-test so far
     */
    public @NotNull Reachability getReachability() {
        return this.reachability;
    }

    /**
     * @return the peer's address as the last accepting helper observed it, or {@code null} if no helper accepted a dial-back yet
     */
    public @Nullable InetSocketAddress getObservedAddress() {
        return this.observedAddress;
    }

    /**
     * @return {@code true} if the first self-test settled (a result, a refusal or no answer in time), {@code false} otherwise (unsent or awaited)
     */
    public boolean isSettled() {
        return this.reachability != Reachability.NOT_VERIFIED || this.tested && this.pending.isEmpty();
    }

    private @NotNull PeerCard getCard() {
        return new PeerCard(this.proofNonce, this.reachability.isFullPeer(), this.coordinates.getReport());
    }


    /**
     * Records that the peer's socket sent a datagram to an address: the peer's own datagrams and, on a shared socket, its owner's.
     *
     * @param target    the receiver's address
     * @param nowMillis the current time
     */
    public void recordContact(@NotNull InetSocketAddress target, long nowMillis) {
        InetSocketAddress key = toContactKey(target);

        this.contacted.remove(key);
        this.contacted.put(key, nowMillis);

        if (this.contacted.size() > this.configuration.getMaximumContactedAddresses()) {
            Iterator<Long> eldest = this.contacted.values().iterator();
            long eldestMillis = eldest.next();

            eldest.remove();

            if (nowMillis - eldestMillis <= this.configuration.getContactMemoryMillis()) {
                this.contactOverflowUntilMillis = eldestMillis + this.configuration.getContactMemoryMillis();
            }
        }
    }

    private boolean isContacted(@NotNull InetSocketAddress source, long nowMillis) {
        if (nowMillis <= this.contactOverflowUntilMillis) {
            return true;
        }

        Long sentMillis = this.contacted.get(toContactKey(source));

        return sentMillis != null && nowMillis - sentMillis <= this.configuration.getContactMemoryMillis();
    }

    /**
     * Runs the requester's part of a round: gives up the dial-backs past their time (an accepted one without its probe makes the peer not
     * reachable) and, when a test is due and none waits, asks up to two random known peers for a dial-back.
     *
     * @param liveNodes the peers known at a proved address
     * @param nowMillis the current time
     */
    public void runRound(@NotNull List<@NotNull NodeEndpoint> liveNodes, long nowMillis) {
        this.removeExpired(nowMillis);

        if (this.configuration.isLeafOnly() || this.reachability == Reachability.REACHABLE || !this.pending.isEmpty() ||
            nowMillis < this.nextTestMillis || liveNodes.isEmpty()) {
            return;
        }

        List<NodeEndpoint> helpers = new ArrayList<>(liveNodes);

        for (int count = 0; count < HELPERS_PER_TEST && !helpers.isEmpty(); count++) {
            NodeEndpoint helper = helpers.remove(this.random.nextInt(helpers.size()));
            long request = this.takeRequestNumber();
            long nonce = this.random.nextLong();

            this.pending.put(request, new PendingDialBack(helper, nonce, nowMillis + this.configuration.getProbeTimeoutMillis()));
            this.sink.send(this.encoder.encodePaddedRequest(new DialBackMessage(this.getCard(), nonce, null), request), helper.getAddress());
        }

        this.tested         = true;
        this.nextTestMillis = nowMillis + this.configuration.getDialBackRetryMillis();
    }

    private void removeExpired(long nowMillis) {
        Iterator<PendingDialBack> iterator = this.pending.values().iterator();
        boolean failed = false;

        while (iterator.hasNext()) {
            PendingDialBack dialBack = iterator.next();

            if (nowMillis > dialBack.deadlineMillis) {
                iterator.remove();
                failed |= dialBack.accepted;
            }
        }

        if (failed && this.reachability != Reachability.REACHABLE) {
            if (this.reachability != Reachability.NOT_REACHABLE) {
                LOGGER.info("Not reachable: the probe of a dial-back to {0} did not arrive.", this.observedAddress);
            }

            this.reachability = Reachability.NOT_REACHABLE;
        }
    }

    /**
     * Handles a dial-back message whose signature and replay check passed: a requester's padded request (forwarded to a third known peer), a
     * forwarded request of a known helper (answered with a probe) or a helper's answer to this peer's request. Dropped are a requester's
     * request that is not padded or whose card has no valid proof, a forwarded request that does not come from a known peer at its address,
     * and a reply that answers no waiting dial-back.
     *
     * @param envelope  the message
     * @param source    the observed source address
     * @param routing   the peer layer, which knows the peers proved at their address
     * @param nowMillis the current time
     * @return {@code null} if the message was handled, otherwise why it was dropped
     */
    public @Nullable DropReason handleDialBack(@NotNull Envelope envelope, @NotNull InetSocketAddress source, @NotNull PeerRouting routing,
                                               long nowMillis) {
        DialBackMessage message = (DialBackMessage) envelope.getBody();
        InetSocketAddress target = message.getAddress();

        if (envelope.isReply()) {
            return this.handleAnswer(envelope, message, source, nowMillis) ? null : DropReason.NOT_A_PEER;
        }

        if (target == null) {
            if (envelope.getLength() < MessageEncoder.UNVALIDATED_REQUEST_LENGTH) {
                return DropReason.UNPADDED;
            }

            if (!message.getCard().isProofValid(envelope.getSender(), this.configuration.getProofDifficulty())) {
                return DropReason.INVALID_PROOF;
            }

            this.forward(envelope, message.getNonce(), source, routing.getLiveNodes());

            return null;
        }

        if (!routing.isKnownAt(envelope.getSender(), source)) {
            return DropReason.NOT_A_PEER;
        }

        if (this.probeLimits.tryAcquire(target.getAddress(), nowMillis)) {
            this.sink.send(this.encoder.encodeRequest(new ProbeMessage(message.getNonce()), this.takeRequestNumber()), target);
        }

        return null;
    }

    private void forward(@NotNull Envelope request, long nonce, @NotNull InetSocketAddress source, @NotNull List<@NotNull NodeEndpoint> liveNodes) {
        List<NodeEndpoint> thirds = new ArrayList<>(liveNodes.size());

        for (NodeEndpoint node : liveNodes) {
            if (!node.getKey().equals(request.getSender()) && !node.getAddress().equals(source)) {
                thirds.add(node);
            }
        }

        if (thirds.isEmpty()) {
            this.sink.send(this.encoder.encodeReply(new DialBackMessage(this.getCard(), nonce, null), request.getRequestNumber()), source);

            return;
        }

        NodeEndpoint third = thirds.get(this.random.nextInt(thirds.size()));

        this.sink.send(this.encoder.encodeRequest(new DialBackMessage(this.getCard(), nonce, source), this.takeRequestNumber()), third.getAddress());
        this.sink.send(this.encoder.encodeReply(new DialBackMessage(this.getCard(), nonce, source), request.getRequestNumber()), source);
    }

    private boolean handleAnswer(@NotNull Envelope reply, @NotNull DialBackMessage message, @NotNull InetSocketAddress source, long nowMillis) {
        PendingDialBack dialBack = this.pending.get(reply.getRequestNumber());
        IdentityKey sender = reply.getSender();

        if (dialBack == null || !dialBack.helper.getKey().equals(sender) || !dialBack.helper.getAddress().equals(source) ||
            dialBack.nonce != message.getNonce()) {
            return false;
        }

        InetSocketAddress observed = message.getAddress();

        if (observed == null) {
            this.pending.remove(reply.getRequestNumber());

            return true;
        }

        dialBack.accepted       = true;
        dialBack.deadlineMillis = nowMillis + this.configuration.getProbeTimeoutMillis();
        this.observedAddress    = observed;
        this.nextTestMillis     = nowMillis + this.configuration.getDialBackIntervalMillis();

        return true;
    }

    /**
     * Handles a probe whose signature and replay check passed: it proves reachability when it answers a waiting dial-back and comes from an
     * address the peer never sent to.
     *
     * @param envelope  the probe
     * @param source    the observed source address
     * @param nowMillis the current time
     * @return {@code true} if the probe answers a waiting dial-back, {@code false} otherwise (it is dropped)
     */
    public boolean handleProbe(@NotNull Envelope envelope, @NotNull InetSocketAddress source, long nowMillis) {
        long nonce = ((ProbeMessage) envelope.getBody()).getNonce();
        Iterator<PendingDialBack> iterator = this.pending.values().iterator();

        while (iterator.hasNext()) {
            if (iterator.next().nonce == nonce) {
                iterator.remove();

                if (this.isContacted(source, nowMillis)) {
                    // The test settled nothing: test again soon, through other helpers and third peers.
                    this.nextTestMillis = nowMillis + this.configuration.getDialBackRetryMillis();
                    LOGGER.debug("A probe from {0} proves nothing: the peer sent to that address.", source);
                } else {
                    this.markReachable("probe", source);
                }

                return true;
            }
        }

        return false;
    }

    /**
     * Notes a hello request whose signature and replay check passed, before it is answered: the first one from an address the peer never
     * sent to proves reachability.
     *
     * @param source    the observed source address
     * @param nowMillis the current time
     */
    public void noteHello(@NotNull InetSocketAddress source, long nowMillis) {
        if (this.reachability != Reachability.REACHABLE && !this.isContacted(source, nowMillis)) {
            this.markReachable("hello", source);
        }
    }

    private void markReachable(@NotNull String proof, @NotNull InetSocketAddress source) {
        if (this.configuration.isLeafOnly()) {
            return;
        }

        this.reachability = Reachability.REACHABLE;
        this.pending.clear();
        LOGGER.info("Reachable: an unrequested {0} arrived from {1}.", proof, source);
    }

    private long takeRequestNumber() {
        long request = this.nextRequestNumber;

        this.nextRequestNumber = (request + 1L) & REQUEST_NUMBER_MASK;

        return request;
    }
}
