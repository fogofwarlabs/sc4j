package org.fogofwarlabs.sc4j.engine.net.transfer;

import org.fogofwarlabs.sc4j.base.exception.Exceptions;
import org.fogofwarlabs.sc4j.base.log.Logger;
import org.fogofwarlabs.sc4j.engine.net.DatagramLink;
import org.fogofwarlabs.sc4j.engine.net.NetworkClock;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.Closeable;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.security.SecureRandom;
import java.util.*;

/**
 * Transfers payloads between peers over the {@link DatagramLink} of a network: the engine's transfer of large data (maps, replays, mod data)
 * beside the lobby and the lockstep transport, one routine for every network. A peer offers a payload under a key ({@link #offer}); another
 * peer requests the key ({@link #request}) into a sink, and the offering peer accepts with the payload's size and digest and sends it in
 * chunks that fill a datagram of the link. The requesting side acknowledges the chunks it holds; the sending side keeps a congestion window of
 * chunks in flight and sends at most {@link #getDatagramRate} datagrams a second to each peer, so the transfer takes the bandwidth the path
 * has without crowding out the other traffic on it. A request resumes after the bytes its sink already holds,
 * and the whole payload's digest is checked before the download is complete ({@link TransferDownload}). Either side can cancel; a side that
 * hears nothing of a transfer for {@value #IDLE_MILLIS} ms ends it.
 * <p>
 * Memory stays bounded: the sending side reads every chunk from its source when it sends it, and the requesting side keeps at most
 * {@value #MAXIMUM_WINDOW} chunks ahead of the first missing one. A service serves at most {@value #MAXIMUM_UPLOADS} uploads at once and
 * refuses more requests.
 * <p>
 * The datagrams are big-endian and start with the magic {@code 0x5354} and the protocol version. The service is not thread-safe and never
 * blocks: its owner calls {@link #update()} from its loop, as often as it runs the other protocols of the link. The service owns the link
 * and closes it.
 *
 * @param <A> the type of the peers' addresses on the link
 */
public abstract class TransferService<A> implements Closeable {
    /**
     * The largest congestion window, in chunks: the most chunks in flight to a peer per upload, and the most chunks a download keeps ahead of
     * the first missing one.
     */
    public static final int MAXIMUM_WINDOW = 1024;
    /**
     * The most uploads a service serves at once.
     */
    public static final int MAXIMUM_UPLOADS = 16;
    /**
     * The datagrams a second a service sends to one peer unless the network gives another rate ({@link #getDatagramRate}).
     */
    public static final int DEFAULT_DATAGRAM_RATE = 8192;
    /**
     * The time without a datagram of a transfer from its peer after which the transfer ends, in milliseconds.
     */
    public static final long IDLE_MILLIS = 15_000L;
    /**
     * The time between two requests of a download the peer has not accepted, in milliseconds.
     */
    static final long REQUEST_INTERVAL_MILLIS = 500L;
    /**
     * The chunks a download takes after which it acknowledges at once instead of at the end of the update.
     */
    static final int ACKNOWLEDGE_CHUNKS = 32;
    /**
     * The time a complete download keeps answering repeated chunks with its last acknowledgement, in milliseconds.
     */
    private static final long LINGER_MILLIS = 5000L;
    /**
     * The datagrams a peer's budget holds at most, as milliseconds of the peer's rate.
     */
    private static final long BURST_MILLIS = 32L;
    /**
     * The least number of datagrams a peer's budget holds at most.
     */
    private static final int MINIMUM_BURST = 16;
    private static final long MILLIS_PER_SECOND = 1000L;

    private static final @NotNull Logger LOGGER = Logger.of(TransferService.class);

    /**
     * Holds the datagrams the service may send to one peer: a token bucket refilled at the peer's rate.
     */
    private static final class PeerBudget {
        private long tokens;
        private long lastMillis;

        private PeerBudget(long nowMillis) {
            this.tokens     = 0;
            this.lastMillis = nowMillis;
        }
    }


    private final @NotNull DatagramLink<A> link;
    private final int maximumDatagramLength;
    private final @NotNull NetworkClock clock;

