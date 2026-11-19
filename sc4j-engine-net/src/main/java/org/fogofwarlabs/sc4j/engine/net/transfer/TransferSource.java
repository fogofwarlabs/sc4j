package org.fogofwarlabs.sc4j.engine.net.transfer;

import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.nio.ByteBuffer;

/**
 * Gives the bytes of a payload a peer offers ({@link TransferService#offer}): the sending side reads each chunk when it sends it, so a payload
 * is never held in memory as a whole.
 */
public interface TransferSource {
    /**
     * @return the payload's size in bytes
     */
    long getSize();

    /**
     * Reads payload bytes into a buffer: as many as the buffer has room for, from its position to its limit.
     *
     * @param offset the offset of the first byte in the payload
     * @param target the buffer that receives the bytes
     * @throws IOException if the bytes cannot be read or the payload ends before the buffer is full
     */
    void read(long offset, @NotNull ByteBuffer target) throws IOException;
}
