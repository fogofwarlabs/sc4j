package org.fogofwarlabs.sc4j.engine.net.transfer;

import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.nio.ByteBuffer;

/**
 * Takes the bytes of a payload a peer requests ({@link TransferService#request}) in order. The bytes it already holds when the request starts
 * are kept: the download resumes after them, and they are read once to check the payload's digest.
 */
public interface TransferSink {
    /**
     * @return the number of bytes held, from the payload's start
     * @throws IOException if the length cannot be read
     */
    long getLength() throws IOException;

    /**
     * Reads held bytes into a buffer: as many as the buffer has room for, from its position to its limit.
     *
     * @param offset the offset of the first byte
     * @param target the buffer that receives the bytes
     * @throws IOException if the bytes cannot be read or the held bytes end before the buffer is full
     */
    void read(long offset, @NotNull ByteBuffer target) throws IOException;

    /**
     * Appends bytes after the held ones.
     *
     * @param data the bytes, read from their position to their limit
     * @throws IOException if the bytes cannot be written
     */
    void write(@NotNull ByteBuffer data) throws IOException;

    /**
     * Drops every held byte.
     *
     * @throws IOException if the bytes cannot be dropped
     */
    void truncate() throws IOException;
}