    private final @NotNull ByteBuffer receiveBuffer;
    private final @NotNull ByteBuffer sendBuffer;

    private final @NotNull List<@NotNull TransferOffer> offers;
    private final @NotNull List<@NotNull TransferUpload<A>> uploads;
    private final @NotNull List<@NotNull TransferDownload<A>> downloads;
    private final @NotNull Map<A, @NotNull PeerBudget> budgets;

    private int nextRequest;
    private int firstUpload;
    private boolean closed;

    /**
     * Creates a {@link TransferService} instance over a link.
     *
     * @param link                  the link, which the service owns
     * @param maximumDatagramLength the longest datagram the link carries
     * @param clock                 the clock of the timeouts and rates
     * @throws IllegalArgumentException if a datagram cannot hold a request with the longest key
     */
    protected TransferService(@NotNull DatagramLink<A> link, int maximumDatagramLength, @NotNull NetworkClock clock) {
        if (maximumDatagramLength < TransferWire.REQUEST_MINIMUM_SIZE + TransferKey.MAXIMUM_LENGTH) {
            throw Exceptions.newIllegalArgumentException("A transfer needs datagrams of at least {0} bytes, not {1}.",
                                                         TransferWire.REQUEST_MINIMUM_SIZE + TransferKey.MAXIMUM_LENGTH, maximumDatagramLength);
        }

        this.link                  = link;
        this.maximumDatagramLength = maximumDatagramLength;
        this.clock                 = clock;

        this.receiveBuffer = ByteBuffer.allocate(maximumDatagramLength);
        this.sendBuffer    = ByteBuffer.allocate(maximumDatagramLength);

        this.offers    = new ArrayList<>();
        this.uploads   = new ArrayList<>();
        this.downloads = new ArrayList<>();
        this.budgets   = new HashMap<>();

        this.nextRequest = new SecureRandom().nextInt();
        this.firstUpload = 0;
        this.closed      = false;
    }

    /**
     * @return the size of the chunks the service sends: a datagram less the chunk header
     */
    public int getChunkSize() {
        return Math.min(this.maximumDatagramLength - TransferWire.DATA_HEADER_SIZE, TransferWire.WORD_MASK);
    }

    /**
     * @return the payloads offered and not withdrawn
     */
    public @NotNull List<@NotNull TransferOffer> getOffers() {
        return this.offers.stream().filter(offer -> !offer.isWithdrawn()).toList();
    }

    /**
     * @return the downloads that have not ended, and the ended ones the service has not dropped yet
     */
    public @NotNull List<@NotNull TransferDownload<A>> getDownloads() {
        return List.copyOf(this.downloads);
    }

    /**
     * @return the number of uploads being served
     */
    public int getUploadCount() {
        return this.uploads.size();
    }


    /**
     * Offers a payload under a key: its digest is computed from the source now, and every peer that requests the key gets it until the offer
     * is withdrawn ({@link TransferOffer#withdraw()}). A payload offered under the same key before is withdrawn.
     *
     * @param key    the key
     * @param source the payload's bytes, read whenever a chunk is sent; the caller closes it after the offer is withdrawn
     * @return the created {@link TransferOffer} instance
     * @throws IOException           if the source cannot be read
     * @throws IllegalStateException if the service is closed
     */
    public @NotNull TransferOffer offer(@NotNull TransferKey key, @NotNull TransferSource source) throws IOException {
        this.checkOpen();

        TransferOffer offer = new TransferOffer(key, TransferPayload.of(source), source);

        for (TransferOffer existing : this.offers) {
            if (existing.getKey().equals(key)) {
                existing.withdraw();
            }
        }

        this.offers.add(offer);

        return offer;
    }

    /**
     * Requests the payload a peer offers under a key into a sink: the download resumes after the bytes the sink holds. The request goes out
     * with the next update.
     *
     * @param peer the peer
     * @param key  the key
     * @param sink the sink; the caller closes it after the download ended
     * @return the created {@link TransferDownload} instance
     * @throws IllegalStateException if the service is closed
     */
    public @NotNull TransferDownload<A> request(@NotNull A peer, @NotNull TransferKey key, @NotNull TransferSink sink) {
        this.checkOpen();

        TransferDownload<A> download = new TransferDownload<>(this, this.nextRequest++, peer, key, sink,
                                                              this.clock.getMillis() - REQUEST_INTERVAL_MILLIS);

        this.downloads.add(download);

        return download;
    }

