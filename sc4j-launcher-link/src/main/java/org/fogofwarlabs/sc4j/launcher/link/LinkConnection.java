package org.fogofwarlabs.sc4j.launcher.link;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.Closeable;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;

/**
 * Carries the frames of one launcher link connection over a blocking socket channel. One thread reads; any thread writes, one frame at a time
 * while it holds the connection's monitor, which a caller may hold longer to order a write with its own state.
 */
final class LinkConnection implements Closeable {
    private final @NotNull SocketChannel channel;

    /**
     * Creates a {@link LinkConnection} instance.
     *
     * @param channel the connected blocking channel, which the connection closes
     */
    LinkConnection(@NotNull SocketChannel channel) {
        this.channel = channel;
    }

    /**
     * @return {@code true} if the connection is open, {@code false} otherwise
     */
    boolean isOpen() {
        return this.channel.isOpen();
    }

    /**
     * Reads the next frame; only the connection's reader thread calls it.
     *
     * @return the frame, or {@code null} at the end of the stream
     * @throws LauncherLinkException if the frame breaks the link's rules
     * @throws IOException           if the channel fails or is closed
     */
    @Nullable LinkFrame read() throws IOException {
        return LinkFrame.read(this.channel);
    }

    /**
     * Writes a frame whole, before any other frame.
     *
     * @param frame the frame
     * @throws LauncherLinkException if the frame is longer than {@link LinkFrame#MAXIMUM_FRAME_LENGTH}
     * @throws IOException           if the channel fails or is closed
     */
    void write(@NotNull LinkFrame frame) throws IOException {
        this.write(frame.encode());
    }

    /**
     * Writes encoded frame bytes whole, before any other frame.
     *
     * @param bytes the frame bytes from {@link LinkFrame#encode()}, which the write consumes
     * @throws IOException if the channel fails or is closed
     */
    synchronized void write(@NotNull ByteBuffer bytes) throws IOException {
        while (bytes.hasRemaining()) {
            this.channel.write(bytes);
        }
    }

    /**
     * Closes the connection; a blocked read ends.
     *
     * @throws IOException if the channel cannot be closed
     */
    @Override
    public void close() throws IOException {
        this.channel.close();
    }
}
