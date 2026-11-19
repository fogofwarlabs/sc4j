package org.fogofwarlabs.sc4j.engine.net.transfer;

import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.nio.ByteBuffer;

/**
 * Sends an offered payload to a peer that requested it: chunks go out while the chunks sent and not acknowledged stay within the congestion
 * window, which grows with every acknowledged chunk (doubling per round trip until the first loss, then by one chunk per round trip), halves
 * on a loss the acknowledgements show and falls to its least size when no acknowledgement advanced for the retransmission timeout. A missing
 * chunk counts as lost once three later chunks were acknowledged and it was sent longer ago than the smoothed round trip and its variation,
 * and is sent again; after the timeout every unacknowledged chunk the peer does not hold is sent again. The round trip is measured on the
 * acknowledgements of chunks that arrived in order, none of them sent again or held behind a missing chunk.
 *
 * @param <A> the type of the peers' addresses
 */
final class TransferUpload<A> {
    /**
     * The congestion window at the start, in chunks.
     */
    static final int INITIAL_WINDOW = 32;
    /**
     * The least congestion window, in chunks.
     */
    static final int MINIMUM_WINDOW = 4;
    /**
     * The number of later chunks acknowledged after which a missing chunk counts as lost.
     */
    private static final int LOSS_THRESHOLD = 3;
    /**
     * The retransmission timeout before the first round trip was measured, in milliseconds.
     */
    private static final long INITIAL_TIMEOUT_MILLIS = 500L;
    /**
     * The least retransmission timeout, in milliseconds.
     */
    private static final long MINIMUM_TIMEOUT_MILLIS = 200L;
    /**
     * The largest retransmission timeout, in milliseconds.
     */
    private static final long MAXIMUM_TIMEOUT_MILLIS = 5000L;
    /**
     * The factor the retransmission timeout grows by when it expires.
     */
    private static final int TIMEOUT_BACKOFF_FACTOR = 2;
    /**
     * The weight of a new round trip sample in the smoothed round trip.
     */
    private static final double ROUND_TRIP_GAIN = 0.125;
    /**
     * The weight of a new round trip sample in the round trip's variation.
     */
    private static final double VARIATION_GAIN = 0.25;
    /**
     * The factor of the round trip's variation in the retransmission timeout.
     */
    private static final int VARIATION_FACTOR = 4;
    /**
     * The divisor that halves the window on a loss and the first round trip sample into the first variation.
     */
    private static final int HALF = 2;

    private static int slot(int index) {
        return index % TransferService.MAXIMUM_WINDOW;
    }


    private final @NotNull TransferOffer offer;
    private final @NotNull A peer;
    private final int request;
    private final long start;
    private final int chunkSize;
    private final int chunkCount;

    private int acknowledged;
    private int next;
    private int highestHeld;
    private double window;
    private double threshold;
    private int recoveryPoint;

    private double roundTripMillis;
    private double variationMillis;
    private long measuredTimeoutMillis;
    private long timeoutMillis;

    private final long @NotNull [] sentMillis;
    private final boolean @NotNull [] resent;
    private final boolean @NotNull [] held;
    private final boolean @NotNull [] lost;
    private int lostCount;

    private long progressMillis;
    private long heardMillis;

    TransferUpload(@NotNull TransferOffer offer, @NotNull A peer, int request, long start, int chunkSize, long nowMillis) {
        this.offer      = offer;
        this.peer       = peer;
        this.request    = request;
        this.start      = start;
        this.chunkSize  = chunkSize;
        this.chunkCount = (int) ((offer.getPayload().getSize() - start + chunkSize - 1) / chunkSize);

        this.acknowledged  = 0;
        this.next          = 0;
        this.highestHeld   = -1;
        this.window        = INITIAL_WINDOW;
        this.threshold     = TransferService.MAXIMUM_WINDOW;
        this.recoveryPoint = 0;

        this.roundTripMillis       = 0;
        this.variationMillis       = 0;
        this.measuredTimeoutMillis = INITIAL_TIMEOUT_MILLIS;
        this.timeoutMillis         = INITIAL_TIMEOUT_MILLIS;

        this.sentMillis = new long[TransferService.MAXIMUM_WINDOW];
        this.resent     = new boolean[TransferService.MAXIMUM_WINDOW];
        this.held       = new boolean[TransferService.MAXIMUM_WINDOW];
        this.lost       = new boolean[TransferService.MAXIMUM_WINDOW];
        this.lostCount  = 0;

        this.progressMillis = nowMillis;
        this.heardMillis    = nowMillis;
    }