    /**
     * Receives the waiting datagrams and carries out the due work: requests and their resends, acknowledgements, cancels, timeouts, and the
     * chunks each upload may send within its window and its peer's rate.
     *
     * @throws IOException           if the link fails
     * @throws IllegalStateException if the service is closed
     */
    public void update() throws IOException {
        this.checkOpen();

        long nowMillis = this.clock.getMillis();

        for (A sender = this.link.receive(this.receiveBuffer.clear()); sender != null; sender = this.link.receive(this.receiveBuffer.clear())) {
            this.receive(sender, this.receiveBuffer.flip(), nowMillis);
        }

        this.updateDownloads(nowMillis);
        this.updateUploads(nowMillis);
    }

    /**
     * Ends every transfer, tells the peers and closes the link. Calling it again does nothing.
     */
    @Override
    public void close() {
        if (this.closed) {
            return;
        }

        long nowMillis = this.clock.getMillis();

        try {
            for (TransferDownload<A> download : this.downloads) {
                if (download.takeCancel() || !download.isDone()) {
                    this.sendHeader(TransferWire.CANCEL, download.getRequest(), download.getPeer());
                }

                download.fail(TransferFailure.CLOSED, nowMillis);
            }

            for (TransferUpload<A> upload : this.uploads) {
                if (!upload.isComplete()) {
                    this.sendHeader(TransferWire.CANCEL, upload.getRequest(), upload.getPeer());
                }
            }
        } catch (IOException exception) {
            LOGGER.debug("TRANSFER: the cancels on close were not sent: {0}", exception.getMessage());
        }

        this.closed = true;
        this.downloads.clear();
        this.uploads.clear();
        this.offers.clear();

        try {
            this.link.close();
        } catch (IOException exception) {
            LOGGER.debug("TRANSFER: the link did not close: {0}", exception.getMessage());
        }
    }

    /**
     * Gives the datagrams a second the service sends to a peer: the rate the path to the peer carries beside its other traffic.
     *
     * @param peer the peer
     * @return the datagrams a second, at least {@code 1}
     */
    protected int getDatagramRate(@NotNull A peer) {
        return DEFAULT_DATAGRAM_RATE;
    }

    @NotNull ByteBuffer getSendBuffer() {
        return this.sendBuffer;
    }

    /**
     * Sends a datagram written into the send buffer.
     *
     * @param datagram the datagram, read from its position to its limit
     * @param peer     the receiver
     * @throws IOException if the link cannot send
     */
    void send(@NotNull ByteBuffer datagram, @NotNull A peer) throws IOException {
        this.link.send(datagram, peer);
    }

    private void checkOpen() {
        if (this.closed) {
            throw Exceptions.newIllegalStateException("The transfer service is closed.");
        }
    }

    private void sendHeader(int type, int request, @NotNull A peer) throws IOException {
        this.send(TransferWire.begin(this.sendBuffer, type, request).flip(), peer);
    }

