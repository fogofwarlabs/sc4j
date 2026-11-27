package org.fogofwarlabs.sc4j.launcher.link;

import org.fogofwarlabs.sc4j.base.exception.Exceptions;
import org.fogofwarlabs.sc4j.base.exception.IgnoreException;
import org.fogofwarlabs.sc4j.base.log.Logger;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.Closeable;
import java.io.IOException;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Serves the named services of the launcher to the runtimes it started, over a Unix domain socket.
 * <p>
 * A client's first frame is its hello with the token of the server's {@link LauncherLinkAddress} and its SC4J version; the server closes the
 * connection on another token or version and answers an accepted hello with its own. Each client then sends requests that the
 * {@link LinkRequestHandler} of their service answers with a response or a failure; a request to a service that is not registered fails. A
 * frame of another kind, or one longer than 1 MiB, closes the client's connection. {@link #publish} sends an event to every accepted client.
 * <p>
 * The server accepts clients on a daemon thread of its own and reads each client on another one. Opening replaces a file left at the socket
 * path; closing deletes the socket file.
 */
public final class LauncherLinkServer implements Closeable {
    private static final @NotNull Logger LOGGER = Logger.of(LauncherLinkServer.class);
    private static final @NotNull String ACCEPT_THREAD_NAME = "sc4j-launcher-link-accept";
    private static final @NotNull String CLIENT_THREAD_NAME = "sc4j-launcher-link-client";

    /**
     * Opens a server on the socket of an address: the socket's folder is created and a file left at its path is replaced.
     *
     * @param address the socket and the token clients must send
     * @param version the SC4J version clients must send
     * @return the created {@link LauncherLinkServer} instance, accepting clients
     * @throws LauncherLinkException if the socket path is longer than {@link LauncherLinkAddress#MAXIMUM_SOCKET_PATH_LENGTH}
     * @throws IOException           if the folder cannot be created, the file cannot be replaced or the socket cannot be bound
     */
    public static @NotNull LauncherLinkServer open(@NotNull LauncherLinkAddress address, @NotNull String version) throws IOException {
        Path socket = address.getSocket();

        if (!LauncherLinkAddress.isSocketPathSupported(socket)) {
            throw LauncherLinkException.create("The launcher link socket path is longer than {0} bytes: {1}",
                                               LauncherLinkAddress.MAXIMUM_SOCKET_PATH_LENGTH, socket);
        }

        Path folder = socket.getParent();

        if (folder != null) {
            Files.createDirectories(folder);
        }

        Files.deleteIfExists(socket);

        ServerSocketChannel channel = ServerSocketChannel.open(StandardProtocolFamily.UNIX);

        try {
            channel.bind(UnixDomainSocketAddress.of(socket));
        } catch (IOException | RuntimeException exception) {
            IgnoreException.execute(
                    channel::close
            );

            throw exception;
        }

        LauncherLinkServer server = new LauncherLinkServer(address, version, channel);

        Thread.ofPlatform().name(ACCEPT_THREAD_NAME).daemon().start(server::acceptClients);

        return server;
    }

    private static @NotNull String describeFailure(@NotNull Throwable failure) {
        Throwable cause = failure instanceof CompletionException && failure.getCause() != null ? failure.getCause() : failure;
        String message = cause.getMessage();

        return message != null ? message : cause.toString();
    }


    private final @NotNull LauncherLinkAddress address;
    private final @NotNull String version;
    private final @NotNull ServerSocketChannel channel;

    private final @NotNull Map<@NotNull String, @NotNull LinkRequestHandler> services;
    private final @NotNull Set<@NotNull LinkConnection> clients;
    private volatile boolean closed;

    /**
     * Creates a {@link LauncherLinkServer} instance.
     *
     * @param address the socket and the token clients must send
     * @param version the SC4J version clients must send
     * @param channel the bound server channel, which the server closes
     */
    private LauncherLinkServer(@NotNull LauncherLinkAddress address, @NotNull String version, @NotNull ServerSocketChannel channel) {
        this.address = address;
        this.version = version;
        this.channel = channel;

        this.services = new ConcurrentHashMap<>();
        this.clients  = ConcurrentHashMap.newKeySet();
        this.closed   = false;
    }

    /**
     * @return the socket and the token of the server, which the launcher passes to the runtimes it starts
     */
    public @NotNull LauncherLinkAddress getAddress() {
        return this.address;
    }

    /**
     * Registers the handler of a service; requests to the service reach it from then on.
     *
     * @param service the service name
     * @param handler the handler of the service's requests
     * @throws IllegalArgumentException if the service has a handler already
     */
    public void register(@NotNull String service, @NotNull LinkRequestHandler handler) {
        if (this.services.putIfAbsent(service, handler) != null) {
            throw Exceptions.newIllegalArgumentException("The launcher link service {0} is registered already.", service);
        }
    }

