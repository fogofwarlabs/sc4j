package org.fogofwarlabs.sc4j.engine.net;

import org.fogofwarlabs.sc4j.base.exception.Exceptions;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.net.*;
import java.nio.ByteBuffer;
import java.nio.channels.DatagramChannel;

/**
 * Sends and receives UDP datagrams at one local IPv4 address: the engine network's default {@link DatagramEndpoint}, a non-blocking
 * {@link DatagramChannel}. A port unreachable notice of an earlier send (a participant that closed its endpoint) is not an error of the
 * receive. The endpoint asks the system for send and receive buffers of {@value #SOCKET_BUFFER_SIZE} bytes, so that the datagrams arriving
 * between two receives of its owner's loop are kept; the system may give less.
 */
public final class UdpDatagramEndpoint implements DatagramEndpoint {
    /**
     * The size of the send and receive buffers the endpoint asks the system for, in bytes.
     */
    public static final int SOCKET_BUFFER_SIZE = 4 * 1024 * 1024;

    /**
     * Creates a {@link UdpDatagramEndpoint} instance bound to a local address.
     *
     * @param address the local address, for example {@code 127.0.0.1} with port {@code 0} for any free port
     * @return the created {@link UdpDatagramEndpoint} instance
     * @throws IOException if the address cannot be bound
     */
    public static @NotNull UdpDatagramEndpoint open(@NotNull InetSocketAddress address) throws IOException {
        DatagramChannel channel = DatagramChannel.open(StandardProtocolFamily.INET);

        try {
            channel.configureBlocking(false);
            channel.setOption(StandardSocketOptions.SO_RCVBUF, SOCKET_BUFFER_SIZE);
            channel.setOption(StandardSocketOptions.SO_SNDBUF, SOCKET_BUFFER_SIZE);
            channel.bind(address);
        } catch (IOException exception) {
            channel.close();

            throw exception;
        }

        return new UdpDatagramEndpoint(channel);
    }


    private final @NotNull DatagramChannel channel;

    private UdpDatagramEndpoint(@NotNull DatagramChannel channel) {
        this.channel = channel;
    }

    /**
     * @return the local address the endpoint receives at
     * @throws IOException if the endpoint is closed
     */
    @Override
    public @NotNull InetSocketAddress getLocalAddress() throws IOException {
        SocketAddress address = this.channel.getLocalAddress();

        if (!(address instanceof InetSocketAddress local)) {
            throw Exceptions.newIOException("The UDP endpoint is closed.");
        }

        return local;
    }


    /**
     * Sends one datagram: the remaining bytes of the buffer.
     *
     * @param datagram the datagram, read from its position to its limit
     * @param target   the receiver's address
     * @throws IOException if the endpoint is closed or the datagram cannot be sent
     */
    @Override
    public void send(@NotNull ByteBuffer datagram, @NotNull InetSocketAddress target) throws IOException {
        this.channel.send(datagram, target);
    }

    /**
     * Receives the next waiting datagram into the buffer, from its position; a datagram longer than the remaining space is cut. Returns at once
     * when none is waiting.
     *
     * @param buffer the buffer that receives the datagram
     * @return the sender's address, or {@code null} if no datagram is waiting
     * @throws IOException if the endpoint is closed
     */
    @Override
    public @Nullable InetSocketAddress receive(@NotNull ByteBuffer buffer) throws IOException {
        while (true) {
            SocketAddress source;

            try {
                source = this.channel.receive(buffer);
            } catch (PortUnreachableException exception) {
                continue;
            }

            return (InetSocketAddress) source;
        }
    }

    /**
     * Closes the endpoint; it receives and sends nothing afterwards.
     *
     * @throws IOException if the endpoint cannot be closed
     */
    @Override
    public void close() throws IOException {
        this.channel.close();
    }
}