    private void receive(@NotNull A sender, @NotNull ByteBuffer datagram, long nowMillis) throws IOException {
        if (datagram.remaining() < TransferWire.HEADER_SIZE || (datagram.getShort() & TransferWire.WORD_MASK) != TransferWire.MAGIC ||
            (datagram.get() & TransferWire.BYTE_MASK) != TransferWire.VERSION) {
            return;
        }

        int type = datagram.get() & TransferWire.BYTE_MASK;
        int request = datagram.getInt();

        switch (type) {
            case TransferWire.REQUEST -> this.receiveRequest(sender, request, datagram, nowMillis);
            case TransferWire.ACCEPT -> {
                TransferDownload<A> download = this.findDownload(sender, request);

                if (download != null && datagram.remaining() == TransferWire.ACCEPT_SIZE - TransferWire.HEADER_SIZE) {
                    download.accept(datagram, nowMillis);
                }
            }
            case TransferWire.REFUSE -> {
                TransferDownload<A> download = this.findDownload(sender, request);

                if (download != null && download.getState() == TransferState.REQUESTING) {
                    download.fail(TransferFailure.REFUSED, nowMillis);
                }
            }
            case TransferWire.DATA -> this.receiveData(sender, request, datagram, nowMillis);
            case TransferWire.ACKNOWLEDGE -> {
                TransferUpload<A> upload = this.findUpload(sender, request);

                if (upload != null && datagram.remaining() == TransferWire.ACKNOWLEDGE_SIZE - TransferWire.HEADER_SIZE) {
                    upload.acknowledge(datagram, nowMillis);
                }
            }
            case TransferWire.CANCEL -> {
                TransferUpload<A> upload = this.findUpload(sender, request);
                TransferDownload<A> download = this.findDownload(sender, request);

                if (upload != null) {
                    this.uploads.remove(upload);
                }

                if (download != null) {
                    download.fail(TransferFailure.CANCELED_BY_PEER, nowMillis);
                }
            }
            default -> {
                // Other message types are ignored.
            }
        }
    }

    /**
     * Handles a request: a repeated one gets the acceptance again; a new one for an offered key starts an upload from the offset the peer
     * holds (from {@code 0} when it holds more than the payload), else it is refused.
     *
     * @param sender    the requesting peer
     * @param request   the request number
     * @param datagram  the request after its header
     * @param nowMillis the clock time
     * @throws IOException if the link cannot send
     */
    private void receiveRequest(@NotNull A sender, int request, @NotNull ByteBuffer datagram, long nowMillis) throws IOException {
        if (datagram.remaining() < TransferWire.REQUEST_MINIMUM_SIZE - TransferWire.HEADER_SIZE) {
            return;
        }

        long held = datagram.getLong();
        int length = datagram.get() & TransferWire.BYTE_MASK;

        if (length == 0 || length > TransferKey.MAXIMUM_LENGTH || datagram.remaining() != length) {
            return;
        }

        byte[] keyBytes = new byte[length];

        datagram.get(keyBytes);

        TransferUpload<A> upload = this.findUpload(sender, request);

        if (upload == null) {
            TransferOffer offer = this.findOffer(TransferKey.of(keyBytes));

            if (offer == null || this.uploads.size() >= MAXIMUM_UPLOADS) {
                this.sendHeader(TransferWire.REFUSE, request, sender);

                return;
            }

            long start = held >= 0 && held <= offer.getPayload().getSize() ? held : 0;

            upload = new TransferUpload<>(offer, sender, request, start, this.getChunkSize(), nowMillis);
            this.uploads.add(upload);
        }

        upload.hear(nowMillis);
        upload.writeAcceptance(this.sendBuffer);
        this.send(this.sendBuffer.flip(), sender);
    }

    private void receiveData(@NotNull A sender, int request, @NotNull ByteBuffer datagram, long nowMillis) throws IOException {
        if (datagram.remaining() < Integer.BYTES) {
            return;
        }

        TransferDownload<A> download = this.findDownload(sender, request);

        if (download == null) {
            this.sendHeader(TransferWire.CANCEL, request, sender);

            return;
        }

        if (download.receive(datagram, nowMillis) && download.takeAcknowledgement()) {
            download.writeAcknowledgement(this.sendBuffer);
            this.send(this.sendBuffer.flip(), sender);
        }
    }

