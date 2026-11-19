package org.fogofwarlabs.sc4j.engine.net.transfer;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.Map;

/**
 * Follows a payload a service requested from a peer ({@link TransferService#request}): the chunks arrive in any order, the sink takes them in
 * order and the digest of the held bytes is checked once the last one arrived. Chunks ahead of the first missing one are kept in memory, at
 * most {@link TransferService#MAXIMUM_WINDOW} of them.
 *
 * @param <A> the type of the peers' addresses
 */
public final class TransferDownload<A> {
    private final @NotNull TransferService<A> service;
    private final int request;
    private final @NotNull A peer;
    private final @NotNull TransferKey key;
    private final @NotNull TransferSink sink;

    private @NotNull TransferState state;
    private @Nullable TransferFailure failure;
    private @Nullable TransferPayload payload;
    private @Nullable MessageDigest digest;

    private long start;
    private int chunkSize;
    private int chunkCount;
    private int next;
    private final @NotNull Map<@NotNull Integer, byte @NotNull []> ahead;

    private long heardMillis;
    private long requestMillis;
    private long finishMillis;
    private boolean acknowledgementDue;
    private int unacknowledgedChunks;
    private boolean cancelUnsent;

    TransferDownload(@NotNull TransferService<A> service, int request, @NotNull A peer, @NotNull TransferKey key, @NotNull TransferSink sink,
                     long nowMillis) {
        this.service = service;
        this.request = request;
        this.peer    = peer;
        this.key     = key;
        this.sink    = sink;

        this.state   = TransferState.REQUESTING;
        this.failure = null;
        this.payload = null;
        this.digest  = null;

        this.start      = 0;
        this.chunkSize  = 0;
        this.chunkCount = 0;
        this.next       = 0;
        this.ahead      = new HashMap<>();

        this.heardMillis          = nowMillis;
        this.requestMillis        = nowMillis;
        this.finishMillis         = 0;
        this.acknowledgementDue   = false;
        this.unacknowledgedChunks = 0;
        this.cancelUnsent         = false;
    }

    /**
     * @return the peer the payload is requested from
     */
    public @NotNull A getPeer() {
        return this.peer;
    }

    /**
     * @return the key the payload is requested under
     */
    public @NotNull TransferKey getKey() {
        return this.key;
    }

    /**
     * @return the download's state
     */
    public @NotNull TransferState getState() {
        return this.state;
    }

    /**
     * @return why the download failed, or {@code null} unless its state is {@link TransferState#FAILED}
     */
    public @Nullable TransferFailure getFailure() {
        return this.failure;
    }

    /**
     * @return the payload the peer announced, or {@code null} before the peer accepted the request
     */
    public @Nullable TransferPayload getPayload() {
        return this.payload;
    }

    /**
     * @return the number of payload bytes the sink holds without gap: those it held at the start and those received since
     */
    public long getReceivedBytes() {
        TransferPayload current = this.payload;

        if (current == null) {
            return 0;
        }

        return this.start + Math.min((long) this.next * this.chunkSize, current.getSize() - this.start);
    }

    /**
     * @return {@code true} if the download ended: complete, failed or canceled; {@code false} otherwise
     */
    public boolean isDone() {
        return this.state == TransferState.COMPLETE || this.state == TransferState.FAILED || this.state == TransferState.CANCELED;
    }

    /**
     * Cancels the download: the service's next update tells the peer. A download that ended stays as it is.
     */
    public void cancel() {
        if (this.isDone()) {
            return;
        }

        this.state        = TransferState.CANCELED;
        this.cancelUnsent = true;
    }


    int getRequest() {
        return this.request;
    }

    long getHeardMillis() {
        return this.heardMillis;
    }

    long getRequestMillis() {
        return this.requestMillis;
    }

    long getFinishMillis() {
        return this.finishMillis;
    }

    /**
     * @return {@code true} once if a cancel must go to the peer, {@code false} otherwise
     */
    boolean takeCancel() {
        boolean unsent = this.cancelUnsent;

        this.cancelUnsent = false;

        return unsent;
    }

    /**
     * @return {@code true} once if an acknowledgement must go to the peer, {@code false} otherwise
     */
    boolean takeAcknowledgement() {
        boolean due = this.acknowledgementDue;

        this.acknowledgementDue   = false;
        this.unacknowledgedChunks = 0;

        return due;
    }

    /**
     * Writes the request.
     *
     * @param buffer    the datagram buffer
     * @param nowMillis the clock time
     * @throws IOException if the sink's length cannot be read
     */
    void writeRequest(@NotNull ByteBuffer buffer, long nowMillis) throws IOException {
        byte[] keyBytes = this.key.getBytes();

        TransferWire.begin(buffer, TransferWire.REQUEST, this.request).putLong(this.sink.getLength()).put((byte) keyBytes.length).put(keyBytes);
        this.requestMillis = nowMillis;
    }

    /**
     * Writes an acknowledgement of the chunks held: those without gap, then the bits of the chunks kept after the first missing one.
     *
     * @param buffer the datagram buffer
     */
    void writeAcknowledgement(@NotNull ByteBuffer buffer) {
        long bits = 0;

        for (int bit = 0; bit < TransferWire.ACKNOWLEDGED_BITS && !this.ahead.isEmpty(); bit++) {
            if (this.ahead.containsKey(this.next + 1 + bit)) {
                bits |= 1L << bit;
            }
        }

        TransferWire.begin(buffer, TransferWire.ACKNOWLEDGE, this.request).putInt(this.next).putLong(bits);
    }