    /**
     * Sends an event of a service to every accepted client; a client whose connection fails is closed.
     *
     * @param service the service name
     * @param payload the event payload
     * @throws IllegalArgumentException if the event is longer than a frame may be
     */
    public void publish(@NotNull String service, byte @NotNull [] payload) {
        ByteBuffer bytes;

        try {
            bytes = new LinkFrame(LinkFrameKind.EVENT, LinkFrame.NO_CORRELATION, service, payload).encode();
        } catch (IOException exception) {
            throw Exceptions.newIllegalArgumentException(exception, "The event of {0} cannot be sent: {1}", service, exception.getMessage());
        }

        for (LinkConnection client : this.clients) {
            try {
                client.write(bytes.duplicate());
            } catch (IOException exception) {
                IgnoreException.execute(
                        client::close
                );
            }
        }
    }

    private void acceptClients() {
        while (!this.closed) {
            SocketChannel accepted;

            try {
                accepted = this.channel.accept();
            } catch (IOException exception) {
                if (!this.closed) {
                    LOGGER.warning("LAUNCHER LINK: no more clients are accepted: {0}", exception.getMessage());
                }

                return;
            }

            LinkConnection connection = new LinkConnection(accepted);

            Thread.ofPlatform().name(CLIENT_THREAD_NAME).daemon().start(() -> this.serveClient(connection));
        }
    }

    private void serveClient(@NotNull LinkConnection connection) {
        try {
            String refusal = this.findRefusal(connection.read());

            if (refusal != null) {
                LOGGER.warning("LAUNCHER LINK: refused a client: {0}", refusal);

                return;
            }

            // The client is listed before its hello is answered, so that every event published after its connect returns reaches it.
            synchronized (connection) {
                this.clients.add(connection);
                connection.write(LinkFrame.createHello(this.version));
            }

            if (this.closed) {
                return;
            }

            LinkFrame frame = connection.read();

            while (frame != null) {
                if (frame.getKind() != LinkFrameKind.REQUEST) {
                    throw LauncherLinkException.create("A client sent a frame of the kind {0}.", frame.getKind());
                }

                this.handleRequest(connection, frame);
                frame = connection.read();
            }
        } catch (IOException exception) {
            if (!this.closed && connection.isOpen()) {
                LOGGER.warning("LAUNCHER LINK: closed a client: {0}", exception.getMessage());
            }
        } finally {
            this.clients.remove(connection);
            IgnoreException.execute(
                    connection::close
            );
        }
    }

    private @Nullable String findRefusal(@Nullable LinkFrame hello) throws LauncherLinkException {
        if (hello == null) {
            return "the connection ended before its hello";
        }

        if (hello.getKind() != LinkFrameKind.HELLO) {
            return "the first frame is no hello";
        }

        String clientVersion = hello.readHelloVersion();

        if (!clientVersion.equals(this.version)) {
            return Exceptions.format("the client runs SC4J {0} instead of {1}", clientVersion, this.version);
        }

        return this.address.matchesToken(hello.readHelloToken()) ? null : "the token is wrong";
    }

    private void handleRequest(@NotNull LinkConnection connection, @NotNull LinkFrame request) throws IOException {
        LinkRequestHandler handler = this.services.get(request.getService());

        if (handler == null) {
            connection.write(LinkFrame.createFailure(request.getCorrelation(), request.getService(),
                                                     Exceptions.format("The launcher has no service {0}.", request.getService())));

            return;
        }

        CompletableFuture<byte[]> response;

        try {
            response = handler.handle(request.getPayload());
        } catch (RuntimeException exception) {
            response = CompletableFuture.failedFuture(exception);
        }

        response.whenComplete((payload, failure) -> this.answer(connection, request, payload, failure));
    }

    private void answer(@NotNull LinkConnection connection, @NotNull LinkFrame request, byte @Nullable [] payload, @Nullable Throwable failure) {
        int correlation = request.getCorrelation();
        String service = request.getService();
        LinkFrame answer;

        if (failure != null) {
            answer = LinkFrame.createFailure(correlation, service, describeFailure(failure));
        } else if (payload == null) {
            answer = LinkFrame.createFailure(correlation, service, Exceptions.format("The launcher service {0} answered no payload.", service));
        } else {
            answer = new LinkFrame(LinkFrameKind.RESPONSE, correlation, service, payload);
        }

        try {
            try {
                connection.write(answer);
            } catch (LauncherLinkException exception) {
                connection.write(LinkFrame.createFailure(correlation, service, String.valueOf(exception.getMessage())));
            }
        } catch (IOException exception) {
            // The client's reader thread closes a failed connection.
        }
    }

    /**
     * Closes the server: no client is accepted any more, every client's connection is closed and the socket file is deleted.
     */
    @Override
    public void close() {
        if (this.closed) {
            return;
        }

        this.closed = true;
        IgnoreException.execute(
                this.channel::close
        );

        for (LinkConnection client : this.clients) {
            IgnoreException.execute(
                    client::close
            );
        }

        IgnoreException.execute(
                () -> Files.deleteIfExists(this.address.getSocket())
        );
    }
}