    private void updateDownloads(long nowMillis) throws IOException {
        for (TransferDownload<A> download : List.copyOf(this.downloads)) {
            A peer = download.getPeer();

            if (download.takeCancel()) {
                this.sendHeader(TransferWire.CANCEL, download.getRequest(), peer);
            }

            if (!download.isDone() && nowMillis - download.getHeardMillis() >= IDLE_MILLIS) {
                this.sendHeader(TransferWire.CANCEL, download.getRequest(), peer);
                download.fail(TransferFailure.TIMED_OUT, nowMillis);
            }

            if (download.getState() == TransferState.REQUESTING && nowMillis - download.getRequestMillis() >= REQUEST_INTERVAL_MILLIS) {
                boolean written;

                try {
                    download.writeRequest(this.sendBuffer, nowMillis);
                    written = true;
                } catch (IOException exception) {
                    download.fail(TransferFailure.STORAGE_FAILED, nowMillis);
                    written = false;
                }

                if (written) {
                    this.send(this.sendBuffer.flip(), peer);
                }
            }

            if ((download.getState() == TransferState.RECEIVING || download.getState() == TransferState.COMPLETE) && download.takeAcknowledgement()) {
                download.writeAcknowledgement(this.sendBuffer);
                this.send(this.sendBuffer.flip(), peer);
            }

            if (download.isDone() && (download.getState() != TransferState.COMPLETE || nowMillis - download.getFinishMillis() >= LINGER_MILLIS)) {
                this.downloads.remove(download);
            }
        }
    }

    /**
     * Ends the uploads that are complete, withdrawn or idle, and lets the others send in turn within their peers' budgets.
     *
     * @param nowMillis the clock time
     * @throws IOException if the link cannot send
     */
    private void updateUploads(long nowMillis) throws IOException {
        for (TransferUpload<A> upload : List.copyOf(this.uploads)) {
            if (upload.isComplete() || nowMillis - upload.getHeardMillis() >= IDLE_MILLIS) {
                this.uploads.remove(upload);
            } else if (upload.getOffer().isWithdrawn()) {
                this.sendHeader(TransferWire.CANCEL, upload.getRequest(), upload.getPeer());
                this.uploads.remove(upload);
            }
        }

        this.offers.removeIf(TransferOffer::isWithdrawn);
        this.budgets.keySet().removeIf(peer -> this.uploads.stream().noneMatch(upload -> upload.getPeer().equals(peer)));

        List<TransferUpload<A>> order = new ArrayList<>(this.uploads);

        Collections.rotate(order, -this.firstUpload);

        for (TransferUpload<A> upload : order) {
            A peer = upload.getPeer();
            PeerBudget budget = this.budgets.computeIfAbsent(peer, ignored -> new PeerBudget(nowMillis));
            int rate = Math.max(1, this.getDatagramRate(peer));
            long capacity = Math.max(MINIMUM_BURST, rate * BURST_MILLIS / MILLIS_PER_SECOND) * MILLIS_PER_SECOND;

            // Tokens are counted in thousandths, so that the refill per millisecond stays an integer.
            budget.tokens     = Math.min(capacity, budget.tokens + Math.max(0L, nowMillis - budget.lastMillis) * rate);
            budget.lastMillis = nowMillis;

            try {
                budget.tokens -= upload.send(this, (int) (budget.tokens / MILLIS_PER_SECOND), nowMillis) * MILLIS_PER_SECOND;
            } catch (IOException exception) {
                LOGGER.warning("TRANSFER: the upload of {0} to {1} ended: {2}", upload.getOffer().getKey(), peer, exception.getMessage());
                this.sendHeader(TransferWire.CANCEL, upload.getRequest(), peer);
                this.uploads.remove(upload);
            }
        }

        this.firstUpload = this.uploads.isEmpty() ? 0 : (this.firstUpload + 1) % this.uploads.size();
    }

    private @Nullable TransferOffer findOffer(@NotNull TransferKey key) {
        for (TransferOffer offer : this.offers) {
            if (!offer.isWithdrawn() && offer.getKey().equals(key)) {
                return offer;
            }
        }

        return null;
    }

    private @Nullable TransferUpload<A> findUpload(@NotNull A peer, int request) {
        for (TransferUpload<A> upload : this.uploads) {
            if (upload.getRequest() == request && upload.getPeer().equals(peer)) {
                return upload;
            }
        }

        return null;
    }

    private @Nullable TransferDownload<A> findDownload(@NotNull A peer, int request) {
        for (TransferDownload<A> download : this.downloads) {
            if (download.getRequest() == request && download.getPeer().equals(peer)) {
                return download;
            }
        }

        return null;
    }
}