    @NotNull TransferOffer getOffer() {
        return this.offer;
    }

    @NotNull A getPeer() {
        return this.peer;
    }

    int getRequest() {
        return this.request;
    }

    long getHeardMillis() {
        return this.heardMillis;
    }

    /**
     * @return {@code true} if the peer acknowledged every chunk, {@code false} otherwise
     */
    boolean isComplete() {
        return this.acknowledged == this.chunkCount;
    }

    /**
     * Records that the peer repeated its request.
     *
     * @param nowMillis the clock time
     */
    void hear(long nowMillis) {
        this.heardMillis = nowMillis;
    }

    /**
     * Writes the acceptance of the request.
     *
     * @param buffer the datagram buffer
     */
    void writeAcceptance(@NotNull ByteBuffer buffer) {
        TransferWire.begin(buffer, TransferWire.ACCEPT, this.request).putLong(this.offer.getPayload().getSize()).putLong(this.start)
                    .putShort((short) this.chunkSize).put(this.offer.getPayload().getDigest());
    }

    /**
     * Takes an acknowledgement: the chunks held without gap move the window on and, when they arrived in order, measure the round trip; the
     * bits mark the chunks held after the first missing one, and a missing chunk with enough held chunks after it is marked lost.
     *
     * @param data      the acknowledgement after its header
     * @param nowMillis the clock time
     */
    void acknowledge(@NotNull ByteBuffer data, long nowMillis) {
        this.heardMillis = nowMillis;

        int count = data.getInt();
        long bits = data.getLong();

        if (count < this.acknowledged || count > this.next) {
            return;
        }

        if (count > this.acknowledged) {
            // Only chunks that arrived in order, none sent again and none held behind a gap, give a round trip sample.
            boolean inOrder = true;

            for (int index = this.acknowledged; index < count; index++) {
                inOrder &= !this.resent[slot(index)] && !this.held[slot(index)];
            }

            if (inOrder) {
                this.measure(nowMillis - this.sentMillis[slot(count - 1)]);
            }

            for (int index = this.acknowledged; index < count; index++) {
                this.clear(index);
            }

            int advanced = count - this.acknowledged;

            this.acknowledged   = count;
            this.progressMillis = nowMillis;
            this.timeoutMillis  = this.measuredTimeoutMillis;
            this.window         = Math.min(TransferService.MAXIMUM_WINDOW,
                                           this.window < this.threshold ? this.window + advanced : this.window + (double) advanced / this.window);
        }

        for (int bit = 0; bit < TransferWire.ACKNOWLEDGED_BITS; bit++) {
            int index = count + 1 + bit;

            if ((bits & 1L << bit) != 0 && index < this.next) {
                this.held[slot(index)] = true;
                this.highestHeld       = Math.max(this.highestHeld, index);
            }
        }

        boolean loss = false;

        for (int index = this.acknowledged; index <= this.highestHeld - LOSS_THRESHOLD; index++) {
            int slot = slot(index);

            if (!this.held[slot] && !this.lost[slot] && nowMillis - this.sentMillis[slot] >= (long) (this.roundTripMillis + this.variationMillis)) {
                this.lost[slot] = true;
                this.lostCount++;

                loss |= index >= this.recoveryPoint;
            }
        }

        if (loss) {
            this.reduce(this.window / HALF);
        }
    }