    /**
     * Takes the peer's acceptance while requesting: the payload, the start offset and the chunk size. Held bytes past a start of {@code 0} are
     * dropped; the held bytes before the start go into the digest.
     *
     * @param data      the acceptance after its header
     * @param nowMillis the clock time
     */
    void accept(@NotNull ByteBuffer data, long nowMillis) {
        this.heardMillis = nowMillis;

        if (this.state != TransferState.REQUESTING) {
            return;
        }

        long size = data.getLong();
        long offset = data.getLong();
        int chunk = data.getShort() & TransferWire.WORD_MASK;
        byte[] announced = new byte[TransferPayload.DIGEST_LENGTH];

        data.get(announced);

        if (size < 0 || offset < 0 || offset > size || chunk == 0 || chunk > this.service.getChunkSize()) {
            this.fail(TransferFailure.REFUSED, nowMillis);

            return;
        }

        try {
            if (offset != this.sink.getLength()) {
                if (offset != 0) {
                    this.fail(TransferFailure.REFUSED, nowMillis);

                    return;
                }

                this.sink.truncate();
            }

            this.digest = TransferPayload.createDigest();

            ByteBuffer block = ByteBuffer.allocate(TransferPayload.READ_BLOCK_SIZE);

            for (long position = 0; position < offset; position += block.limit()) {
                block.clear().limit((int) Math.min(TransferPayload.READ_BLOCK_SIZE, offset - position));
                this.sink.read(position, block);
                this.digest.update(block.array(), 0, block.limit());
            }
        } catch (IOException exception) {
            this.fail(TransferFailure.STORAGE_FAILED, nowMillis);

            return;
        }

        this.payload    = TransferPayload.of(size, announced);
        this.start      = offset;
        this.chunkSize  = chunk;
        this.chunkCount = (int) ((size - offset + chunk - 1) / chunk);
        this.state      = TransferState.RECEIVING;

        if (this.chunkCount == 0) {
            this.finish(nowMillis);
        }
    }

    /**
     * Takes a chunk: in order it goes to the sink, ahead of a missing chunk it is kept; either way an acknowledgement becomes due.
     *
     * @param data      the chunk after its header, from its index on
     * @param nowMillis the clock time
     * @return {@code true} if enough chunks arrived since the last acknowledgement that one should go out at once, {@code false} otherwise
     */
    boolean receive(@NotNull ByteBuffer data, long nowMillis) {
        this.heardMillis = nowMillis;

        if (this.state == TransferState.COMPLETE) {
            this.acknowledgementDue = true;

            return false;
        }

        TransferPayload current = this.payload;

        if (this.state != TransferState.RECEIVING || current == null) {
            return false;
        }

        int index = data.getInt();

        if (index < 0 || index >= this.chunkCount) {
            return false;
        }

        long offset = (long) index * this.chunkSize;
        int length = (int) Math.min(this.chunkSize, current.getSize() - this.start - offset);

        if (data.remaining() != length) {
            return false;
        }

        this.acknowledgementDue = true;
        this.unacknowledgedChunks++;

        if (index < this.next || index >= this.next + TransferService.MAXIMUM_WINDOW || this.ahead.containsKey(index)) {
            return false;
        }

        if (index != this.next) {
            byte[] chunk = new byte[length];

            data.get(chunk);
            this.ahead.put(index, chunk);

            return this.unacknowledgedChunks >= TransferService.ACKNOWLEDGE_CHUNKS;
        }

        try {
            this.write(data);

            for (byte[] chunk = this.ahead.remove(this.next); chunk != null; chunk = this.ahead.remove(this.next)) {
                this.write(ByteBuffer.wrap(chunk));
            }
        } catch (IOException exception) {
            this.fail(TransferFailure.STORAGE_FAILED, nowMillis);

            return false;
        }

        if (this.next == this.chunkCount) {
            this.finish(nowMillis);
        }

        return this.unacknowledgedChunks >= TransferService.ACKNOWLEDGE_CHUNKS;
    }

    /**
     * Ends the download because the peer refused or canceled it, the peer went silent, the sink failed or the service closed.
     *
     * @param reason    why the download failed
     * @param nowMillis the clock time
     */
    void fail(@NotNull TransferFailure reason, long nowMillis) {
        if (this.isDone()) {
            return;
        }

        this.state        = TransferState.FAILED;
        this.failure      = reason;
        this.finishMillis = nowMillis;
        this.ahead.clear();
    }

    private void write(@NotNull ByteBuffer chunk) throws IOException {
        MessageDigest current = this.digest;

        if (current != null) {
            current.update(chunk.duplicate());
        }

        this.sink.write(chunk);
        this.next++;
    }

    /**
     * Checks the digest of the held bytes: the download is complete, or it failed and the sink's bytes are dropped.
     *
     * @param nowMillis the clock time
     */
    private void finish(long nowMillis) {
        MessageDigest current = this.digest;
        TransferPayload announced = this.payload;

        this.acknowledgementDue = true;
        this.finishMillis       = nowMillis;

        if (current != null && announced != null && announced.hasDigest(current.digest())) {
            this.state = TransferState.COMPLETE;

            return;
        }

        this.state   = TransferState.FAILED;
        this.failure = TransferFailure.VERIFICATION_FAILED;

        try {
            this.sink.truncate();
        } catch (IOException exception) {
            this.failure = TransferFailure.STORAGE_FAILED;
        }
    }
}
