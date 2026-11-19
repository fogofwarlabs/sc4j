package org.fogofwarlabs.sc4j.engine.net.transfer;

import org.jetbrains.annotations.NotNull;

import java.nio.ByteBuffer;

/**
 * Writes the datagrams of the transfer protocol ({@link TransferService}). Big-endian; every datagram starts with the header: {@link #MAGIC}
 * (two bytes), {@link #VERSION} (one byte), the message type (one byte) and the requesting side's request number (four bytes).
 */
final class TransferWire {
    /**
     * The first two bytes of every datagram of the protocol.
     */
    static final int MAGIC = 0x5354;
    /**
     * The protocol version.
     */
    static final int VERSION = 1;
    /**
     * The size of the header.
     */
    static final int HEADER_SIZE = 8;

    /**
     * Request: the offset the requesting side holds the payload up to (eight bytes), the key's length (one byte) and the key.
     */
    static final int REQUEST = 1;
    /**
     * Acceptance: the payload's size (eight bytes), the offset the chunks start at (eight), the chunk size (two) and the payload's digest.
     */
    static final int ACCEPT = 2;
    /**
     * Refusal: no body.
     */
    static final int REFUSE = 3;
    /**
     * Chunk: the chunk's index from the start offset (four bytes) and its bytes.
     */
    static final int DATA = 4;
    /**
     * Acknowledgement: the number of chunks held without gap (four bytes) and the bits of the {@value #ACKNOWLEDGED_BITS} chunks after the
     * first missing one that are held (eight, the lowest bit first).
     */
    static final int ACKNOWLEDGE = 5;
    /**
     * Cancel, from either side: no body.
     */
    static final int CANCEL = 6;

    /**
     * The size of a chunk's header.
     */
    static final int DATA_HEADER_SIZE = HEADER_SIZE + Integer.BYTES;
    /**
     * The size of an acceptance.
     */
    static final int ACCEPT_SIZE = HEADER_SIZE + Long.BYTES + Long.BYTES + Short.BYTES + TransferPayload.DIGEST_LENGTH;
    /**
     * The size of an acknowledgement.
     */
    static final int ACKNOWLEDGE_SIZE = HEADER_SIZE + Integer.BYTES + Long.BYTES;
    /**
     * The smallest request.
     */
    static final int REQUEST_MINIMUM_SIZE = HEADER_SIZE + Long.BYTES + Byte.BYTES;
    /**
     * The number of chunks an acknowledgement reports after the first missing one.
     */
    static final int ACKNOWLEDGED_BITS = Long.SIZE;

    static final int BYTE_MASK = 0xFF;
    static final int WORD_MASK = 0xFFFF;

    /**
     * Starts a datagram: clears the buffer and writes the header.
     *
     * @param buffer  the datagram buffer
     * @param type    the message type
     * @param request the request number
     * @return the buffer
     */
    static @NotNull ByteBuffer begin(@NotNull ByteBuffer buffer, int type, int request) {
        return buffer.clear().putShort((short) MAGIC).put((byte) VERSION).put((byte) type).putInt(request);
    }

    private TransferWire() {
    }
}
