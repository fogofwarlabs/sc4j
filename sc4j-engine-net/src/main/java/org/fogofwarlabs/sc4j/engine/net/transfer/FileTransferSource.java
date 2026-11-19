package org.fogofwarlabs.sc4j.engine.net.transfer;

import org.fogofwarlabs.sc4j.base.exception.Exceptions;
import org.jetbrains.annotations.NotNull;

import java.io.Closeable;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * Gives the bytes of a file as a payload ({@link TransferSource}): the file is open for reading until the source is closed, and its size is
 * the size when it was opened.
 */
public final class FileTransferSource implements TransferSource, Closeable {
    /**
     * Opens a file as a source.
     *
     * @param file the file
     * @return the created {@link FileTransferSource} instance
     * @throws IOException if the file cannot be opened
     */
    public static @NotNull FileTransferSource open(@NotNull Path file) throws IOException {
        FileChannel channel = FileChannel.open(file, StandardOpenOption.READ);

        try {
            return new FileTransferSource(channel, channel.size());
        } catch (RuntimeException exception) {
            channel.close();

            throw exception;
        }
    }


    private final @NotNull FileChannel channel;
    private final long size;

    private FileTransferSource(@NotNull FileChannel channel, long size) {
        this.channel = channel;
        this.size    = size;
    }

    /**
     * @return the file's size in bytes when it was opened
     */
    @Override
    public long getSize() {
        return this.size;
    }

    /**
     * Reads file bytes into a buffer: as many as the buffer has room for, from its position to its limit.
     *
     * @param offset the offset of the first byte in the file
     * @param target the buffer that receives the bytes
     * @throws IOException if the bytes cannot be read or the file ends before the buffer is full
     */
    @Override
    public void read(long offset, @NotNull ByteBuffer target) throws IOException {
        readFully(this.channel, offset, target);
    }

    /**
     * Closes the file.
     *
     * @throws IOException if the file cannot be closed
     */
    @Override
    public void close() throws IOException {
        this.channel.close();
    }

    /**
     * Reads file bytes into a buffer until it is full.
     *
     * @param channel the file
     * @param offset  the offset of the first byte in the file
     * @param target  the buffer that receives the bytes
     * @throws IOException if the bytes cannot be read or the file ends before the buffer is full
     */
    static void readFully(@NotNull FileChannel channel, long offset, @NotNull ByteBuffer target) throws IOException {
        long position = offset;

        while (target.hasRemaining()) {
            int read = channel.read(target, position);

            if (read < 0) {
                throw Exceptions.newIOException("The file ends at {0}, before the {1} bytes to read.", position, target.remaining());
            }

            position += read;
        }
    }
}
