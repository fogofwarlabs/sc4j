package org.fogofwarlabs.sc4j.translate.service.sentencepiece;

import org.fogofwarlabs.sc4j.base.binary.LittleEndianUtils;
import org.fogofwarlabs.sc4j.base.exception.Exceptions;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;

/**
 * Reads the fields of a protocol buffer message one by one: the tag, then the value of its wire type.
 */
final class ProtobufReader {
    static final int VARINT = 0;
    static final int FIXED64 = 1;
    static final int LENGTH_DELIMITED = 2;
    static final int FIXED32 = 5;

    private static final int WIRE_TYPE_BITS = 3;
    private static final int WIRE_TYPE_MASK = 0x7;
    private static final int VARINT_PAYLOAD_MASK = 0x7F;
    private static final int VARINT_CONTINUATION = 0x80;
    private static final int VARINT_SHIFT = 7;
    private static final int MAXIMUM_VARINT_SHIFT = 63;
    private static final int FIXED64_SIZE = 8;
    private static final int FIXED32_SIZE = 4;

    private final byte @NotNull [] data;
    private final int end;

    private int position;
    private int wireType;

    /**
     * Creates a {@link ProtobufReader} instance over a range of bytes.
     *
     * @param data  the bytes
     * @param start the index of the first byte of the message
     * @param end   the index after the last byte of the message
     */
    ProtobufReader(byte @NotNull [] data, int start, int end) {
        this.data = data;
        this.end  = end;

        this.position = start;
        this.wireType = VARINT;
    }

    /**
     * @return {@code true} if the message has another field, {@code false} otherwise
     */
    boolean hasField() {
        return this.position < this.end;
    }

    /**
     * Reads the tag of the next field.
     *
     * @return the field number
     * @throws IOException if the message ends inside the tag
     */
    int readField() throws IOException {
        long tag = this.readVarint();

        this.wireType = (int) (tag & WIRE_TYPE_MASK);

        return (int) (tag >>> WIRE_TYPE_BITS);
    }

    /**
     * @return the wire type of the field read last
     */
    int getWireType() {
        return this.wireType;
    }

    /**
     * Reads a variable-length integer.
     *
     * @return the value
     * @throws IOException if the message ends inside the value or the value is longer than 64 bits
     */
    long readVarint() throws IOException {
        long value = 0;

        for (int shift = 0; shift <= MAXIMUM_VARINT_SHIFT; shift += VARINT_SHIFT) {
            if (this.position >= this.end) {
                throw Exceptions.newIOException("The protocol buffer message ends inside a number at {0}", this.position);
            }

            int next = this.data[this.position++] & 0xFF;

            value |= (long) (next & VARINT_PAYLOAD_MASK) << shift;

            if ((next & VARINT_CONTINUATION) == 0) {
                return value;
            }
        }

        throw Exceptions.newIOException("The protocol buffer number at {0} is too long", this.position);
    }

    /**
     * Reads a 32-bit little-endian value, such as a {@code float}.
     *
     * @return the value
     * @throws IOException if the message ends inside the value
     */
    int readFixed32() throws IOException {
        this.require(FIXED32_SIZE);

        int value = LittleEndianUtils.readInt32(this.data, this.position);

        this.position += FIXED32_SIZE;

        return value;
    }

    /**
     * Reads the length of a length-delimited value and moves past the value.
     *
     * @return the index of the first byte of the value; the value ends at {@link #getPosition()}
     * @throws IOException if the message ends inside the value
     */
    int readLengthDelimited() throws IOException {
        long length = this.readVarint();

        if (length < 0 || length > this.end - this.position) {
            throw Exceptions.newIOException("The protocol buffer value at {0} is longer than its message", this.position);
        }

        int start = this.position;

        this.position += (int) length;

        return start;
    }

    /**
     * @return the index of the next byte
     */
    int getPosition() {
        return this.position;
    }

    /**
     * Moves past the value of the field read last.
     *
     * @throws IOException if the wire type is not known or the message ends inside the value
     */
    void skipValue() throws IOException {
        switch (this.wireType) {
            case VARINT -> this.readVarint();
            case FIXED64 -> {
                this.require(FIXED64_SIZE);
                this.position += FIXED64_SIZE;
            }
            case LENGTH_DELIMITED -> this.readLengthDelimited();
            case FIXED32 -> {
                this.require(FIXED32_SIZE);
                this.position += FIXED32_SIZE;
            }
            default -> throw Exceptions.newIOException("Unknown protocol buffer wire type {0} at {1}", this.wireType, this.position);
        }
    }

    private void require(int size) throws IOException {
        if (this.end - this.position < size) {
            throw Exceptions.newIOException("The protocol buffer message ends inside a value at {0}", this.position);
        }
    }
}