    /**
     * Sends the chunks marked lost, then new chunks within the window, while the budget lasts; when no acknowledgement advanced for the
     * timeout, every unacknowledged chunk the peer does not hold is marked lost, the window falls to its least size and the timeout doubles.
     *
     * @param service   the service that sends the datagrams
     * @param budget    the datagrams the upload may send
     * @param nowMillis the clock time
     * @return the datagrams sent
     * @throws IOException if the source cannot be read or the link cannot send
     */
    int send(@NotNull TransferService<A> service, int budget, long nowMillis) throws IOException {
        if (this.acknowledged < this.next && nowMillis - this.progressMillis >= this.timeoutMillis) {
            for (int index = this.acknowledged; index < this.next; index++) {
                int slot = slot(index);

                if (!this.held[slot] && !this.lost[slot]) {
                    this.lost[slot] = true;
                    this.lostCount++;
                }
            }

            double half = this.window / HALF;

            this.reduce(MINIMUM_WINDOW);
            this.threshold      = Math.max(MINIMUM_WINDOW, half);
            this.timeoutMillis  = Math.min(MAXIMUM_TIMEOUT_MILLIS, this.timeoutMillis * TIMEOUT_BACKOFF_FACTOR);
            this.progressMillis = nowMillis;
        }

        int sent = 0;

        for (int index = this.acknowledged; index < this.next && this.lostCount > 0 && sent < budget; index++) {
            int slot = slot(index);

            if (this.lost[slot]) {
                this.lost[slot] = false;
                this.lostCount--;
                this.resent[slot] = true;
                this.sendChunk(service, index, nowMillis);
                sent++;
            }
        }

        while (sent < budget && this.next < this.chunkCount && this.next - this.acknowledged < (int) this.window) {
            int slot = slot(this.next);

            this.resent[slot] = false;
            this.held[slot]   = false;
            this.lost[slot]   = false;
            this.sendChunk(service, this.next, nowMillis);
            this.next++;
            sent++;
        }

        return sent;
    }

    private void sendChunk(@NotNull TransferService<A> service, int index, long nowMillis) throws IOException {
        long offset = this.start + (long) index * this.chunkSize;
        int length = (int) Math.min(this.chunkSize, this.offer.getPayload().getSize() - offset);
        ByteBuffer buffer = TransferWire.begin(service.getSendBuffer(), TransferWire.DATA, this.request).putInt(index);
        int end = buffer.position() + length;

        this.offer.getSource().read(offset, buffer.limit(end));
        this.sentMillis[slot(index)] = nowMillis;
        service.send(buffer.flip(), this.peer);
    }

    private void clear(int index) {
        int slot = slot(index);

        if (this.lost[slot]) {
            this.lost[slot] = false;
            this.lostCount--;
        }

        this.held[slot]   = false;
        this.resent[slot] = false;
    }

    /**
     * Lowers the window after a loss; a loss of a chunk sent before this one does not lower it again.
     *
     * @param size the new window
     */
    private void reduce(double size) {
        this.window        = Math.max(MINIMUM_WINDOW, size);
        this.threshold     = Math.max(MINIMUM_WINDOW, this.window);
        this.recoveryPoint = this.next;
    }

    /**
     * Takes a round trip sample into the smoothed round trip, its variation and the retransmission timeout.
     *
     * @param sampleMillis the sample
     */
    private void measure(long sampleMillis) {
        if (this.roundTripMillis == 0) {
            this.roundTripMillis = sampleMillis;
            this.variationMillis = sampleMillis / (double) HALF;
        } else {
            this.variationMillis += VARIATION_GAIN * (Math.abs(this.roundTripMillis - sampleMillis) - this.variationMillis);
            this.roundTripMillis += ROUND_TRIP_GAIN * (sampleMillis - this.roundTripMillis);
        }

        long timeout = (long) (this.roundTripMillis + VARIATION_FACTOR * this.variationMillis);

        this.measuredTimeoutMillis = Math.clamp(timeout, MINIMUM_TIMEOUT_MILLIS, MAXIMUM_TIMEOUT_MILLIS);
        this.timeoutMillis         = this.measuredTimeoutMillis;
    }
}
