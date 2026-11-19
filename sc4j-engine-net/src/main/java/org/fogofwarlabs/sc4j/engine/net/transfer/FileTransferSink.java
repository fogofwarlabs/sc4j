package org.fogofwarlabs.sc4j.engine.net.transfer;

import org.jetbrains.annotations.NotNull;

import java.io.Closeable;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * Takes the bytes of a payload into a file ({@link TransferSink}): the file is created when it does not exist, and the bytes it holds are the
 * bytes a download resumes after. The file is open until the sink is closed.
 */
public final class FileTransferSink implements TransferSink, Closeable {
    /**
     * Opens a file as a sink.
     *
     * @param file the file, created when it does not exist
     * @return the created {@link FileTransferSink} instance
     * @throws IOException if the file cannot be opened
     */
    public static @NotNull FileTransferSink open(@NotNull Path file) throws IOException {
        return new FileTransferSink(FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.READ, StandardOpenOption.WRITE));
    }


    private final @NotNull FileChannel channel;

    private FileTransferSink(@NotNull FileChannel channel) {
        this.channel = channel;
    }

    /**
     * @return the file's size in bytes
     * @throws IOException if the size cannot be read
     */
    @Override
    public long getLength() throws IOException {
        return this.channel.size();
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
        FileTransferSource.readFully(this.channel, offset, target);
    }

    /**
     * Appends bytes at the file's end.
     *
     * @param data the bytes, read from their position to their limit
     * @throws IOException if the bytes cannot be written
     */
    @Override
    public void write(@NotNull ByteBuffer data) throws IOException {
        long position = this.channel.size();

        while (data.hasRemaining()) {
            position += this.channel.write(data, position);
        }
    }

    /**
     * Empties the file.
     *
     * @throws IOException if the file cannot be emptied
     */
    @Override
    public void truncate() throws IOException {
        this.channel.truncate(0);
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
}
